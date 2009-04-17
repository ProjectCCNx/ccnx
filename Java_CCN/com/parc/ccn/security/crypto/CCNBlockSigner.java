package com.parc.ccn.security.crypto;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SignatureException;
import java.sql.Timestamp;

import com.parc.ccn.Library;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.security.KeyLocator;
import com.parc.ccn.data.security.PublisherPublicKeyDigest;
import com.parc.ccn.data.security.SignedInfo.ContentType;
import com.parc.ccn.library.CCNSegmenter;
import com.parc.ccn.library.profiles.SegmentationProfile;

/**
 * An unaggregating aggregated signer. Signs each block individually.
 * @author smetters
 *
 */
public class CCNBlockSigner implements CCNAggregatedSigner {

	public long putBlocks(			
			CCNSegmenter segmenter,
			ContentName name, long baseNameIndex,
			byte[][] contentBlocks, int blockCount, int baseBlockIndex,
			int lastBlockLength, ContentType type, Timestamp timestamp,
			Integer freshnessSeconds, Long finalSegmentIndex, KeyLocator locator,
			PublisherPublicKeyDigest publisher) throws InvalidKeyException,
			SignatureException, NoSuchAlgorithmException, IOException {
		
		if (blockCount == 0) 
			return baseNameIndex;
				
		// fill in the last segment. Otherwise we're all set.
		if ((null != finalSegmentIndex) && (finalSegmentIndex.equals(CCNSegmenter.LAST_SEGMENT))) {
			long length = 0;
			for (int j = baseBlockIndex; j < baseBlockIndex + blockCount - 1; j++) {
				length += contentBlocks[j].length;
			}
			// don't include last block length; want intervening byte count before the last block

			// compute final segment number; which might be this one if blockCount == 1
			finalSegmentIndex = segmenter.lastSegmentIndex(baseNameIndex, length, blockCount);
		}

		long nextSegmentIndex = segmenter.putFragment(name, baseNameIndex, contentBlocks[baseBlockIndex], 0, 
				contentBlocks[baseBlockIndex].length, type, timestamp, freshnessSeconds, 
				finalSegmentIndex, locator, publisher);
		if (blockCount == 1) 
			return nextSegmentIndex;

		for (int i=baseBlockIndex+1; i < (baseBlockIndex + blockCount - 1); ++i) {
			nextSegmentIndex = segmenter.putFragment(name, nextSegmentIndex, contentBlocks[i], 0, 
					contentBlocks[i].length, type, timestamp, freshnessSeconds, 
					finalSegmentIndex, locator, publisher);
		}
		nextSegmentIndex = segmenter.putFragment(name, nextSegmentIndex, contentBlocks[baseBlockIndex + blockCount - 1], 0, 
				lastBlockLength, type, timestamp, freshnessSeconds, 
				finalSegmentIndex, locator, publisher);
		return nextSegmentIndex;
	}

	public long putBlocks(
			CCNSegmenter segmenter,
			ContentName[] names, byte[][] contentBlocks,
			int blockCount, int baseBlockIndex, int lastBlockLength,
			ContentType type, Timestamp timestamp, Integer freshnessSeconds,
			Long finalSegmentIndex, KeyLocator locator, PublisherPublicKeyDigest publisher)
			throws InvalidKeyException, SignatureException,
			NoSuchAlgorithmException, IOException {
		
		if (blockCount == 0) 
			return SegmentationProfile.baseSegment();

		// finalBlockID makes no sense for this method unless the blocks really are
		// already segmented. But this function likely going away...
		// fill in the last segment. Otherwise we're all set.
		if ((null != finalSegmentIndex) && (finalSegmentIndex.equals(CCNSegmenter.LAST_SEGMENT))) {
			
			// setting this for this case makes little sense, but this whole function is probably
			// going away..
			finalSegmentIndex = SegmentationProfile.getSegmentNumber(names[blockCount-1].lastComponent());
		}

		segmenter.putFragment(names[0], SegmentationProfile.baseSegment(), contentBlocks[baseBlockIndex], 0, 
				contentBlocks[baseBlockIndex].length, type, timestamp, freshnessSeconds, 
				finalSegmentIndex, locator, publisher);
		if (blockCount == 1) 
			return SegmentationProfile.baseSegment();;

		for (int i=baseBlockIndex+1; i < (baseBlockIndex + blockCount - 1); ++i) {
			segmenter.putFragment(names[i-(baseBlockIndex+1)], SegmentationProfile.baseSegment(), contentBlocks[i], 0, 
					contentBlocks[i].length, type, timestamp, freshnessSeconds, 
					finalSegmentIndex, locator, publisher);
		}
		segmenter.putFragment(names[blockCount - 1], SegmentationProfile.baseSegment(),
				contentBlocks[baseBlockIndex + blockCount - 1], 0, 
				lastBlockLength, type, timestamp, freshnessSeconds, 
				finalSegmentIndex, locator, publisher);
		return SegmentationProfile.baseSegment();
	}

	public long putBlocks(
			CCNSegmenter segmenter,
			ContentName name, long baseNameIndex,
			byte[] content, int offset, int length, int blockWidth,
			ContentType type, Timestamp timestamp, Integer freshnessSeconds,
			Long finalSegmentIndex, KeyLocator locator, PublisherPublicKeyDigest publisher)
	throws InvalidKeyException, SignatureException, NoSuchAlgorithmException, IOException {
		
		if (length <= 0) {
			Library.logger().info("Nothing to write.");
			return baseNameIndex;
		}

		// fill in the last segment. Otherwise we're all set.
		if ((null != finalSegmentIndex) && (finalSegmentIndex.equals(CCNSegmenter.LAST_SEGMENT))) {

			// compute final segment number; which might be this one if blockCount == 1
			int blockCount = CCNMerkleTree.blockCount(length, blockWidth);
			finalSegmentIndex = segmenter.lastSegmentIndex(baseNameIndex, (blockCount-1)*blockWidth, 
											blockCount);
		}

		long nextSegmentIndex = segmenter.putFragment(name, baseNameIndex, content,
				0, ((length < blockWidth) ? length : blockWidth), type, timestamp, freshnessSeconds, 
				finalSegmentIndex, locator, publisher);
		if (length > blockWidth) {
			int numBlocks = MerkleTree.blockCount(length, blockWidth);
			int i = 1;
			for (i=1; i < (numBlocks - 1); ++i) {
				nextSegmentIndex = segmenter.putFragment(name, nextSegmentIndex, content, i*blockWidth, 
						blockWidth, type, timestamp, freshnessSeconds, 
						finalSegmentIndex, locator, publisher);
			}
			nextSegmentIndex = segmenter.putFragment(name, nextSegmentIndex, content,
					i*blockWidth, 
					(((length % blockWidth) == 0) ? blockWidth : (length % blockWidth)), 
					type, timestamp, freshnessSeconds, 
					finalSegmentIndex, locator, publisher);	
		}
		return nextSegmentIndex;
	}
}
