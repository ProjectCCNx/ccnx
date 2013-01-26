/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008-2012 Palo Alto Research Center, Inc.
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

package org.ccnx.ccn.profiles.security.access.group;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.logging.Level;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.impl.CCNFlowControl.SaveType;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.CCNReader;
import org.ccnx.ccn.io.content.Collection;
import org.ccnx.ccn.io.content.ContentDecodingException;
import org.ccnx.ccn.io.content.ContentEncodingException;
import org.ccnx.ccn.io.content.ContentNotReadyException;
import org.ccnx.ccn.io.content.Link;
import org.ccnx.ccn.io.content.PublicKeyObject;
import org.ccnx.ccn.profiles.nameenum.EnumeratedNameList;
import org.ccnx.ccn.profiles.namespace.ParameterizedName;
import org.ccnx.ccn.profiles.security.access.group.GroupAccessControlProfile.PrincipalInfo;
import org.ccnx.ccn.protocol.CCNTime;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.PublisherID;

/**
 * A meta-class for group management, handling in particular:
 * - Creation of new groups 
 * - Retrieval of existing groups
 * - Determination of group membership
 * - Retrieval of group private and public keys
 *
 * There is currently one GroupManager per Group namespace. If you haven't loaded the GroupManager
 * for a given namespace, and attempt to write ACLs naming groups defined in that namespace, you will
 * get errors as the access control code won't recognise those entitites as Groups or be able to 
 * find their public keys.
 */
public class GroupManager {
	
	private GroupAccessControlManager _accessManager;
	private ParameterizedName _groupStorage;
	private EnumeratedNameList _groupList;
	private HashMap<String, Group> _groupCache = new HashMap<String, Group>();
	private HashSet<String> _myGroupMemberships = new HashSet<String>();
	private CCNHandle _handle;

	public GroupManager(GroupAccessControlManager accessManager,
						ParameterizedName groupStorage, CCNHandle handle) {
		_handle = handle;
		_accessManager = accessManager;
		_groupStorage = groupStorage;
	}
	
	/**
	 * A "quiet" constructor that doesn't enumerate anything, and in fact does 
	 * little to be used for non-group based uses of KeyDirectory, really
	 * a temporary hack till we refactor KD.
	 * @return
	 */
	GroupManager(GroupAccessControlManager accessManager, CCNHandle handle) throws IOException {
		_handle = handle;
		_accessManager = accessManager;
		_groupStorage = null; // try this, see if it explodes
	}
	
	public GroupAccessControlManager getAccessManager() { return _accessManager; }

	/**
	 * Enumerate groups
	 * @return the enumeration of groups
	 * @throws IOException
	 */
	public EnumeratedNameList groupList() throws IOException {
		if (null == _groupList) {
			_groupList = new EnumeratedNameList(_groupStorage.prefix(), _handle);
		}
		return _groupList;
	}

