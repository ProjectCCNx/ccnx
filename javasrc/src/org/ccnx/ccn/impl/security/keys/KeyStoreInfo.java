/**
 * 
 */
package org.ccnx.ccn.impl.security.keys;

import java.security.KeyStore;

import org.ccnx.ccn.protocol.CCNTime;

public class KeyStoreInfo {
	// Where did we load this from
	String _keyStoreURI;
	String _configurationFileURI;
	KeyStore _keyStore;
	CCNTime _version;
	
	public KeyStoreInfo(String keyStoreURI, KeyStore keyStore, CCNTime version) {
		_keyStoreURI = keyStoreURI;
		_keyStore = keyStore;
		_version = version;
	}
	
	/**
	 * In case we don't know the 
	 * @param keyStore
	 */
	public void setKeyStore(KeyStore keyStore) {
		_keyStore = keyStore;
	}
	
	public void setVersion(CCNTime version) {
		_version = version;
	}
	
	public void setConfigurationFileURI(String configurationFileURI) {
		_configurationFileURI = configurationFileURI;
	}
	
	public KeyStore getKeyStore() { return _keyStore; }
	public CCNTime getVersion() { return _version; }
	public String getKeyStoreURI() { return _keyStoreURI; }
	public String getConfigurationFileURI() { return _configurationFileURI; }

}