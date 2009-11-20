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
import java.security.PrivateKey;
import java.security.Provider;
import java.security.PublicKey;
import java.security.Security;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.impl.security.keys.BasicKeyManager;
import org.ccnx.ccn.impl.security.keys.KeyRepository;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.KeyLocator;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;


/**
 * Top-level interface for managing our own keys, as well as maintaining an address book containing
 * the keys of others (which will be used by the TrustManager). Also handles loading of the BouncyCastle
 * provider, which we need for many things. Very minimal interface now, expect to evolve extensively.
 */
public abstract class KeyManager {
	
	/**
	 * Currently default to SHA-256. Only thing that associates a specific digest algorithm
	 * with a version of the CCN protocol is the calculation of the vestigial content digest component
	 * of ContentName used in Interest matching, and publisher digests. Changing the latter
	 * is handled by backwards-compatible changes to the protocol encoding. All other digests are 
	 * stored prefaced with an algorithm identifier, to allow them to be modified.
	 * We expect the protocol default digest algorithm to move to SHA3 when defined.
	 */
	public static final String DEFAULT_DIGEST_ALGORITHM = "SHA-256";
	protected static Provider BC_PROVIDER = null;
	
	/**
	 * The default KeyManager for this user/VM pair. The KeyManager will eventually have access
	 * to significant cached state, and so a single one should be shared by as many processes
	 * within the same trust domain as possible. We might make multiple KeyManagers representing
	 * different "users" for testing purposes.
	 */
	protected static KeyManager _defaultKeyManager = null;
	
	/**
	 * Accessor to retrieve default key manager instance, or create it if necessary.
	 * @return the KeyManager
	 * @throws ConfigurationException if there is a problem with the user or system configuration that
	 * 		requires intervention to resolve, or we have a significant problem starting up the key manager.
	 */
	public static KeyManager getDefaultKeyManager() throws ConfigurationException {
		if (null != _defaultKeyManager) 
			return _defaultKeyManager;
		try {
			return createKeyManager();
		} catch (IOException io) {
			throw new ConfigurationException(io);
		}
	}
	
	/**
	 * Load the BouncyCastle and other necessary providers, should be called once for initialization. 
	 * Currently this is done by CCNHandle.
	 */
	public static void initializeProvider() {
		synchronized(KeyManager.class) {
			if (null == BC_PROVIDER) {
				Provider bc = new BouncyCastleProvider();
				Security.addProvider(bc);
				BC_PROVIDER = bc;
				if (null != BC_PROVIDER)
					Log.fine("Installed BouncyCastle provider.");
				else
					Log.severe("ERROR: NULL default provider! Cannot load BouncyCastle!");
			}
		}
	}
	
	/**
	 * Retrieve our default BouncyCastle provider.
	 * @return the BouncyCastle provider instance
	 */
	public static Provider getDefaultProvider() {
		if (null == BC_PROVIDER) {
			initializeProvider();
		}
		if (null == BC_PROVIDER) {
			Log.severe("ERROR: NULL default provider! Cannot load BouncyCastle!");
		}
		return BC_PROVIDER;
	}
	
	/**
	 * Get our current KeyManager.
	 * @return the key manager
	 */
	public static KeyManager getKeyManager() {
		try {
			return getDefaultKeyManager();
		} catch (ConfigurationException e) {
			Log.warning("Configuration exception attempting to get KeyManager: " + e.getMessage());
			Log.warningStackTrace(e);
			throw new RuntimeException("Error in system configuration. Cannot get KeyManager.",e);
		}
	}
	
	/**
	 * Create the default key manager.
	 * @return the key manager
	 * @throws ConfigurationException if there is a problem with the user or system configuration
	 * 	that requires intervention to fix
	 * @throws IOException if there is an operational problem loading data or initializing the key store
	 */
	protected static synchronized KeyManager createKeyManager() throws ConfigurationException, IOException {
		if (null == _defaultKeyManager) {
			_defaultKeyManager = new BasicKeyManager();
			_defaultKeyManager.initialize();
		}
		return _defaultKeyManager;
	}
	
	/**
	 * Allows subclasses to specialize key manager initialization.
	 * @throws ConfigurationException
	 */
	public abstract void initialize() throws ConfigurationException;
	
	/**
	 * Get the key repository
	 * @return the key repository
	 */
	public static KeyRepository getKeyRepository() {
		return getKeyManager().keyRepository();
	}

	/**
	 * Get our default key ID.
	 * @return the digest of our default key
	 */
	public abstract PublisherPublicKeyDigest getDefaultKeyID();

	/**
	 * Get our default private key.
	 * @return our default private key
	 */
	public abstract PrivateKey getDefaultSigningKey();

	/**
	 * Get our default public key.
	 * @return our default public key
	 */
	public abstract PublicKey getDefaultPublicKey();

	/**
	 * Get our default key locator.
	 * @return our default key locator
	 */
	public abstract KeyLocator getDefaultKeyLocator();

	/**
	 * Get the default key locator for a particular public key
	 * @param publisherKeyID the key whose locator we want to retrieve
	 * @return the default key locator for that key
	 */
	public abstract KeyLocator getKeyLocator(PublisherPublicKeyDigest publisherKeyID);

