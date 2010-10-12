/*
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

import java.security.InvalidKeyException;
import java.security.Key;
import java.security.NoSuchAlgorithmException;

import javax.crypto.NoSuchPaddingException;

import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.ContentEncodingException;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;


/**
 * 
 * A subclass of EncryptedIVStaticContentKeys that uses the methods from
 * that class to derive a per-segment key and IV from a master seed, that
 * itself is derived from content name and publisher information (plus
 * a text label) using the key derivation function described in 
 * KeyDerivationFunction.
 */
public class KDFContentKeys extends EncryptedIVStaticContentKeys implements Cloneable {
	
	protected String _label;
	
	/**
	 * EncryptedIVStaticContentKeys constructor.
	 * @param encryptionAlgorithm (e.g. AES/CTR/NoPadding) the encryption algorithm to use.
	 * 		First component of algorithm should be the algorithm associated with the key.
	 * @param key key material to be used
	 * @param ivctr iv or counter material to be used with specified algorithm 
	 * @throws NoSuchPaddingException 
	 * @throws NoSuchAlgorithmException 
	 */
	public KDFContentKeys(String encryptionAlgorithm, byte [] masterKey, String label) 
					throws NoSuchAlgorithmException, NoSuchPaddingException {
		super(encryptionAlgorithm, masterKey, null);
		_label = label;
	}
	
	/**
	 * Create a EncryptedIVStaticContentKeys with the default algorithm.
	 * @throws NoSuchPaddingException 
	 * @throws NoSuchAlgorithmException 
	 */
	public KDFContentKeys(byte [] masterKey, String label) throws NoSuchAlgorithmException, NoSuchPaddingException {
		super(null, masterKey, null);
		_label = label;
	}
	
	/**
	 * KDFContentKeys constructor.
	 */
	public KDFContentKeys(String encryptionAlgorithm, Key masterKey, String label) throws NoSuchAlgorithmException, NoSuchPaddingException {
		super(encryptionAlgorithm, masterKey, null);
		_label = label;
	}
	
	public KDFContentKeys(KDFContentKeys other) {
		super(other);
		_label = other._label;
	}
	
	public KDFContentKeys(ContentKeys other, String label) {
		super(other);
		_label = label;
	}
		
	protected synchronized KeyAndIV getKeyAndIVForContent(ContentName contentName, PublisherPublicKeyDigest publisher, long segmentNumber) throws InvalidKeyException, ContentEncodingException {
		ContentInfo contentInfo = new ContentInfo(contentName, publisher, getLabel());
		KeyAndIV keyAndIV = getCachedKeyInformation(contentInfo);
		if (null != keyAndIV) {
			return keyAndIV;
		}
		keyAndIV = KeyDerivationFunction.DeriveKeysForObject(getKeyAlgorithm(), getMasterKey().getEncoded(), contentInfo);
		addCachedKeyInformation(contentInfo, keyAndIV);
		Log.finer("KDFContentKeys: key for {0} publisher {1} and segment " + segmentNumber + " is {2}", contentName, publisher, keyAndIV);
		return keyAndIV;
	}

	/**
	 * Create a set of random encryption/decryption keys using the default algorithm.
	 * @return a randomly-generated set of keys and IV that can be used for encryption
	 * @throws NoSuchPaddingException 
	 * @throws NoSuchAlgorithmException 
	 */
	public static synchronized ContentKeys generateRandomKeys(String label) throws NoSuchAlgorithmException, NoSuchPaddingException {
		return new KDFContentKeys(StaticContentKeys.generateRandomKeys(), label);
	}

	public KDFContentKeys clone() {
		return new KDFContentKeys(this);
	}	
	
	public String getLabel() { return _label; }
	
	public void setLabel(String newLabel) { _label = newLabel; }
}
