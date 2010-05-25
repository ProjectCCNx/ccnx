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

package org.ccnx.ccn.impl.security.keys;

import java.security.Key;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.UnrecoverableEntryException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.TreeMap;
import java.io.Serializable;

import org.ccnx.ccn.KeyManager;
import org.ccnx.ccn.impl.security.crypto.CCNDigestHelper;
import org.ccnx.ccn.impl.support.ByteArrayCompare;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;


/**
 * A container for our private keys and other secret key 
 * material that we have retrieved (e.g. from access control).
 * 
 * TODO: provide mechanism to save and reload at least the non-keystore keys
 * as encrypted CCNx content.
 */
public class SecureKeyCache implements Serializable {
	
	

	/**
	 * 
	 */
	private static final long serialVersionUID = 2652940059623137734L;

	static Comparator<byte[]> byteArrayComparator = new ByteArrayCompare();
	
	/** Map the digest of a key to the key. */
	private TreeMap<byte [], Key> _keyMap = new TreeMap<byte [], Key>(byteArrayComparator);
	/** Map the digest of a public key to <I>my</I> corresponding private key. */
	private TreeMap<byte [], PrivateKey> _myKeyMap = new TreeMap<byte [], PrivateKey>(byteArrayComparator);
	/** Map the digest of a public key to the corresponding private key */
	private TreeMap<byte [], PrivateKey> _privateKeyMap = new TreeMap<byte [], PrivateKey>(byteArrayComparator);
	/** Map the digest of a private key to the digest of the corresponding public key. */
	private TreeMap<byte [], byte []> _privateKeyIdentifierMap = new TreeMap<byte [], byte[]>(byteArrayComparator);
	/** Map the name of a key to its digest */
	private TreeMap<ContentName, byte []> _nameKeyMap = new TreeMap<ContentName, byte []>();
	
	public SecureKeyCache() {
	}
	
	/**
	 * Constructor that loads keys from a KeyManager
	 * @param keyManagerToLoadFrom the key manager
	 */
	public SecureKeyCache(KeyManager keyManagerToLoadFrom) {
		PrivateKey [] pks = keyManagerToLoadFrom.getSigningKeys();
		for (PrivateKey pk : pks) {
			PublisherPublicKeyDigest ppkd = keyManagerToLoadFrom.getPublisherKeyID(pk);
			Log.info("KeyCache: loading signing key {0}", ppkd);
			addMyPrivateKey(ppkd.digest(), pk);
		}
	}
	
	/**
	 * Load the private keys from a KeyStore.
	 * @param keystore
	 * @throws KeyStoreException 
	 */
	public void loadKeyStore(KeyStoreInfo keyStoreInfo, char [] password, PublicKeyCache publicKeyCache) throws KeyStoreException {
		Enumeration<String> aliases = keyStoreInfo.getKeyStore().aliases();
		String alias;
		KeyStore.PrivateKeyEntry entry = null;
		KeyStore.PasswordProtection passwordProtection = new KeyStore.PasswordProtection(password);
		while (aliases.hasMoreElements()) {
			alias = aliases.nextElement();
			if (keyStoreInfo.getKeyStore().isKeyEntry(alias)) {
				try {
					entry = (KeyStore.PrivateKeyEntry)keyStoreInfo.getKeyStore().getEntry(alias, passwordProtection);
				} catch (NoSuchAlgorithmException e) {
					throw new KeyStoreException("Unexpected NoSuchAlgorithm retrieving key for alias : " + alias, e);
				} catch (UnrecoverableEntryException e) {
					throw new KeyStoreException("Unexpected UnrecoverableEntryException retrieving key for alias : " + alias, e);
				}
				if (null == entry) {
					Log.warning("Cannot get private key entry for alias: " + alias);
				} else {
					PrivateKey pk = entry.getPrivateKey();
					if (null != pk) {
						X509Certificate certificate = (X509Certificate)entry.getCertificate();
						if (null != certificate) {
							PublisherPublicKeyDigest ppkd = new PublisherPublicKeyDigest(certificate.getPublicKey());
							if (null != ppkd) {
								Log.info("KeyCache: loading signing key {0}, remembering public key in public key cache.", ppkd);
								addMyPrivateKey(ppkd.digest(), pk);
								publicKeyCache.remember(certificate, keyStoreInfo.getVersion());
							} else {
								Log.warning("Certificate has null public key for alias " + alias + "!");
							}
						} else {
							Log.warning("Private key for alias: " + alias + " has no certificate entry. No way to get public key. Not caching.");
						}
					} else {
						Log.warning("Cannot retrieve private key for key entry alias " + alias);
					}
				}
			}
		}
	}

