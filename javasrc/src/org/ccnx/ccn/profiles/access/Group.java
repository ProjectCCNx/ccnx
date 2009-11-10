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

package org.ccnx.ccn.profiles.access;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.util.ArrayList;

import org.bouncycastle.crypto.InvalidCipherTextException;
import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.Collection;
import org.ccnx.ccn.io.content.ContentDecodingException;
import org.ccnx.ccn.io.content.ContentEncodingException;
import org.ccnx.ccn.io.content.ContentGoneException;
import org.ccnx.ccn.io.content.ContentNotReadyException;
import org.ccnx.ccn.io.content.Link;
import org.ccnx.ccn.io.content.LinkAuthenticator;
import org.ccnx.ccn.io.content.PublicKeyObject;
import org.ccnx.ccn.io.content.WrappedKey;
import org.ccnx.ccn.io.content.Link.LinkObject;
import org.ccnx.ccn.profiles.VersionMissingException;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.protocol.CCNTime;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.PublisherID;


/**
 * Wrapper for Group public key, and a way to access its private keys.
 * The private keys are stored in a KeyDirectory, wrapped under the
 * public keys of the members of the group. 
 * Model for private key access: if you're not allowed to get a key,
 * we throw AccessDeniedException.
 * 
 * Right now dynamically load both public key and membership list.
 * For efficiency might want to only load public key, and pull membership
 * list only when we need to.
 *
 */
public class Group {
	
	private ContentName _groupNamespace;
	private PublicKeyObject _groupPublicKey;
	private MembershipList _groupMembers; 
	private String _groupFriendlyName;
	private CCNHandle _handle;
	private GroupManager _groupManager;

	/** 
	 * The <KeyDirectory> which stores the group private key wrapped
	 * in the public keys of the members of the group. 
	 */
	private KeyDirectory _privKeyDirectory = null;
	
	/**
	 * Group constructor
	 * @param namespace the group namespace
	 * @param groupFriendlyName the friendly name by which the group is known
	 * @param handle the CCN handle
	 * @param manager the group manager
	 * @throws IOException 
	 * @throws ContentDecodingException 
	 */
	public Group(ContentName namespace, String groupFriendlyName, CCNHandle handle,GroupManager manager) 
			throws ContentDecodingException, IOException {
		_handle = handle;
		_groupNamespace = namespace;
		_groupFriendlyName = groupFriendlyName;
		_groupPublicKey = new PublicKeyObject(AccessControlProfile.groupPublicKeyName(_groupNamespace, _groupFriendlyName), _handle);
		_groupPublicKey.updateInBackground(true);
		_groupManager = manager;
	}
	
	/**
	 * Constructor
	 * @param groupName
	 * @param handle
	 * @param manager
	 * @throws IOException 
	 * @throws ContentDecodingException 
	 */
	public Group(ContentName groupName, CCNHandle handle, GroupManager manager) throws ContentDecodingException, IOException {
		this(groupName.parent(), AccessControlProfile.groupNameToFriendlyName(groupName), handle,manager);
	}
		
	/**
	 * Constructor that creates a new group and generates a first key pair for it.
	 * @param namespace the group namespace
	 * @param groupFriendlyName the friendly name by which the group is known
	 * @param members the membership list of the group
	 * @param handle the CCN handle
	 * @param manager the group manager
	 * @throws IOException 
	 * @throws ContentEncodingException 
	 * @throws ConfigurationException 
	 * @throws InvalidKeyException 
	 * @throws InvalidCipherTextException
	 */
	Group(ContentName namespace, String groupFriendlyName, MembershipList members, 
					CCNHandle handle, GroupManager manager) 
			throws ContentEncodingException, IOException, InvalidKeyException, ConfigurationException, InvalidCipherTextException {	
		_handle = handle;
		_groupNamespace = namespace;
		_groupFriendlyName = groupFriendlyName;
		_groupManager = manager;
		
		_groupMembers = members;
		_groupMembers.saveToRepository();

		createGroupPublicKey(manager, members);
	}
	
	/**
	 * Add new users to an existing group
	 * @param newUsers the list of new users
	 * @throws IOException 
	 * @throws ConfigurationException 
	 * @throws ContentDecodingException 
	 * @throws InvalidCipherTextException 
	 * @throws InvalidKeyException 
	 */
	public void addMembers(ArrayList<Link> newUsers) 
			throws InvalidKeyException, InvalidCipherTextException, 
					ContentDecodingException, ConfigurationException, IOException {
		modify(newUsers, null);						
	}

