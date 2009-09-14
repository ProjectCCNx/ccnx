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

package org.ccnx.ccn;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.PublicKey;
import java.security.Security;
import java.security.cert.CertificateEncodingException;
import java.security.spec.InvalidKeySpecException;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
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
	protected static Provider BC_PROVIDER = null;
	
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
	
	public static void initializeProvider() {
		synchronized(KeyManager.class) {
			if (null == BC_PROVIDER) {
				BC_PROVIDER = new BouncyCastleProvider();
				Security.addProvider(BC_PROVIDER);		
			}
		}
	}
	
	public static Provider getDefaultProvider() {
		if (null == BC_PROVIDER) {
			initializeProvider();
		}
		return BC_PROVIDER;
	}
	
	/**
	 * Right now, the handle uses the default key manager
	 * interface. We want a generic mechanism for getting
	 * key managers used by low-level apps not relying on the
	 * handle to use that puts all the exception handling in
	 * one place...
	 * @return
	 */
	public static KeyManager getKeyManager() {
		try {
			return getDefaultKeyManager();
		} catch (ConfigurationException e) {
			Log.warning("Configuration exception attempting to get KeyManager: " + e.getMessage());
			Log.warningStackTrace(e);
			throw new RuntimeException("Error in system configuration. Cannot get KeyManager.",e);
		} catch (IOException e) {
			Log.warning("IO exception attempting to get KeyManager: " + e.getMessage());
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
	 * publish our own keys, but can specify other keys we have in our cache.
	 * @param keyName the name under which the key should be published. For the moment, keys are
	 * 		  unversioned.
	 * @param keyToPublish can be null, in which case we publish our own default public key
	 * @throws InvalidKeyException 
	 * @throws ConfigurationException 
	 * @throws ConfigurationException 
	 * @throws NoSuchAlgorithmException 
	 * @throws InvalidKeySpecException 
	 * @throws CertificateEncodingException 
	 */
	public abstract void publishKey(ContentName keyName, PublisherPublicKeyDigest keyToPublish) throws InvalidKeyException, IOException, ConfigurationException;
	
	/**
	 * Publish a key at a certain name, ensuring that it is stored in a repository. Will throw an
	 * exception if no repository available. Usually used to publish our own keys, but can specify
	 * any key known to our key cache.
	 * @param keyName Name under which to publish the key. Currently not versioned (no version added).
	 * @param keyToPublish can be null, in which case we publish our own default public key.
	 * @throws InvalidKeyException
	 * @throws IOException
	 * @throws ConfigurationException
	 */
	public abstract void publishKeyToRepository(ContentName keyName, 
												PublisherPublicKeyDigest keyToPublish, 
												CCNHandle handle) throws InvalidKeyException, IOException, ConfigurationException;

	/**
	 * Publish our default key to a repository at its default location.
	 * @param handle
	 * @throws InvalidKeyException
	 * @throws IOException
	 * @throws ConfigurationException
	 */
	public abstract void publishKeyToRepository(CCNHandle handle) throws InvalidKeyException, IOException, ConfigurationException;

	public abstract KeyRepository keyRepository();

}
