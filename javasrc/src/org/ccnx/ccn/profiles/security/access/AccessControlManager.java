package org.ccnx.ccn.profiles.security.access;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.PrivateKey;
import java.security.SecureRandom;

import javax.crypto.spec.SecretKeySpec;

import org.bouncycastle.crypto.InvalidCipherTextException;
import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.impl.security.crypto.ContentKeys;
import org.ccnx.ccn.impl.security.crypto.KeyDerivationFunction;
import org.ccnx.ccn.impl.support.DataUtils;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.ContentDecodingException;
import org.ccnx.ccn.io.content.ContentEncodingException;
import org.ccnx.ccn.io.content.ContentGoneException;
import org.ccnx.ccn.io.content.ContentNotReadyException;
import org.ccnx.ccn.io.content.WrappedKey;
import org.ccnx.ccn.io.content.WrappedKey.WrappedKeyObject;
import org.ccnx.ccn.profiles.namespace.NamespaceManager;
import org.ccnx.ccn.profiles.namespace.NamespaceManager.Root.RootObject;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;

public abstract class AccessControlManager {

	/**
	 * Default data key length in bytes. No real reason this can't be bumped up to 32. It
	 * acts as the seed for a KDF, not an encryption key.
	 */
	public static final int DEFAULT_DATA_KEY_LENGTH = 16;
	/**
	 * The keys we're wrapping are really seeds for a KDF, not keys in their own right.
	 * Eventually we'll use CMAC, so call them AES...
	 */
	public static final String DEFAULT_DATA_KEY_ALGORITHM = "AES";
	public static final String DATA_KEY_LABEL = "Data Key";
	protected ContentName _namespace;
	protected KeyCache _keyCache;
	protected CCNHandle _handle;
	protected SecureRandom _random = new SecureRandom();

	/**
	 * Factory method.
	 * Eventually split between a superclass AccessControlManager that handles many
	 * access schemes and a subclass GroupBasedAccessControlManager. For now, put
	 * a factory method here that makes you an ACM based on information in a stored
	 * root object. Have to trust that object as a function of who signed it.
	 */
	public static AccessControlManager createManager(RootObject policyInformation, CCNHandle handle) {
		return null; // TODO fill in 
	}

	/**
	 * Labels for deriving various types of keys.
	 * @return
	 */
	public String dataKeyLabel() {
		return DATA_KEY_LABEL;
	}

	public CCNHandle handle() { return _handle; }

	protected KeyCache keyCache() { return _keyCache; }
	
	public boolean inProtectedNamespace(ContentName content) {
		return _namespace.isPrefixOf(content);
	}

	/**
	 * Used by content reader to retrieve the keys necessary to decrypt this content.
	 * Delegates to specific subclasses to retrieve data key using retrieveWrappedDataKey,
	 * and then if key used to encrypt data key isn't
	 * in cache, delegates retrieving the unwrapping key 
	 * to subclasses using getDataKeyUnwrappingKey. Provides a default implementation
	 * of retrieveDataKey. 
	 * To turn the result of this into a key for decrypting content,
	 * follow the steps in the comments to #generateAndStoreDataKey(ContentName).
	 * @param dataNodeName
	 * @return
	 * @throws IOException 
	 * @throws ContentDecodingException 
	 * @throws InvalidCipherTextException 
	 * @throws InvalidKeyException 
	 */
	public Key getDataKey(ContentName dataNodeName) throws ContentDecodingException,
				IOException, InvalidKeyException, InvalidCipherTextException {
		
		// Let subclasses change data key storage conventions.
		WrappedKeyObject wdko = retrieveWrappedDataKey(dataNodeName);
		if (null == wdko) {
			return null;
		}
		
		Key dataKey = null;
		Key wrappingKey = null;
		
		if (hasKey(wdko.wrappedKey().wrappingKeyIdentifier())) {
			wrappingKey = getKey(wdko.wrappedKey().wrappingKeyIdentifier());
			if (null == wrappingKey) {
				Log.warning("Thought we had key {0} in cache, but cannot retrieve it! Data node: {1}.", 
						DataUtils.printHexBytes(wdko.wrappedKey().wrappingKeyIdentifier()),
						dataNodeName);
				// fall through, try subclass retrieval
			} else {
				Log.fine("Unwrapping key for data node {0} with cached key {1}.", dataNodeName,
						DataUtils.printHexBytes(wdko.wrappedKey().wrappingKeyIdentifier()));	
			}
		}
		// Could simplify to remove cache-retry logic.
		if (null == wrappingKey) {
			// No dice. Try subclass-specific retrieval.
			wrappingKey = getDataKeyWrappingKey(dataNodeName, wdko);
		}
		if (null != wrappingKey) {
			dataKey = wdko.wrappedKey().unwrapKey(wrappingKey);
			return dataKey;
		}
		return null;
	}
	
