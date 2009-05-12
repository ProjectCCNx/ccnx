package com.parc.ccn.security.access;

import java.security.AccessControlException;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;

import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.SecretKeySpec;
import javax.xml.stream.XMLStreamException;

import com.parc.ccn.Library;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.content.LinkReference;
import com.parc.ccn.data.security.WrappedKey;
import com.parc.ccn.data.security.WrappedKey.WrappedKeyObject;
import com.parc.ccn.data.util.DataUtils;
import com.parc.ccn.library.profiles.AccessControlProfile;

public class AccessControlManager {
	
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
	public static final String NODE_KEY_LABEL = "Node Key";

	private ContentName _namespace;
	private ContentName _groupStorage;
	private ContentName _userStorage;
	private KeyCache _keyCache = new KeyCache();
	private SecureRandom _random = new SecureRandom();
	
	public AccessControlManager(ContentName namespace) {
		this(namespace, AccessControlProfile.groupNamespaceName(namespace), AccessControlProfile.userNamespaceName(namespace));
	}
	
	public AccessControlManager(ContentName namespace, ContentName groupStorage, ContentName userStorage) {
		_namespace = namespace;
		_groupStorage = groupStorage;
		_userStorage = userStorage;
		// DKS TODO here, check for a namespace marker, and if one not there, write it (async)
	}
	
	/**
	 * Labels for deriving various types of keys.
	 * @return
	 */
	public String dataKeyLabel() {
		return DATA_KEY_LABEL;
	}
	
	public String nodeKeyLabel() {
		return NODE_KEY_LABEL;
	}
	
	private KeyCache keyCache() { return _keyCache; }
	
	public ArrayList<String> listGroups() {
		
	}
	
	public ArrayList<String> listUsers() {
		
	}

	public Group getGroup(String friendlyName) {
		
	}
	
	public Group createGroup(String friendlyName, MembershipList members) {
		
	}
	
	public Group modifyGroup(String friendlyName, ArrayList<LinkReference> membersToAdd, ArrayList<LinkReference> membersToRemove) {
	}
	
	public Group addUsers(String friendlyName, ArrayList<LinkReference> newUsers) {
		return modifyGroup(friendlyName, newUsers, null);
	}
	
	public Group removeUsers(String friendlyName, ArrayList<LinkReference> removedUsers) {
		
	}
	
	public void deleteGroup(String friendlyName) {
		
	}
	
	/**
	 * Retrieve an ACL at this node, if any.
	 * @param nodeName
	 * @return
	 */
	public ACL getACL(ContentName nodeName) {
		
	}
	
	/**
	 * Adds an ACL to a node that doesn't have one, or replaces one that exists.
	 * Does the diffs on any current ACL and makes any necessary changes.
	 */
	public ACL setACL(ContentName nodeName, ACL newACL) {
		
	}
	
	public ACL updateACL(ContentName nodeName, 
						ArrayList<LinkReference> addReaders, ArrayList<LinkReference> removeReaders,
						ArrayList<LinkReference> addWriters, ArrayList<LinkReference> removeWriters,
						ArrayList<LinkReference> addManagers, ArrayList<LinkReference> removeManagers) {
		
	}
		
	public ACL addReaders(ContentName nodeName, ArrayList<LinkReference> newReaders) {
		
	}
	
	public ACL addWriters(ContentName nodeName, ArrayList<LinkReference> newWriters) {
		
	}
	/**
	 * Retrieve the effective ACL operating at this node, either stored here or
	 * inherited from a parent.
	 */
	public ACL getEffectiveACL(ContentName nodeName) {
		
	}
	
	/**
	 * Get a raw node key in force at this node, if any exists and we have rights to decrypt it
	 * with some key we know.
	 * Used in updating node keys and by {@link #getEffectiveNodeKey(ContentName)}.
	 * To achieve this, we walk up the tree for this node. At each point, we check to
	 * see if a node key exists. If one exists, we decrypt it if we know an appropriate
	 * key. Otherwise we return null.
	 * @param nodeName
	 * @return
	 */
	public NodeKey getNodeKey(ContentName nodeName) {
		
	}
	
	/**
	 * Retrieve a specific node key from a given location, as specified by a
	 * key it was used to wrap, and, if possible, find a key we can use to
	 * unwrap the node key.
	 * 
	 * Throw an exception if there is no node key block at the appropriate name.
	 * @param nodeKeyName
	 * @param nodeKeyIdentifier
	 * @return
	 */
	public NodeKey getNodeKey(ContentName nodeKeyName, byte [] nodeKeyIdentifier) {
		
		if ((null == nodeKeyName) && (null == nodeKeyIdentifier)) {
			throw new IllegalArgumentException("Node key name and identifier cannot both be null!");
		}
		// First, do we have it in the cache?
		NodeKey nk = null;
		if (null != nodeKeyIdentifier) {
			nk = keyCache().getNodeKey(nodeKeyIdentifier);
		}
		
		if (null == nk) {
			if (null == nodeKeyName) {
				Library.logger().warning("Cannot find node key " + DataUtils.printHexBytes(nodeKeyIdentifier) +
						" in cache, and no name given.");
				return null;
			}
			// We should know what node key to use, but we have to find the specific
			// node key we can decrypt.
			nk = getNodeKeyByName(nodeKeyName, nodeKeyIdentifier);
			if (null == nk) {
				Library.logger().warning("No decryptable node key available at " + nodeKeyName + ", access denied.");
				return null;
			}
		}
	
		return nk;
	}
	
