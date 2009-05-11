package com.parc.ccn.security.access;

import java.util.ArrayList;

import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.content.LinkReference;
import com.parc.ccn.library.profiles.AccessControlProfile;

public class AccessControlManager {

	private ContentName _namespace;
	private ContentName _groupStorage;
	private ContentName _userStorage;
	
	public AccessControlManager(ContentName namespace) {
		this(namespace, AccessControlProfile.groupNamespaceName(namespace), AccessControlProfile.userNamespaceName(namespace));
	}
	
	public AccessControlManager(ContentName namespace, ContentName groupStorage, ContentName userStorage) {
		_namespace = namespace;
		_groupStorage = groupStorage;
		_userStorage = userStorage;
		// DKS TODO here, check for a namespace marker, and if one not there, write it (async)
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
	 * Get a raw node key stored at this node, if any exists and we have rights to decrypt it.
	 * Used in updating node keys and by {@link #getEffectiveNodeKey(ContentName)}.
	 * @param nodeName
	 * @return
	 */
	public NodeKey getNodeKey(ContentName nodeName) {
		
	}
	
	/**
	 * Get the effective node key in force at this node, used to derive keys to 
	 * encrypt and decrypt content.
	 */
	public NodeKey getEffectiveNodeKey(ContentName nodeName) {
		
	}
	
	public DataKey getDataKey(ContentName dataNodeName) {
		
	}
	
	/**
	 * Take a randomly generated data key and store it.
	 * @param dataNodeName
	 * @param newRandomDataKey
	 */
	public void storeDataKey(ContentName dataNodeName, byte [] newRandomDataKey) {
		
	}
	
	/**
	 * Generate a random data key, store it, and return it to use to derive keys to encrypt
	 * content. All that's left is to call
	 * byte [][] keyandiv = 
	 * 		KeyDerivationFunction.DeriveKeyForObject(randomDataKey, keyLabel, 
	 * 												 dataNodeName, dataPublisherPublicKeyDigest)
	 * and then give keyandiv to the segmenter to encrypt the data.
	 **/
	public byte [] generateAndStoreDataKey(ContentName dataNodeName) {
		// Generate new random data key of appropriate length
		byte [] dataKey = new byte[DEFAULT_DATA_KEY_LENGTH];
		storeDataKey(dataNodeName, dataKey);
		return dataKey;
	}
}	