	/**
	 * Generate the default name under which to write this key.
	 * @param keyID the binary digest of the name component of the key
	 * @return the name of the key to publish under
	 */
	public abstract ContentName getDefaultKeyName(byte [] keyID);
	
	/**
	 * Get the public key associated with a given Java keystore alias
	 * @param alias the alias for the key
	 * @return the key, or null if no such alias
	 */
	public abstract PublicKey getPublicKey(String alias);

	/**
	 * Get the public key associated with a given publisher
	 * @param publisher the digest of the desired key
	 * @return the key, or null if no such key known to our cache
	 * @throws IOException
	 */
	public abstract PublicKey getPublicKey(PublisherPublicKeyDigest publisher) throws IOException;

	/**
	 * Get the publisher key digest associated with one of our signing keys
	 * @param signingKey key whose publisher data we want
	 * @return the digest of the corresponding public key
	 */
	public abstract PublisherPublicKeyDigest getPublisherKeyID(PrivateKey signingKey);

	/**
	 * Get the default key locator associated with one of our signing keys
	 * @param signingKey key whose locator data we want
	 * @return the default key locator for that key
	 */
	public abstract KeyLocator getKeyLocator(PrivateKey signingKey);

	/**
	 * Get the private key associated with a given Java keystore alias
	 * @param alias the alias for the key
	 * @return the key, or null if no such alias
	 */
	public abstract PrivateKey getSigningKey(String alias);

	/**
	 * Get the private key associated with a given publisher 
	 * @param publisherKeyID the public key digest of the desired key
	 * @return the key, or null if no such key known to our cache
	 */
	public abstract PrivateKey getSigningKey(PublisherPublicKeyDigest publisherKeyID);
	
	/**
	 * Get all of our private keys, used for cache loading.
	 * @return an array of our currently available private keys
	 */
	public abstract PrivateKey[] getSigningKeys();
	
	/**
	 * Get the public key for a given publisher, going to the network to retrieve it if necessary.
	 * TODO should ensure it is stored in cache
	 * @param publisherKeyID the digest of the keys we want
	 * @param keyLocator the key locator to tell us where to retrieve the key from
	 * @param timeout how long to try to retrieve the key 
	 * @return the key
	 * @throws IOException if we run into an error attempting to read the key
	 */
	public abstract PublicKey getPublicKey(PublisherPublicKeyDigest publisherKeyID, KeyLocator keyLocator, long timeout) throws IOException;

	/**
	 * Get the public key for a given publisher, going to the network to retrieve it if necessary.
	 * Uses the default timeout.
	 * TODO should ensure it is stored in cache
	 * @param publisherKeyID the digest of the keys we want
	 * @param keyLocator the key locator to tell us where to retrieve the key from
	 * @return the key
	 * @throws IOException if we run into an error attempting to read the key
	 */
	public PublicKey getPublicKey(PublisherPublicKeyDigest publisherKeyID, KeyLocator keyLocator) throws IOException {
		return getPublicKey(publisherKeyID, keyLocator, SystemConfiguration.getDefaultTimeout());
	}
	
	/**
	 * Publish a key at a certain name, signed by our default identity. Usually used to
	 * publish our own keys, but can specify other keys we have in our cache.
	 * 
	 * This publishes our key to our own internal key server, from where it can be retrieved
	 * as long as this KeyManager is running. It does not put it on the wire until someone
	 * requests it. 
	 * Implementation Note: This code is used in CCNHandle initialization, and as such it
	 * cannot use a CCNHandle or any of the standard network operations without introducing
	 * a circular dependency. The code is very low-level and should only be modified with
	 * great caution.
	 * 
	 * @param keyName the name under which the key should be published. For the moment, keys are
	 * 		  unversioned.
	 * @param keyToPublish can be null, in which case we publish our own default public key
	 * @throws InvalidKeyException 
	 * @throws IOException
	 * @throws ConfigurationException 
	 */
	public abstract void publishKey(ContentName keyName, PublisherPublicKeyDigest keyToPublish) throws InvalidKeyException, IOException, ConfigurationException;
	
	/**
	 * Publish a key at a certain name, ensuring that it is stored in a repository. Will throw an
	 * exception if no repository available. Usually used to publish our own keys, but can specify
	 * any key known to our key cache.
	 * @param keyName Name under which to publish the key. Currently not versioned (no version added).
	 * @param keyToPublish can be null, in which case we publish our own default public key.
	 * @param handle the handle to use for network requests
	 * @throws InvalidKeyException
	 * @throws IOException
	 * @throws ConfigurationException
	 */
	public abstract void publishKeyToRepository(ContentName keyName, 
												PublisherPublicKeyDigest keyToPublish, 
												CCNHandle handle) throws InvalidKeyException, IOException, ConfigurationException;

	/**
	 * Publish our default key to a repository at its default location.
	 * @param handle the handle used for network requests
	 * @throws InvalidKeyException
	 * @throws IOException
	 * @throws ConfigurationException
	 */
	public abstract void publishKeyToRepository(CCNHandle handle) throws InvalidKeyException, IOException, ConfigurationException;

	/**
	 * Access our internal key store/key server.
	 * @return our KeyRepository
	 */
	public abstract KeyRepository keyRepository();

}