	/**
	 * Get the effective node key in force at this node, used to derive keys to 
	 * encrypt and decrypt content.
	 * @throws XMLStreamException 
	 * @throws InvalidKeyException 
	 */
	public NodeKey getEffectiveNodeKey(ContentName nodeName) throws InvalidKeyException, XMLStreamException {
		// Get the ancestor node key in force at this node.
		NodeKey nodeKey = getNodeKey(nodeName);
		if (null == nodeKey) {
			throw new IllegalStateException("Cannot retrieve node key for node: " + nodeName + ".");
		}
		NodeKey effectiveNodeKey = nodeKey.computeDescendantNodeKey(nodeName, nodeKeyLabel()); 
		Library.logger().info("Computing effective node key for " + nodeName + " using stored node key " + effectiveNodeKey.storedNodeKeyName());
		return effectiveNodeKey;
	}
	
	/**
	 * Used by content reader to retrieve the keys necessary to decrypt this content
	 * under this access control model.
	 * Given a data location, pull the data key block and decrypt it using
	 * whatever node keys are necessary.
	 * To turn the result of this into a key for decrypting content,
	 * follow the steps in the comments to {@link #generateAndStoreDataKey(ContentName)}.
	 * @param dataNodeName
	 * @return
	 */
	public byte [] getDataKey(ContentName dataNodeName) {
		// DKS TODO -- library/flow control handling
		WrappedKeyObject wdko = new WrappedKeyObject(AccessControlProfile.dataKeyName(dataNodeName));
		wdko.update();
		if (null == wdko.wrappedKey()) {
			Library.logger().warning("Could not retrieve data key for node: " + dataNodeName);
			return null;
		}
		
		// First, we go and look for the node key where the data key suggests
		// it should be, and attempt to decrypt it from there.
		NodeKey nk = getNodeKey(wdko.wrappedKey().wrappingKeyName(), 
								wdko.wrappedKey().wrappingKeyIdentifier());
		if (null == nk) {
			// OK, we will have gotten an exception if the node key simply didn't exist
			// there, so this means that we don't have rights to read it there.
			// The only way we might have rights not visible from this link is if an
			// ACL has been interposed between where we are and the node key, and that
			// ACL does give us rights.
			nk = getNodeKeyUsingInterposedACL(dataNodeName, wdko.wrappedKey().wrappingKeyName(), 
											  wdko.wrappedKey().wrappingKeyIdentifier());
			if (null == nk) {
				// Still can't find it. Give up. Return null, and allow caller to throw the 
				// access exception.
				return null;
			}
		}
		NodeKey enk = nk.computeDescendantNodeKey(dataNodeName, dataKeyLabel());
		Key dataKey = wdko.wrappedKey().unwrapKey(enk.nodeKey());
		return dataKey.getEncoded();
	}
	
	/**
	 * Take a randomly generated data key and store it. This requires finding
	 * the current effective node key, and wrapping this data key in it.
	 * @param dataNodeName
	 * @param newRandomDataKey
	 * @throws IllegalBlockSizeException 
	 * @throws NoSuchPaddingException 
	 * @throws NoSuchAlgorithmException 
	 * @throws InvalidKeyException 
	 */
	public void storeDataKey(ContentName dataNodeName, byte [] newRandomDataKey) throws InvalidKeyException, NoSuchAlgorithmException, NoSuchPaddingException, IllegalBlockSizeException {
		NodeKey effectiveNodeKey = getEffectiveNodeKey(dataNodeName);
		if (null == effectiveNodeKey) {
			throw new IllegalStateException("Cannot retrieve effective node key for node: " + dataNodeName + ".");
		}
		Library.logger().info("Wrapping data key for node: " + dataNodeName + " with effective node key for node: " + 
							  effectiveNodeKey.nodeName() + " derived from stored node key for node: " + 
							  effectiveNodeKey.storedNodeKeyName());
		WrappedKey wrappedDataKey = WrappedKey.wrapKey(new SecretKeySpec(newRandomDataKey, DEFAULT_DATA_KEY_ALGORITHM), 
													   null, dataKeyLabel(), 
													   effectiveNodeKey.nodeKey());
		wrappedDataKey.setWrappingKeyIdentifier(effectiveNodeKey.storedNodeKeyID());
		wrappedDataKey.setWrappingKeyName(effectiveNodeKey.storedNodeKeyName());
		
		storeKeyContent(AccessControlProfile.dataKeyName(dataNodeName), wrappedDataKey);
		
	}
	
	/**
	 * Generate a random data key, store it, and return it to use to derive keys to encrypt
	 * content. All that's left is to call
	 * byte [] randomDataKey = generateAndStoreDataKey(dataNodeName);
	 * byte [][] keyandiv = 
	 * 		KeyDerivationFunction.DeriveKeyForObject(randomDataKey, keyLabel, 
	 * 												 dataNodeName, dataPublisherPublicKeyDigest)
	 * and then give keyandiv to the segmenter to encrypt the data.
	 * @throws IllegalBlockSizeException 
	 * @throws NoSuchPaddingException 
	 * @throws NoSuchAlgorithmException 
	 * @throws InvalidKeyException 
	 **/
	public byte [] generateAndStoreDataKey(ContentName dataNodeName) throws InvalidKeyException, NoSuchAlgorithmException, NoSuchPaddingException, IllegalBlockSizeException {
		// Generate new random data key of appropriate length
		byte [] dataKey = new byte[DEFAULT_DATA_KEY_LENGTH];
		_random.nextBytes(dataKey);
		storeDataKey(dataNodeName, dataKey);
		return dataKey;
	}
}	
