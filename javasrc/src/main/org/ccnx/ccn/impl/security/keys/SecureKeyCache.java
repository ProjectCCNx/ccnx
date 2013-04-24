/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008-2013 Palo Alto Research Center, Inc.
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

import java.io.Serializable;
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
import java.util.logging.Level;

import javax.crypto.SecretKey;

import org.ccnx.ccn.KeyManager;
import org.ccnx.ccn.impl.security.crypto.CCNDigestHelper;
import org.ccnx.ccn.impl.support.ByteArrayCompare;
import org.ccnx.ccn.impl.support.DataUtils;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;


/**
 * A container for our private keys and other secret key 
 * material that we have retrieved (e.g. from access control).
 * 
 * TODO: finish mechanism that saves the key cache between runs.
 */
public class SecureKeyCache implements Serializable {
	
	/**
	 * 
	 */
	private static final long serialVersionUID = 2652940059623137734L;
	
	public static String privateKeyFormat = "PKCS#8";

	static Comparator<byte[]> byteArrayComparator = new ByteArrayCompare();
	
	/** Map the digest of a key to the key. */
	private TreeMap<byte [], Key> _keyMap = new TreeMap<byte [], Key>(byteArrayComparator);
	/** Map the digest of a public key to <I>my</I> corresponding private key. */
	private TreeMap<byte [], PrivateKey> _myKeyMap = new TreeMap<byte [], PrivateKey>(byteArrayComparator);
	/** Map the digest of a public key to the corresponding private key */
	private TreeMap<byte [], PrivateKey> _privateKeyMap = new TreeMap<byte [], PrivateKey>(byteArrayComparator);
	/** Map the digest of a secret key to the corresponding key. 
	 * TODO - do we need to keep the secretKeyMap & privateKeyMap separate? */
	private TreeMap<byte[], SecretKey> _secretKeyMap = new TreeMap<byte[], SecretKey>(byteArrayComparator);
	private TreeMap<byte [], byte []> _privateKeyIdentifierMap = new TreeMap<byte [], byte[]>(byteArrayComparator);
	/** Map the name of a key to its digest */
	private TreeMap<ContentName, byte []> _nameKeyMap = new TreeMap<ContentName, byte []>();
	
	public SecureKeyCache() {
	}
	
