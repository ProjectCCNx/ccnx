package com.parc.ccn.security.access;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

import javax.jcr.AccessDeniedException;
import javax.xml.stream.XMLStreamException;

import org.bouncycastle.crypto.InvalidCipherTextException;

import com.parc.ccn.Library;
import com.parc.ccn.config.ConfigurationException;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.content.CollectionData;
import com.parc.ccn.data.content.LinkReference;
import com.parc.ccn.data.security.PublicKeyObject;
import com.parc.ccn.data.security.PublisherID;
import com.parc.ccn.data.util.DataUtils;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.library.EnumeratedNameList;
import com.parc.ccn.library.profiles.AccessControlProfile;
import com.parc.ccn.library.profiles.VersioningProfile;

public class GroupManager {
	
	private AccessControlManager _accessManager;
	private ContentName _groupStorage;
	private EnumeratedNameList _groupList;
	private HashMap<String, Group> _groupCache = new HashMap<String, Group>();
	private HashSet<String> _myGroupMemberships = new HashSet<String>();
	private CCNLibrary _library;

	public GroupManager(AccessControlManager accessManager,
						ContentName groupStorage, CCNLibrary library) throws IOException {
		_library = library;
		_accessManager = accessManager;
		_groupStorage = groupStorage;
		groupList();
	}
	
	public AccessControlManager getAccessManager() { return _accessManager; }
	
	public EnumeratedNameList groupList() throws IOException {
		if (null == _groupList) {
			_groupList = new EnumeratedNameList(_groupStorage, _library);
		}
		return _groupList;
	}
	
	public Group getGroup(String groupFriendlyName) throws IOException, ConfigurationException, XMLStreamException {
		Group theGroup = _groupCache.get(groupFriendlyName);
		if ((null == theGroup) && (groupList().hasChild(groupFriendlyName))) {
			// Only go hunting for it if we think it exists, otherwise we'll block.
			synchronized(_groupCache) {
				theGroup = _groupCache.get(groupFriendlyName);
				if (null == theGroup) {
					theGroup = new Group(_groupStorage, groupFriendlyName, _library);
					// wait for group to be ready?
					_groupCache.put(groupFriendlyName, theGroup);
				}
			}
		}
		// either we've got it, or we don't believe it exists.
		// DKS startup transients? do we need to block for group list?
		return theGroup;
	}
	
	public void cacheGroup(Group newGroup) {
		synchronized(_groupCache) {
			_groupCache.put(newGroup.friendlyName(), newGroup);
		}
	}
	
	public Group createGroup(String groupFriendlyName, ArrayList<LinkReference> newMembers) 
				throws XMLStreamException, IOException, ConfigurationException, InvalidKeyException, 
						InvalidCipherTextException, AccessDeniedException {
		Group existingGroup = getGroup(groupFriendlyName);
		if (null != existingGroup) {
			existingGroup.setMembershipList(this, newMembers);
			return existingGroup;
		} else {
			// Need to make key pair, directory, and store membership list.
			MembershipList ml = 
				new MembershipList(
						AccessControlProfile.groupMembershipListName(_groupStorage, groupFriendlyName), 
						new CollectionData(newMembers), _library);
			Group newGroup =  new Group(_groupStorage, groupFriendlyName, ml, _library, this);
			cacheGroup(newGroup);
			// If I'm a group member (I end up knowing the private key of the group if I
			// created it, but I could simply forget it...).
			if (amCurrentGroupMember(newGroup)) {
				_myGroupMemberships.add(groupFriendlyName);
			}
			return newGroup;
		}
	}
	
	public Group modifyGroup(String friendlyName, 
							ArrayList<LinkReference> membersToAdd, 
							ArrayList<LinkReference> membersToRemove) 
				throws XMLStreamException, IOException, InvalidKeyException, InvalidCipherTextException, AccessDeniedException, ConfigurationException {
		Group theGroup = getGroup(friendlyName);
		
		// DKS we really want to be sure we get the group if it's out there...
		if (null != theGroup) {
			Library.logger().info("Got existing group to modify: " + theGroup);
			theGroup.modify(this, membersToAdd, membersToRemove);
		} else {
			Library.logger().info("No existing group to modify: " + friendlyName + " adding new one.");
			theGroup = createGroup(friendlyName, membersToAdd);
		}
		return theGroup;
	}
	
	public Group addUsers(String friendlyName, ArrayList<LinkReference> newUsers) throws XMLStreamException, IOException, InvalidKeyException, InvalidCipherTextException, AccessDeniedException, ConfigurationException {
		return modifyGroup(friendlyName, newUsers, null);
	}
	
	public Group removeUsers(String friendlyName, ArrayList<LinkReference> removedUsers) throws XMLStreamException, IOException, InvalidKeyException, InvalidCipherTextException, AccessDeniedException, ConfigurationException {
		return modifyGroup(friendlyName, null, removedUsers);
	}
	
	public void deleteGroup(String friendlyName) throws IOException, ConfigurationException, XMLStreamException {
		Group existingGroup = getGroup(friendlyName);
		
		// DKS we really want to be sure we get the group if it's out there...
		if (null != existingGroup) {
			Library.logger().info("Got existing group to delete: " + existingGroup);
			existingGroup.delete();
		} else {
			Library.logger().warning("No existing group: " + friendlyName + ", ignoring delete request.");
		}
	}
	
