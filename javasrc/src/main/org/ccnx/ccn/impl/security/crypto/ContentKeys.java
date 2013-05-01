/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008, 2009, 2010, 2013 Palo Alto Research Center, Inc.
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
import java.security.Key;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashMap;

import javax.crypto.Cipher;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.SecretKeySpec;

import org.ccnx.ccn.KeyManager;
import org.ccnx.ccn.impl.security.crypto.util.CryptoConstants;
import org.ccnx.ccn.impl.support.DataUtils;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.ContentEncodingException;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;

/**
 * ContentKeys is a container class holding a key and optional IV or counter value,
 * plus an algorithm specifier. It is used to carry the state necessary to perform
 * symmetric encryption of content. To do so, it requires a function that maps from
 * a key set to the keying data to be used to encrypt/decrypt a specific block of
 * content (see getSegmentEncryptionCipher and getSegmentDecryptionCipher), which may,
 * either use this key material directly or use a key derivation function to obtain
 * subkeys specific to each segment.
 *
 */
public abstract class ContentKeys implements Cloneable {

	public static final String DEFAULT_KEY_ALGORITHM = CryptoConstants.AES_ALGORITHM;
	public static final String DEFAULT_CIPHER_ALGORITHM = CryptoConstants.AES_CTR_MODE;
	public static final int DEFAULT_KEY_LENGTH = 16; // bytes, 128 bits (do NOT increase for AES,
	 												 // security of AES-192 and AES-256 actually
												     // more suspect than AES-128
	
	/** 
	 * A simple source of key derivation material. 
	 */
	private static SecureRandom _random;

	protected String _encryptionAlgorithm;
	protected KeyAndIV _masterKeyAndIVCtr;
	
	/**
	 * Not used in this class, but available to subclasses.
	 */
	protected HashMap<ContentInfo, KeyAndIV> _keyCache;
	
	public static class KeyAndIV {
		private SecretKeySpec _key;
		private byte [] _iv;
		
		public KeyAndIV(String algorithm, byte [] key, byte [] iv) {
			_key = new SecretKeySpec(key, algorithm);
			_iv = iv;
		}
		
		public KeyAndIV(Key key, byte [] iv) {
			_key = new SecretKeySpec(key.getEncoded(), key.getAlgorithm());
			_iv = iv;
		}

		public SecretKeySpec getKey() { return _key; }
		
		public byte [] getIV() { return _iv; }

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + Arrays.hashCode(_iv);
			result = prime * result + ((_key == null) ? 0 : _key.hashCode());
			return result;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			KeyAndIV other = (KeyAndIV) obj;
			if (!Arrays.equals(_iv, other._iv))
				return false;
			if (_key == null) {
				if (other._key != null)
					return false;
			} else if (!_key.equals(other._key))
				return false;
			return true;
		}
		