	/**
	 * Get a group specified by its friendly name
	 * @param groupFriendlyName the friendly name of the group
	 * @return the corresponding group
	 * @throws IOException 
	 * @throws ContentDecodingException 
	 */
	public Group getGroup(String groupFriendlyName, long timeout) throws ContentDecodingException, IOException {
		if ((null == groupFriendlyName) || (groupFriendlyName.length() == 0)) {
			if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.INFO)) {
				Log.info(Log.FAC_ACCESSCONTROL, "Asked to retrieve group with empty name.");
			}
			return null;
		}
		Group theGroup = _groupCache.get(groupFriendlyName);
		
		// Need to wait for data and add time out. 
		// The first time you run this, nothing will be read. 
		/*if( null == theGroup) {
			groupList().waitForData();
			SortedSet<ContentName> children = groupList().getChildren();
			System.out.println("found the following groups:.................");
			for (ContentName child : children) {
				System.out.println(child);
			}
		}*/
		
		if (null == theGroup) {
			if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.INFO)) {
				Log.info(Log.FAC_ACCESSCONTROL, "The group {0} was not found in the group cache.", groupFriendlyName);
			}

			synchronized(_groupCache) {
				theGroup = _groupCache.get(groupFriendlyName);
				if (null == theGroup) {
					// Only go hunting for it if we think it exists, otherwise we'll block.
					if (groupExists(groupFriendlyName, timeout)) {

						theGroup = new Group(_groupStorage, groupFriendlyName, _handle, this);
						// wait for group to be ready?
						_groupCache.put(groupFriendlyName, theGroup);
					}
				}
			}
		}
		// either we've got it, or we don't believe it exists.
		// startup transients? do we need to block for group list?
		return theGroup;
	}
	
	/**
	 * Get the group specified by a link
	 * @param theGroup link to the group
	 * @return the corresponding group
	 * @throws IOException 
	 * @throws ContentDecodingException 
	 */
	public Group getGroup(Link theGroup, long timeout) throws ContentDecodingException, IOException {
		if (null == theGroup) {
			if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.INFO)) {
				Log.info(Log.FAC_ACCESSCONTROL, "Asked to retrieve group with empty link.");
			}
			return null;
		}
		if (!isGroup(theGroup))
			return null;
		String friendlyName = GroupAccessControlProfile.groupNameToFriendlyName(theGroup.targetName());
		return getGroup(friendlyName, timeout);
	}
	
	/**
	 * Replace enumeration-based test of existence with direct test.
	 * @throws IOException 
	 */
	public boolean groupExists(String groupFriendlyName, long timeout) throws IOException {
		ContentName publicKeyName = 
			GroupAccessControlProfile.groupPublicKeyName(_groupStorage, groupFriendlyName);
		
		// Take any content below the public key name -- key fragments, keys, whatever's fastest.
		// This will take a long time if the group doesn't exist, but should be fast if it does,
		// with no pre-enumeration required.
		return (null != CCNReader.isAnyContentAvailable(publicKeyName, null, timeout, _handle));
	}
	
	/**
	 * Adds the specified group to the cache
	 * @param newGroup the group
	 */
	public void cacheGroup(Group newGroup) {
		synchronized(_groupCache) {
			_groupCache.put(newGroup.friendlyName(), newGroup);
		}
	}
	
	/**
	 * Create a new group with a specified friendly name and list of members
	 * The creator of the group ends up knowing the private key of the newly created group
	 * but is simply assumed to forget it if not a member.
	 * @param groupFriendlyName the friendly name of the group
	 * @param newMembers the members of the group
	 * @return the group
	 * @throws IOException 
	 * @throws ConfigurationException 
	 * @throws ContentEncodingException 
	 * @throws InvalidKeyException 
	 * @throws NoSuchAlgorithmException 
	 */
	public Group createGroup(String groupFriendlyName, ArrayList<Link> newMembers, long timeToWaitForPreexisting) 
			throws InvalidKeyException, ContentEncodingException, IOException, NoSuchAlgorithmException {
		
		Group existingGroup = null;
		
		if (timeToWaitForPreexisting > 0) {
			existingGroup = getGroup(groupFriendlyName, timeToWaitForPreexisting);
		}
		if (null != existingGroup) {
			existingGroup.setMembershipList(this, newMembers);
			return existingGroup;
		} else {
			// Need to make key pair, directory, and store membership list.
			MembershipListObject ml = 
				new MembershipListObject(
						GroupAccessControlProfile.groupMembershipListName(_groupStorage, groupFriendlyName), 
						new Collection(newMembers), SaveType.REPOSITORY, _handle);
			Group newGroup =  new Group(_groupStorage, groupFriendlyName, ml, _handle, this);
			cacheGroup(newGroup);
			if (amCurrentGroupMember(newGroup)) {
				_myGroupMemberships.add(groupFriendlyName);
			}
			return newGroup;
		}
	}
	
	/**
	 * Delete an existing group specified by its friendly name.
	 * @param friendlyName the friendly name of the group
	 * @throws IOException 
	 * @throws ContentDecodingException 
	 */
	public void deleteGroup(String friendlyName) throws ContentDecodingException, IOException {
		Group existingGroup = getGroup(friendlyName, SystemConfiguration.EXTRA_LONG_TIMEOUT);		
		// We really want to be sure we get the group if it's out there...
		if (null != existingGroup) {
			if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.INFO)) {
				Log.info(Log.FAC_ACCESSCONTROL, "Got existing group to delete: {0}", existingGroup);
			}
			existingGroup.delete();
		} else {
			if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.WARNING)) {
				Log.warning(Log.FAC_ACCESSCONTROL, "No existing group: {0}, ignoring delete request.", friendlyName);
			}
		}
	}
	
	/**
	 * Does this member refer to a user or a group. Groups have to be in the
	 * group namespace, users can be anywhere.
	 * @param member
	 * @return
	 */
	public boolean isGroup(Link member) {
		return _groupStorage.prefix().isPrefixOf(member.targetName());
	}
	
	public boolean isGroup(String principal, long timeout) throws IOException {
		return (null != getGroup(principal, timeout));
	}
	
	public boolean isGroup(ContentName publicKeyName) {
		return _groupStorage.prefix().isPrefixOf(publicKeyName);
	}

	public boolean haveKnownGroupMemberships() {
		return _myGroupMemberships.size() > 0;
	}

	public boolean amKnownGroupMember(String principal) {
		return _myGroupMemberships.contains(principal);
	}

	public boolean amCurrentGroupMember(String principal) throws ContentDecodingException, IOException {
		return amCurrentGroupMember(getGroup(principal, SystemConfiguration.EXTRA_LONG_TIMEOUT));
	}
	
	/**
	 * Determine if I am a current group member of a specified group.
	 * The current implementation of this method is slow and simple. 
	 * It can be optimized later.
	 * @param group the group
	 * @return
	 * @throws IOException 
	 * @throws ContentDecodingException 
	 */
	public boolean amCurrentGroupMember(Group group) throws ContentDecodingException, IOException {
		MembershipListObject ml = group.membershipList(); // will update
		if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.FINER)) {
			Log.finer(Log.FAC_ACCESSCONTROL, "amCurrentGroupMember: group {0} has {1} member(s).", group.groupName(), ml.membershipList().size());
		}
		for (Link lr : ml.membershipList().contents()) {
			if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.FINER)) {
				Log.finer(Log.FAC_ACCESSCONTROL, "amCurrentGroupMember: {0} is a member of group {1}", lr.targetName(), group.groupName());
			}
			GroupManager gm = _accessManager.groupManager(lr.targetName().parent());
			if (gm != null && gm.isGroup(lr)) {
				if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.FINER)) {
					Log.finer(Log.FAC_ACCESSCONTROL, "amCurrentGroupMember: {0} is itself a group.", lr.targetName());
				}
				String groupFriendlyName = GroupAccessControlProfile.groupNameToFriendlyName(lr.targetName());
				if (gm.amCurrentGroupMember(groupFriendlyName)) {
					_myGroupMemberships.add(groupFriendlyName);
					return true;
				} else {
					// Don't need to test first. Won't remove if isn't there.
					_myGroupMemberships.remove(groupFriendlyName);
				}
			} else {
				// Not a group. Is it me?
				if (_accessManager.haveIdentity(lr.targetName())) {
					if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.FINER)) {
						Log.finer(Log.FAC_ACCESSCONTROL, "amCurrentGroupMember: {0} is me!", lr.targetName());
					}
					_myGroupMemberships.add(group.friendlyName());
					return true;
				}
				else {
					if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.FINER)) {
						Log.finer(Log.FAC_ACCESSCONTROL, "amCurrentGroupMember: {0} is not me.", lr.targetName());
					}
				}
			}
		}
		return false;
	}

	/**
	 * Get the private key of a group specified by its friendly name.
	 * I already believe I should have access to this private key.
	 * @param groupFriendlyName the group friendly name
	 * @param privateKeyVersion the version of the private key
	 * @return the group private key
	 * @throws IOException 
	 * @throws ContentDecodingException 
	 * @throws InvalidKeyException 
	 * @throws NoSuchAlgorithmException 
	 */
	public Key getGroupPrivateKey(String groupFriendlyName, CCNTime privateKeyVersion) 
			throws ContentDecodingException, IOException, InvalidKeyException, NoSuchAlgorithmException {
		// Heuristic check
		if (!amKnownGroupMember(groupFriendlyName)) {
			if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.INFO)) {
				Log.info(Log.FAC_ACCESSCONTROL, "Unexpected: we don't think we're a group member of group " + groupFriendlyName);
			}
		}
		// Need to get the KeyDirectory for this version of the private key, or the 
		// latest if no version given.
		PrincipalKeyDirectory privateKeyDirectory;
		PublicKey theGroupPublicKey = null;
		if (null == privateKeyVersion) {
			Group theGroup = getGroup(groupFriendlyName, SystemConfiguration.EXTRA_LONG_TIMEOUT); // will pull latest public key
			privateKeyDirectory = theGroup.privateKeyDirectory(_accessManager);
			privateKeyDirectory.waitForNoUpdatesOrResult(SystemConfiguration.SHORT_TIMEOUT);
			theGroupPublicKey = theGroup.publicKey();
		} else {
			// Assume one is there...
			ContentName versionedPublicKeyName = 
				new ContentName(
						GroupAccessControlProfile.groupPublicKeyName(_groupStorage, groupFriendlyName),
						privateKeyVersion);
			privateKeyDirectory =
				new PrincipalKeyDirectory(_accessManager, 
					GroupAccessControlProfile.groupPrivateKeyDirectory(versionedPublicKeyName), _handle);
			privateKeyDirectory.waitForNoUpdatesOrResult(SystemConfiguration.SHORT_TIMEOUT);
			
			PublicKeyObject thisPublicKey = new PublicKeyObject(versionedPublicKeyName, _handle);
			thisPublicKey.waitForData();
			theGroupPublicKey = thisPublicKey.publicKey();
		}
		
		Key privateKey = privateKeyDirectory.getPrivateKey();
		if (null != privateKey && (privateKey instanceof PrivateKey)) {
			_handle.keyManager().getSecureKeyCache().addPrivateKey(privateKeyDirectory.getPrivateKeyBlockName(), 
					PublisherID.generatePublicKeyDigest(theGroupPublicKey), (PrivateKey)privateKey);
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
	 * Get the algorithm of the group key.
	 * Eventually let namespace control this.
	 * @return the algorithm of the group key
	 */
	public String getGroupKeyAlgorithm() {
		return GroupAccessControlManager.DEFAULT_GROUP_KEY_ALGORITHM;
	}
	
	/**
	 * Get the parameterized Name used by this group manager 
	 * @return the parameterized name for the group storage location
	 */
	public ParameterizedName getGroupStorage() {
		return _groupStorage;
	}

	/**
	 * Get the versioned private key for a group.
	 * @param keyDirectory the key directory associated with the group
	 * @param principal the principal
	 * @return the versioned private key
	 * @throws IOException 
	 * @throws ContentNotReadyException
	 * @throws ContentDecodingException
	 * @throws InvalidKeyException 
	 * @throws NoSuchAlgorithmException 
	 */
	protected Key getVersionedPrivateKeyForGroup(PrincipalInfo pi) 
			throws InvalidKeyException, ContentNotReadyException, ContentDecodingException, 
					IOException, NoSuchAlgorithmException {
		String principal = pi.friendlyName();
		Key privateKey = null;
		try {
			privateKey = getGroupPrivateKey(principal, pi.versionTimestamp());
		} catch (PrincipalKeyDirectory.NoPrivateKeyException e) {
			if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.INFO)) {
				Log.info(Log.FAC_ACCESSCONTROL, "Unexpected: we believe we are a member of group {0} but cannot retrieve private key version {1} our membership revoked?",
						principal, pi);			
			}
			// Check to see if we are a current member.
			if (!amCurrentGroupMember(principal)) {
				// Removes this group from my list of known groups, adds it to my
				// list of groups I don't believe I'm a member of.
				removeGroupMembership(principal);
			}
		}
		return privateKey;
	}

	/**
	 * Get the latest public key for a group specified by its principal name
	 * @param principal
	 * @return
	 * @throws IOException 
	 * @throws ContentDecodingException 
	 */
	public PublicKeyObject getLatestPublicKeyForGroup(Link principal) throws ContentDecodingException, IOException {
		Group theGroup = getGroup(principal, SystemConfiguration.EXTRA_LONG_TIMEOUT);
		if (null == theGroup) 
			return null;
		return theGroup.publicKeyObject();
	}

}
