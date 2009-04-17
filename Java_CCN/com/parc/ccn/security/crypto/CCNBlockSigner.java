package com.parc.ccn.security.crypto;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SignatureException;
import java.sql.Timestamp;

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
		
		long nextSegmentIndex = segmenter.putFragment(name, baseNameIndex, contentBlocks[0], 0, 
				contentBlocks[0].length, type, timestamp, freshnessSeconds, 
				finalSegmentIndex, locator, publisher);
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
		
		segmenter.putFragment(names[0], SegmentationProfile.baseSegment(), contentBlocks[0], 0, 
				contentBlocks[0].length, type, timestamp, freshnessSeconds, 
				finalSegmentIndex, locator, publisher);
		for (int i=baseBlockIndex+1; i < (baseBlockIndex + blockCount - 1); ++i) {
			segmenter.putFragment(names[i], SegmentationProfile.baseSegment(), contentBlocks[i], 0, 
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