	/**
	 * Does this member refer to a user or a group. Groups have to be in the
	 * group namespace, users can be anywhere.
	 * @param member
	 * @return
	 */
	public boolean isGroup(LinkReference member) {
		return _groupStorage.isPrefixOf(member.targetName());
	}
	
	public boolean isGroup(String principal) {
		return _groupList.hasChild(principal);
	}

	public boolean haveKnownGroupMemberships() {
		return _myGroupMemberships.size() > 0;
	}

	public boolean amKnownGroupMember(String principal) {
		return _myGroupMemberships.contains(principal);
	}

	public boolean amCurrentGroupMember(String principal) throws IOException, XMLStreamException, ConfigurationException {
		return amCurrentGroupMember(getGroup(principal));
	}
	
	/**
	 * Start out doing this the slow and simple way. Optimize later.
	 * @param group
	 * @return
	 * @throws IOException 
	 * @throws XMLStreamException 
	 * @throws ConfigurationException 
	 */
	public boolean amCurrentGroupMember(Group group) throws IOException, XMLStreamException, ConfigurationException {
		MembershipList ml = group.membershipList(); // will update
		for (LinkReference lr : ml.membershipList().contents()) {
			if (isGroup(lr)) {
				String groupFriendlyName = AccessControlProfile.groupNameToFriendlyName(lr.targetName());
				if (amCurrentGroupMember(groupFriendlyName)) {
					_myGroupMemberships.add(groupFriendlyName);
					return true;
				} else {
					// Don't need to test first. Won't remove if isn't there.
					_myGroupMemberships.remove(groupFriendlyName);
				}
			} else {
				// Not a group. Is it me?
				if (_accessManager.haveIdentity(lr.targetName())) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * I already believe I should have access to this private key.
	 * @param group
	 * @param privateKeyVersion
	 * @return
	 * @throws XMLStreamException 
	 * @throws IOException 
	 * @throws InvalidCipherTextException 
	 * @throws AccessDeniedException 
	 * @throws InvalidKeyException 
	 * @throws AccessDeniedException 
	 * @throws ConfigurationException 
	 */
	public PrivateKey getGroupPrivateKey(String groupFriendlyName, Timestamp privateKeyVersion) throws InvalidKeyException, InvalidCipherTextException, IOException, XMLStreamException, AccessDeniedException, ConfigurationException {
		// Heuristic check
		if (!amKnownGroupMember(groupFriendlyName)) {
			Library.logger().info("Unexpected: we don't think we're a group member of group " + groupFriendlyName);
		}
		// Need to get the KeyDirectory for this version of the private key, or the 
		// latest if no version given.
		KeyDirectory privateKeyDirectory = null;
		PublicKey theGroupPublicKey = null;
		if (null == privateKeyVersion) {
			Group theGroup = getGroup(groupFriendlyName); // will pull latest public key
			privateKeyDirectory = theGroup.privateKeyDirectory(_accessManager);
			theGroupPublicKey = theGroup.publicKey();
		} else {
			// Assume one is there...
			ContentName versionedPublicKeyName = 
				VersioningProfile.versionName(
						AccessControlProfile.groupPublicKeyName(_groupStorage, groupFriendlyName),
						privateKeyVersion);
			privateKeyDirectory =
				new KeyDirectory(_accessManager, 
					AccessControlProfile.groupPrivateKeyDirectory(versionedPublicKeyName), _library);
			PublicKeyObject thisPublicKey = new PublicKeyObject(versionedPublicKeyName, _library);
			theGroupPublicKey = thisPublicKey.publicKey();
		}
		if (null == privateKeyDirectory) {
			Library.logger().info("Unexpected: null private key directory for group " + groupFriendlyName + " version " + privateKeyVersion + " as stamp " + 
					DataUtils.printHexBytes(DataUtils.timestampToBinaryTime12(privateKeyVersion)));
			return null;
		}
		PrivateKey privateKey = privateKeyDirectory.getPrivateKey();
		if (null != privateKey) {
			_accessManager.keyCache().addPrivateKey(privateKeyDirectory.getName(), PublisherID.generatePublicKeyDigest(theGroupPublicKey), 
					privateKey);
		}
		return privateKey;
	}

	/**
	 * We might or might not still be a member of this group, or be a member
	 * again. This merely removes our cached notion that we are a member.
	 * @param principal
	 */
	public void removeGroupMembership(String principal) {
		_myGroupMemberships.remove(principal);
	}
	
	/**
	 * Eventually let namespace control this.
	 * @return
	 */
	public String getGroupKeyAlgorithm() {
		return AccessControlManager.DEFAULT_GROUP_KEY_ALGORITHM;
	}

	// TODO should throw access denied?
	protected Key getVersionedPrivateKeyForGroup(KeyDirectory keyDirectory, String principal) 
			throws IOException, InvalidKeyException, AccessDeniedException, InvalidCipherTextException, 
					XMLStreamException, ConfigurationException {
		Key privateKey = getGroupPrivateKey(principal, keyDirectory.getPrincipals().get(principal));
		if (null == privateKey) {
			Library.logger().info("Unexpected: we beleive we are a member of group " + principal + " but cannot retrieve private key version: " + keyDirectory.getPrincipals().get(principal) + " our membership revoked?");
			// Check to see if we are a current member.
			if (!amCurrentGroupMember(principal)) {
				// Removes this group from my list of known groups, adds it to my
				// list of groups I don't believe I'm a member of.
				removeGroupMembership(principal);
			}
		}
		return privateKey;
	}

}