	/**
	 * Remove users from an existing group
	 * @param removedUsers the list of users to be removed.
	 * @throws IOException 
	 * @throws ConfigurationException 
	 * @throws ContentDecodingException 
	 * @throws InvalidCipherTextException 
	 * @throws InvalidKeyException 
	 */
	public void removeMembers( ArrayList<Link> removedUsers) 
			throws InvalidKeyException, InvalidCipherTextException, ContentDecodingException, 
					ConfigurationException, IOException {
		modify(null, removedUsers);
	}

	/**
	 * Checks whether the group public key has been created.
	 * @return
	 */
	public boolean ready() {
		return _groupPublicKey.available();
	}
	
	/**
	 * Returns the KeyDirectory which stores the group private key wrapped
	 * in the public keys of the members of the group.
	 * A new private key directory is created if it does not already exist
	 * and if the group public key is ready. 
	 * @param manager the access control manager
	 * @return the key directory of the group
	 * @throws IOException
	 */
	public KeyDirectory privateKeyDirectory(AccessControlManager manager) throws IOException {
		if (_privKeyDirectory != null) {
			return _privKeyDirectory;
		}
		if (_groupPublicKey.available()) {
			_privKeyDirectory = new KeyDirectory(manager, 
					AccessControlProfile.groupPrivateKeyDirectory(_groupPublicKey.getVersionedName()), _handle);
			return _privKeyDirectory;
		}
		Log.info("Public key not ready for group: " + friendlyName());
		return null;
	}
	
	/**
	 * Stop enumerating the private key directory.
	 * @throws IOException
	 */
	protected void stopPrivateKeyDirectoryEnumeration() throws IOException {
		if(_privKeyDirectory != null){
			_privKeyDirectory.stopEnumerating();
		}
	}
	
	/**
	 * Restart the enumeration of the private key directory.
	 * @param manager the access control manager.
	 * @throws IOException
	 */
	public void restartPrivateKeyDirectoryEnumeration(AccessControlManager manager) throws IOException {
		stopPrivateKeyDirectoryEnumeration();
		_privKeyDirectory = null;
		privateKeyDirectory(manager);
	}
	
	/**
	 * Get the friendly name by which the group is known
	 * @return the group friendly name.
	 */
	public String friendlyName() { return _groupFriendlyName; }

	/**
	 * Get the name of the namespace for the group
	 * @return the group namespace
	 */
	public ContentName groupName() {return ContentName.fromNative(_groupNamespace, _groupFriendlyName);}

	/**
	 * Returns a list containing all the members of a Group.
	 * Sets up the list to automatically update in the background.
	 * @return MembershipList a list containing all the members of a Group object
	 * @throws ContentDecodingException
	 * @throws IOException
	 */
	public MembershipList membershipList() throws ContentDecodingException, IOException { 
		if (null == _groupMembers) {
			// Read constructor. Synchronously updates. 
			// Throws an exception if no membership list is found or upon error.
			// Reading membership list from network. Needs error handling.
			_groupMembers = new MembershipList(AccessControlProfile.groupMembershipListName(_groupNamespace, _groupFriendlyName), _handle);
			// Keep dynamically updating.
			_groupMembers.updateInBackground(true);
		}
		return _groupMembers; 
	}
	
	/**
	 * Get the versioned name of the group membership list
	 * @return the versioned name of the group membership list
	 * @throws IOException 
	 * @throws ContentDecodingException 
	 */
	public ContentName membershipListName() throws ContentDecodingException, IOException { 
		return membershipList().getVersionedName(); 
	}
	
	/**
	 * Get the version of the membership list
	 * @return the version of the membership list
	 * @throws IOException 
	 * @throws ContentDecodingException 
	 */
	public CCNTime membershipListVersion() throws ContentDecodingException, IOException {
		ContentName name = membershipListName();
		if (VersioningProfile.hasTerminalVersion(name)) {
			try {
				return VersioningProfile.getLastVersionAsTimestamp(name);
			} catch (VersionMissingException e) {
				Log.warning("Should not happen: VersionMissingException on name where isVersioned is true: " + name + ": " + e.getMessage());
			}
		}
		return null;
	}
	
	/**
	 * Clear the cached membership list.
	 * This does not actually remove any members from the group, it just
	 * clears out our in-memory copy of the membership list.
	 */
	public void clearCachedMembershipList() {
		if (null != _groupMembers) {
			_groupMembers.cancelInterest(); // stop updating
			_groupMembers = null;
		}
	}

	/**
	 * Get the public key of the group
	 * @return the group public key
	 */
	PublicKeyObject publicKeyObject() { return _groupPublicKey; }
	
