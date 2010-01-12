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
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.SecretKeySpec;

import org.ccnx.ccn.KeyManager;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.protocol.ContentName;

public abstract class ContentKeys implements Cloneable {

	/** 
	 * A simple source of key derivation material. 
	 */
	private static SecureRandom _random;

	protected String _encryptionAlgorithm;
	protected SecretKeySpec _encryptionKey;
	protected ContentName _cachedContentName;
	
	protected ContentKeys(String encryptionAlgorithm, SecretKeySpec encryptionKey) {
		_encryptionAlgorithm = encryptionAlgorithm;
		_encryptionKey = encryptionKey;
	}
	
	protected void setEncryptionAlgorithm(String encryptionAlgorithm) {
		_encryptionAlgorithm = encryptionAlgorithm;
	}
	
	protected String getEncryptionAlgorithm() { return _encryptionAlgorithm; }
	
	protected void setEncryptionKey(SecretKeySpec encryptionKey) {
		_encryptionKey = encryptionKey;
	}
	
	protected SecretKeySpec getEncryptionKey() { return _encryptionKey; }

	protected static synchronized SecureRandom getRandom() {
		// see http://www.cigital.com/justiceleague/2009/08/14/proper-use-of-javas-securerandom/
		// also Fedora seems to have screwed up the built in PRNG provider, slowing thing down dramatically
		if (null != _random)
			return _random;
		try {
			_random = SecureRandom.getInstance("SHA1PRNG", KeyManager.getDefaultProvider());
		} catch (NoSuchAlgorithmException e) {
			Log.warning("Cannot find random number generation algorithm SHA1PRNG: " + e.getMessage());
			_random = new SecureRandom();
		}
		if (null == _random) {
			Log.severe("ERROR: Cannot create secure random number generator!");
		}
		return _random;
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
	 * Make an encrypting or decrypting Cipher to be used in making a CipherStream to
	 * wrap CCN data.
	 */
	public Cipher getSegmentEncryptionCipher(ContentName contentName, long segmentNumber)
	throws InvalidKeyException, InvalidAlgorithmParameterException {
		return getSegmentCipher(contentName, segmentNumber, true);
	}

	/**
	 * Create a decryption cipher for the specified segment.
	 * @param segmentNumber the segment to decrypt
	 * @return the Cipher
	 * @throws InvalidKeyException
	 * @throws InvalidAlgorithmParameterException
	 * @see getSegmentEncryptionCipher(long)
	 */
	public Cipher getSegmentDecryptionCipher(ContentName contentName, long segmentNumber)
	throws InvalidKeyException, InvalidAlgorithmParameterException {
		return getSegmentCipher(contentName, segmentNumber, false);
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
	protected abstract Cipher getSegmentCipher(ContentName contentName, long segmentNumber, boolean encryption)
			throws InvalidKeyException, InvalidAlgorithmParameterException;
	
	/**
	 * Helper methods to let subclasses cache information that is unlikely to have changed.
	 */
	protected boolean hasCachedContentNameChanged(ContentName newContentName) {
		if (null == newContentName) {
			Log.info("Unexpected: content name is null!");
			return (getCachedContentName() == null);
		}
		return newContentName.equals(getCachedContentName());
	}
	
	protected void setCachedContentName(ContentName newContentName) {
		_cachedContentName = newContentName;
	}
	
	protected ContentName getCachedContentName() {
		return _cachedContentName;
	}

	/**
	 * Converts a segment number to a byte array representation (big-endian).
	 * @param segmentNumber the segment number to convert
	 * @return the byte array representation of segmentNumber
	 */
	public static byte [] segmentNumberToByteArray(long segmentNumber) {
		byte [] ba = new byte[KDFContentKeys.SEGMENT_NUMBER_LENGTH];
		// Is this the fastest way to do this?
		byte [] bv = BigInteger.valueOf(segmentNumber).toByteArray();
		System.arraycopy(bv, 0, ba, KDFContentKeys.SEGMENT_NUMBER_LENGTH-bv.length, bv.length);
		return ba;
	}
}