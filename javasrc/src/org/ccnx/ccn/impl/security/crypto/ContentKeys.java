/**
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008, 2009 Palo Alto Research Center, Inc.
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

import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidParameterSpecException;

import javax.crypto.Cipher;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.ccnx.ccn.KeyManager;
import org.ccnx.ccn.impl.support.DataUtils;
import org.ccnx.ccn.impl.support.Log;


/**
 * Specifies encryption algorithm, keys, and if necessary, IV to use for encrypting content.
 */
public class ContentKeys {
	/**
	 * The core encryption algorithms supported. Any native encryption
	 * mode supported by Java *should* work, but these are compactly
	 * encodable.
	 */
	public static final String AES_CTR_MODE = "AES/CTR/NoPadding";
	public static final String AES_CBC_MODE = "AES/CBC/PKCS5Padding";
	public static final String DEFAULT_CIPHER_ALGORITHM = AES_CTR_MODE;
	public static final String DEFAULT_KEY_ALGORITHM = "AES";
	public static final int DEFAULT_KEY_LENGTH = 16;
	
	public static final int DEFAULT_AES_KEY_LENGTH = 16; // bytes, 128 bits
	public static final int IV_MASTER_LENGTH = 8; // bytes
	public static final int SEGMENT_NUMBER_LENGTH = 6; // bytes
	public static final int BLOCK_COUNTER_LENGTH = 2; // bytes
	private static final byte [] INITIAL_BLOCK_COUNTER_VALUE = new byte[]{0x00, 0x01};

	public String _encryptionAlgorithm;
	public SecretKeySpec _encryptionKey;
	public IvParameterSpec _masterIV;
	
	/**
	 * Create a set of ContentKeys using the default cipher and key algorithm.
	 * @param key key material to be used with the default key algorithm.
	 * @param iv iv key material to be used with default key algorithm 
	 */
	public ContentKeys(byte [] key, byte [] iv) {
		assert(key.length == DEFAULT_KEY_LENGTH);
		assert(iv.length == IV_MASTER_LENGTH);
		this._encryptionAlgorithm = DEFAULT_CIPHER_ALGORITHM;
		this._encryptionKey = new SecretKeySpec(key, DEFAULT_KEY_ALGORITHM);
		this._masterIV = new IvParameterSpec(iv);
		// TODO: this assumes the default algorithms are available. Should probably check during startup
	}

	/**
	 * Create a set of ContentKeys.
	 * @param encryptionAlgorithm algorithm to use
	 * @param encryptionKey encryption key
	 * @param masterIV iv
	 * @throws NoSuchAlgorithmException if encryptionAlgorithm unknown
	 * @throws NoSuchPaddingException if encryptionAlgorithm specifies an unknown padding type
	 */
	public ContentKeys(String encryptionAlgorithm, SecretKeySpec encryptionKey,
						IvParameterSpec masterIV) throws NoSuchAlgorithmException, NoSuchPaddingException {
		// ensure NoSuchPaddingException cannot be thrown later when a Cipher is made
		Cipher.getInstance(encryptionAlgorithm);
		// TODO check secret key/iv not empty?
		this._encryptionAlgorithm = encryptionAlgorithm;
		this._encryptionKey = encryptionKey;
		this._masterIV = masterIV;
	}

	@SuppressWarnings("unused")
	private ContentKeys() {
	}
	
	/**
	 * A number of users of ContentKeys only support using the default algorithm.
	 * @throws UnsupportedOperationException if the algorithm for this object is not the default.
	 */
	public void requireDefaultAlgorithm() {
		// For now we only support the default algorithm.
		if (!_encryptionAlgorithm.equals(ContentKeys.DEFAULT_CIPHER_ALGORITHM)) {
			String err = "Right now the only encryption algorithm we support is: " + 
			ContentKeys.DEFAULT_CIPHER_ALGORITHM + ", " + _encryptionAlgorithm + 
			" will come later.";
			Log.severe(err);
			throw new UnsupportedOperationException(err);
		}
	}
	
	/**
	 * @return The base algorithm used in the encryption algorithm specified for this
	 * ContentKeys. For example, if the encryptionAlgorithm is "AES/CTR/NoPadding",
	 * the base algorithm is AES.
	 */
	public String getBaseAlgorithm() {
		if (_encryptionAlgorithm.contains("/")) {
			return _encryptionAlgorithm.substring(0, _encryptionAlgorithm.indexOf("/"));
		}
		return _encryptionAlgorithm;
	}
	
	/**
	 * Create a cipher for the encryption algorithm used by this ContentKeys
	 * @return the cipher
	 */
	public Cipher getCipher() {
		// We have tried a dummy call to Cipher.getInstance on construction of this ContentKeys - so
		// further "NoSuch" exceptions should not happen here.
		try {
			return Cipher.getInstance(_encryptionAlgorithm, KeyManager.getDefaultProvider());
		} catch (NoSuchAlgorithmException e) {
			String err = "Unexpected NoSuchAlgorithmException for an algorithm we have already used!";
			Log.severe(err);
			throw new RuntimeException(err, e);
		} catch (NoSuchPaddingException e) {
			String err = "Unexpected NoSuchPaddingException for an algorithm we have already used!";
			Log.severe(err);
			throw new RuntimeException(err, e);
		}
	}
	
