package org.ccnx.ccn.profiles.security.access;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.SecureRandom;

import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.SecretKeySpec;

import org.bouncycastle.crypto.InvalidCipherTextException;
import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.impl.CCNFlowControl.SaveType;
import org.ccnx.ccn.impl.security.crypto.ContentKeys;
import org.ccnx.ccn.impl.security.crypto.KDFContentKeys;
import org.ccnx.ccn.impl.support.DataUtils;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.ContentDecodingException;
import org.ccnx.ccn.io.content.ContentEncodingException;
import org.ccnx.ccn.io.content.ContentGoneException;
import org.ccnx.ccn.io.content.ContentNotReadyException;
import org.ccnx.ccn.io.content.WrappedKey;
import org.ccnx.ccn.io.content.WrappedKey.WrappedKeyObject;
import org.ccnx.ccn.profiles.SegmentationProfile;
import org.ccnx.ccn.profiles.namespace.NamespaceManager;
import org.ccnx.ccn.profiles.namespace.NamespaceManager.Root.RootObject;
import org.ccnx.ccn.profiles.security.access.group.NodeKey;
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
	
	public ContentName getNamespaceRoot() { return _namespace; }

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
	 * Find the key to use to wrap a data key at this node. This requires
	 * the current effective node key, and wrapping this data key in it. If the
	 * current node key is dirty, this causes a new one to be generated.
	 * If data at the current node is public, this returns null. Does not check
	 * to see whether content is excluded from encryption (e.g. by being access
	 * control data).
	 * @param dataNodeName the node for which to find a data key wrapping key
	 * @return if null, the data is to be unencrypted.  (Alteratively, could
	 *   return a NodeKey that indicates public.)
	 * @param newRandomDataKey
	 * @throws AccessDeniedException if we don't have rights to retrieve key.
	 * @throws InvalidKeyException 
	 * @throws ContentEncodingException
	 * @throws IOException
	 * @throws InvalidCipherTextException 
	 */
	public abstract NodeKey getDataKeyWrappingKey(ContentName dataNodeName)
	 	throws AccessDeniedException, InvalidKeyException,
	 		ContentEncodingException, IOException, InvalidCipherTextException;

	/**
	 * Wrap a data key in a given node key and store it.
	 * @param dataNodeName
	 * @param dataKey
	 * @param wrappingKey
	 * @throws InvalidKeyException
	 * @throws ContentEncodingException
	 * @throws IOException
	 */
	public void storeDataKey(ContentName dataNodeName, Key dataKey, NodeKey wrappingKey) throws InvalidKeyException, ContentEncodingException, IOException {
		Log.info("Wrapping data key for node: " + dataNodeName + " with ewrappingKey for node: " + 
				wrappingKey.nodeName() + " derived from stored node key for node: " + 
				wrappingKey.storedNodeKeyName());
		// TODO another case where we're wrapping in an effective node key but labeling it with
		// the stored node key information. This will work except if we interpose an ACL in the meantime -- 
		// we may not have the information necessary to figure out how to decrypt.
		WrappedKey wrappedDataKey = WrappedKey.wrapKey(dataKey, 
				null, dataKeyLabel(), 
				wrappingKey.nodeKey());
		wrappedDataKey.setWrappingKeyIdentifier(wrappingKey.storedNodeKeyID());
		wrappedDataKey.setWrappingKeyName(wrappingKey.storedNodeKeyName());

		storeKeyContent(AccessControlProfile.dataKeyName(dataNodeName), wrappedDataKey);		
	}

	/**
	 * Generate a random data key.
	 * @throws IOException 
	 * @throws ContentEncodingException 
	 * @throws AccessDeniedException 
	 * @throws InvalidKeyException 
	 * @throws InvalidCipherTextException 
	 **/
	public Key generateDataKey(ContentName dataNodeName)
	throws InvalidKeyException, AccessDeniedException,
	ContentEncodingException, IOException, InvalidCipherTextException {
		// Generate new random data key of appropriate length
		byte [] dataKeyBytes = new byte[DEFAULT_DATA_KEY_LENGTH];
		_random.nextBytes(dataKeyBytes);
		Key dataKey = new SecretKeySpec(dataKeyBytes, DEFAULT_DATA_KEY_ALGORITHM);
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
		WrappedKeyObject wko = new WrappedKeyObject(AccessControlProfile.dataKeyName(dataNodeName), wrappedKey, SaveType.REPOSITORY, handle());
		wko.save();
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
	public ContentKeys getContentKeys(ContentName dataNodeName)
	throws InvalidKeyException, InvalidCipherTextException, AccessDeniedException, IOException {
		if (SegmentationProfile.isSegment(dataNodeName)) {
			dataNodeName = SegmentationProfile.segmentRoot(dataNodeName);
		}
		Key dataKey = getDataKey(dataNodeName);
		if (null == dataKey)
			return null;
		return getDefaultAlgorithmContentKeys(dataKey);
	}
	
	public static ContentKeys getDefaultAlgorithmContentKeys(Key dataKey) throws InvalidKeyException {
		try {
			// TODO - figure out where algorithm spec lives
			return new KDFContentKeys(ContentKeys.DEFAULT_CIPHER_ALGORITHM, dataKey.getEncoded(), DATA_KEY_LABEL);
		} catch (NoSuchAlgorithmException e) {
			String err = "Unexpected NoSuchAlgorithmException for default algorithm we have already used!";
			Log.severe(err);
			throw new InvalidKeyException(err, e);
		} catch (NoSuchPaddingException e) {
			String err = "Unexpected NoSuchPaddingException for default algorithm we have already used!";
			Log.severe(err);
			throw new InvalidKeyException(err, e);
		}		
	}

	/**
	 * Called when a stream is opened for reading, to determine if the name is under a root ACL, and
	 * if so find or create an AccessControlManager, and get keys for access. Only called if
	 * content is encrypted.
	 * @param name name of the stream to be opened.
	 * @param library CCN Library instance to use for any network operations.
	 * @return If the stream is under access control then keys to decrypt the data are returned if it's
	 * encrypted. If the stream is not under access control (no Root ACL block can be found) then null is
	 * returned.
	 * @throws IOException if a problem happens getting keys.
	 */
	public static ContentKeys keysForInput(ContentName name, CCNHandle handle)
	throws IOException {
		AccessControlManager acm;
		try {
			acm = NamespaceManager.findACM(name, handle);
			if (acm != null) {
				Log.info("keysForInput: retrieving key for data node {0}", name);
				return acm.getContentKeys(name);
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
			acm = null;
			
			if ((acm != null) && (acm.isProtectedContent(name, handle))) {
				// First we need to figure out whether this content is public or unprotected...
				Log.info("keysForOutput: found ACM, protected content, generating new data key for data node {0}", name);
				NodeKey dataKeyWrappingKey = acm.getDataKeyWrappingKey(name);
				if (null == dataKeyWrappingKey) {
					// if content is public -- either null or a special value would work
					return null; // no keys
				}
				Key dataKey = acm.generateDataKey(name);
				acm.storeDataKey(name, dataKey, dataKeyWrappingKey);
				return getDefaultAlgorithmContentKeys(dataKey);
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
	public boolean isProtectedContent(ContentName name, CCNHandle hande) {

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
