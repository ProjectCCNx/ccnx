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
	
	private HashMap<String, Key> _keyMap = new HashMap<String, Key>();
	private HashMap<String, PrivateKey> _myKeyMap = new HashMap<String, PrivateKey>();
	private HashMap<String, PrivateKey> _privateKeyMap = new HashMap<String, PrivateKey>();
	private HashMap<String, String> _privateKeyIdentifierMap = new HashMap<String, String>();
	private HashMap<String, ContentName> _keyNameMap = new HashMap<String, ContentName>();
	
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
		String desiredKeyIdentifierAsString = new String(desiredKeyIdentifier);
		Key theKey = _keyMap.get(desiredKeyIdentifierAsString);
		if (null == theKey) {
			theKey = _privateKeyMap.get(desiredKeyIdentifierAsString);
		}
		if (null == theKey) {
			theKey = _myKeyMap.get(desiredKeyIdentifierAsString);
		}
		return theKey;
	}
	
	public boolean containsKey(byte [] keyIdentifier) {
		String keyIdentifierAsString = new String(keyIdentifier);
		if ((_keyMap.containsKey(keyIdentifierAsString)) || (_myKeyMap.containsKey(keyIdentifierAsString)) ||
					(_privateKeyMap.containsKey(keyIdentifierAsString))) {
			return true;
		}
		return false;
	}
	
	public ContentName getKeyName(byte [] keyIdentifier) {
		String keyIdentifierAsString = new String(keyIdentifier);
		return _keyNameMap.get(keyIdentifierAsString);
	}
	
	public ContentName getKeyName(Key key) {
		return getKeyName(getKeyIdentifier(key));
	}

	PrivateKey getPrivateKey(byte [] desiredPublicKeyIdentifier) {
		String desiredPublicKeyIdentifierAsString = new String(desiredPublicKeyIdentifier);
		PrivateKey key = _myKeyMap.get(desiredPublicKeyIdentifierAsString);
		if (null == key) {
			_privateKeyMap.get(desiredPublicKeyIdentifierAsString);
		}
		return key;
	}
	
	void addPrivateKey(ContentName keyName, byte [] publicKeyIdentifier, PrivateKey pk) {
		String publicKeyIdentifierAsString = new String(publicKeyIdentifier);
		_privateKeyMap.put(publicKeyIdentifierAsString, pk);
		String privateKeyIdentifierAsString = new String(getKeyIdentifier(pk));
		_privateKeyIdentifierMap.put(privateKeyIdentifierAsString, publicKeyIdentifierAsString);
		if (null != keyName)
			_keyNameMap.put(publicKeyIdentifierAsString, keyName);
	}

	void addMyPrivateKey(byte [] publicKeyIdentifier, PrivateKey pk) {
		String publicKeyIdentifierAsString = new String(publicKeyIdentifier);
		String privateKeyIdentifierAsString = new String(getKeyIdentifier(pk));
		_privateKeyIdentifierMap.put(privateKeyIdentifierAsString, publicKeyIdentifierAsString);
		_myKeyMap.put(publicKeyIdentifierAsString, pk);
	}
	
	public void addKey(ContentName name, Key key) {
		String id = new String(getKeyIdentifier(key));
		_keyMap.put(id, key);
		_keyNameMap.put(id, name);
	}
	
	public static byte [] getKeyIdentifier(Key key) {
		// Works on symmetric and public.
		return CCNDigestHelper.digest(key.getEncoded());
	}
}
