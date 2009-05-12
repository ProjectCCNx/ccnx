package com.parc.ccn.security.access;

import java.security.PrivateKey;
import java.util.HashMap;

import com.parc.ccn.security.keys.KeyManager;

/**
 * A cache for decrypted symmetric keys for access control.
 * @author smetters
 *
 */
public class KeyCache {
	
	private HashMap<byte [], NodeKey> _nodeKeyMap = new HashMap<byte [], NodeKey>();
	private HashMap<byte [], PrivateKey> _myKeyMap = new HashMap<byte [], PrivateKey>();
	private HashMap<byte [], PrivateKey> _groupKeyMap = new HashMap<byte [], PrivateKey>();
	
	public KeyCache() {
	}
	
	public KeyCache(KeyManager keyManagerToLoadFrom) {
		PrivateKey [] pks = keyManagerToLoadFrom.getSigningKeys();
		for (PrivateKey pk : pks) {
			addMyPrivateKey(pk);
		}
	}
	
	NodeKey getNodeKey(byte [] desiredKeyIdentifier) {
		return _nodeKeyMap.get(desiredKeyIdentifier);
	}
	
	void addNodeKey(NodeKey nk) {
		_nodeKeyMap.put(nk.generateKeyID(), nk);
	}
	
	PrivateKey getPrivateKey(byte [] desiredKeyIdentifier) {
		PrivateKey key = _myKeyMap.get(desiredKeyIdentifier);
		if (null == key) {
			_groupKeyMap.get(desiredKeyIdentifier);
		}
		return key;
	}
	
	void addGroupPrivateKey(PrivateKey pk) {
		_groupKeyMap.put(NodeKey.generateKeyID(pk.getEncoded()), pk);
	}

	void addMyPrivateKey(PrivateKey pk) {
		_myKeyMap.put(NodeKey.generateKeyID(pk.getEncoded()), pk);
	}
	
	public boolean containsKey(byte [] keyIdentifier) {
		if (_nodeKeyMap.containsKey(keyIdentifier))
			return true;
		if (_myKeyMap.containsKey(keyIdentifier))
			return true;
		if (_groupKeyMap.containsKey(keyIdentifier))
			return true;
		return false;
	}
}