	/**
	 * Constructor that loads keys from a KeyManager
	 * @param keyManagerToLoadFrom the key manager
	 * TODO bug -- should merge key caches, not just load signing keys.
	 */
	public SecureKeyCache(KeyManager keyManagerToLoadFrom) {
		Key [] pks = keyManagerToLoadFrom.getSigningKeys();
		for (Key pk : pks) {
			PublisherPublicKeyDigest ppkd = keyManagerToLoadFrom.getPublisherKeyID(pk);
			Log.info("KeyCache: loading signing key {0}", ppkd);
			addMySigningKey(ppkd.digest(), pk);
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
							Log.info("KeyCache: loading signing key {0}, remembering public key in public key cache.", ppkd);
							addMySigningKey(ppkd.digest(), pk);
							publicKeyCache.remember(certificate, keyStoreInfo.getVersion());
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
	 * Checks whether we have a record of a key specified by its digest, or in the case
	 * of a private key, the digest of the corresponding public key.
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
	 * Returns the private key corresponding to a key identified by its digest.
	 * To restrict access to keys, store key cache in a private variable, and don't
	 * allow references to it from untrusted code. 
	 * @param desiredPublicKeyIdentifier the digest of the public key.
	 * @return the corresponding private key.
	 */
	public Key getPrivateKey(byte [] desiredPublicKeyIdentifier) {
		Key key = _myKeyMap.get(desiredPublicKeyIdentifier);
		if (null == key) {
			key = _secretKeyMap.get(desiredPublicKeyIdentifier);
		}
		if (null == key) {
			key = _privateKeyMap.get(desiredPublicKeyIdentifier);
		}
		return key;
	}
	
	public Key getPrivateKey(ContentName desiredKeyName) {
		byte [] keyID = _nameKeyMap.get(desiredKeyName);
		if (null != keyID) {
			return getPrivateKey(keyID);
		}
		return null;
	}
	
	/**
	 * Returns all private keys in cache, loaded from keystore or picked up during operation.
	 */
	public PrivateKey [] getPrivateKeys() {
		ArrayList<PrivateKey> allKeys = new ArrayList<PrivateKey>();
		
		allKeys.addAll(_myKeyMap.values());
		allKeys.addAll(_privateKeyMap.values());
		
		PrivateKey [] pkarray = new PrivateKey[allKeys.size()];
		return allKeys.toArray(pkarray);
	}
	
	public PrivateKey [] getMyPrivateKeys() {
		PrivateKey [] pkarray = new PrivateKey[_myKeyMap.size()];
		return _myKeyMap.values().toArray(pkarray);
	}
	
	private ContentName getContentName(byte[] ident) {
		for (ContentName name : _nameKeyMap.keySet()) {
			if (byteArrayComparator.compare(ident, _nameKeyMap.get(name)) == 0) {
				return name;
			}
		}
		return null;
	}
	
	
	/**
	 * Records a private key and the name and digest of the corresponding public key.
	 * @param keyName a name under which to look up the private key
	 * @param publicKeyIdentifier the digest of the public key
	 * @param pk the private key
	 */
	public synchronized void addPrivateKey(ContentName keyName, byte [] publicKeyIdentifier, PrivateKey pk) {
		_privateKeyMap.put(publicKeyIdentifier, pk);
		_privateKeyIdentifierMap.put(getKeyIdentifier(pk), publicKeyIdentifier);
		if (null != keyName) {
			_nameKeyMap.put(keyName, publicKeyIdentifier);
			Log.info(Log.FAC_ACCESSCONTROL, "SecureKeyCache: adding private key {0} with name {1}",
					DataUtils.printHexBytes(publicKeyIdentifier), keyName);
		} else {
			Log.info(Log.FAC_ACCESSCONTROL, "SecureKeyCache: adding private key {0}",
					DataUtils.printHexBytes(publicKeyIdentifier));			
		}
	}
	
	/**
	 * Records a secret (symmetric) key and the name and digest of the corresponding identifier
	 * (which should be a "PublisherPublicKeyDigest".
	 * @param keyName a name under which to look up the private key
	 * @param identifier the digest of the public key
	 * @param sk the secret key
	 */
	public synchronized void addSecretKey(ContentName keyName, byte [] identifier, SecretKey sk) {
		_secretKeyMap.put(identifier, sk);
		_privateKeyIdentifierMap.put(getKeyIdentifier(sk), identifier);
		if (null != keyName) {
			_nameKeyMap.put(keyName, identifier);
			Log.info(Log.FAC_ACCESSCONTROL, "SecureKeyCache: adding secret key {0} with name {1}",
					DataUtils.printHexBytes(identifier), keyName);
		} else {
			Log.info(Log.FAC_ACCESSCONTROL, "SecureKeyCache: adding secret key {0}",
					DataUtils.printHexBytes(identifier));			
		}
	}

	/**
	 * Records one of my private keys and the digest of the corresponding public key.
	 * @param publicKeyIdentifier the digest of the public key.
	 * @param pk the corresponding private key.
	 */
	public synchronized void addMySigningKey(byte [] publicKeyIdentifier, Key k) {
		_privateKeyIdentifierMap.put(getKeyIdentifier(k), publicKeyIdentifier);
		String alg = k.getFormat();
		if (alg.equals("RAW"))
			_secretKeyMap.put(publicKeyIdentifier, (SecretKey)k);
		else
			_myKeyMap.put(publicKeyIdentifier, (PrivateKey)k);
		Log.info(Log.FAC_ACCESSCONTROL, "SecureKeyCache: adding my private key {0}",
				DataUtils.printHexBytes(publicKeyIdentifier));			
	}
	
	/**
	 * Make a record of a key by its name and digest.
	 * @param name the name of the key.
	 * @param key the key.
	 */
	public synchronized void addKey(ContentName name, Key key) {
		byte [] id = getKeyIdentifier(key);
		_keyMap.put(id, key);
		if (null != name) {
			_nameKeyMap.put(name, id);
			Log.info(Log.FAC_ACCESSCONTROL, "SecureKeyCache: adding key {0} with name {1} of type {2}",
					DataUtils.printHexBytes(id), name, key.getClass().getName());
		} else {
			Log.info(Log.FAC_ACCESSCONTROL, "SecureKeyCache: adding key {0} of type {1}",
					DataUtils.printHexBytes(id), key.getClass().getName());			
		}
	}
	
	public PublisherPublicKeyDigest getPublicKeyIdentifier(Key pk) {
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
	
	/**
	 * Return a total count of keys in this cache.
	 * @return
	 */
	public int size() {
		int count = _keyMap.size();
		count += _myKeyMap.size();
		count += _privateKeyMap.size();
		return count;
	}
	
	/**
	 * Merges the SecureKeyCache with a given SecureKeyCache. The original SecureKeyCache
	 * dominates, i.e. the merged cache will contain the names in the original cache if there
	 * are any conflicts 
	 * 
	 * @param cache the SecureKeyCache to merge with
	 */
	public synchronized void merge(SecureKeyCache cache) {
		
		/**
		_keyMap.putAll(cache._keyMap);
		_myKeyMap.putAll(cache._myKeyMap);
		_privateKeyMap.putAll(cache._privateKeyMap);
		_privateKeyIdentifierMap.putAll(cache._privateKeyIdentifierMap);
		
		Collection<byte[]> digests = cache._nameKeyMap.values();
		Iterator<byte[]> it = digests.iterator();
		while (it.hasNext()) {
			
			if (this._nameKeyMap.containsValue(it.next())) {
				it.remove();
			}
		}
		
		_nameKeyMap.putAll(cache._nameKeyMap);
		*/
		 
		// check that all my private keys are already in cache
		for (PrivateKey pkey : cache._myKeyMap.values()) {
			byte[] identifier = cache.getPublicKeyIdentifier(pkey).digest();
			if (!this._myKeyMap.containsKey(identifier)) {
				this.addMySigningKey(identifier, pkey);
			}
		}
		
		// check that all my symmetric keys are already in cache
		for (SecretKey skey : cache._secretKeyMap.values()) {
			byte[] identifier = cache.getPublicKeyIdentifier(skey).digest();
			if (!this._myKeyMap.containsKey(identifier)) {
				this.addMySigningKey(identifier, skey);
			}
		}
		
		// check that all other private keys are already in cache
		for (PrivateKey pkey : cache._privateKeyMap.values()) {
			byte[] identifier = cache.getPublicKeyIdentifier(pkey).digest();
			ContentName name = cache.getContentName(identifier);
			if (!this._privateKeyMap.containsKey(identifier)) {	
				this.addPrivateKey(name, identifier, pkey);
			}
			else {
				if (this.getContentName(identifier) == null) {
					_nameKeyMap.put(name, identifier);
				}
			}
		}
		
		// check that all symmetric keys are already in cache
		for (Key key : cache._keyMap.values()) {
			byte[] identifier = getKeyIdentifier(key);
			ContentName name = cache.getContentName(identifier);
			if (!this.containsKey(identifier)) {	
				this.addKey(name, key);
			}
			else {
				if (this.getContentName(identifier) == null) {
					_nameKeyMap.put(name, identifier);
				}
			}
		}
			
	}
	
	
	/**
	 * Debugging utility to print the contents of the secureKeyCache
	 */
	public void printContents() {
		Log.info(Log.FAC_ACCESSCONTROL, "SecureKeyCache: {0} keys in _keyMap ", _keyMap.size());
		Log.info(Log.FAC_ACCESSCONTROL, "SecureKeyCache: {0} keys in _myKeyMap ", _myKeyMap.size());
		for (byte[] b: _myKeyMap.keySet()) {
			Log.info(Log.FAC_ACCESSCONTROL, "SecureKeyCache: myKeyMap contains key with hash {0}", DataUtils.printHexBytes(b));
		}
		Log.info(Log.FAC_ACCESSCONTROL, "SecureKeyCache: {0} keys in _privateKeyMap ", _privateKeyMap.size());
		for (ContentName cn: _nameKeyMap.keySet()) {
			Log.info(Log.FAC_ACCESSCONTROL, "SecureKeyCache: _nameKeyMap contains a key with name {0} and hash {1}", 
					cn, DataUtils.printHexBytes(_nameKeyMap.get(cn)));
		}
		
		Log.info(Log.FAC_ACCESSCONTROL, "Dumping _keyMap"); 
		for (byte [] keyHash : _keyMap.keySet()) {
			Log.info(Log.FAC_ACCESSCONTROL, "  KeyID: {0}", DataUtils.printHexBytes(keyHash));
		}
		
		Log.info(Log.FAC_ACCESSCONTROL, "Dumping _myKeyMap"); 
		for (byte [] keyHash : _myKeyMap.keySet()) {
			Log.info(Log.FAC_ACCESSCONTROL, "  KeyID: {0}", DataUtils.printHexBytes(keyHash));
		}
		
		Log.info(Log.FAC_ACCESSCONTROL, "Dumping _privateKeyMap"); 
		for (byte [] keyHash : _privateKeyMap.keySet()) {
			Log.info(Log.FAC_ACCESSCONTROL, "  KeyID: {0}", DataUtils.printHexBytes(keyHash));
		}
	}
	
	/**
	 * Make sure everything in here is Serializable.
	 * @return
	 */
	public boolean validateForWriting() {
		boolean valid = true;
		for (Key key : _keyMap.values()) {
			if (!(key instanceof Serializable)) {
				if (Log.isLoggable(Log.FAC_KEYS, Level.WARNING)) {
					Log.warning(Log.FAC_KEYS, "Cannot serialize key of type {0}: {1}", key.getClass().getName(),
							key);
				}
				valid = false;
			}
		}
		
		for (Key key : _myKeyMap.values()) {
			if (!(key instanceof Serializable)) {
				if (Log.isLoggable(Log.FAC_KEYS, Level.WARNING)) {
					Log.warning(Log.FAC_KEYS, "Cannot serialize key of type {0}: {1}", key.getClass().getName(),
							key);
				}
				valid = false;
			}
		}
		
		for (Key key : _privateKeyMap.values()) {
			if (!(key instanceof Serializable)) {
				if (Log.isLoggable(Log.FAC_KEYS, Level.WARNING)) {
					Log.warning(Log.FAC_KEYS, "Cannot serialize key of type {0}: {1}", key.getClass().getName(),
							key);
				}
				valid = false;
			}
		}

		return valid;

	}

}
