package com.parc.ccn.security.access;

import java.security.Key;
import java.security.PrivateKey;
import java.util.TreeMap;
import java.util.Comparator;

import org.ccnx.ccn.protocol.ContentName;

import com.parc.ccn.security.crypto.CCNDigestHelper;
import com.parc.ccn.security.keys.KeyManager;
import com.parc.ccn.data.query.ByteArrayCompare;

/**
 * A cache for decrypted symmetric keys for access control.
 * @author smetters
 *
 */
public class KeyCache {
	
	static Comparator<byte[]> byteArrayComparator = new ByteArrayCompare();
	
	private TreeMap<byte [], Key> _keyMap = new TreeMap<byte [], Key>(byteArrayComparator);
	private TreeMap<byte [], PrivateKey> _myKeyMap = new TreeMap<byte [], PrivateKey>(byteArrayComparator);
	private TreeMap<byte [], PrivateKey> _privateKeyMap = new TreeMap<byte [], PrivateKey>(byteArrayComparator);
	private TreeMap<byte [], byte []> _privateKeyIdentifierMap = new TreeMap<byte [], byte[]>(byteArrayComparator);
	private TreeMap<byte [], ContentName> _keyNameMap = new TreeMap<byte [], ContentName>(byteArrayComparator);
	
	public KeyCache() {
		this(KeyManager.getKeyManager());
	}
	
	public KeyCache(KeyManager keyManagerToLoadFrom) {
		PrivateKey [] pks = keyManagerToLoadFrom.getSigningKeys();
		for (PrivateKey pk : pks) {
			addMyPrivateKey(keyManagerToLoadFrom.getPublisherKeyID(pk).digest(), pk);
		}
	}
	
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
	
	public boolean containsKey(byte [] keyIdentifier) {
		if ((_keyMap.containsKey(keyIdentifier)) || (_myKeyMap.containsKey(keyIdentifier)) ||
					(_privateKeyMap.containsKey(keyIdentifier))) {
			return true;
		}
		return false;
	}
	
	public ContentName getKeyName(byte [] keyIdentifier) {
		return _keyNameMap.get(keyIdentifier);
	}
	
	public ContentName getKeyName(Key key) {
		return getKeyName(getKeyIdentifier(key));
	}

	PrivateKey getPrivateKey(byte [] desiredPublicKeyIdentifier) {
		PrivateKey key = _myKeyMap.get(desiredPublicKeyIdentifier);
		if (null == key) {
			_privateKeyMap.get(desiredPublicKeyIdentifier);
		}
		return key;
	}
	
	void addPrivateKey(ContentName keyName, byte [] publicKeyIdentifier, PrivateKey pk) {
		_privateKeyMap.put(publicKeyIdentifier, pk);
		_privateKeyIdentifierMap.put(getKeyIdentifier(pk), publicKeyIdentifier);
		if (null != keyName)
			_keyNameMap.put(publicKeyIdentifier, keyName);
	}

	void addMyPrivateKey(byte [] publicKeyIdentifier, PrivateKey pk) {
		_privateKeyIdentifierMap.put(getKeyIdentifier(pk), publicKeyIdentifier);
		_myKeyMap.put(publicKeyIdentifier, pk);
	}
	
	public void addKey(ContentName name, Key key) {
		byte [] id = getKeyIdentifier(key);
		_keyMap.put(id, key);
		_keyNameMap.put(id, name);
	}
	
	public static byte [] getKeyIdentifier(Key key) {
		// Works on symmetric and public.
		return CCNDigestHelper.digest(key.getEncoded());
	}
}
