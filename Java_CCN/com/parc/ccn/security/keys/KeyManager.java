package com.parc.ccn.security.keys;

import java.io.IOException;
import java.security.PrivateKey;
import java.security.PublicKey;

import com.parc.ccn.Library;
import com.parc.ccn.config.ConfigurationException;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.security.KeyLocator;
import com.parc.ccn.data.security.PublisherID;
import com.parc.ccn.data.security.PublisherKeyID;

/**
 * Front-end for key repository, both our keys
 * and other peoples' keys.
 * 
 * By JDK 1.6 we have ways to get at both MSCAPI
 * keys (we can use them, but not export them; it's
 * not clear we can import things into there either),
 * and PKCS#11 keys, which by derivation also gets
 * us Mozilla/Firefox keys, as well as smart cards.
 * 
 * @author smetters
 *
 */
public abstract class KeyManager {
	
	public static final String DEFAULT_DIGEST_ALGORITHM = "SHA-256";
	
	protected static KeyManager _defaultKeyManager = null;
	
	public static KeyManager getDefaultKeyManager() throws ConfigurationException, IOException {
		if (null != _defaultKeyManager) 
			return _defaultKeyManager;
		try {
			return createKeyManager();
		} catch (IOException io) {
			throw new RuntimeException(io);
		}
	}
	
	/**
	 * Right now, the library uses the default key manager
	 * interface. We want a generic mechanism for getting
	 * key managers used by low-level apps not relying on the
	 * library to use that puts all the exception handling in
	 * one place...
	 * @return
	 */
	public static KeyManager getKeyManager() {
		try {
			return getDefaultKeyManager();
		} catch (ConfigurationException e) {
			Library.logger().warning("Configuration exception attempting to get KeyManager: " + e.getMessage());
			Library.warningStackTrace(e);
			throw new RuntimeException("Error in system configuration. Cannot get KeyManager.",e);
		} catch (IOException e) {
			Library.logger().warning("IO exception attempting to get KeyManager: " + e.getMessage());
			Library.warningStackTrace(e);
			throw new RuntimeException("Error in system IO. Cannot get KeyManager.",e);
		}
	}
	
	protected static synchronized KeyManager createKeyManager() throws ConfigurationException, IOException {
		if (null == _defaultKeyManager) {
			_defaultKeyManager = new BasicKeyManager();
		}
		return _defaultKeyManager;
	}
	
	public static KeyRepository getKeyRepository() {
		return getKeyManager().keyRepository();
	}

	/**
	 * Get our default keys.
	 * @return
	 */
	public abstract PublisherKeyID getDefaultKeyID();

	public abstract PrivateKey getDefaultSigningKey();
	public abstract PublicKey getDefaultPublicKey();
	public abstract KeyLocator getDefaultKeyLocator();

	public abstract ContentName getDefaultKeyName(byte [] keyID);
	
	/**
	 * Get our public and private keys.
	 * @param alias
	 * @return
	 */
	public abstract PublicKey getPublicKey(String alias);
	public abstract PublicKey getPublicKey(PublisherKeyID publisher);

	public abstract PublisherKeyID getPublisherKeyID(PrivateKey signingKey);

	public abstract KeyLocator getKeyLocator(PrivateKey signingKey);

	public abstract PrivateKey getSigningKey(String alias);
	public abstract PrivateKey getSigningKey(PublisherID publisher);
	public abstract PrivateKey getSigningKey(PublisherKeyID publisherKeyID);
	

	/**
	 * Get someone else's public keys. Interesting to see
	 * whether or not this should be handled by a TrustManager.
	 * @param publisherID
	 * @param keyLocator
	 * @return
	 * @throws IOException 
	 * @throws InterruptedException 
	 */
	public abstract PublicKey getPublicKey(PublisherKeyID publisherKeyID, KeyLocator keyLocator) throws IOException, InterruptedException;

	public abstract PublicKey getKey(PublisherKeyID desiredKeyID,
									KeyLocator locator) throws IOException, InterruptedException;
	
	
	public abstract KeyRepository keyRepository();
	
}
