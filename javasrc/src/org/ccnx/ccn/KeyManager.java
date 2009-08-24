package org.ccnx.ccn;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.CertificateEncodingException;
import java.security.spec.InvalidKeySpecException;

import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.impl.security.keys.BasicKeyManager;
import org.ccnx.ccn.impl.security.keys.KeyRepository;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.KeyLocator;
import org.ccnx.ccn.protocol.PublisherID;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;


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
			Log.logger().warning("Configuration exception attempting to get KeyManager: " + e.getMessage());
			Log.warningStackTrace(e);
			throw new RuntimeException("Error in system configuration. Cannot get KeyManager.",e);
		} catch (IOException e) {
			Log.logger().warning("IO exception attempting to get KeyManager: " + e.getMessage());
			Log.warningStackTrace(e);
			throw new RuntimeException("Error in system IO. Cannot get KeyManager.",e);
		}
	}
	
	protected static synchronized KeyManager createKeyManager() throws ConfigurationException, IOException {
		if (null == _defaultKeyManager) {
			_defaultKeyManager = new BasicKeyManager();
			_defaultKeyManager.initialize();
		}
		return _defaultKeyManager;
	}
	
	public abstract void initialize() throws ConfigurationException;
	
	public static KeyRepository getKeyRepository() {
		return getKeyManager().keyRepository();
	}

	/**
	 * Get our default keys.
	 * @return
	 */
	public abstract PublisherPublicKeyDigest getDefaultKeyID();

	public abstract PrivateKey getDefaultSigningKey();
	public abstract PublicKey getDefaultPublicKey();
	public abstract KeyLocator getDefaultKeyLocator();
	public abstract KeyLocator getKeyLocator(PublisherPublicKeyDigest publisherKeyID);

	public abstract ContentName getDefaultKeyName(byte [] keyID);
	
	/**
	 * Get our public and private keys.
	 * @param alias
	 * @return
	 */
	public abstract PublicKey getPublicKey(String alias);
	public abstract PublicKey getPublicKey(PublisherPublicKeyDigest publisher) throws IOException;

	public abstract PublisherPublicKeyDigest getPublisherKeyID(PrivateKey signingKey);

	public abstract KeyLocator getKeyLocator(PrivateKey signingKey);

	public abstract PrivateKey getSigningKey(String alias);
	public abstract PrivateKey getSigningKey(PublisherID publisher);
	public abstract PrivateKey getSigningKey(PublisherPublicKeyDigest publisherKeyID);
	
	/**
	 * Get my identities, e.g. for loading a cache.
	 * @return
	 */
	public abstract PrivateKey[] getSigningKeys();
	
	/**
	 * Get someone else's public keys. Interesting to see
	 * whether or not this should be handled by a TrustManager.
	 * @param publisherID
	 * @param keyLocator
	 * @return
	 * @throws IOException 
	 * @throws InterruptedException 
	 * @throws NoSuchAlgorithmException 
	 * @throws InvalidKeySpecException 
	 * @throws CertificateEncodingException 
	 */
	public abstract PublicKey getPublicKey(PublisherPublicKeyDigest publisherKeyID, KeyLocator keyLocator, long timeout) throws IOException, InterruptedException;

	public PublicKey getPublicKey(PublisherPublicKeyDigest publisherKeyID, KeyLocator keyLocator) throws IOException, InterruptedException {
		return getPublicKey(publisherKeyID, keyLocator,KeyRepository.DEFAULT_KEY_TIMEOUT);
	}
	
	public abstract PublicKey getKey(PublisherPublicKeyDigest desiredKeyID,
									KeyLocator locator, long timeout) throws IOException, InterruptedException;
	
	/**
	 * Publish a key at a certain name, signed by our default identity. Usually used to
	 * publish our own keys. Version that takes a public key can be used to
	 * publish other people's keys.
	 * @param keyName
	 * @param keyToPublish can be null, in which case we publish our own default public key
	 * @throws InvalidKeyException 
	 * @throws ConfigurationException 
	 * @throws ConfigurationException 
	 * @throws NoSuchAlgorithmException 
	 * @throws InvalidKeySpecException 
	 * @throws CertificateEncodingException 
	 */
	public abstract void publishKey(ContentName keyName, PublisherPublicKeyDigest keyToPublish) throws InvalidKeyException, IOException, ConfigurationException;
	
	public abstract KeyRepository keyRepository();

}
