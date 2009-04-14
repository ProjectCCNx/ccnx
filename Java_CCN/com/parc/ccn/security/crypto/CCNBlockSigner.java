package com.parc.ccn.security.crypto;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SignatureException;
import java.sql.Timestamp;

import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.security.KeyLocator;
import com.parc.ccn.data.security.PublisherKeyID;
import com.parc.ccn.data.security.SignedInfo.ContentType;
import com.parc.ccn.library.CCNSegmenter;

/**
 * An unaggregating aggregated signer. Signs each block individually.
 * @author smetters
 *
 */
public class CCNBlockSigner implements CCNAggregatedSigner {

	public ContentObject putBlocks(			
			CCNSegmenter segmenter,
			ContentName name, int baseNameIndex,
			byte[][] contentBlocks, int blockCount, int baseBlockIndex,
			int lastBlockLength, ContentType type, Timestamp timestamp,
			Integer freshnessSeconds, Integer lastSegment, KeyLocator locator,
			PublisherKeyID publisher) throws InvalidKeyException,
			SignatureException, NoSuchAlgorithmException, IOException {
		
		ContentObject result = segmenter.putFragment(name, baseNameIndex, contentBlocks[0], 0, 
				contentBlocks[0].length, type, timestamp, freshnessSeconds, 
				lastSegment, locator, publisher);
		for (int i=baseBlockIndex+1; i < (baseBlockIndex + blockCount - 1); ++i) {
			segmenter.putFragment(name, baseNameIndex + i, contentBlocks[i], 0, 
					contentBlocks[i].length, type, timestamp, freshnessSeconds, 
					lastSegment, locator, publisher);
		}
		segmenter.putFragment(name, baseNameIndex + blockCount - 1, contentBlocks[baseBlockIndex + blockCount - 1], 0, 
				lastBlockLength, type, timestamp, freshnessSeconds, 
				lastSegment, locator, publisher);
		return result;
	}

	public ContentObject putBlocks(
			CCNSegmenter segmenter,
			ContentName[] names, byte[][] contentBlocks,
			int blockCount, int baseBlockIndex, int lastBlockLength,
			ContentType type, Timestamp timestamp, Integer freshnessSeconds,
			Integer lastSegment, KeyLocator locator, PublisherKeyID publisher)
			throws InvalidKeyException, SignatureException,
			NoSuchAlgorithmException, IOException {
		
		ContentObject result = segmenter.putFragment(names[0], 0, contentBlocks[0], 0, 
				contentBlocks[0].length, type, timestamp, freshnessSeconds, 
				lastSegment, locator, publisher);
		for (int i=baseBlockIndex+1; i < (baseBlockIndex + blockCount - 1); ++i) {
			segmenter.putFragment(names[i], i, contentBlocks[i], 0, 
					contentBlocks[i].length, type, timestamp, freshnessSeconds, 
					lastSegment, locator, publisher);
		}
		segmenter.putFragment(names[blockCount - 1], baseBlockIndex + blockCount - 1,
				contentBlocks[baseBlockIndex + blockCount - 1], 0, 
				lastBlockLength, type, timestamp, freshnessSeconds, 
				lastSegment, locator, publisher);
		return result;
	}

	public ContentObject putBlocks(
			CCNSegmenter segmenter,
			ContentName name, int baseNameIndex,
			byte[] content, int offset, int length, int blockWidth,
			ContentType type, Timestamp timestamp, Integer freshnessSeconds,
			Integer lastSegment, KeyLocator locator, PublisherKeyID publisher)
			throws InvalidKeyException, SignatureException,
			NoSuchAlgorithmException, IOException {
		
		ContentObject result = segmenter.putFragment(name, baseNameIndex, content,
				0, ((length < blockWidth) ? length : blockWidth), type, timestamp, freshnessSeconds, 
				lastSegment, locator, publisher);
		if (length > blockWidth) {
			int numBlocks = MerkleTree.blockCount(length, blockWidth);
			int i = 1;
			for (i=1; i < (numBlocks - 1); ++i) {
				segmenter.putFragment(name, baseNameIndex + i, content, i*blockWidth, 
						blockWidth, type, timestamp, freshnessSeconds, 
						lastSegment, locator, publisher);
			}
			segmenter.putFragment(name, baseNameIndex+i, content,
					i*blockWidth, 
					(((length % blockWidth) == 0) ? blockWidth : (length % blockWidth)), 
					type, timestamp, freshnessSeconds, 
					lastSegment, locator, publisher);	
		}
		return result;
	}
}