	/**
	 * Get the group public key
	 * @return the group public key
	 * @throws ContentNotReadyException
	 * @throws ContentGoneException
	 */
	public PublicKey publicKey() throws ContentNotReadyException, ContentGoneException { 
		return _groupPublicKey.publicKey(); 
	}
	
	/**
	 * Get the versioned name of the group public key
	 * @return the versioned name of the group public key
	 */
	public ContentName publicKeyName() { 
		return _groupPublicKey.getVersionedName();
	}
	
	/**
	 * Get the version of the group public key
	 * @return the version of the group public key
	 * @throws IOException
	 */
	public CCNTime publicKeyVersion() throws IOException {
		return _groupPublicKey.getVersion();
	}

	/**
	 * Sets the membership list of the group. Existing members of the group are removed.
	 * @param groupManager the group manager
	 * @param newMembers the list of new group members
	 * @throws IOException 
	 * @throws ContentDecodingException 
	 * @throws ConfigurationException 
	 * @throws InvalidCipherTextException 
	 * @throws InvalidKeyException 
	 * @throws InvalidCipherTextException
	 */
	public void setMembershipList(GroupManager groupManager, java.util.Collection<Link> newMembers) 
			throws ContentDecodingException, IOException, InvalidKeyException, InvalidCipherTextException, 
					ConfigurationException {
		// need to figure out if we need to know private key; if we do and we don't, throw access denied.
		// We're deleting anyone that exists
		this._groupManager = groupManager;
		MembershipList ml = membershipList(); // force retrieval if haven't already.
		if (ml.available() && !ml.isGone() && (ml.membershipList().contents().size() > 0)) {
			modify(newMembers, ml.membershipList().contents());
		} else {
			modify(newMembers, null);
		}
	}
	
	/**
	 * Generate a new group public key, e.g. after membership update.
	 * The caller of this method must have access rights to the existing (soon to be previous)
	 * private key of the group.
	 * The new key is created with a call to createGroupPublicKey. This method also wraps
	 * the new private key under the public keys of all the members of the group.
	 * Finally, a superseded block and a link to the previous key are written to the repository.
	 * @param manager the group manager
	 * @param ml the new membership list
	 * @throws IOException 
	 * @throws ContentEncodingException 
	 * @throws ConfigurationException 
	 * @throws InvalidKeyException 
	 * @throws InvalidCipherTextException
	 */
	public void newGroupPublicKey(GroupManager manager, MembershipList ml) 
			throws ContentEncodingException, IOException, InvalidKeyException, ConfigurationException, 
					InvalidCipherTextException {
		KeyDirectory oldPrivateKeyDirectory = privateKeyDirectory(manager.getAccessManager());
		oldPrivateKeyDirectory.waitForData();
		Key oldPrivateKeyWrappingKey = oldPrivateKeyDirectory.getUnwrappedKey(null);
		if (null == oldPrivateKeyWrappingKey) {
			throw new AccessDeniedException("Cannot update group membership, do not have access rights to private key for group " + friendlyName());
		}else{
			stopPrivateKeyDirectoryEnumeration();
		}
		
		// Generate key pair
		// Write public key to new versioned name
		// Open key directory under that name
		// Wrap private key in wrapping key, write that block
		// For each principal on membership list, write wrapped key block
		Key privateKeyWrappingKey = createGroupPublicKey(manager, ml);
		
		// Write superseded block in old key directory
		oldPrivateKeyDirectory.addSupersededByBlock(oldPrivateKeyWrappingKey, publicKeyName(), privateKeyWrappingKey);
		// Write link back to previous key
		Link lr = new Link(_groupPublicKey.getVersionedName(), new LinkAuthenticator(new PublisherID(_handle.keyManager().getDefaultKeyID())));
		LinkObject precededByBlock = new LinkObject(KeyDirectory.getPreviousKeyBlockName(publicKeyName()), lr, _handle);
		precededByBlock.saveToRepository();		
	}
	