	protected abstract Key getDataKeyWrappingKey(ContentName dataNodeName, WrappedKeyObject wrappedDataKeyObject) throws
			InvalidKeyException, ContentNotReadyException, ContentGoneException, ContentEncodingException, 
				ContentDecodingException, InvalidCipherTextException, IOException;		
	
	protected WrappedKeyObject retrieveWrappedDataKey(ContentName dataNodeName) 
				throws ContentDecodingException, ContentGoneException, ContentNotReadyException, IOException {
		
		WrappedKeyObject wdko = new WrappedKeyObject(AccessControlProfile.dataKeyName(dataNodeName), handle());
		if (null == wdko.wrappedKey()) {
			Log.warning("Could not retrieve data key for node: " + dataNodeName);
			return null;
		}
		return wdko;
	}
	
	/**
	 * Take a randomly generated data key and store it. This requires finding
	 * the current effective node key, and wrapping this data key in it. If the
	 * current node key is dirty, this causes a new one to be generated.
	 * @param dataNodeName
	 * @param newRandomDataKey
	 * @throws AccessDeniedException 
	 * @throws InvalidKeyException 
	 * @throws ContentEncodingException
	 * @throws IOException
	 * @throws InvalidCipherTextException 
	 */
	public abstract void storeDataKey(ContentName dataNodeName, Key newRandomDataKey)
	 	throws AccessDeniedException, InvalidKeyException,
	 		ContentEncodingException, IOException, InvalidCipherTextException;

	/**
	 * Generate a random data key, store it, and return it to use to derive keys to encrypt
	 * content. All that's left is to call
	 * byte [] randomDataKey = generateAndStoreDataKey(dataNodeName);
	 * byte [][] keyandiv = 
	 * 		KeyDerivationFunction.DeriveKeyForObject(randomDataKey, keyLabel, 
	 * 												 dataNodeName, dataPublisherPublicKeyDigest)
	 * and then give keyandiv to the segmenter to encrypt the data.
	 * @throws IOException 
	 * @throws ContentEncodingException 
	 * @throws AccessDeniedException 
	 * @throws InvalidKeyException 
	 * @throws InvalidCipherTextException 
	 **/
	public Key generateAndStoreDataKey(ContentName dataNodeName)
	throws InvalidKeyException, AccessDeniedException,
	ContentEncodingException, IOException, InvalidCipherTextException {
		// Generate new random data key of appropriate length
		byte [] dataKeyBytes = new byte[DEFAULT_DATA_KEY_LENGTH];
		_random.nextBytes(dataKeyBytes);
		Key dataKey = new SecretKeySpec(dataKeyBytes, DEFAULT_DATA_KEY_ALGORITHM);
		storeDataKey(AccessControlProfile.dataKeyName(dataNodeName), dataKey);
		return dataKey;
	}

	/**
	 * Actual output functions. 
	 * @param dataNodeName -- the content node for whom this is the data key.
	 * @param wrappedDataKey
	 * @throws IOException 
	 * @throws ContentEncodingException 
	 */
	protected void storeKeyContent(ContentName dataNodeName, WrappedKey wrappedKey) throws ContentEncodingException, IOException {
		WrappedKeyObject wko = new WrappedKeyObject(AccessControlProfile.dataKeyName(dataNodeName), wrappedKey, handle());
		wko.saveToRepository();
	}

	/**
	 * Add a private key to our cache
	 * @param keyName
	 * @param publicKeyIdentifier
	 * @param pk
	 */
	public void addPrivateKey(ContentName keyName, byte [] publicKeyIdentifier, PrivateKey pk) {
		_keyCache.addPrivateKey(keyName, publicKeyIdentifier, pk);
	}

	/**
	 * Add my private key to our cache
	 * @param publicKeyIdentifier
	 * @param pk
	 */
	public void addMyPrivateKey(byte [] publicKeyIdentifier, PrivateKey pk) {
		_keyCache.addMyPrivateKey(publicKeyIdentifier, pk);
	}

	/**
	 * Add a key to our cache
	 * @param name
	 * @param key
	 */
	public void addKey(ContentName name, Key key) {
		_keyCache.addKey(name, key);
	}
	
	public boolean hasKey(byte [] keyID) {
		return _keyCache.containsKey(keyID);
	}
	
	protected Key getKey(byte [] desiredKeyIdentifier) {
		return _keyCache.getKey(desiredKeyIdentifier);
	}

