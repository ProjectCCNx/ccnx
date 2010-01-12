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

import java.security.AlgorithmParameters;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidParameterSpecException;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.ccnx.ccn.KeyManager;
import org.ccnx.ccn.impl.security.crypto.util.CryptoConstants;
import org.ccnx.ccn.impl.support.DataUtils;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.ContentEncodingException;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;


/**
 * Specifies encryption algorithm, keys and if necessary IV to use for encrypting
 * or decrypting content.
 * 
 * The segmenter will be called with parameters identifying:
 *
 *   * the encryption algorithm and mode to use, if any
 *   * the encryption key to use for this particular data item o (the object to be segmented)
 *   * an 8-byte value used as an IV seed for this item (CBC mode) or a random counter 
 *   		component (CTR) (derived in KeyDerivation)
 *   * the desired full segment (packet) length, including supporting data 
 * 
 * In CTR mode, the counter for a given block B (number Bnum) in segment Snum will be constructed as follows: 
 * 
 * 	CTR = IVseed || Snum || Bnum
 * 
 * where the segment and block numbers is represented in unsigned, 1-based big endian format. 
 * The total width of the counter value is 16 bytes, where the first 8 bytes are the IV seed 
 * value, the next 6 bytes are the segment number, and the last 2 bytes are the block number. 
 * A single-segment object following the SegmentationProfile? will still have a segment number 
 * component in its name, and will follow the specification above for managing its encryption keys.
 * 
 * In CBC mode, the input IV will be used as a seed to generate an IV for each segment S as follows:
 * 
 *   IV = Eko (IVseed || Snum || B0)
 * 
 * Where the segment number is encoded in 1-based, unsigned, big-endian form, and 
 * represented in the B-L rightmost bytes of the plaintext above, where B is the width of 
 * the block cipher in use, and L is the length of the numeric representation of the 
 * segment number. B0 = 1 to maintain consistency with standard CTR mode use. The same IV 
 * expansion function is used regardless of mode for simplicity.
 * The encryption is done with the specified key, in CBC mode, using the all-zeros IV
 */
public class KDFContentKeys extends ContentKeys implements Cloneable {
	
	/**
	 * TODO these should probably move somewhere.
	 */
	public static final String DEFAULT_KEY_ALGORITHM = CryptoConstants.AES_ALGORITHM;
	public static final String DEFAULT_CIPHER_ALGORITHM = CryptoConstants.AES_CTR_MODE;
	public static final int DEFAULT_KEY_LENGTH = 16;
	
	public static final int DEFAULT_AES_KEY_LENGTH = DEFAULT_KEY_LENGTH; // bytes, 128 bits (do NOT increase for AES,
																		 // security of AES-192 and AES-256 actually
																		 // more suspect than AES-128
	public static final int IV_MASTER_LENGTH = 8; // bytes
	public static final int SEGMENT_NUMBER_LENGTH = 6; // bytes
	public static final int BLOCK_COUNTER_LENGTH = 2; // bytes
	private static final byte [] INITIAL_BLOCK_COUNTER_VALUE = new byte[]{0x00, 0x01};
	
	public IvParameterSpec _masterIV;
	
	/**
	 * ContentKeys constructor using default cipher and key algorithm.
	 * @param encryptionAlgorithm (e.g. AES/CTR/NoPadding) the encryption algorithm to use.
	 * 		First component of algorithm should be the algorithm associated with the key.
	 * @param key key material to be used
	 * @param ivctr iv or counter material to be used with specified algorithm 
	 * @throws NoSuchPaddingException 
	 * @throws NoSuchAlgorithmException 
	 */
	public KDFContentKeys(String encryptionAlgorithm, byte [] key, byte [] ivctr) throws NoSuchAlgorithmException, NoSuchPaddingException {
		assert(null != key);
		assert(null != ivctr);
		if (null != encryptionAlgorithm) {
			Cipher.getInstance(encryptionAlgorithm, KeyManager.getDefaultProvider());
		}
		this._encryptionAlgorithm = (null != encryptionAlgorithm) ? encryptionAlgorithm : DEFAULT_CIPHER_ALGORITHM;
		this._encryptionKey = new SecretKeySpec(key, encryptionAlgorithm.substring(0, encryptionAlgorithm.indexOf('/')));
		this._masterIV = new IvParameterSpec(ivctr);
		// TODO: this assumes the default algorithms are available. Should probably check during startup
	}
	
	/**
	 * Create a ContentKeys with the default algorithm.
	 */
	public KDFContentKeys(byte [] key, byte [] ivctr) {
		assert(null != key);
		assert(null != ivctr);
		this._encryptionAlgorithm = DEFAULT_CIPHER_ALGORITHM;
		this._encryptionKey = new SecretKeySpec(key, DEFAULT_KEY_ALGORITHM);
		this._masterIV = new IvParameterSpec(ivctr);
	}