		@Override
		public String toString() {
			return "Key: " + DataUtils.printHexBytes(_key.getEncoded()) + " IV: " + DataUtils.printHexBytes(_iv);
		}
	}
	
	public static class ContentInfo {
		private ContentName _contentName;
		private PublisherPublicKeyDigest _publisher;
		private String _label; 
		
		public ContentInfo(ContentName contentName, PublisherPublicKeyDigest publisher, String label) {
			_contentName = contentName;
			_publisher = publisher;
			_label = label;
		}
		
		public ContentName getContentName() { return _contentName; }
		
		public PublisherPublicKeyDigest getPublisher() { return _publisher; }
		
		public String getLabel() { return _label; }

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result
					+ ((_contentName == null) ? 0 : _contentName.hashCode());
			result = prime * result
					+ ((_label == null) ? 0 : _label.hashCode());
			result = prime * result
					+ ((_publisher == null) ? 0 : _publisher.hashCode());
			return result;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			ContentInfo other = (ContentInfo) obj;
			if (_contentName == null) {
				if (other._contentName != null)
					return false;
			} else if (!_contentName.equals(other._contentName))
				return false;
			if (_label == null) {
				if (other._label != null)
					return false;
			} else if (!_label.equals(other._label))
				return false;
			if (_publisher == null) {
				if (other._publisher != null)
					return false;
			} else if (!_publisher.equals(other._publisher))
				return false;
			return true;
		}
	}
	
	private ContentKeys(String encryptionAlgorithm) throws NoSuchAlgorithmException, NoSuchPaddingException {
		if (null != encryptionAlgorithm) {
			Cipher.getInstance(encryptionAlgorithm, KeyManager.PROVIDER);
			_encryptionAlgorithm = encryptionAlgorithm;
		} else {
			_encryptionAlgorithm = DEFAULT_CIPHER_ALGORITHM;
		}		
	}

	protected ContentKeys(String encryptionAlgorithm, byte [] masterEncryptionKey, byte [] masterIVCtr) throws NoSuchAlgorithmException, NoSuchPaddingException  {
		this(encryptionAlgorithm);
		Log.finer("ContentKeys: initializing key for algorithm {0}, master key {1}, iv/ctr {2}", encryptionAlgorithm,
				DataUtils.printHexBytes(masterEncryptionKey), DataUtils.printHexBytes(masterIVCtr));
		this._masterKeyAndIVCtr = new KeyAndIV(_encryptionAlgorithm.substring(0, _encryptionAlgorithm.indexOf('/')), masterEncryptionKey, masterIVCtr);
	}
	
	protected ContentKeys(String encryptionAlgorithm, Key masterEncryptionKey, byte [] masterIVCtr) throws NoSuchAlgorithmException, NoSuchPaddingException  {
		this(encryptionAlgorithm);
		this._masterKeyAndIVCtr = new KeyAndIV(masterEncryptionKey, masterIVCtr);
	}

	public ContentKeys(ContentKeys other) {
	}
	
	/**
	 * Get the full algorithm specification, including mode and padding.
	 * @return
	 */
	protected String getEncryptionAlgorithm() { return _encryptionAlgorithm; }
	
	/**
	 * Get the simple algorithm specification for the algorithm used by the key (e.g. "AES").
	 * @return
	 */
	protected String getKeyAlgorithm() { return _masterKeyAndIVCtr.getKey().getAlgorithm(); }

	protected static synchronized SecureRandom getRandom() {
		// see http://www.cigital.com/justiceleague/2009/08/14/proper-use-of-javas-securerandom/
		// also Fedora seems to have screwed up the built in PRNG provider, slowing thing down dramatically
		if (null != _random)
			return _random;
		try {
			_random = SecureRandom.getInstance("SHA1PRNG", KeyManager.PROVIDER);
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
	 * Put this here temporarily. It will disappear as soon as we get the rest of the CBC code
	 * in place. 
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
			return Cipher.getInstance(_encryptionAlgorithm, KeyManager.PROVIDER);
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
	 * @throws ContentEncodingException 
	 */
	public Cipher getSegmentEncryptionCipher(ContentName contentName, PublisherPublicKeyDigest publisher, long segmentNumber)
	throws InvalidKeyException, InvalidAlgorithmParameterException, ContentEncodingException {
		return getSegmentCipher(contentName, publisher, segmentNumber, true);
	}

	/**
	 * Create a decryption cipher for the specified segment.
	 * @param segmentNumber the segment to decrypt
	 * @return the Cipher
	 * @throws InvalidKeyException
	 * @throws InvalidAlgorithmParameterException
	 * @throws ContentEncodingException 
	 * @see getSegmentEncryptionCipher(long)
	 */
	public Cipher getSegmentDecryptionCipher(ContentName contentName, PublisherPublicKeyDigest publisher, long segmentNumber)
	throws InvalidKeyException, InvalidAlgorithmParameterException, ContentEncodingException {
		return getSegmentCipher(contentName, publisher, segmentNumber, false);
	}

	/**
	 * Generate a segment encryption or decryption cipher using these ContentKeys
	 * to encrypt or decrypt a particular segment.
	 * @param segmentNumber segment to encrypt/decrypt
	 * @param encryption true for encryption, false for decryption
	 * @return the Cipher
	 * @throws InvalidKeyException
	 * @throws InvalidAlgorithmParameterException
	 * @throws ContentEncodingException 
	 * @see getSegmentEncryptionCipher(long)
	 */
	protected abstract Cipher getSegmentCipher(ContentName contentName, PublisherPublicKeyDigest publisher, long segmentNumber, boolean encryption)
			throws InvalidKeyException, InvalidAlgorithmParameterException, ContentEncodingException;
	
	/**
	 * Helper methods to let subclasses cache derived key information that might be
	 * expensive to re-derive.
	 */
	protected synchronized boolean hasCachedKeyInformation(ContentInfo contentInfo) {
		if (null == _keyCache) {
			return false;
		}
		if (null == contentInfo) {
			Log.info("Unexpected: content info is null!");
			return false;
		}
		return (null != getCachedKeyInformation(contentInfo));
	}
	
	protected synchronized void addCachedKeyInformation(ContentInfo contentInfo, KeyAndIV keyAndIV) {
		if (null == _keyCache) {
			_keyCache = new HashMap<ContentInfo, KeyAndIV>();
		}
		_keyCache.put(contentInfo, keyAndIV);
	}
	
	protected synchronized KeyAndIV getCachedKeyInformation(ContentInfo contentInfo) {
		if (null == _keyCache) {
			return null;
		}
		return _keyCache.get(contentInfo);
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
	
	public ContentKeys clone() {
		try {
			ContentKeys ck = (ContentKeys)super.clone();
			// probably should clone
			ck._encryptionAlgorithm = this._encryptionAlgorithm;
			ck._masterKeyAndIVCtr = this._masterKeyAndIVCtr;
			return ck;
		} catch (CloneNotSupportedException e) {
			throw new AssertionError(e);
		}
	}
	
	public Key getMasterKey() {
		return _masterKeyAndIVCtr.getKey();
	}

	public byte [] getMasterIVCtr() {
		return _masterKeyAndIVCtr.getIV();
	}
}
