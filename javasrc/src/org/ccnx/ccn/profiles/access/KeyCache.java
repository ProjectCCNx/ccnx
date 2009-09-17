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

package org.ccnx.ccn.profiles.access;

import java.security.Key;
import java.security.PrivateKey;
import java.util.TreeMap;
import java.util.Comparator;

import org.ccnx.ccn.KeyManager;
import org.ccnx.ccn.impl.security.crypto.CCNDigestHelper;
import org.ccnx.ccn.impl.support.ByteArrayCompare;
import org.ccnx.ccn.protocol.ContentName;


/**
 * A cache for decrypted symmetric keys for access control.
 *
 */
public class KeyCache {
	
	static Comparator<byte[]> byteArrayComparator = new ByteArrayCompare();
	
	/** Map the digest of a key to the key. */
	private TreeMap<byte [], Key> _keyMap = new TreeMap<byte [], Key>(byteArrayComparator);
	/** Map the digest of a public key to <I>my</I> corresponding private key. */
	private TreeMap<byte [], PrivateKey> _myKeyMap = new TreeMap<byte [], PrivateKey>(byteArrayComparator);
	/** Map the digest of a public key to the corresponding private key */
	private TreeMap<byte [], PrivateKey> _privateKeyMap = new TreeMap<byte [], PrivateKey>(byteArrayComparator);
	/** Map the digest of a private key to the digest of the corresponding public key. */
	private TreeMap<byte [], byte []> _privateKeyIdentifierMap = new TreeMap<byte [], byte[]>(byteArrayComparator);
	/** Map the digest of a key to its name */
	private TreeMap<byte [], ContentName> _keyNameMap = new TreeMap<byte [], ContentName>(byteArrayComparator);
	
	public KeyCache() {
		this(KeyManager.getKeyManager());
	}
	
	/**
	 * Constructor that loads keys from a KeyManager
	 * @param keyManagerToLoadFrom the key manager
	 */
	public KeyCache(KeyManager keyManagerToLoadFrom) {
		PrivateKey [] pks = keyManagerToLoadFrom.getSigningKeys();
		for (PrivateKey pk : pks) {
			addMyPrivateKey(keyManagerToLoadFrom.getPublisherKeyID(pk).digest(), pk);
		}
	}
	
	/**
	 * Retrieve a key specified by its digest
	 * @param desiredKeyIdentifier the digest
	 * @return the key
	 */
	Key getKey(byte [] desiredKeyIdentifier) {
		Key theKey = _keyMap.get(desiredKeyIdentifier);
		if (null == theKey) {
			theKey = _privateKeyMap.get(desiredKeyIdentifier);
		}
		if (null == theKey) {
			theKey = _myKeyMap.get(desiredKeyIdentifier);
		}
		return theKey;
	}

	/**
	 * Checks whether we have a record of a key specified by its digest.
	 * @param keyIdentifier the key digest.
	 * @return
	 */
	public boolean containsKey(byte [] keyIdentifier) {
		if ((_keyMap.containsKey(keyIdentifier)) || (_myKeyMap.containsKey(keyIdentifier)) ||
					(_privateKeyMap.containsKey(keyIdentifier))) {
			return true;
		}
		return false;
	}

	/**
	 * Returns the name of a key specified by its digest
	 * @param keyIdentifier the digest of the key.
	 * @return the name of the key.
	 */
	public ContentName getKeyName(byte [] keyIdentifier) {
		return _keyNameMap.get(keyIdentifier);
	}
	
	/**
	 * Get the name of a specified key
	 * @param key the key
	 * @return the name
	 */
	public ContentName getKeyName(Key key) {
		return getKeyName(getKeyIdentifier(key));
	}

	/**
	 * Returns the private key corresponding to a public key specified by its digest.
	 * @param desiredPublicKeyIdentifier the digest of the public key.
	 * @return the corresponding private key.
	 */
	PrivateKey getPrivateKey(byte [] desiredPublicKeyIdentifier) {
		PrivateKey key = _myKeyMap.get(desiredPublicKeyIdentifier);
		if (null == key) {
			key = _privateKeyMap.get(desiredPublicKeyIdentifier);
		}
		return key;
	}
	
	/**
	 * Records a private key and the name and digest of the corresponding public key.
	 * @param keyName the name of the public key
	 * @param publicKeyIdentifier the digest of the public key
	 * @param pk the private key
	 */
	void addPrivateKey(ContentName keyName, byte [] publicKeyIdentifier, PrivateKey pk) {
		_privateKeyMap.put(publicKeyIdentifier, pk);
		_privateKeyIdentifierMap.put(getKeyIdentifier(pk), publicKeyIdentifier);
		if (null != keyName)
			_keyNameMap.put(publicKeyIdentifier, keyName);
	}

	/**
	 * Records one of my private keys and the digest of the corresponding public key.
	 * @param publicKeyIdentifier the digest of the public key.
	 * @param pk the corresponding private key.
	 */
	void addMyPrivateKey(byte [] publicKeyIdentifier, PrivateKey pk) {
		_privateKeyIdentifierMap.put(getKeyIdentifier(pk), publicKeyIdentifier);
		_myKeyMap.put(publicKeyIdentifier, pk);
	}
	
	/**
	 * Make a record of a key by its name and digest.
	 * @param name the name of the key.
	 * @param key the key.
	 */
	public void addKey(ContentName name, Key key) {
		byte [] id = getKeyIdentifier(key);
		_keyMap.put(id, key);
		_keyNameMap.put(id, name);
	}
	
	/**
	 * Returns the digest of a specified key.
	 * @param key the key.
	 * @return the digest.
	 */
	public static byte [] getKeyIdentifier(Key key) {
		// Works on symmetric and public.
		return CCNDigestHelper.digest(key.getEncoded());
	}
}
