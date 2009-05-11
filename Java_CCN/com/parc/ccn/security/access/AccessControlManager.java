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
	 * Given a data location, pull the data key block and decrypt it using
	 * whatever node keys are necessary.
	 * To turn the result of this into a key for decrypting content,
	 * follow the steps in the comments to {@link #generateAndStoreDataKey(ContentName)}.
	 * @param dataNodeName
	 * @return
	 */
	public byte [] getDataKey(ContentName dataNodeName) {
		// DKS TODO -- library/flow control handling
		WrappedKeyObject wdko = new WrappedKeyObject(dataNodeName);
		wdko.update();
		
		// DKS TODO -- attempt to pull the node key used to encrypt the data key
		// out of the cache.
		NodeKey nk = keyCache().getNodeKey(wdko.wrappedKey().wrappingKeyIdentifier());
		if (null == nk) {
			if (null == wdko.wrappedKey().wrappingKeyName()) {
				throw new IllegalStateException("Data key for node " + dataNodeName + " does not specify its wrapping node key!");
			}
			// We should know what node key to use, but we have to find the specific
			// node key we can decrypt.
			nk = getNodeKey(wdko.wrappedKey().wrappingKeyName());
			if (null == nk) {
				throw new AccessControlException("No decryptable node key available for " + wdko.wrappedKey().wrappingKeyName() + ", access denied.");
			}
		}
	
		NodeKey enk = retrieveNodeKey(wdko.wrappedKey().wrappingKeyName());
		Key dataKey = wko.wrappedKey().unwrapKey(enk.nodeKey());
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