	/**
	 * ContentKeys constructor; builds IV or CTR from master seed.
	 * @param encryptionAlgorithm algorithm to use
	 * @param encryptionKey encryption key
	 * @param masterIV iv
	 * @throws NoSuchAlgorithmException if encryptionAlgorithm unknown
	 * @throws NoSuchPaddingException if encryptionAlgorithm specifies an unknown padding type
	 */
	public KDFContentKeys(String encryptionAlgorithm, SecretKeySpec encryptionKey,
						IvParameterSpec masterIV) throws NoSuchAlgorithmException, NoSuchPaddingException {
		this._encryptionAlgorithm = (null != encryptionAlgorithm) ? encryptionAlgorithm : DEFAULT_CIPHER_ALGORITHM;
		// ensure NoSuchPaddingException cannot be thrown later when a Cipher is made
		Cipher.getInstance(_encryptionAlgorithm, KeyManager.getDefaultProvider());
		// TODO check secret key/iv not empty?
		this._encryptionKey = encryptionKey;
		this._masterIV = masterIV;
	}
	
	/**
	 * Create a ContentKeys from a master secret key, using the KeyDerivationFunction
	 * to derive a subkey and master IV unique to this ContentName/Publisher combination.
	 * @param encryptionAlgorithm encryption algorithm and cipher mode to use (if null, default to AES/CTR/NoPadding)
	 * @param masterKeyBytes the master key from which to derive the key and IV
	 * @param keyBitLength the key length in bits to derive
	 * @param ivBitLength the IV length in bits to derive
	 * @param label a label to add diversity to the key derivcation function. If you use keys
	 * 		for different functions, associate each function with a label. To arrive at the same
	 * 		key, callers must supply not only the same masterKeyByte, contentName and publisher, but also
	 * 		the same label.
	 * @param contentName the name of the content for which we are deriving a key (including versions, but not
	 * 		segment numbers)
	 * @param publisher the publisher of the content for which we are deriving a key
	 * @throws ContentEncodingException 
	 * @throws InvalidKeyException 
	 * @throws NoSuchPaddingException 
	 * @throws NoSuchAlgorithmException 
	 */
	public KDFContentKeys(String encryptionAlgorithm, byte [] masterKeyBytes, 
					   int keyBitLength, int ivBitLength,
					   String label,
					   ContentName contentName, 
					   PublisherPublicKeyDigest publisher) throws InvalidKeyException, ContentEncodingException, NoSuchAlgorithmException, NoSuchPaddingException {
		
		this._encryptionAlgorithm = (null != encryptionAlgorithm) ? encryptionAlgorithm : DEFAULT_CIPHER_ALGORITHM;
		// ensure NoSuchPaddingException cannot be thrown later when a Cipher is made
		Cipher.getInstance(_encryptionAlgorithm, KeyManager.getDefaultProvider());
		byte [][] keyAndIV = KeyDerivationFunction.DeriveKeysForObject(masterKeyBytes, keyBitLength, ivBitLength, label, contentName, publisher);
		
		this._encryptionKey = new SecretKeySpec(keyAndIV[0], getBaseAlgorithm());
		this._masterIV = new IvParameterSpec(keyAndIV[1]);
	}
	
	public KDFContentKeys(KDFContentKeys other) {
		_encryptionAlgorithm = other._encryptionAlgorithm;
		_encryptionKey = other._encryptionKey;
		_masterIV = other._masterIV;
	}
	
	@SuppressWarnings("unused")
	private KDFContentKeys() {
	}
	
	/**
	 * Create a set of random encryption/decryption keys using the default algorithm.
	 * @return a randomly-generated set of keys and IV that can be used for encryption
	 */
	public static synchronized ContentKeys generateRandomKeys() {
		byte [] key = new byte[DEFAULT_KEY_LENGTH];
		byte [] iv = new byte[IV_MASTER_LENGTH];
		// do we want additional whitening?
		SecureRandom random = ContentKeys.getRandom();
		random.nextBytes(key);
		random.nextBytes(iv);
		return new KDFContentKeys(key, iv);
	}

	public KDFContentKeys clone() {
		return new KDFContentKeys(this);
	}

	/**
	 * Test if this is using the default encryption algorithm.
	 * A number of users of ContentKeys only support using the default algorithm, and use this to verify.
	 * @throws UnsupportedOperationException if the algorithm for this object is not the default.
	 */
	public void requireDefaultAlgorithm() {
		// For now we only support the default algorithm.
		if (!_encryptionAlgorithm.equals(KDFContentKeys.DEFAULT_CIPHER_ALGORITHM)) {
			String err = "Right now the only encryption algorithm we support is: " + 
			KDFContentKeys.DEFAULT_CIPHER_ALGORITHM + ", " + _encryptionAlgorithm + 
			" will come later.";
			Log.severe(err);
			throw new UnsupportedOperationException(err);
		}
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
	 * @see getSegmentEncryptionCipher(long)
	 */
	protected Cipher getSegmentCipher(ContentName contentName, long segmentNumber, boolean encryption)
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
		cipher.init(encryption ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, _encryptionKey, algorithmParams);

		return cipher;
	}
	
