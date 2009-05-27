package com.parc.ccn.security.access;

import java.security.Key;
import java.security.PrivateKey;
import java.util.HashMap;

import com.parc.ccn.data.ContentName;
import com.parc.ccn.security.crypto.CCNDigestHelper;
import com.parc.ccn.security.keys.KeyManager;

/**
 * A cache for decrypted symmetric keys for access control.
 * @author smetters
 *
 */
public class KeyCache {
	
	private HashMap<byte [], Key> _keyMap = new HashMap<byte [], Key>();
	private HashMap<byte [], PrivateKey> _myKeyMap = new HashMap<byte [], PrivateKey>();
	private HashMap<byte [], PrivateKey> _privateKeyMap = new HashMap<byte [], PrivateKey>();
	private HashMap<byte [], byte []> _privateKeyIdentifierMap = new HashMap<byte [], byte[]>();
	private HashMap<byte [], ContentName> _keyNameMap = new HashMap<byte [], ContentName>();
	
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
	
	public byte [] getKeyIdentifier(Key key) {
		// Works on symmetric and public.
		return CCNDigestHelper.digest(key.getEncoded());
	}
}
