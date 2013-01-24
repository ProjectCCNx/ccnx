/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008, 2009, 2013 Palo Alto Research Center, Inc.
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License version 2.1
 * as published by the Free Software Foundation. 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details. You should have received
 * a copy of the GNU Lesser General Public License along with this library;
 * if not, write to the Free Software Foundation, Inc., 51 Franklin Street,
 * Fifth Floor, Boston, MA 02110-1301 USA.
 */

package org.ccnx.ccn.impl.security.crypto;

import java.security.AlgorithmParameters;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidParameterSpecException;

import javax.crypto.Cipher;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.IvParameterSpec;

import org.ccnx.ccn.impl.support.DataUtils;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.ContentEncodingException;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;


/**
 * This is a very simple approach to encryption keying that uses a fixed, static
 * encryption key and an IV "seed" that is used to prefix segment-specific IVs
 * or counters.
 * 
 * The segmenter will be called with parameters identifying:
 *
 *   * the encryption algorithm and mode to use, if any
 *   * the encryption key to use for this particular data item (the object to be segmented)
 *   * an 8-byte value used as an IV seed for this item (CBC or other block mode) or a random counter 
 *   		component (CTR) (derived in KeyDerivation)
 *   * the desired full segment (packet) length, including supporting data 
 * 
 * The initial counter or IV for a given block B (number Bnum) in segment Snum will be constructed as follows: 
 * 
 * Block IV/CTR = IVseed || Snum || Bnum
 * 
 * where the segment and block numbers is represented in unsigned, 1-based big endian format. 
 * 
 * The total width of the IV/Counter value is B, the block width of the cipher. For a stream
 * cipher (e.g. CTR mode), the width B is taken to be 16 bytes.
 * 
 * This IV/CTR is divided into 3 components:
 * 
 * Master IV/IVseed : the master IV seed value, specified by the caller. For this simple static key approach,
 *   this is by default 8 bytes. It is given by masterIVLength().
 *   
 * Segment number: this is a binary representation of the segment number. A single-segment object
 *    following the SegmentationProfile will still have a segment number component in its name,
 *    and will use the specified segment number (usually SegmentationProfile.baseSegment()). 
 *  
 *  The segment number is encoded in 1-based, unsigned, big-endian form, and 
 * represented in the L-N rightmost bytes of the plaintext above, where L is the length of 
 * the numeric representation of the segment number, and N is the length of the block number
 * within the segment (for CTR mode, this value is fixed at 1 for CBC and other block modes).
 * 
 *  The default width of the segment number is 6 bytes, leaving 8 bytes for the default Master IV
 *  width.
 * 
 * Block number: for CTR mode, the last 2 bytes of the IV contain the block (counter) index,
 *  starting with 1. For CBC and other block modes, that last two bytes contains the 
 *  (big endian) value 0x0001.
 *  
 *  The same IV expansion function is used regardless of mode for simplicity.
 *  
 *  Many of the expansion function calculations are broken out into separate methods to
 *  allow for easier subclassing.
 *
 * IMPORTANT NOTE: Do not use static keying to encrypt network objects in CTR mode, unless
 * you are careful to only save them once per key. Use CBC mode (under development) or
 * a dynamic keying method, such as KDFContentKeys.
 */
public class StaticContentKeys extends ContentKeys implements Cloneable {
	
	public static final int IV_MASTER_LENGTH = 8; // bytes
	public static final int SEGMENT_NUMBER_LENGTH = 6; // bytes
	private static final byte [] INITIAL_BLOCK_COUNTER_VALUE = new byte[]{0x00, 0x01};
	public static final int BLOCK_COUNTER_LENGTH = INITIAL_BLOCK_COUNTER_VALUE.length; // bytes
		
	/**
	 * StaticContentKeys constructor.
	 * @param encryptionAlgorithm (e.g. AES/CTR/NoPadding) the encryption algorithm to use.
	 * 		First component of algorithm should be the algorithm associated with the key.
	 * @param key key material to be used
	 * @param ivctr iv or counter material to be used with specified algorithm 
	 * @throws NoSuchPaddingException 
	 * @throws NoSuchAlgorithmException 
	 */
	public StaticContentKeys(String encryptionAlgorithm, byte [] key, byte [] ivCtr) 
					throws NoSuchAlgorithmException, NoSuchPaddingException {
		super(encryptionAlgorithm, key, ivCtr);
	}
	
