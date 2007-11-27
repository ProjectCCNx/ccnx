package com.parc.ccn.security.keys;

import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;

import com.parc.ccn.data.security.KeyLocator;
import com.parc.ccn.data.security.PublisherID;

public class BasicKeyManager extends KeyManager {
	
	protected KeyStore _keystore = null;
	protected String _defaultAlias = null;
	
	public BasicKeyManager() {
		
	}

	public PublisherID getDefaultKeyID() {
		// TODO Auto-generated method stub
		return null;
	}

	public PublicKey getDefaultPublicKey() {
		// TODO Auto-generated method stub
		return null;
	}

	public PrivateKey getDefaultSigningKey() {
		// TODO Auto-generated method stub
		return null;
	}

	public KeyLocator getKeyLocator(PrivateKey signingKey) {
		// TODO Auto-generated method stub
		return null;
	}

	public PublicKey getPublicKey(String alias) {
		// TODO Auto-generated method stub
		return null;
	}

	public PublicKey getPublicKey(PublisherID publisher) {
		// TODO Auto-generated method stub
		return null;
	}

	public PrivateKey getSigningKey(String alias) {
		// TODO Auto-generated method stub
		return null;
	}

	public PrivateKey getSigningKey(PublisherID publisher) {
		// TODO Auto-generated method stub
		return null;
	}

}
