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

import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.SecretKeySpec;

import org.ccnx.ccn.KeyManager;
import org.ccnx.ccn.impl.support.Log;

public abstract class ContentKeys {

	/** 
	 * A simple source of key derivation material. 
	 */
	private static SecureRandom _random;

	public String _encryptionAlgorithm;
	public SecretKeySpec _encryptionKey;
	
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
		return getSegmentCipher(segmentNumber, false);
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
	protected abstract Cipher getSegmentCipher(long segmentNumber, boolean encryption)
			throws InvalidKeyException, InvalidAlgorithmParameterException;
}