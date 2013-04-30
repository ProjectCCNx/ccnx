/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008-2013 Palo Alto Research Center, Inc.
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

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.Provider;
import java.security.PublicKey;
import java.security.Security;
import java.util.logging.Level;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.impl.CCNFlowControl;
import org.ccnx.ccn.impl.CCNFlowControl.SaveType;
import org.ccnx.ccn.impl.security.keys.BasicKeyManager;
import org.ccnx.ccn.impl.security.keys.PublicKeyCache;
import org.ccnx.ccn.impl.security.keys.SecureKeyCache;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.impl.support.Tuple;
import org.ccnx.ccn.io.CCNReader;
import org.ccnx.ccn.io.ErrorStateException;
import org.ccnx.ccn.io.content.PublicKeyObject;
import org.ccnx.ccn.profiles.SegmentationProfile;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.profiles.repo.RepositoryControl;
import org.ccnx.ccn.profiles.security.KeyProfile;
import org.ccnx.ccn.profiles.security.access.AccessControlManager;
import org.ccnx.ccn.protocol.CCNTime;
import org.ccnx.ccn.protocol.Component;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.ContentObject.SimpleVerifier;
import org.ccnx.ccn.protocol.KeyLocator;
import org.ccnx.ccn.protocol.KeyLocator.KeyLocatorType;
import org.ccnx.ccn.protocol.KeyName;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;
import org.ccnx.ccn.protocol.SignedInfo.ContentType;


/**
 * Top-level interface for managing our own keys, as well as maintaining an address book containing
 * the keys of others (which will be used by the TrustManager). Also handles loading of the BouncyCastle
 * provider, which we need for many things. Very minimal interface now, expect to evolve extensively.
 */
public abstract class KeyManager {
	
	/**
	 * Canary value, indicates we want to override any other key locator available.
	 */
	protected static final KeyLocator SELF_SIGNED_KEY_LOCATOR = new KeyLocator();
	
	/**
	 * Currently default to SHA-256. Only thing that associates a specific digest algorithm
	 * with a version of the CCN protocol is the calculation of the vestigial content digest component
	 * of ContentName used in Interest matching, and publisher digests. Changing the latter
	 * is handled by backwards-compatible changes to the protocol encoding. All other digests are 
	 * stored prefaced with an algorithm identifier, to allow them to be modified.
	 * We expect the protocol default digest algorithm to move to SHA3 when defined.
	 */
	public static final String DEFAULT_DIGEST_ALGORITHM = "SHA-256";
	public static final Provider PROVIDER = getBcProvider();
	
	/**
	 * The default KeyManager for this user/VM pair. The KeyManager will eventually have access
	 * to significant cached state, and so a single one should be shared by as many processes
	 * within the same trust domain as possible. We might make multiple KeyManagers representing
	 * different "users" for testing purposes.
	 */
	protected static KeyManager _defaultKeyManager = null;
	
	/**
	 * A default verifier to use, relative to these key caches and all. Move to TrustManager eventually.
	 */
	protected ContentVerifier _verifier = null;
	
	/**
	 * Accessor to retrieve default key manager instance, or create it if necessary.
	 * @return the KeyManager
	 * @throws ConfigurationException if there is a problem with the user or system configuration that
	 * 		requires intervention to resolve, or we have a significant problem starting up the key manager.
	 */
	public static synchronized KeyManager getDefaultKeyManager() {
		// could print a stack trace
		if (Log.isLoggable(Log.FAC_KEYS, Level.FINER)) {
			Log.finer(Log.FAC_KEYS, "NOTICE: retrieving default key manager. Do you really want to do this?");
			try {
				throw new ConfigurationException("THIS IS NOT AN ERROR: tracking stack trace to find use of default key manager.");
			} catch (ConfigurationException e) {
				Log.logStackTrace(Level.FINER, e);
			}
		}
		if (null != _defaultKeyManager) 
			return _defaultKeyManager;
		try {
			return createDefaultKeyManager();
		} catch (IOException io) {
			Log.warning("IOException attempting to get KeyManager: " + io.getClass().getName() + ":" + io.getMessage());
			Log.warningStackTrace(io);
			throw new RuntimeException("Error in system configuration. Cannot get KeyManager.",io);
		} catch (InvalidKeyException io) {
			Log.warning("InvalidKeyException attempting to get KeyManager: " + io.getClass().getName() + ":" + io.getMessage());
			Log.warningStackTrace(io);
			throw new RuntimeException("Error in system configuration. Cannot get KeyManager.",io);
		} catch (ConfigurationException e) {
			Log.warning("Configuration exception attempting to get KeyManager: " + e.getMessage());
			Log.warningStackTrace(e);
			throw new RuntimeException("Error in system configuration. Cannot get KeyManager.",e);
		}
	}
	