	/**
	 * Create a StaticContentKeys with the default algorithm.
	 * @throws NoSuchPaddingException 
	 * @throws NoSuchAlgorithmException 
	 */
	public StaticContentKeys(byte [] key, byte [] ivCtr) throws NoSuchAlgorithmException, NoSuchPaddingException {
		super(null, key, ivCtr);
	}
	
	/**
	 * StaticContentKeys constructor.
	 */
	public StaticContentKeys(String encryptionAlgorithm, Key key, byte [] ivCtr) throws NoSuchAlgorithmException, NoSuchPaddingException {
		super(encryptionAlgorithm, key, ivCtr);
	}
		
	public StaticContentKeys(ContentKeys other) {
		super(other);
	}
	
	/**
	 * Create a set of random encryption/decryption keys using the default algorithm.
	 * @return a randomly-generated set of keys and IV that can be used for encryption
	 * @throws NoSuchPaddingException 
	 * @throws NoSuchAlgorithmException 
	 */
	public static synchronized ContentKeys generateRandomKeys() throws NoSuchAlgorithmException, NoSuchPaddingException {
		byte [] key = new byte[DEFAULT_KEY_LENGTH];
		byte [] iv = new byte[IV_MASTER_LENGTH];
		// do we want additional whitening?
		SecureRandom random = ContentKeys.getRandom();
		random.nextBytes(key);
		random.nextBytes(iv);
		return new StaticContentKeys(key, iv);
	}

	public StaticContentKeys clone() {
		return (StaticContentKeys)super.clone();
	}

	/**
	 * Generate a segment encryption or decryption cipher using this stored
	 * key material to encrypt or decrypt a particular segment.
	 * 
	 * This will use the CCN defaults for IV handling, to ensure that segments
	 * of a given larger piece of content do not have overlapping key streams.
	 * Higher-level functionality embodied in the library (or application-specific
	 * code) should be used to make sure that the key, _masterIV pair used for a 
	 * given multi-block piece of content is unique for that content.
	 * 
	 * CCN encryption algorithms assume deterministic IV generation (e.g. from 
	 * cryptographic MAC or ciphers themselves), and therefore do not transport
	 * the IV explicitly. Applications that wish to do so need to arrange
	 * IV transport.
	 * 
	 * We assume this stream starts on the first block of a multi-block segement,
	 * so for CTR mode, the initial block counter is 1 (block ==  encryption
	 * block). (Conventions for counter start them at 1, not 0.) The cipher
	 * will automatically increment the counter; if it overflows the two bytes
	 * we've given to it it will start to increment into the segment number.
	 * This runs the risk of potentially using up some of the IV space of
	 * other segments. 
	 * 
	 * CTR_init = IV_master || segment_number || block_counter
	 * CBC_iv = E_Ko(IV_master || segment_number || 0x0001)
	 * 		(just to make it easier, use the same feed value)
	 * 
	 * CTR value is 16 bytes.
	 * 		8 bytes are the IV.
	 * 		6 bytes are the segment number.
	 * 		last 2 bytes are the block number (for 16 byte blocks); if you 
	 * 	    have more space, use it for the block counter.
	 * IV value is the block width of the cipher.
	 * 
	 * @param segmentNumber segment to encrypt/decrypt
	 * @param encryption true for encryption, false for decryption
	 * @return the Cipher
	 * @throws InvalidKeyException
	 * @throws InvalidAlgorithmParameterException
	 * @throws ContentEncodingException 
	 * @see getSegmentEncryptionCipher(long)
	 */
	protected Cipher getSegmentCipher(ContentName contentName, PublisherPublicKeyDigest publisher, long segmentNumber, boolean encryption)
		throws InvalidKeyException, InvalidAlgorithmParameterException, ContentEncodingException {

		Cipher cipher = getCipher();

		// Construct the IV/initial counter.
		if (0 == cipher.getBlockSize()) {
			Log.warning(_encryptionAlgorithm + " is not a block cipher!");
			throw new InvalidAlgorithmParameterException(_encryptionAlgorithm + " is not a block cipher!");
		}
		
		KeyAndIV keyAndIV = getKeyAndIVForContent(contentName, publisher, segmentNumber);

		if (keyAndIV.getIV().length < IV_MASTER_LENGTH) {
			throw new InvalidAlgorithmParameterException("Master IV length must be at least " + IV_MASTER_LENGTH + " bytes, it is: " + _masterKeyAndIVCtr.getIV().length);
		}

		IvParameterSpec iv_ctrSpec = buildIVCtr(keyAndIV, segmentNumber, cipher.getBlockSize());
		AlgorithmParameters algorithmParams = null;
		try {
			algorithmParams = AlgorithmParameters.getInstance(getBaseAlgorithm());
			algorithmParams.init(iv_ctrSpec);
		} catch (NoSuchAlgorithmException e) {
			Log.warning("Unexpected exception: have already validated that algorithm {0} exists: {1}", cipher.getAlgorithm(), e);
			throw new InvalidKeyException("Unexpected exception: have already validated that algorithm " + cipher.getAlgorithm() + " exists: " + e);
		} catch (InvalidParameterSpecException e) {
			Log.warning("InvalidParameterSpecException attempting to create algorithm parameters: {0}", e);
			throw new InvalidAlgorithmParameterException("Error creating a parameter object from IV/CTR spec!", e);
		}
		
		Log.finest(encryption?"En":"De"+"cryption Key: "+DataUtils.printHexBytes(keyAndIV.getKey().getEncoded())+" iv="+DataUtils.printHexBytes(iv_ctrSpec.getIV()));
		cipher.init(encryption ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, keyAndIV.getKey(), algorithmParams);

		return cipher;
	}
	
