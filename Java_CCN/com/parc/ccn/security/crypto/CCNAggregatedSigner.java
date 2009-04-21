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

/**
 * An aggregated signer takes a set of blocks and computes signatures
 * over them such that each block can be verified individually.
 * An example aggregated signer computes a Merkle hash tree over
 * the component blocks and then constructs signatures for each.
 * 
 * This could be a base abstract class or an interface; the former
 * would have a set of constructors or static factory methods that
 * made an object returning blocks. Instead, we try an interface
 * that has a set of bulk put methods which construct blocks, put
 * them to the network, and return an individual ContentObject.
 * 
 * 
 * @author smetters
 *
 */
public interface CCNAggregatedSigner {
	
	// public CCNAggregatedSigner(); // example constructor
	
	/**
	 * @param contentBlocks array of blocks of data, not all may be used
	 * @param blockCount how many blocks of the array to use - number of leaves in the tree
	 * @param baseBlockIndex first block to use
	 * @param lastBlockLength how many bytes of last block to use
	 */
	public long putBlocks(
			CCNSegmenter segmenter,
			ContentName name, long baseNameIndex,
			byte [][] contentBlocks, int blockCount, 
			int baseBlockIndex, int lastBlockLength,
			ContentType type, 
			Timestamp timestamp,
			Integer freshnessSeconds, Long finalSegmentIndex,
			KeyLocator locator, 
			PublisherPublicKeyDigest publisher) throws InvalidKeyException, SignatureException, 
											 NoSuchAlgorithmException, IOException;

	public long putBlocks(
			CCNSegmenter segmenter,
			ContentName name, long baseNameIndex,
			byte [] content, int offset, int length, int blockWidth,
			ContentType type, 
			Timestamp timestamp,
			Integer freshnessSeconds, Long finalSegmentIndex,
			KeyLocator locator, 
			PublisherPublicKeyDigest publisher) throws InvalidKeyException, 
									SignatureException, NoSuchAlgorithmException, IOException;

	
}