	/**
	 * Retrieve a key specified by its digest
	 * To restrict access to keys, store key cache in a private variable, and don't
	 * allow references to it from untrusted code. 
	 * @param desiredKeyIdentifier the digest
	 * @return the key
	 */
	public Key getKey(byte [] desiredKeyIdentifier) {
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
	 * Retrieve a key specified by its name.
	 */
	public Key getKey(ContentName desiredKeyName) {
		byte [] keyID = _nameKeyMap.get(desiredKeyName);
		if (null != keyID) {
			return getKey(keyID);
		}
		return null;
	}
	
	/**
	 * Try both in one call.
	 */
	public Key getKey(ContentName desiredKeyName, byte [] desiredKeyID) {
		Key targetKey = null;
		
		if (null != desiredKeyID) {
			targetKey = getKey(desiredKeyID);
		}
		
		if ((null == targetKey) && (null != desiredKeyName)) {
			targetKey = getKey(desiredKeyName);
		}
		return targetKey;
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
	 * As the map from name to content is not unique, this might not give you a
	 * definite answer, and you should still check the digest.
	 * @param keyName
	 * @return
	 */
	public boolean containsKey(ContentName keyName) {
		if (_nameKeyMap.containsKey(keyName))
			return true;
		return false;
	}
	
	/**
	 * Get the key ID associated with a name, if we have one. Currently store
	 * keys under versioned names -- might be nice to effectively search
	 * over versions of a key... This can be used to look up the key, allowing
	 * the caller to be sure they have the right key.
	 */
	public byte [] getKeyID(ContentName versionedName) {
		return _nameKeyMap.get(versionedName);
	}

	/**
	 * Returns the private key corresponding to a public key specified by its digest.
	 * To restrict access to keys, store key cache in a private variable, and don't
	 * allow references to it from untrusted code. 
	 * @param desiredPublicKeyIdentifier the digest of the public key.
	 * @return the corresponding private key.
	 */
	public PrivateKey getPrivateKey(byte [] desiredPublicKeyIdentifier) {
		PrivateKey key = _myKeyMap.get(desiredPublicKeyIdentifier);
		if (null == key) {
			key = _privateKeyMap.get(desiredPublicKeyIdentifier);
		}
		return key;
	}
	
	public PrivateKey getPrivateKey(ContentName desiredKeyName) {
		byte [] keyID = _nameKeyMap.get(desiredKeyName);
		if (null != keyID) {
			return getPrivateKey(keyID);
		}
		return null;
	}
	
	public PrivateKey [] getPrivateKeys() {
		ArrayList<PrivateKey> allKeys = new ArrayList<PrivateKey>();
		
		for (PrivateKey k : _myKeyMap.values()) {
			allKeys.add(k);
		}
		
		for (PrivateKey k : _privateKeyMap.values()) {
			allKeys.add(k);
		}
		
		PrivateKey [] pkarray = new PrivateKey[allKeys.size()];
		return allKeys.toArray(pkarray);
	}
	
	/**
	 * Records a private key and the name and digest of the corresponding public key.
	 * @param keyName a name under which to look up the private key
	 * @param publicKeyIdentifier the digest of the public key
	 * @param pk the private key
	 */
	public void addPrivateKey(ContentName keyName, byte [] publicKeyIdentifier, PrivateKey pk) {
		_privateKeyMap.put(publicKeyIdentifier, pk);
		_privateKeyIdentifierMap.put(getKeyIdentifier(pk), publicKeyIdentifier);
		if (null != keyName) {
			_nameKeyMap.put(keyName, publicKeyIdentifier);
		}
	}

	/**
	 * Records one of my private keys and the digest of the corresponding public key.
	 * @param publicKeyIdentifier the digest of the public key.
	 * @param pk the corresponding private key.
	 */
	public void addMyPrivateKey(byte [] publicKeyIdentifier, PrivateKey pk) {
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
		if (null != name) {
			_nameKeyMap.put(name, id);
		}
	}
	
	public PublisherPublicKeyDigest getPublicKeyIdentifier(PrivateKey pk) {
		return new PublisherPublicKeyDigest(_privateKeyIdentifierMap.get(getKeyIdentifier(pk)));
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