	protected KeyAndIV getKeyAndIVForContent(ContentName contentName, PublisherPublicKeyDigest publisher, long segmentNumber) throws InvalidKeyException, ContentEncodingException {
		return _masterKeyAndIVCtr;
	}
	
	/**
	 * Turn a master IV and a segment number into an initial counter of IV for this segment
	 * (used in CTR mode).
	 * @param masterIV the master IV
	 * @param segmentNumber the segment number
	 * @param ctrLen the output IV length requested
	 * @return the initial counter
	 * @throws InvalidAlgorithmParameterException 
	 * @throws InvalidKeyException 
	 */
	public IvParameterSpec buildIVCtr(KeyAndIV keyAndIV, long segmentNumber, int ctrLen) throws InvalidKeyException, InvalidAlgorithmParameterException {

		Log.finest("Thread="+Thread.currentThread()+" Building fixed IV/CTR - master="+DataUtils.printHexBytes(keyAndIV.getIV())+" segment="+segmentNumber+" ctrLen="+ctrLen);
		
		byte [] ctr = segmentSeedValue(keyAndIV.getIV(), segmentNumber, ctrLen);
		
		IvParameterSpec ctrSpec = new IvParameterSpec(ctr);
		Log.finest("CTR: ivParameterSpec source="+DataUtils.printHexBytes(ctr)+"ivParameterSpec.getIV()="+DataUtils.printHexBytes(keyAndIV.getIV()));
		return ctrSpec;
	}
	
	public static byte [] segmentSeedValue(byte [] ivCtr, long segmentNumber, int seedLen) {
		
		byte [] seed = new byte[seedLen];
		
		System.arraycopy(ivCtr, 0, seed, 0, ivCtr.length);
		byte [] byteSegNum = ContentKeys.segmentNumberToByteArray(segmentNumber);
		System.arraycopy(byteSegNum, 0, seed, ivCtr.length, byteSegNum.length);
		System.arraycopy(INITIAL_BLOCK_COUNTER_VALUE, 0, seed,
				seed.length - BLOCK_COUNTER_LENGTH, BLOCK_COUNTER_LENGTH);
		return seed;
	}
}
