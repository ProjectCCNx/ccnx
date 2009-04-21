package com.parc.ccn.security.crypto;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.SignatureException;
import java.sql.Timestamp;

import com.parc.ccn.Library;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.security.KeyLocator;
import com.parc.ccn.data.security.PublisherPublicKeyDigest;
import com.parc.ccn.data.security.SignedInfo;
import com.parc.ccn.data.security.SignedInfo.ContentType;
import com.parc.ccn.library.CCNSegmenter;
import com.parc.ccn.library.profiles.SegmentationProfile;

public class CCNMerkleTreeSigner implements CCNAggregatedSigner {
	
	public CCNMerkleTreeSigner() {
	}

	public long putBlocks(CCNSegmenter segmenter,
			ContentName name, long baseNameIndex,
			byte[][] contentBlocks, int blockCount, int baseBlockIndex,
			int lastBlockLength, ContentType type, Timestamp timestamp,
			Integer freshnessSeconds, Long finalSegmentIndex, KeyLocator locator,
			PublisherPublicKeyDigest publisher) throws InvalidKeyException,
			SignatureException, NoSuchAlgorithmException, IOException {
		
		if (blockCount == 0)
			return baseNameIndex;
		
		if (null == publisher) {
			publisher = segmenter.getFlowControl().getLibrary().keyManager().getDefaultKeyID();
		}
		PrivateKey signingKey = segmenter.getFlowControl().getLibrary().keyManager().getSigningKey(publisher);

		if (null == locator)
			locator = segmenter.getFlowControl().getLibrary().keyManager().getKeyLocator(signingKey);

		ContentName rootName = SegmentationProfile.segmentRoot(name);
		segmenter.getFlowControl().addNameSpace(rootName);

		if (null == type) {
			type = ContentType.DATA;
		}

		byte [] finalBlockID = null;
		if (null != finalSegmentIndex) {
			if (finalSegmentIndex.equals(CCNSegmenter.LAST_SEGMENT)) {
				long length = 0;
				for (int j = baseBlockIndex; j < baseBlockIndex + blockCount - 1; j++) {
					length += contentBlocks[j].length;
				}
				// don't include last block length; want intervening byte count before the last block
				
				// compute final segment number
				finalBlockID = SegmentationProfile.getSegmentID(
						segmenter.lastSegmentIndex(baseNameIndex, length, blockCount));
			} else {
				finalBlockID = SegmentationProfile.getSegmentID(finalSegmentIndex);
			}
		}

		// Digest of complete contents
		// If we're going to unique-ify the block names
		// (or just in general) we need to incorporate the names
		// and signedInfos in the MerkleTree blocks. 
		// For now, this generates the root signature too, so can
		// ask for the signature for each block.
		// DKS TODO -- enable different fragment numbering schemes
		// DKS TODO -- limit number of copies in creation of MerkleTree -- have to make
		//  a copy when making CO, don't make extra ones.
		CCNMerkleTree tree = 
			new CCNMerkleTree(rootName, baseNameIndex,
					new SignedInfo(publisher, timestamp, type, locator, freshnessSeconds, finalBlockID),
					contentBlocks, false, blockCount, baseBlockIndex, lastBlockLength, signingKey);

		for (int i = 0; i < blockCount-1; i++) {
			try {
				Library.logger().info("putMerkleTree: writing block " + i + " of " + blockCount + " to name " + tree.blockName(i));
				segmenter.getFlowControl().put(tree.block(i, contentBlocks[i], 0, contentBlocks[i].length));
			} catch (IOException e) {
				Library.logger().warning("This should not happen: we cannot put our own blocks!");
				Library.warningStackTrace(e);
				throw e;
			}
		}
		// last block
		try {
			Library.logger().info("putMerkleTree: writing last block of " + blockCount + " to name " + tree.blockName(blockCount-1) + " length: " + lastBlockLength);
			segmenter.getFlowControl().put(tree.block(blockCount-1, contentBlocks[blockCount-1], 0, lastBlockLength));
		} catch (IOException e) {
			Library.logger().warning("This should not happen: we cannot put our own last block!");
			Library.warningStackTrace(e);
			throw e;
		}		

		return segmenter.nextSegmentIndex(SegmentationProfile.getSegmentNumber(tree.blockName(blockCount-1)),
										  lastBlockLength);
	}