	public static byte [] segmentSeedValue(IvParameterSpec masterIV, long segmentNumber, int seedLen) {
		
		byte [] seed = new byte[seedLen];
		
		System.arraycopy(masterIV.getIV(), 0, seed, 0, masterIV.getIV().length);
		byte [] byteSegNum = ContentKeys.segmentNumberToByteArray(segmentNumber);
		System.arraycopy(byteSegNum, 0, seed, masterIV.getIV().length, byteSegNum.length);
		System.arraycopy(INITIAL_BLOCK_COUNTER_VALUE, 0, seed,
				seed.length - BLOCK_COUNTER_LENGTH, BLOCK_COUNTER_LENGTH);
		return seed;
	}
	
	public IvParameterSpec buildIVCtr(IvParameterSpec masterIV, long segmentNumber, int ivCtrLen) throws InvalidKeyException, InvalidAlgorithmParameterException {
		
		if (_encryptionAlgorithm.contains(CryptoConstants.CTR_MODE)) {
			return buildCtr(masterIV, segmentNumber, ivCtrLen);
		} else {
			return buildIV(masterIV, segmentNumber, ivCtrLen);
		}
	}

	/**
	 * Turn a master IV and a segment number into an initial counter for this segment
	 * (used in CTR mode).
	 * @param masterIV the master IV
	 * @param segmentNumber the segment number
	 * @param ctrLen the output IV length requested
	 * @return the initial counter
	 */
	public IvParameterSpec buildCtr(IvParameterSpec masterIV, long segmentNumber, int ctrLen) {

		Log.finest("Thread="+Thread.currentThread()+" Building CTR - master="+DataUtils.printHexBytes(masterIV.getIV())+" segment="+segmentNumber+" ctrLen="+ctrLen);
		
		byte [] ctr = segmentSeedValue(masterIV, segmentNumber, ctrLen);
		
		IvParameterSpec ctrSpec = new IvParameterSpec(ctr);
		Log.finest("CTR: ivParameterSpec source="+DataUtils.printHexBytes(ctr)+"ivParameterSpec.getIV()="+DataUtils.printHexBytes(masterIV.getIV()));
		return ctrSpec;
	}
	
	/**
	 * Turn a master IV and a segment number into an IV for this segment
	 * (used in CBC mode).
	 * TODO check use of input and output lengths
	 * @param masterIV the master IV
	 * @param segmentNumber the segmeont number
	 * @param ivLen the output IV length requested
	 * @return the IV
	 * @throws InvalidAlgorithmParameterException 
	 * @throws InvalidKeyException 
	 * @throws BadPaddingException 
	 * @throws IllegalBlockSizeException 
	 */
	public IvParameterSpec buildIV(IvParameterSpec masterIV, long segmentNumber, int ivLen) throws InvalidKeyException, InvalidAlgorithmParameterException {
		Log.finest("Thread="+Thread.currentThread()+" Building CTR - master="+DataUtils.printHexBytes(masterIV.getIV())+" segment="+segmentNumber+" ivLen="+ivLen);

		Cipher cipher = getCipher();
		IvParameterSpec zeroIv = new IvParameterSpec(new byte[cipher.getBlockSize()]);
		cipher.init(Cipher.ENCRYPT_MODE, _encryptionKey, zeroIv);

		byte [] iv_input = segmentSeedValue(masterIV, segmentNumber, ivLen);

		byte[] iv_output;
		try {
			iv_output = cipher.doFinal(iv_input);
		} catch (IllegalBlockSizeException e) {
			String err = "Unexpected IllegalBlockSizeException for an algorithm we have already used! Rethrowing as InvalidAlgorithmParameterException.";
			Log.severe(err);
			throw new InvalidAlgorithmParameterException(err, e);
		} catch (BadPaddingException e) {
			String err = "Unexpected BadPaddingException for an algorithm we have already used! Rethrowing as InvalidAlgorithmParameterException.";
			Log.severe(err);
			throw new InvalidAlgorithmParameterException(err, e);
		}

		IvParameterSpec iv = new IvParameterSpec(iv_output, 0, ivLen);
		Log.finest("IV: ivParameterSpec source="+DataUtils.printHexBytes(iv_output)+"ivParameterSpec.getIV()="+DataUtils.printHexBytes(masterIV.getIV()));
		return iv;
	}

}