	/**
	 * Creates a public key for the group, 
	 * We don't expect there to be an existing key. So we just write a new one.
	 * If we're not supposed to be a member, this is tricky... we just live
	 * with the fact that we know the private key, and forget it.
	 * @param manager the group manager.
	 * @param ml the membership list.
	 * @return the group private key wrapping key. 
	 * @throws IOException 
	 * @throws ContentEncodingException 
	 * @throws ConfigurationException 
	 * @throws InvalidKeyException
	 * @throws InvalidCipherTextException 
	 */	
	public Key createGroupPublicKey(GroupManager manager, MembershipList ml) 
			throws ContentEncodingException, IOException, ConfigurationException, InvalidKeyException, InvalidCipherTextException {
		
		KeyPairGenerator kpg = null;
		try {
			kpg = KeyPairGenerator.getInstance(manager.getGroupKeyAlgorithm());
		} catch (NoSuchAlgorithmException e) {
			if (manager.getGroupKeyAlgorithm().equals(AccessControlManager.DEFAULT_GROUP_KEY_ALGORITHM)) {
				Log.severe("Cannot find default group public key algorithm: " + AccessControlManager.DEFAULT_GROUP_KEY_ALGORITHM + ": " + e.getMessage());
				throw new RuntimeException("Cannot find default group public key algorithm: " + AccessControlManager.DEFAULT_GROUP_KEY_ALGORITHM + ": " + e.getMessage());
			}
			throw new ConfigurationException("Specified group public key algorithm " + manager.getGroupKeyAlgorithm() + " not found. " + e.getMessage());
		}
		kpg.initialize(AccessControlManager.DEFAULT_GROUP_KEY_LENGTH);
		KeyPair pair = kpg.generateKeyPair();
		
		_groupPublicKey = 
			new PublicKeyObject(
					AccessControlProfile.groupPublicKeyName(_groupNamespace, _groupFriendlyName), 
					pair.getPublic(),
					_handle);
		_groupPublicKey.saveToRepository();
		_groupPublicKey.updateInBackground(true);
		
		stopPrivateKeyDirectoryEnumeration();
		_privKeyDirectory = null;
		
		KeyDirectory newPrivateKeyDirectory = privateKeyDirectory(manager.getAccessManager()); // takes from new public key
		
		Key privateKeyWrappingKey = WrappedKey.generateNonceKey();
		
		try {
			// write the private key
			newPrivateKeyDirectory.addPrivateKeyBlock(pair.getPrivate(), privateKeyWrappingKey);
		} catch (InvalidKeyException e) {
			Log.warning("Unexpected -- InvalidKeyException wrapping key with keys we just generated! " + e.getMessage());
			throw e;
		}
		
		// Wrap the private key in the public keys of all the members of the group 
		updateGroupPublicKey(manager, privateKeyWrappingKey, ml.membershipList().contents());
		
		return privateKeyWrappingKey;
	}
	
	/**
	 * Adds members to an existing group.
	 * The caller of this method must have access to the private key of the group.
	 * We need to wrap the group public key wrapping key in the latest public
	 * keys of the members to add.
	 * Since members are only added, there is no need to replace the group key.
	 * @param manager the group manager
	 * @param privateKeyWrappingKey the private key wrapping key
	 * @param membersToAdd the members added to the group
	 * @throws InvalidKeyException 
	 * @throws AccessDeniedException
	 * @throws IOException 
	 * @throws ContentDecodingException 
	 * @throws InvalidCipherTextException 
	 */
	public void updateGroupPublicKey(GroupManager manager, Key privateKeyWrappingKey,
									 java.util.Collection<Link> membersToAdd) 
			throws InvalidKeyException, InvalidCipherTextException, ContentDecodingException, AccessDeniedException, IOException {		
		if ((null == membersToAdd) || (membersToAdd.size() == 0))
			return;
		
		KeyDirectory privateKeyDirectory = privateKeyDirectory(manager.getAccessManager());
		
		PublicKeyObject latestPublicKey = null;
		for (Link lr : membersToAdd) {
			try {
				// DKS TODO verify target public key against publisher, etc in link
				
				ContentName pkName = lr.targetName();
				if (manager.isGroup(lr)){
					pkName = AccessControlProfile.groupPublicKeyName(pkName);
					// write a back pointer from child group to parent group
					// PG TODO check for existence of back pointer to avoid writing multiple copies of the same pointer
					Link backPointer = new Link(groupName(), friendlyName(), null);
					ContentName bpNamespace = AccessControlProfile.groupPointerToParentGroupName(lr.targetName());
					LinkObject bplo = new LinkObject(ContentName.fromNative(bpNamespace, friendlyName()), backPointer, _handle);
					bplo.saveToRepository();
				}

				latestPublicKey = new PublicKeyObject(pkName, _handle);
				if (!latestPublicKey.available()) {
					Log.warning("Could not retrieve public key for " + pkName);
					continue;
				}
				// Need to write wrapped key block and linking principal name.
				privateKeyDirectory.addWrappedKeyBlock(
						privateKeyWrappingKey, 
						latestPublicKey.getVersionedName(), 
						latestPublicKey.publicKey());
			} catch (IOException e) {
				Log.warning("Could not retrieve public key for principal " + lr.targetName() + ", skipping.");
			} catch (VersionMissingException e) {
				Log.warning("Unexpected: public key name not versioned! " + latestPublicKey.getVersionedName() + ", unable to retrieve principal's public key. Skipping.");
			}
		}
	}
	