	/**
	 * Given the name of a content stream, this function verifies that access is allowed and returns the
	 * keys required to decrypt the stream.
	 * @param dataNodeName The name of the stream, including version component, but excluding
	 * segment component.
	 * @return Returns the keys ready to be used for en/decryption, or null if the content is not encrypted.
	 * @throws IOException 
	 * @throws InvalidKeyException 
	 * @throws InvalidCipherTextException 
	 * @throws AccessDeniedException 
	 */
	public ContentKeys getContentKeys(ContentName dataNodeName, PublisherPublicKeyDigest publisher)
	throws InvalidKeyException, InvalidCipherTextException, AccessDeniedException, IOException {
		Key dataKey = getDataKey(dataNodeName);
		if (null == dataKey)
			return null;
		return KeyDerivationFunction.DeriveKeysForObject(dataKey.getEncoded(), DATA_KEY_LABEL, dataNodeName, publisher);
	}

	/**
	 * Called when a stream is opened for reading, to determine if the name is under a root ACL, and
	 * if so find or create an AccessControlManager, and get keys for access. Only called if
	 * content is encrypted.
	 * @param name name of the stream to be opened.
	 * @param publisher publisher of the stream to be opened.
	 * @param library CCN Library instance to use for any network operations.
	 * @return If the stream is under access control then keys to decrypt the data are returned if it's
	 * encrypted. If the stream is not under access control (no Root ACL block can be found) then null is
	 * returned.
	 * @throws IOException if a problem happens getting keys.
	 */
	public static ContentKeys keysForInput(ContentName name, PublisherPublicKeyDigest publisher, CCNHandle handle)
	throws IOException {
		AccessControlManager acm;
		try {
			acm = NamespaceManager.findACM(name, handle);
			if (acm != null) {
				Log.info("keysForInput: retrieving key for data node {0}", name);
				return acm.getContentKeys(name, publisher);
			}
		} catch (ConfigurationException e) {
			// TODO use 1.6 constuctors that take nested exceptions when can move off 1.5
			throw new IOException(e.getClass().getName() + ": Opening stream for input: " + e.getMessage());
		} catch (InvalidCipherTextException e) {
			// TODO use 1.6 constuctors that take nested exceptions when can move off 1.5
			throw new IOException(e.getClass().getName() + ": Opening stream for input: " + e.getMessage());
		} catch (InvalidKeyException e) {
			// TODO use 1.6 constuctors that take nested exceptions when can move off 1.5
			throw new IOException(e.getClass().getName() + ": Opening stream for input: " + e.getMessage());
		}
		return null;
	}

	/**
	 * Get keys to encrypt content as its' written, if that content is to be protected.
	 * @param name
	 * @param publisher
	 * @param handle
	 * @return
	 * @throws IOException
	 */
	public static ContentKeys keysForOutput(ContentName name, PublisherPublicKeyDigest publisher, CCNHandle handle)
	throws IOException {
		AccessControlManager acm;
		try {
			acm = NamespaceManager.findACM(name, handle);
			if ((acm != null) && (acm.isProtectedContent(name, publisher, handle))) {
				Log.info("keysForOutput: generating new data key for data node {0}", name);
				Key dataKey = acm.generateAndStoreDataKey(name);
				return KeyDerivationFunction.DeriveKeysForObject(dataKey.getEncoded(), DATA_KEY_LABEL, name, publisher);
			}
		} catch (ConfigurationException e) {
			// TODO use 1.6 constuctors that take nested exceptions when can move off 1.5
			throw new IOException(e.getClass().getName() + ": Opening stream for input: " + e.getMessage());
		} catch (InvalidCipherTextException e) {
			// TODO use 1.6 constuctors that take nested exceptions when can move off 1.5
			throw new IOException(e.getClass().getName() + ": Opening stream for input: " + e.getMessage());
		} catch (InvalidKeyException e) {
			// TODO use 1.6 constuctors that take nested exceptions when can move off 1.5
			throw new IOException(e.getClass().getName() + ": Opening stream for input: " + e.getMessage());
		}
		return null;
	}

	/**
	 * Allow AccessControlManagers to specify some content is not to be protected; for example,
	 * access control lists are not themselves encrypted. 
	 * TODO: should headers be exempt from encryption?
	 */
	public boolean isProtectedContent(ContentName name, PublisherPublicKeyDigest publisher, CCNHandle hande) {

		if (!inProtectedNamespace(name)) {
			return false;
		}

		if (AccessControlProfile.isAccessName(name)) {
			// Don't encrypt the access control metadata itself, or we couldn't get the
			// keys to decrypt the other stuff.
			return false;
		}
		return true;
	}

}