	/**
	 * Clean up state left around by the default key manager and remove it.
	 * For now that just means shutting down the network manager started by it
	 */
	public static synchronized void closeDefaultKeyManager() {
		if (null != _defaultKeyManager) {
			_defaultKeyManager.close();
			_defaultKeyManager = null;
		}
	}
	
	/**
	 * Create the default key manager.
	 * @return the key manager
	 * @throws ConfigurationException if there is a problem with the user or system configuration
	 * 	that requires intervention to fix
	 * @throws IOException if there is an operational problem loading data or initializing the key store
	 * @throws ConfigurationException 
	 */
	protected static synchronized KeyManager createDefaultKeyManager() throws InvalidKeyException, IOException, ConfigurationException {
		if (null == _defaultKeyManager) {
			_defaultKeyManager = new BasicKeyManager();
			_defaultKeyManager.initialize();
		}
		return _defaultKeyManager;
	}
	
	/**
	 * Set the default key manager to one of our choice. If you do this, be careful on 
	 * calling close().
	 */
	public static synchronized void setDefaultKeyManager(KeyManager keyManager) {
		if (null == keyManager) {
			Log.warning("Setting default key manager to NULL. Default user key manager will be loaded on next request for default key manager.");
		}
		closeDefaultKeyManager();
		if (Log.isLoggable(Log.FAC_KEYS, Level.INFO)) { 
			Log.info(Log.FAC_KEYS, "Setting default key manager: new KeyManager {0}", keyManager.getClass().getName());
		}
		_defaultKeyManager = keyManager;
	}
	
	/**
	 * Load the BouncyCastle and other necessary providers, should be called once for initialization. 
	 * Currently this is done by CCNHandle.
	 */
	private static Provider getBcProvider() {
		// first try and get it, in case some other code has already created it.
		Provider p = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME);
		