	public long putBlocks(
			CCNSegmenter segmenter,
			ContentName name, long baseNameIndex,
			byte[] content, int offset, int length, int blockWidth,
			ContentType type, Timestamp timestamp, Integer freshnessSeconds,
			Long finalSegmentIndex, KeyLocator locator, PublisherPublicKeyDigest publisher)
			throws InvalidKeyException, SignatureException,
			NoSuchAlgorithmException, IOException {
		
		if (length == 0)
			return baseNameIndex;
		
		if (null == publisher) {
			publisher = segmenter.getFlowControl().getLibrary().keyManager().getDefaultKeyID();
		}
		PrivateKey signingKey = segmenter.getFlowControl().getLibrary().keyManager().getSigningKey(publisher);

		if (null == locator)
			locator = segmenter.getFlowControl().getLibrary().keyManager().getKeyLocator(signingKey);

		ContentName rootName = SegmentationProfile.segmentRoot(name);
		segmenter.getFlowControl().addNameSpace(rootName);

		if (null == type) {
			type = ContentType.DATA;
		}
		
		byte [] finalBlockID = null;
		if (null != finalSegmentIndex) {
			if (finalSegmentIndex.equals(CCNSegmenter.LAST_SEGMENT)) {
				// compute final segment number
				// compute final segment number; which might be this one if blockCount == 1
				int blockCount = CCNMerkleTree.blockCount(length, blockWidth);
				finalBlockID = SegmentationProfile.getSegmentID(
					segmenter.lastSegmentIndex(baseNameIndex, (blockCount-1)*blockWidth, 
												blockCount));
			} else {
				finalBlockID = SegmentationProfile.getSegmentID(finalSegmentIndex);
			}
		}
		
		// Digest of complete contents
		// If we're going to unique-ify the block names
		// (or just in general) we need to incorporate the names
		// and signedInfos in the MerkleTree blocks. 
		// For now, this generates the root signature too, so can
		// ask for the signature for each block.
		// DKS TODO -- enable different fragment numbering schemes
		// DKS TODO -- limit number of copies in creation of MerkleTree -- have to make
		//  a copy when making CO, don't make extra ones.
		// DKS TODO -- handling of lastBlocks flag in signed info.
		CCNMerkleTree tree = 
			new CCNMerkleTree(rootName, baseNameIndex,
					new SignedInfo(publisher, timestamp, type, locator, freshnessSeconds, finalBlockID),
					content, offset, length, blockWidth, signingKey);

		for (int i = 0; i < tree.numLeaves()-1; i++) {
			try {
				Library.logger().info("putMerkleTree: writing block " + i + " of " + tree.numLeaves() + " to name " + tree.blockName(i));
				segmenter.getFlowControl().put(tree.block(i, content, i*blockWidth, blockWidth));
			} catch (IOException e) {
				Library.logger().warning("This should not happen: we cannot put our own blocks!");
				Library.warningStackTrace(e);
				throw e;
			}
		}
		// last block
		try {
			Library.logger().info("putMerkleTree: writing last block of " + tree.numLeaves() + 
									" to name " + tree.blockName(tree.numLeaves()-1) + " its length: " + (length - (blockWidth*(tree.numLeaves()-1))) + " (blocks: " + blockWidth + ")");
			segmenter.getFlowControl().put(tree.block(tree.numLeaves()-1, content, (blockWidth*(tree.numLeaves()-1)), 
											(length - (blockWidth*(tree.numLeaves()-1)))));
		} catch (IOException e) {
			Library.logger().warning("This should not happen: we cannot put our own last block!");
			Library.warningStackTrace(e);
			throw e;
		}		

		return segmenter.nextSegmentIndex(
				SegmentationProfile.getSegmentNumber(tree.blockName(tree.numLeaves()-1)),
				(length - (blockWidth*(tree.numLeaves()-1))));
	}

}
