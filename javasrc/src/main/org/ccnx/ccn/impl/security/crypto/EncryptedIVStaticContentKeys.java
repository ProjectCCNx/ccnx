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

import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.NoSuchAlgorithmException;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.IvParameterSpec;

import org.ccnx.ccn.impl.security.crypto.util.CryptoConstants;
import org.ccnx.ccn.impl.support.DataUtils;
import org.ccnx.ccn.impl.support.Log;


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
 * 
 * IMPORTANT NOTE: Do not use static keying to encrypt network objects in CTR mode, unless
 * you are careful to only save them once per key. Use CBC mode (under development) or
 * a dynamic keying method, such as KDFContentKeys.
 */
public class EncryptedIVStaticContentKeys extends StaticContentKeys implements Cloneable {
	
	/**
	 * EncryptedIVStaticContentKeys constructor.
	 * @param encryptionAlgorithm (e.g. AES/CTR/NoPadding) the encryption algorithm to use.
	 * 		First component of algorithm should be the algorithm associated with the key.
	 * @param key key material to be used
	 * @param ivctr iv or counter material to be used with specified algorithm 
	 * @throws NoSuchPaddingException 
	 * @throws NoSuchAlgorithmException 
	 */
	public EncryptedIVStaticContentKeys(String encryptionAlgorithm, byte [] key, byte [] ivctr) 
					throws NoSuchAlgorithmException, NoSuchPaddingException {
		super(encryptionAlgorithm, key, ivctr);
	}
	
	/**
	 * Create a EncryptedIVStaticContentKeys with the default algorithm.
	 * @throws NoSuchPaddingException 
	 * @throws NoSuchAlgorithmException 
	 */
	public EncryptedIVStaticContentKeys(byte [] key, byte [] ivctr) throws NoSuchAlgorithmException, NoSuchPaddingException {
		super(null, key, ivctr);
	}
		
	/**
	 * EncryptedIVStaticContentKeys constructor.
	 */
	public EncryptedIVStaticContentKeys(String encryptionAlgorithm, Key key, byte [] ivCtr) throws NoSuchAlgorithmException, NoSuchPaddingException {
		super(encryptionAlgorithm, key, ivCtr);
	}
		
	public EncryptedIVStaticContentKeys(ContentKeys other) {
		super(other);
	}
	
	/**
	 * Create a set of random encryption/decryption keys using the default algorithm.
	 * @return a randomly-generated set of keys and IV that can be used for encryption
	 * @throws NoSuchPaddingException 
	 * @throws NoSuchAlgorithmException 
	 */
	public static synchronized ContentKeys generateRandomKeys() throws NoSuchAlgorithmException, NoSuchPaddingException {
		return new EncryptedIVStaticContentKeys(StaticContentKeys.generateRandomKeys());
	}

	public EncryptedIVStaticContentKeys clone() {
		return (EncryptedIVStaticContentKeys)super.clone();
	}
	
	public IvParameterSpec buildIVCtr(KeyAndIV keyAndIV, long segmentNumber, int ivCtrLen) throws InvalidKeyException, InvalidAlgorithmParameterException {
		
		if (_encryptionAlgorithm.contains(CryptoConstants.CTR_MODE)) {
			return super.buildIVCtr(keyAndIV, segmentNumber, ivCtrLen);
		} else {
			return buildEncryptedIV(keyAndIV, segmentNumber, ivCtrLen);
		}
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
	public IvParameterSpec buildEncryptedIV(KeyAndIV keyAndIV, long segmentNumber, int ivLen) throws InvalidKeyException, InvalidAlgorithmParameterException {
		Log.finest("Thread="+Thread.currentThread()+" Building CTR - master="+DataUtils.printHexBytes(keyAndIV.getIV())+" segment="+segmentNumber+" ivLen="+ivLen);

		Cipher cipher = getCipher();
		IvParameterSpec zeroIv = new IvParameterSpec(new byte[cipher.getBlockSize()]);
		cipher.init(Cipher.ENCRYPT_MODE, keyAndIV.getKey(), zeroIv);

		byte [] iv_input = segmentSeedValue(keyAndIV.getIV(), segmentNumber, ivLen);

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
		Log.finest("IV: ivParameterSpec source="+DataUtils.printHexBytes(iv_output)+"ivParameterSpec.getIV()="+DataUtils.printHexBytes(keyAndIV.getIV()));
		return iv;
	}

}