		// it's not yet known to the Security class, so create it.
		if (p == null) {
			p = new BouncyCastleProvider();
			Security.addProvider(p);
		}
		return p;
	}
	
	/**
	 * Subclasses can override with fancier verification behavior; again move to TrustManager eventually
	 */
	public synchronized ContentVerifier getDefaultVerifier() {
		if (null == _verifier) {
			_verifier = new SimpleVerifier(null, this);
		}
		return _verifier;
	}

	/**
	 * Close any connections we have to the network. Ideally prepare to
	 * reopen them when they are next needed.
	 */
	public void close() {
		synchronized (KeyManager.class) {
			if (_defaultKeyManager == this) {
				_defaultKeyManager = null;
			}
		}
	}

	/**
	 * Allows subclasses to specialize key manager initialization.
	 * @throws ConfigurationException
	 * @throws IOException 
	 */
	public abstract void initialize() throws InvalidKeyException, IOException, ConfigurationException;
	
	public abstract boolean initialized();
	
	public abstract void clearSavedConfigurationState() throws FileNotFoundException, IOException;
		
	/**
	 * Get our default key ID.
	 * @return the digest of our default key
	 */
	public abstract PublisherPublicKeyDigest getDefaultKeyID();
	
	public boolean isOurDefaultKey(PublisherPublicKeyDigest keyID) {
		if (getDefaultKeyID().equals(keyID))
			return true;
		return false;
	}
	
	/**
	 * Access our collected store of public keys.
	 * @return our PublicKeyCache
	 */
	public abstract PublicKeyCache getPublicKeyCache();
	
	/**
	 * Access our store of private keys and other secret key 
	 * material that we have retrieved.
	 * @return our SecureKeyCache
	 */
	public abstract SecureKeyCache getSecureKeyCache();

	public abstract void saveSecureKeyCache() throws FileNotFoundException, IOException;

	public abstract void saveConfigurationState() throws FileNotFoundException,
			IOException;
	
	/**
	 * Not sure that this is the best idea, but others want to bootstrap on
	 * our configuration data store to stash their own config data. Return 
	 * location as a URI as it might be a namespace rather than a directory.
	 */
	public abstract URI getConfigurationDataURI();

	/**
	 * Get our default private key.
	 * @return our default private key
	 */
	public abstract Key getDefaultSigningKey();

	/**
	 * Get our default public key.
	 * @return our default public key
	 */
	public abstract PublicKey getDefaultPublicKey();

	/**
	 * Return the key's content name for a given key id, given
	 * a specified prefix and version. 
	 * The default key name is the publisher ID itself,
	 * under the user's key collection. 
	 * @param keyID[] publisher ID
	 * @return content name
	 */
	public ContentName getDefaultKeyName(ContentName keyPrefix, PublisherPublicKeyDigest keyID, CCNTime keyVersion) {
		if (null == keyPrefix) {
			keyPrefix = getDefaultKeyNamePrefix();
			if (Log.isLoggable(Log.FAC_KEYS, Level.INFO)) { 
				Log.info(Log.FAC_KEYS, "Got default key name prefix: {0}", keyPrefix);
			}
		}
		ContentName keyName = KeyProfile.keyName(keyPrefix, keyID);
		if (keyVersion == null)
			return keyName;
		return new ContentName(keyName, keyVersion);
	}

	/**
	 * Get the key-manager determined default key name for a key. Might include
	 * a version, might allow caller to save with generated version.
	 */
	public abstract ContentName getDefaultKeyName(PublisherPublicKeyDigest keyID);

	
	/**
	 * Allow subclasses to override default publishing location.
	 */
	public abstract ContentName getDefaultKeyNamePrefix();
	
	/**
	 * Gets the preferred key locator for this signing key.
	 * @param publisherKeyID the key whose locator we want to retrieve, 
	 * 		if null retrieves the key locator for our default key
	 * @return the current preferred key locator for that key
	 */
	public abstract KeyLocator getKeyLocator(PublisherPublicKeyDigest publisherKeyID);

	/**
	 * Get our current preferred key locator for this signing key. Uses
	 * getKeyLocator(PublisherPublicKeyDigest).
	 */
	public abstract KeyLocator getKeyLocator(Key signingKey);
	
	/**
	 * Get the key locator for our default key. Same as getKeyLocator(null)
	 */
	public KeyLocator getDefaultKeyLocator() {
		return getKeyLocator(getDefaultKeyID());
	}

	public abstract boolean haveStoredKeyLocator(PublisherPublicKeyDigest keyID);

	public abstract KeyLocator getStoredKeyLocator(PublisherPublicKeyDigest keyID);

	public abstract void clearStoredKeyLocator(PublisherPublicKeyDigest keyID);
		
	/**
	 * Remember the key locator to use for a given key. Use
	 * this to publish this key in the future if not overridden by method
	 * calls. If no key locator stored for this key, and no override
	 * given, compute a KEY type key locator if this key has not been
	 * published, and the name given to it when published if it has.
	 * @param publisherKeyID the key whose locator to set; if null sets it for our
	 * 		default key
	 * @param keyLocator the new key locator for this key; overrides any previous value.
	 * 	If null, erases previous value and defaults will be used.
	 */
	public abstract void setKeyLocator(PublisherPublicKeyDigest publisherKeyID, KeyLocator keyLocator);
	
	/**
	 * Get a KEY type key locator for a particular public key.
	 * @param publisherKeyID the key whose locator we want to retrieve
	 * @return the key locator
	 * @throws IOException 
	 */
	public KeyLocator getKeyTypeKeyLocator(PublisherPublicKeyDigest publisherKeyID) {
		PublicKey theKey = getPublicKey(publisherKeyID);
		if (null == theKey) {
			return null;
		}
		return new KeyLocator(theKey);
	}
	
	
	/**
	 * Get the public key associated with a given publisher
	 * @param publisher the digest of the desired key
	 * @return the key, or null if no such key known to our cache
	 * @throws IOException
	 */
	public abstract PublicKey getPublicKey(PublisherPublicKeyDigest publisher);

	/**
	 * Get the publisher key digest associated with one of our signing keys
	 * @param signingKey key whose publisher data we want
	 * @return the digest of the corresponding public key
	 */
	public abstract PublisherPublicKeyDigest getPublisherKeyID(Key signingKey);
	
	/**
	 * Get the private key associated with a given publisher 
	 * @param publisherKeyID the public key digest of the desired key
	 * @return the key, or null if no such key known to our cache
	 */
	public abstract Key getSigningKey(PublisherPublicKeyDigest publisherKeyID);
	
	/**
	 * Get all of our private keys, used for cache loading.
	 * @return an array of our currently available private keys
	 */
	public abstract Key[] getSigningKeys();
	
	/**
	 * Get the public key digest of all our signing keys -- essentially our available identities.
	 */
	public abstract PublisherPublicKeyDigest [] getAvailableIdentities();
	
	/**
	 * Get any timestamp associate with this key.
	 * @param keyID
	 * @return
	 */
	public abstract CCNTime getKeyVersion(PublisherPublicKeyDigest keyID);

	/**
	 * Get the verification key for a given publisher, going to the network to retrieve it if necessary.
	 * @param publisherKeyID the digest of the keys we want
	 * @param keyLocator the key locator to tell us where to retrieve the key from
	 * @param timeout how long to try to retrieve the key 
	 * @return the key
	 * @throws IOException if we run into an error attempting to read the key
	 */
	public abstract Key getVerificationKey(
			PublisherPublicKeyDigest publisherKeyID, KeyLocator keyLocator, 
			long timeout) throws IOException;

	/**
	 * Get the verification key for a given publisher, going to the network to retrieve it if necessary.
	 * Uses the SystemConfiguration.EXTRA_LONG_TIMEOUT to be aggressive and reexpress.
	 * @param publisherKeyID the digest of the keys we want
	 * @param keyLocator the key locator to tell us where to retrieve the key from
	 * @return the key
	 * @throws IOException if we run into an error attempting to read the key
	 */
	public Key getVerificationKey(
			PublisherPublicKeyDigest publisherKeyID, KeyLocator keyLocator) throws IOException {
		return getVerificationKey(publisherKeyID, keyLocator, SystemConfiguration.EXTRA_LONG_TIMEOUT);
	}
	
	/**
	 * Get the public key for a given publisher as it was explicitly published, 
	 * going to the network to retrieve it if necessary. If the key was not
	 * published as a KEY content item (was in our keystore, or was in a KEY
	 * type of key locator), this wil not retrieve anything.
	 * @param publisherKeyID the digest of the keys we want
	 * @param keyLocator the key locator to tell us where to retrieve the key from
	 * @param timeout how long to try to retrieve the key 
	 * @return the key
	 * @throws IOException if we run into an error attempting to read the key
	 */
	public abstract PublicKeyObject getPublicKeyObject(
			PublisherPublicKeyDigest desiredKeyID, KeyLocator locator, long timeout) throws IOException;

	/**
	 * Allow subclasses to specialize key publication, if any.
	 * @param defaultPrefix our default namespace, if we know
	 * 	one for this environment. If null, take user defaults.
	 * @throws ConfigurationException 
	 */
	public PublicKeyObject publishDefaultKey(ContentName keyName)
			throws IOException, InvalidKeyException {
		if (!initialized()) {
			throw new IOException("KeyServer: cannot publish keys, have not yet initialized KeyManager!");
		}
		return publishKey(keyName, getDefaultKeyID(), null, null);
	}


	/**
	 * Publish a key at a certain name, signed by a specified identity (our
	 * default, if null). Usually used to
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
	 * @param keyName content name of the public key
	 * @param keyToPublish public key digest of key to publish, if null publish our default key
	 * @param handle handle for ccn
	 * @throws IOException
	 * @throws InvalidKeyException
	 */
	public PublicKeyObject publishKey(ContentName keyName, 
						   PublisherPublicKeyDigest keyToPublish,
						   PublisherPublicKeyDigest signingKeyID,
						   KeyLocator signingKeyLocator) throws InvalidKeyException, IOException {
		if (null == keyToPublish) {
			keyToPublish = getDefaultKeyID();
		} 
		PublicKey theKey = getPublicKey(keyToPublish);
		if (null == theKey) {
			Log.warning("Cannot publish key {0} to name {1}, do not have public key in cache.", keyToPublish, keyName);
			return null;
		}
		return publishKey(keyName, theKey, signingKeyID, signingKeyLocator, true);
	}
	
	/**
	 * Publish my public key to a local key server run in this JVM, as a self-signed key
	 * record. We do this by default if we don't have any credentials for this key; this
	 * just allows the caller to explicitly request this behavior even if we do have
	 * credentials.
	 * TODO need mechanism for controlling whether this ends up in the key locator...
	 * @param keyName content name of the public key
	 * @param keyToPublish public key digest of key to publish and to sign with
	 * @param handle handle for ccn
	 * @throws IOException
	 * @throws InvalidKeyException
	 */
	public PublicKeyObject publishSelfSignedKey(ContentName keyName, 
						   PublisherPublicKeyDigest keyToPublish,
						   boolean learnKeyLocator) throws InvalidKeyException, IOException {
		if (null == keyToPublish) {
			keyToPublish = getDefaultKeyID();
		} 
		PublicKey theKey = getPublicKey(keyToPublish);
		if (null == theKey) {
			Log.warning("Cannot publish key {0} to name {1}, do not have public key in cache.", keyToPublish, keyName);
			return null;
		}
		return publishKey(keyName, theKey, keyToPublish, SELF_SIGNED_KEY_LOCATOR, learnKeyLocator);
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
	 * @param signingKeyID key to sign with, if we wish to override default
	 * @param signingKeyLocator locator to use, if we wish to override default; if null, one will
	 * 	be computed
	 * @param learnKeyLocator do we remember the key locator used as the default for this signing key
	 * @throws InvalidKeyException 
	 * @throws IOException
	 * @throws ConfigurationException 
	 */
	public abstract PublicKeyObject publishKey(ContentName keyName, 
			   PublicKey keyToPublish,
			   PublisherPublicKeyDigest signingKeyID,
			   KeyLocator signingKeyLocator,
			   boolean learnKeyLocator) throws InvalidKeyException, IOException;

	/**
	 * Publish a key at a certain name, ensuring that it is stored in a repository. Will throw an
	 * exception if no repository available. Usually used to publish our own keys, but can specify
	 * any key known to our key cache.
	 * @param keyName Name under which to publish the key. Currently added under existing version, or version
	 * 	included in keyName.
	 * @param keyToPublish can be null, in which case we publish our own default public key.
	 * @param handle the handle to use for network requests
	 * @throws InvalidKeyException
	 * @throws IOException
	 */
	public abstract PublicKeyObject publishKeyToRepository(ContentName keyName, 
															PublisherPublicKeyDigest keyToPublish,
															long timeToWaitForPreexisting)
			throws InvalidKeyException, IOException;


	/**
	 * Publish one of our keys to a repository, if it isn't already there, and ensure
	 * that it's self-signed regardless of what credentials we have for it (this
	 * is the default behavior if we have no credentials for the key. Throws an exception
	 * if no repository is available
	 * @param keyName Name under which to publish the key. Currently added under existing version, or version
	 * 	included in keyName.
	 * @param theKey the public key to publish, if we happen to have it; otherwise it will be retrieved
	 *    from cache based on keyToPublish.
	 * @param keyToPublish can be null, in which case we publish our own default public key.
	 * @param handle the handle to use for network requests
	 * @throws InvalidKeyException
	 * @throws IOException
	 */
	public abstract PublicKeyObject publishSelfSignedKeyToRepository(ContentName keyName, 
															PublicKey theKey,
															PublisherPublicKeyDigest keyToPublish,
															long timeToWaitForPreexisting)
			throws InvalidKeyException, IOException;

	/**
	 * Publish our default key to a repository at its default location.
	 * @param handle the handle used for network requests
	 * @throws InvalidKeyException
	 * @throws IOException
	 */
	public PublicKeyObject publishKeyToRepository() throws InvalidKeyException, IOException {
		return publishKeyToRepository(null, null);
	}
	
	public PublicKeyObject publishKeyToRepository(ContentName keyName, PublisherPublicKeyDigest keyToPublish) 
		throws InvalidKeyException, IOException {
		return publishKeyToRepository(keyName, keyToPublish, SystemConfiguration.SHORT_TIMEOUT);
	}
	
	/**
	 * Publish a public key to repository, if it isn't already there.
	 * @param keyName content name of the public key to publish under (adds a version)
	 * @param keyToPublish the key to publish
	 * @param handle the handle to use to publish it with
	 * @return the published information about this key, whether we published it or someone else had
	 * @throws IOException 
	 */
	public static PublicKeyObject publishKeyToRepository(
			ContentName keyName, 
			PublicKey keyToPublish,
			PublisherPublicKeyDigest signingKeyID, 
			KeyLocator signingKeyLocator,
			CCNHandle handle) throws IOException {
		return publishKeyToRepository(keyName, keyToPublish, signingKeyID, signingKeyLocator, 
					SystemConfiguration.SHORT_TIMEOUT, false, handle);
	}
	
	/**
	 * Publish a public key to repository, if it isn't already there.
	 * @param keyName content name of the public key to publish under (adds a version)
	 * @param keyToPublish the key to publish
	 * @param signingKeyID the key to sign with
	 * @param signingKeyLocator the key locator to use
	 * @param timeToWaitForPreexisting how long to wait to see if it has already been published
	 * (avoid re-publishing). If 0, we don't even try to find preexisting content.
	 * @param requirePublisherMatch check to see if we match the specified publisher. Key locator
	 * match too complex to check, make caller do that one.
	 * @param handle the handle to use to publish it with
	 * @return the published information about this key, whether we published it or someone else had
	 * @throws IOException 
	 */
	public static PublicKeyObject publishKeyToRepository(
			ContentName keyName, 
			PublicKey keyToPublish,
			PublisherPublicKeyDigest signingKeyID, 
			KeyLocator signingKeyLocator,
			long timeToWaitForPreexisting,
			boolean requirePublisherMatch,
			CCNHandle handle) throws IOException {


		// To avoid repeating work, we first see if this content is available on the network, then
		// if it's in a repository. That's because if it's not in a repository, we need to know if
		// it's on the network, and this way we save doing that work twice (as the repo-checking code
		// also needs to know if it's on the network).
		PublisherPublicKeyDigest keyDigest = new PublisherPublicKeyDigest(keyToPublish);
		
		// Returns immediately if timeToWaitForPreexisting is 0.
		ContentObject availableContent = 
			CCNReader.isVersionedContentAvailable(keyName, ContentType.KEY, keyDigest.digest(), 
					(requirePublisherMatch ? signingKeyID : null), null, timeToWaitForPreexisting, handle);
		
		// If we want it self-signed...
		if ((SELF_SIGNED_KEY_LOCATOR == signingKeyLocator) && (null != availableContent)) {
			// do mean == here....
			// have already verified that keyDigest is the digest of the content of availableContent
			if (!PublicKeyObject.isSelfSigned(SegmentationProfile.segmentRoot(availableContent.name()), 
					keyDigest, availableContent.signedInfo().getKeyLocator())) {
				// it would be perfect, but it's not self-signed
				if (Log.isLoggable(Log.FAC_KEYS, Level.INFO)) {
					Log.info(Log.FAC_KEYS, "Found our key published under desired name {0}, but not self-signed as required - key locator is {1}.",
							availableContent.name(), availableContent.signedInfo().getKeyLocator());
				}
				availableContent = null;
			}
		}
		
		if (null != availableContent) {
			// Make sure the key is in our repository
			PublicKeyObject pko = new PublicKeyObject(availableContent, handle);
			RepositoryControl.localRepoSync(handle, pko);
			return pko;
			
		} else {		
			// We need to write this content ourselves, nobody else has it. We know we really want to 
			// write it, no point in checking again to see if it's there.
			PublicKeyObject publishedKey =
				publishKey(keyName, keyToPublish, signingKeyID, signingKeyLocator, 
							  null, SaveType.REPOSITORY, handle, handle.keyManager());
			
			if (Log.isLoggable(Log.FAC_KEYS, Level.INFO)) {
				Log.info(Log.FAC_KEYS, "Published key {0} from scratch as content {1}.", publishedKey.getVersionedName(), 
						Component.printURI(publishedKey.getContentDigest()));
			}
			return publishedKey;
		}
	}

	/**
	 * Note: this is the lowest level interface to key publication; there are many higher-level
	 * interfaces that are probably what you want. This needs to be public to get across
	 * package constraints.
	 * Publish a signed record for this key. We've already decided we need to publish,
	 * and how; no more checks are made to see if the key already exists.
	 * 
	 * @param keyName the key's content name. Will add a version when saving if it doesn't
	 * 	have one already. If it does have a version, will use that one (see below for effect
	 * 	of version on the key locator). (Note that this is not
	 * 		standard behavior for savable network content, which needs its version explicitly
	 * 		set.)
	 * @param keyToPublish the public key to publish
	 * @param keyID the publisher id
	 * @param signingKeyID the key id of the key pair to sign with
	 * @param signingKeyLocator the key locator to use if we save this key (if it is not already published).
	 * 	If not specified, we look for the default locator for the signing key. If there is none,
	 * 	and we are signing with the same key we are publishing, we build a
	 * 	self-referential key locator, using the name passed in (versioned or not).
	 * @param flowController flow controller to use. If non-null, saveType is ignored.
	 * @param saveType -- if we don't want to hand in a special-purpose flow controller, set saveType to RAW
	 *   or REPO to get standard publishing behavior.
	 * @param handle the handle to use if we haven't specified a flow controller. Makes a flow controller
	 * 	of the type specified by saveType. 
	 * @param keyManager the key manager to use to pull additional signing information (default keys
	 *   and locators if not specified). If null, taken from handle. Also publish key added to its cache.
	 * @return the published information about this key, whether we published it or someone else had
	 * @throws IOException
	 */
	public static PublicKeyObject publishKey(
			ContentName keyName, PublicKey keyToPublish,
			PublisherPublicKeyDigest signingKeyID, KeyLocator signingKeyLocator,
			CCNFlowControl flowController,
			SaveType saveType, 
			CCNHandle handle,
			KeyManager keyManager) 

	throws IOException {

		if ((null == keyManager) && (null != handle)) {
			keyManager = handle.keyManager();
		}
		
		if ((null == keyManager) || ((null == flowController) && (null == handle)) || 
				((null == flowController) && (null == saveType))) {
			
			// TODO DKS not quite right type...
			throw new ErrorStateException("Must provide a flow controller or a handle and a save type, and a key manager!");
		}
		
		// Now, finally; it's not published, so make an object to write it
		// with. We've already tried to pull it, so don't try here. Will
		// set publisher info below.
		
		// Need a key locator to stick in data entry for
		// locator. Could use key itself, but then would have
		// key both in the content for this item and in the
		// key locator, which is redundant. Use naming form
		// that allows for self-referential key names -- the
		// CCN equivalent of a "self-signed cert". Means that
		// we will refer to only the base key name and the publisher ID.
		if (null == signingKeyID) {
			signingKeyID = keyManager.getDefaultKeyID();
		}

		// Here is where we get tricky. We might really want the key to be of a particular
		// version. In general, as we use the network objects to write versioned versioned stuff,
		// we might not be able to take the last component of a name, if versioned, as the version
		// to use to save -- might really want <name>/<version1>/<version2>. So unless we want to 
		// make that impossible to achieve, we need to not have the network objects take the 
		// name <name>/<version1> and save to <version1> (though they read from <version1> just
		// fine given the same). You always want to save to a new version, unless someone tells you
		// something different from the outside. 
		// Come up with a contorted option. If you want to publish <version>/<version> stuff, you
		// need to pass in the second version...
		
		CCNTime keyVersion = null; // do we force a version?
		Tuple<ContentName, byte []> nameAndVersion = VersioningProfile.cutTerminalVersion(keyName);

		if (null != nameAndVersion.second()) {
			keyVersion = VersioningProfile.getVersionComponentAsTimestamp(nameAndVersion.second());
		} else {
			keyVersion = new CCNTime(); // so we can use it in locator
		}
		
		// Set key locator if not specified, include version for self-signed.
		// Really do want == here
		if ((null == signingKeyLocator) || (SELF_SIGNED_KEY_LOCATOR == signingKeyLocator)) {
			
			KeyLocator existingLocator = keyManager.getKeyLocator(signingKeyID);
			
			// If we've asked for this to be self-signed, or we have made the default KEY
			// type key locator, make this a self-signed key.
			if ((SELF_SIGNED_KEY_LOCATOR == signingKeyLocator) || 
					(existingLocator.type() == KeyLocatorType.KEY)) {
				
				PublisherPublicKeyDigest keyDigest = new PublisherPublicKeyDigest(keyToPublish);
				if (signingKeyID.equals(keyDigest)) {
					// Make a self-referential key locator. Include the version, in case we are not using the key ID in the name.
					// People wanting versionless key locators need to construct their own.
					existingLocator = new KeyLocator(
							new KeyName(new ContentName(nameAndVersion.first(), keyVersion), signingKeyID));
					
					if (Log.isLoggable(Log.FAC_KEYS, Level.FINER)) {
						Log.finer(Log.FAC_KEYS, "Overriding constructed key locator of type KEY, making self-referential locator {0}", existingLocator);
					}
				}
			}
			signingKeyLocator = existingLocator;
		}			

		PublicKeyObject keyObject = null;
		if (null != flowController) {
			// If a flow controller was specified, use that
			keyObject = new PublicKeyObject(nameAndVersion.first(), keyToPublish, 
											signingKeyID, signingKeyLocator, flowController);
		} else {
			// No flow controller given, use specified saveType.
			keyObject = new PublicKeyObject(nameAndVersion.first(), keyToPublish, saveType,
											signingKeyID, signingKeyLocator, handle);
		}
		

		if (Log.isLoggable(Log.FAC_KEYS, Level.INFO)) { 
			Log.info(Log.FAC_KEYS, "publishKey: key not previously published, making new key object {0} with version {1} displayed as {2}", 
				keyObject.getVersionedName(), keyVersion, 
				((null != nameAndVersion.second()) ? Component.printURI(nameAndVersion.second()) : "<no version>"));
		}

		// Eventually may want to find something already published and link to it, but be simple here.

		if (!keyObject.save(keyVersion)) {
			if (Log.isLoggable(Log.FAC_KEYS, Level.INFO)) { 
				Log.info(Log.FAC_KEYS, "Not saving key when we thought we needed to: desired key value {0}, have key value {1}, " +
					keyToPublish, new PublisherPublicKeyDigest(keyObject.publicKey()));
			}
		} else {
			if (Log.isLoggable(Log.FAC_KEYS, Level.INFO)) { 
				Log.info(Log.FAC_KEYS, "Published key {0} to name {1} with key locator {2}; ephemeral digest {3}.", 
						keyToPublish, keyObject.getVersionedName(), signingKeyLocator,
						Component.printURI(keyObject.getFirstDigest()));
			}
		}
		keyManager.getPublicKeyCache().remember(keyObject);
		return keyObject;
	}

	/**
	 * Right now KeyServers are hidden in our subclasses.... this makes it hard to expose
	 * control of filter registration. This is a bad attempt at an API for that, it should
	 * change. Don't make it abstract as subclasses may not need it.
	 * @throws IOException 
	 */
	public void respondToKeyRequests(ContentName keyPrefix) throws IOException {}
	
	/**
	 * Handle access control manager cache.
	 * @param contentName
	 * @return
	 */
	public abstract AccessControlManager getAccessControlManagerForName(ContentName contentName);
	
	public abstract void rememberAccessControlManager(AccessControlManager acm);
}