	/**
	 * Print useful name and version information.
	 */
	@Override
	public String toString() {
		StringBuffer sb = new StringBuffer("Group ");
		sb.append(friendlyName());
		sb.append(": public key: ");
		if (!_groupPublicKey.available()) {
			sb.append("not ready, write to " + 
					AccessControlProfile.groupPublicKeyName(_groupNamespace, friendlyName()));
		} else {
			sb.append(publicKeyName());
		}
		sb.append(" membership list: ");
		if ((null == _groupMembers) || (!_groupMembers.available())) {
			sb.append("not ready, will write to " + 
					AccessControlProfile.groupMembershipListName(_groupNamespace, friendlyName()));
		} else {
			try {
				sb.append(membershipListName());
			} catch (Exception e) {
				Log.warning("Unexpected " + e.getClass().getName() + " exception in getMembershipListName(): " + e.getMessage());
				sb.append("Membership list name unavailable!");
			} 
		}
		return sb.toString();
	}

	/**
	 * Modify will add and remove members from a Group.
	 * It can be used to only add members, in which case the membersToRemove list is null
	 * or it can be used to only remove members, in which case the membersToAdd list is null.
	 * If both lists are passed in, then the items in the membersToAdd list are added and the
	 * items in the membersToRemove are then removed from the Group members list.
	 *  
	 * @param membersToAdd list of group members to be added
	 * @param membersToRemove list of group members to be removed
	 * @throws IOException 
	 * @throws ContentDecodingException 
	 * @throws InvalidCipherTextException 
	 * @throws InvalidKeyException 
	 * @throws ConfigurationException 
	 */
	public void modify(java.util.Collection<Link> membersToAdd,
					   java.util.Collection<Link> membersToRemove) 
			throws InvalidKeyException, InvalidCipherTextException, ContentDecodingException, IOException, ConfigurationException {
		
		boolean addedMembers = false;
		boolean removedMembers = false;
		
		if (((null == membersToAdd) || (membersToAdd.size() == 0)) && ((null == membersToRemove) || (membersToRemove.size() == 0))) {
			return; // nothing to do
		}
		
		// You don't want to modify membership list if you dont have permission. 
		// Assume no concurrent writer.  
		
		KeyDirectory privateKeyDirectory = privateKeyDirectory(_groupManager.getAccessManager());
		privateKeyDirectory.waitForData();
		Key privateKeyWrappingKey = privateKeyDirectory.getUnwrappedKey(null);
		if (null == privateKeyWrappingKey) {
			throw new AccessDeniedException("Cannot update group membership, do not have acces rights to private key for group " + friendlyName());
		}else{
			stopPrivateKeyDirectoryEnumeration();
		}

		// Do we need to wait for data to come in? We use this to create new groups as well...
		// so in that case, don't expect any.
		
		// Add before remove so that remove overrides adds.
		if ((null != membersToAdd) && (!membersToAdd.isEmpty())) {
			if (null == _groupMembers.membershipList()) {
				_groupMembers.setData(new Collection(membersToAdd));
				addedMembers = true;
			} else {
				// Optimization: check to see if any were already in there before adding them....
				addedMembers = _groupMembers.membershipList().contents().addAll(membersToAdd);
			}
		}
		if ((null != membersToRemove) && (!membersToRemove.isEmpty()) &&
			_groupMembers.available() && // do we wait if it's not ready? we know one exists.
			(!_groupMembers.isGone()) && 
			(_groupMembers.membershipList().contents().size() > 0)) {
	
			// There were already members. Remove them and make a new key.
			removedMembers = _groupMembers.membershipList().contents().removeAll(membersToRemove);
		}
		
		if (removedMembers) {
			// Don't save membership list till we know we can update private key.
			// If we can't update the private key, this will throw AccessDeniedException.
			newGroupPublicKey(_groupManager, _groupMembers); 
		} else if (addedMembers) {
			// additions only. Don't have to make  a new key if one exists,
			// just rewrap it for added members.
			if (null != _groupPublicKey.publicKey()) {
				updateGroupPublicKey(_groupManager, privateKeyWrappingKey, membersToAdd);
			} else {
				createGroupPublicKey(_groupManager, _groupMembers);
			}
		}
		// Don't actually save the new membership list till we're sure we can update the
		// key.
		_groupMembers.saveToRepository();
	}

	public void delete() throws IOException {
		// Deleting the group -- mark both membership list and public key as GONE.
		_groupMembers.saveAsGone();
		_groupPublicKey.saveAsGone();
	}
}