	/**
	 * Create a set of random encryption/decryption keys using the default algorithm.
	 * @return a randomly-generated set of keys and IV that can be used for encryption
	 */
	public static ContentKeys generateRandomKeys() {
		return new ContentKeys(SecureRandom.getSeed(DEFAULT_KEY_LENGTH),
				SecureRandom.getSeed(IV_MASTER_LENGTH));
	}
	
	/**
	 * Make an encrypting or decrypting Cipher to be used in making a CipherStream to
	 * wrap CCN data.
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
	 * @param segmentNumber the segment number to create an encryption cipher for
	 * @throws InvalidAlgorithmParameterException 
	 * @throws InvalidKeyException 
	 */
	public Cipher getSegmentEncryptionCipher(long segmentNumber)
		throws InvalidKeyException, InvalidAlgorithmParameterException {
		return getSegmentCipher(segmentNumber, true);
	}

	/**
	 * Create a decryption cipher for the specified segment.
	 * @param segmentNumber the segment to decrypt
	 * @return the Cipher
	 * @throws InvalidKeyException
	 * @throws InvalidAlgorithmParameterException
	 * @see getSegmentEncryptionCipher(long)
	 */
	public Cipher getSegmentDecryptionCipher(long segmentNumber)
		throws InvalidKeyException, InvalidAlgorithmParameterException {
		return getSegmentCipher(segmentNumber, true);
	}

	/**
	 * Generate a segment encryption or decryption cipher using these ContentKeys
	 * to encrypt or decrypt a particular segment.
	 * @param segmentNumber segment to encrypt/decrypt
	 * @param encryption true for encryption, false for decryption
	 * @return the Cipher
	 * @throws InvalidKeyException
	 * @throws InvalidAlgorithmParameterException
	 * @see getSegmentEncryptionCipher(long)
	 */
	protected Cipher getSegmentCipher(long segmentNumber, boolean encryption)
		throws InvalidKeyException, InvalidAlgorithmParameterException {

		Cipher cipher = getCipher();

		// Construct the IV/initial counter.
		if (0 == cipher.getBlockSize()) {
			Log.warning(_encryptionAlgorithm + " is not a block cipher!");
			throw new InvalidAlgorithmParameterException(_encryptionAlgorithm + " is not a block cipher!");
		}

		if (_masterIV.getIV().length < IV_MASTER_LENGTH) {
			throw new InvalidAlgorithmParameterException("Master IV length must be at least " + IV_MASTER_LENGTH + " bytes, it is: " + _masterIV.getIV().length);
		}

		IvParameterSpec iv_ctrSpec = buildIVCtr(_masterIV, segmentNumber, cipher.getBlockSize());
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
		
		Log.finest(encryption?"En":"De"+"cryption Key: "+DataUtils.printHexBytes(_encryptionKey.getEncoded())+" iv="+DataUtils.printHexBytes(iv_ctrSpec.getIV()));
		cipher.init(encryption?Cipher.ENCRYPT_MODE:Cipher.DECRYPT_MODE, _encryptionKey, algorithmParams);

		return cipher;
	}

	/**
	 * Turn a master IV and a segment number into an IV for this segment
	 * @param masterIV the master IV
	 * @param segmentNumber the segment number
	 * @param ivLen the output IV length requested
	 * @return the IV
	 */
	public static IvParameterSpec buildIVCtr(IvParameterSpec masterIV, long segmentNumber, int ivLen) {

		Log.finest("Thread="+Thread.currentThread()+" Building IV - master="+DataUtils.printHexBytes(masterIV.getIV())+" segment="+segmentNumber+" ivLen="+ivLen);
		
		byte [] iv_ctr = new byte[ivLen];
		
		System.arraycopy(masterIV.getIV(), 0, iv_ctr, 0, IV_MASTER_LENGTH);
		byte [] byteSegNum = segmentNumberToByteArray(segmentNumber);
		System.arraycopy(byteSegNum, 0, iv_ctr, IV_MASTER_LENGTH, byteSegNum.length);
		System.arraycopy(INITIAL_BLOCK_COUNTER_VALUE, 0, iv_ctr,
				iv_ctr.length - BLOCK_COUNTER_LENGTH, BLOCK_COUNTER_LENGTH);
		
		IvParameterSpec iv_ctrSpec = new IvParameterSpec(iv_ctr);
		Log.finest("ivParameterSpec source="+DataUtils.printHexBytes(iv_ctr)+"ivParameterSpec.getIV()="+DataUtils.printHexBytes(masterIV.getIV()));
		return iv_ctrSpec;
	}
	
	/**
	 * Converts a segment number to a byte array representation (big-endian).
	 * @param segmentNumber the segment number to convert
	 * @return the byte array representation of segmentNumber
	 */
	public static byte [] segmentNumberToByteArray(long segmentNumber) {
		byte [] ba = new byte[SEGMENT_NUMBER_LENGTH];
		// Is this the fastest way to do this?
		byte [] bv = BigInteger.valueOf(segmentNumber).toByteArray();
		System.arraycopy(bv, 0, ba, SEGMENT_NUMBER_LENGTH-bv.length, bv.length);
		return ba;
	}
}