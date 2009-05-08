package com.parc.ccn.security.access;

import java.util.ArrayList;

import com.parc.ccn.data.ContentName;
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
	public byte [] getNodeKey(ContentName nodeName) {
		
	}
	
	/**
	 * Get the effective node key in force at this node, used to derive keys to 
	 * encrypt and decrypt content.
	 */
	public byte [] getEffectiveNodeKey(ContentName nodeName) {
		
	}
}
