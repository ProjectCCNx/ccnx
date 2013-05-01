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
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.SortedSet;
import java.util.logging.Level;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.impl.CCNFlowControl.SaveType;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.ErrorStateException;
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
import org.ccnx.ccn.profiles.nameenum.EnumeratedNameList;
import org.ccnx.ccn.profiles.namespace.ParameterizedName;
import org.ccnx.ccn.profiles.security.access.AccessDeniedException;
import org.ccnx.ccn.protocol.CCNTime;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.PublisherID;


/**
 * This class represents a Group for group-based access control. A Group
 * is essentially a list of members, and a public/private key pair. The
 * public key is stored in CCN and is used to encrypt node keys (see CCNx
 * Access Control Specification); the private key is stored encrypted under
 * the public keys of the members of the group (which could be users or
 * groups). The private key is represented in a KeyDirectory.
 * 
 * Model for private key access: if you're not allowed to get a key,
 * we throw AccessDeniedException.
 * 
 * Right now dynamically load both public key and membership list.
 * For efficiency might want to only load public key, and pull membership
 * list only when we need to.
 *
 */
public class Group {
	
	private static final long PARENT_GROUP_ENUMERATION_TIMEOUT = 3000;
	
	private ParameterizedName _groupNamespace;
	private PublicKeyObject _groupPublicKey;
	private MembershipListObject _groupMembers; 
	private String _groupFriendlyName;
	private CCNHandle _handle;
	private GroupManager _groupManager;

	/** 
	 * The <KeyDirectory> which stores the group private key wrapped
	 * in the public keys of the members of the group. 
	 */
	private PrincipalKeyDirectory _privKeyDirectory = null;
	
	/**
	 * Group constructor
	 * @param namespace the group namespace
	 * @param groupFriendlyName the friendly name by which the group is known
	 * @param handle the CCN handle
	 * @param manager the group manager
	 * @throws IOException 
	 * @throws ContentDecodingException 
	 */
	public Group(ParameterizedName groupNamespace, String groupFriendlyName, CCNHandle handle,GroupManager manager) 
			throws ContentDecodingException, IOException {
		_handle = handle;
		_groupNamespace = groupNamespace;
		_groupFriendlyName = groupFriendlyName;
		_groupPublicKey = new PublicKeyObject(GroupAccessControlProfile.groupPublicKeyName(_groupNamespace, _groupFriendlyName), _handle);
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
		this(manager.getGroupStorage(), GroupAccessControlProfile.groupNameToFriendlyName(groupName), handle,manager);
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
	 */
	Group(ParameterizedName groupNamespace, String groupFriendlyName, MembershipListObject members, 
					CCNHandle handle, GroupManager manager) 
			throws ContentEncodingException, IOException, InvalidKeyException {	
		_handle = handle;
		_groupNamespace = groupNamespace;
		_groupFriendlyName = groupFriendlyName;
		_groupManager = manager;
		
		_groupMembers = members;
		_groupMembers.save();

		createGroupPublicKey(members);
	}
	
	/**
	 * Add new users to an existing group
	 * @param newUsers the list of new users
	 * @throws IOException 
	 * @throws ConfigurationException 
	 * @throws ContentDecodingException 
	 * @throws InvalidKeyException 
	 * @throws NoSuchAlgorithmException 
	 */
	public void addMembers(ArrayList<Link> newUsers) 
			throws InvalidKeyException, ContentDecodingException, IOException, NoSuchAlgorithmException {
		modify(newUsers, null);						
	}

	/**
	 * Remove users from an existing group
	 * @param removedUsers the list of users to be removed.
	 * @throws IOException 
	 * @throws ConfigurationException 
	 * @throws ContentDecodingException 
	 * @throws InvalidKeyException 
	 * @throws NoSuchAlgorithmException 
	 */
	public void removeMembers( ArrayList<Link> removedUsers) 
			throws InvalidKeyException, ContentDecodingException, 
					IOException, NoSuchAlgorithmException {
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
	public PrincipalKeyDirectory privateKeyDirectory(GroupAccessControlManager manager) throws IOException {
		if (_privKeyDirectory != null) {
			// check that our version of KeyDirectory is not stale
			if (_privKeyDirectory.getName().equals(GroupAccessControlProfile.groupPrivateKeyDirectory(_groupPublicKey.getVersionedName()))) {
				return _privKeyDirectory;				
			}
		}
		if (_groupPublicKey.available()) {
			_privKeyDirectory = new PrincipalKeyDirectory(manager, 
					GroupAccessControlProfile.groupPrivateKeyDirectory(_groupPublicKey.getVersionedName()), _handle);
			return _privKeyDirectory;
		}
		if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.INFO)) {
			Log.info(Log.FAC_ACCESSCONTROL, "Public key not ready for group: " + friendlyName());
		}
		return null;
	}

	/**
	 * Stop enumerating the private key directory.
	 * @throws IOException
	 */
	protected void stopPrivateKeyDirectoryEnumeration() throws IOException {
		if (_privKeyDirectory != null) {
			_privKeyDirectory.stopEnumerating();
		}
	}
	
	/**
	 * Restart the enumeration of the private key directory.
	 * @param manager the access control manager.
	 * @throws IOException
	 */
	public void restartPrivateKeyDirectoryEnumeration(GroupAccessControlManager manager) throws IOException {
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
	public ContentName groupName() {return new ContentName(_groupNamespace.prefix(), _groupFriendlyName); }

	/**
	 * Returns a list containing all the members of a Group.
	 * Sets up the list to automatically update in the background.
	 * @return MembershipList a list containing all the members of a Group object
	 * @throws ContentDecodingException
	 * @throws IOException
	 */
	public MembershipListObject membershipList() throws ContentDecodingException, IOException { 
		if (null == _groupMembers) {
			// Read constructor. Synchronously updates. 
			// Throws an exception if no membership list is found or upon error.
			// Reading membership list from network. Needs error handling.
			_groupMembers = new MembershipListObject(GroupAccessControlProfile.groupMembershipListName(_groupNamespace, _groupFriendlyName), _handle);
			// Keep dynamically updating.
			_groupMembers.updateInBackground(true);
			_groupMembers.setupSave(SaveType.REPOSITORY);
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
				if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.WARNING)) {
					Log.warning(Log.FAC_ACCESSCONTROL, "Should not happen: VersionMissingException on name where isVersioned is true: " + name + ": " + e.getMessage());
				}
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
	public PublicKeyObject publicKeyObject() { return _groupPublicKey; }
	
	/**
	 * Get the group public key
	 * @return the group public key
	 * @throws ContentNotReadyException
	 * @throws ContentGoneException
	 * @throws ErrorStateException 
	 */
	public PublicKey publicKey() throws ContentNotReadyException, ContentGoneException, ErrorStateException { 
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
	 * @throws InvalidKeyException 
	 * @throws NoSuchAlgorithmException 
	 */
	public void setMembershipList(GroupManager groupManager, java.util.Collection<Link> newMembers) 
			throws ContentDecodingException, IOException, InvalidKeyException, 
					NoSuchAlgorithmException {
		// need to figure out if we need to know private key; if we do and we don't, throw access denied.
		// We're deleting anyone that exists
		this._groupManager = groupManager;
		// TODO don't pull ML twice -- either get it and hand it to modify or let modify get it
		MembershipListObject ml = membershipList(); // force retrieval if haven't already.
		if (ml.available() && !ml.isGone() && (ml.membershipList().contents().size() > 0)) {
			modify(newMembers, ml.membershipList().contents());
		} else {
			modify(newMembers, null);
		}
	}
	
	/**
	 * Generate a new group public key, e.g. after membership update.
	 * Note that this method does NOT update the public keys of parent and ancestor groups.
	 * To ensure correct recursive updates of the public keys of all ancestor groups, 
	 * use instead the public method newGroupPublicKey.
	 * The caller of this method must have access rights to the existing (soon to be previous)
	 * private key of the group.
	 * The new key is created with a call to createGroupPublicKey. This method also wraps
	 * the new private key under the public keys of all the members of the group.
	 * Finally, a superseded block and a link to the previous key are written to the repository.
	 * @param ml the new membership list
	 * @throws IOException 
	 * @throws ContentEncodingException 
	 * @throws ConfigurationException 
	 * @throws InvalidKeyException 
	 * @throws NoSuchAlgorithmException 
	 */
	private void newGroupPublicKeyNonRecursive(MembershipListObject ml) 
			throws ContentEncodingException, IOException, InvalidKeyException, NoSuchAlgorithmException{
		PrincipalKeyDirectory oldPrivateKeyDirectory = privateKeyDirectory(_groupManager.getAccessManager());
		oldPrivateKeyDirectory.waitForNoUpdates(SystemConfiguration.MEDIUM_TIMEOUT);
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
		Key privateKeyWrappingKey = createGroupPublicKey(ml);
		
		// Write superseded block in old key directory
		oldPrivateKeyDirectory.addSupersededByBlock(oldPrivateKeyWrappingKey, publicKeyName(), null, privateKeyWrappingKey);
		// Write link back to previous key
		Link lr = new Link(_groupPublicKey.getVersionedName(), new LinkAuthenticator(new PublisherID(_handle.keyManager().getDefaultKeyID())));
		LinkObject precededByBlock = new LinkObject(PrincipalKeyDirectory.getPreviousKeyBlockName(publicKeyName()), lr, SaveType.REPOSITORY, _handle);
		precededByBlock.save();
	}
	
	/**
	 * Generate a new group public key, e.g. after membership update.
	 * The caller of this method must have access rights to the existing (soon to be previous)
	 * private key of the group.
	 * The new key is created with a call to createGroupPublicKey. This method also wraps
	 * the new private key under the public keys of all the members of the group.
	 * Finally, a superseded block and a link to the previous key are written to the repository.
	 * @param ml the new membership list
	 * @throws IOException 
	 * @throws ContentEncodingException 
	 * @throws ConfigurationException 
	 * @throws InvalidKeyException 
	 * @throws NoSuchAlgorithmException 
	 */
	public void newGroupPublicKey(MembershipListObject ml) 
			throws ContentEncodingException, IOException, InvalidKeyException, NoSuchAlgorithmException {

		PrincipalKeyDirectory oldPrivateKeyDirectory = privateKeyDirectory(_groupManager.getAccessManager());
		oldPrivateKeyDirectory.waitForChildren();
		Key oldPrivateKeyWrappingKey = oldPrivateKeyDirectory.getUnwrappedKey(null);
		if (null == oldPrivateKeyWrappingKey) {
			throw new AccessDeniedException("Cannot update group membership, do not have access rights to private key for group " + friendlyName());
		} else {
			stopPrivateKeyDirectoryEnumeration();
		}
		
		// Generate key pair
		// Write public key to new versioned name
		// Open key directory under that name
		// Wrap private key in wrapping key, write that block
		// For each principal on membership list, write wrapped key block
		Key privateKeyWrappingKey = createGroupPublicKey(ml);
		
		// Write superseded block in old key directory
		oldPrivateKeyDirectory.addSupersededByBlock(oldPrivateKeyWrappingKey, publicKeyName(), null, privateKeyWrappingKey);
		// Write link back to previous key
		Link lr = new Link(_groupPublicKey.getVersionedName(), new LinkAuthenticator(new PublisherID(_handle.keyManager().getDefaultKeyID())));
		LinkObject precededByBlock = new LinkObject(PrincipalKeyDirectory.getPreviousKeyBlockName(publicKeyName()), lr, SaveType.REPOSITORY, _handle);
		precededByBlock.save();
		
		// generate new public keys for ancestor groups
		ArrayList<Link> ancestors = recursiveAncestorList(null);
		Iterator<Link> iter = ancestors.iterator();
		while (iter.hasNext()) {
			Group parentGroup = new Group(iter.next().targetName(), _handle, _groupManager);
			parentGroup.newGroupPublicKeyNonRecursive(parentGroup.membershipList());
		}
	}
	
	/**
	 * Creates a public key for the group, 
	 * We don't expect there to be an existing key. So we just write a new one.
	 * If we're not supposed to be a member, this is tricky... we just live
	 * with the fact that we know the private key, and forget it.
	 * @param ml the membership list.
	 * @return the group private key wrapping key. 
	 * @throws IOException 
	 * @throws ContentEncodingException 
	 * @throws ConfigurationException 
	 * @throws InvalidKeyException
	 */	
	public Key createGroupPublicKey(MembershipListObject ml) 
			throws ContentEncodingException, IOException, InvalidKeyException {
		
		KeyPairGenerator kpg = null;
		try {
			kpg = KeyPairGenerator.getInstance(_groupManager.getGroupKeyAlgorithm());
		} catch (NoSuchAlgorithmException e) {
			if (_groupManager.getGroupKeyAlgorithm().equals(GroupAccessControlManager.DEFAULT_GROUP_KEY_ALGORITHM)) {
				if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.SEVERE)) {
					Log.severe(Log.FAC_ACCESSCONTROL, "Cannot find default group public key algorithm: " + GroupAccessControlManager.DEFAULT_GROUP_KEY_ALGORITHM + ": " + e.getMessage());
				}
				throw new RuntimeException("Cannot find default group public key algorithm: " + GroupAccessControlManager.DEFAULT_GROUP_KEY_ALGORITHM + ": " + e.getMessage());
			}
			throw new InvalidKeyException("Specified group public key algorithm " + _groupManager.getGroupKeyAlgorithm() + " not found. " + e.getMessage());
		}
		kpg.initialize(GroupAccessControlManager.DEFAULT_GROUP_KEY_LENGTH);
		KeyPair pair = kpg.generateKeyPair();
		
		_groupPublicKey = 
			new PublicKeyObject(
					GroupAccessControlProfile.groupPublicKeyName(_groupNamespace, _groupFriendlyName), 
					pair.getPublic(), SaveType.REPOSITORY,
					_handle);
		_groupPublicKey.save();
		_groupPublicKey.updateInBackground(true);
		
		stopPrivateKeyDirectoryEnumeration();
		_privKeyDirectory = null;
		
		PrincipalKeyDirectory newPrivateKeyDirectory = privateKeyDirectory(_groupManager.getAccessManager()); // takes from new public key
		
		Key privateKeyWrappingKey = WrappedKey.generateNonceKey();
		
		try {
			// write the private key
			newPrivateKeyDirectory.addPrivateKeyBlock(pair.getPrivate(), privateKeyWrappingKey);
		} catch (InvalidKeyException e) {
			if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.WARNING)) {
				Log.warning(Log.FAC_ACCESSCONTROL, "Unexpected -- InvalidKeyException wrapping key with keys we just generated! " + e.getMessage());
			}
			throw e;
		}
		
		// Wrap the private key in the public keys of all the members of the group 
		updateGroupPublicKey(privateKeyWrappingKey, ml.membershipList().contents());
		
		return privateKeyWrappingKey;
	}
	
	@SuppressWarnings("serial")
	public static class CouldNotRetrievePublicKeyException extends IOException {} 
	
	/**
	 * Adds members to an existing group.
	 * The caller of this method must have access to the private key of the group.
	 * We need to wrap the group public key wrapping key in the latest public
	 * keys of the members to add.
	 * Since members are only added, there is no need to replace the group key.
	 * @param privateKeyWrappingKey the private key wrapping key
	 * @param membersToAdd the members added to the group
	 * @throws InvalidKeyException 
	 * @throws AccessDeniedException
	 * @throws IOException 
	 * @throws ContentDecodingException 
	 */
	public void updateGroupPublicKey(Key privateKeyWrappingKey, java.util.Collection<Link> membersToAdd) 
			throws InvalidKeyException, ContentDecodingException, AccessDeniedException, IOException {		
		if ((null == membersToAdd) || (membersToAdd.size() == 0))
			return;

		if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.FINEST))
			Log.finest(Log.FAC_ACCESSCONTROL, " {0} Group.updateGroupPublicKey()", _groupNamespace.prefix());

		PrincipalKeyDirectory privateKeyDirectory = privateKeyDirectory(_groupManager.getAccessManager());

		PublicKeyObject latestPublicKey = null;
		for (Link lr : membersToAdd) {
			// DKS TODO verify target public key against publisher, etc in link
			
			ContentName mlName = lr.targetName();
			ContentName pkName = null;
			if (_groupManager.getAccessManager().isGroupName(mlName)){
				// MLAC mods to make sure we fully parameterize key names
				pkName = _groupManager.getAccessManager().groupPublicKeyName(mlName);
				// write a back pointer from child group to parent group
				// PG TODO check for existence of back pointer to avoid writing multiple copies of the same pointer
				Link backPointer = new Link(groupName(), friendlyName(), null);
				ContentName bpNamespace = GroupAccessControlProfile.groupPointerToParentGroupName(lr.targetName());
				LinkObject bplo = new LinkObject(new ContentName(bpNamespace, friendlyName()), backPointer, SaveType.REPOSITORY, _handle);
				bplo.save();
			} else {
				// MLAC mods to make sure we fully parameterize key names
				pkName = _groupManager.getAccessManager().userPublicKeyName(mlName);
			}

			latestPublicKey = new PublicKeyObject(pkName, _handle);
			if (!latestPublicKey.available()) {
				if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.WARNING)) {
					Log.warning(Log.FAC_ACCESSCONTROL, "Could not retrieve public key for " + pkName);
				}
				throw new CouldNotRetrievePublicKeyException();
			}
			// Need to write wrapped key block and linking principal name.
			try {
				privateKeyDirectory.addWrappedKeyBlock(
						privateKeyWrappingKey, 
						latestPublicKey.getVersionedName(), 
						latestPublicKey.publicKey());
			} catch (VersionMissingException e) {
				// TODO make VersionMissingException a subclass of IOException, see case #100070
				Log.warningStackTrace(e);
				throw new IOException(e.toString());
			}
		}
	}
	
	/**
	 * You won't actually get the PrivateKey unles you have the rights to decrypt it;
	 * otherwise you'll get an AccessDeniedException.
	 * @throws IOException 
	 * @throws NoSuchAlgorithmException 
	 * @throws InvalidKeyException 
	 */
	public PrivateKey getPrivateKey() throws IOException, InvalidKeyException, NoSuchAlgorithmException {
		// TODO might do a little unnecessary enumeration, but will pull from cache if in cache. 
		PrincipalKeyDirectory privateKeyDirectory = privateKeyDirectory(_groupManager.getAccessManager());
		PrivateKey privateKey = (PrivateKey)privateKeyDirectory.getPrivateKey();
		if (null != privateKey) {
			// Will redundantly re-add to cache. TODO move caching into getPrivateKey; it needs
			// the public key to do that.
			_handle.keyManager().getSecureKeyCache().addPrivateKey(privateKeyDirectory.getPrivateKeyBlockName(), 
					publicKeyObject().publicKeyDigest().digest(), privateKey);
		}
		return privateKey;
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
					GroupAccessControlProfile.groupPublicKeyName(_groupNamespace, friendlyName()));
		} else {
			sb.append(publicKeyName());
		}
		sb.append(" membership list: ");
		if ((null == _groupMembers) || (!_groupMembers.available())) {
			sb.append("not ready, will write to " + 
					GroupAccessControlProfile.groupMembershipListName(_groupNamespace, friendlyName()));
		} else {
			try {
				sb.append(membershipListName());
			} catch (Exception e) {
				if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.WARNING)) {
					Log.warning(Log.FAC_ACCESSCONTROL, "Unexpected " + e.getClass().getName() + " exception in getMembershipListName(): " + e.getMessage());
				}
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
	 * @throws InvalidKeyException 
	 * @throws ConfigurationException 
	 * @throws NoSuchAlgorithmException 
	 */
	public void modify(java.util.Collection<Link> membersToAdd,
					   java.util.Collection<Link> membersToRemove) 
			throws InvalidKeyException, ContentDecodingException, IOException, NoSuchAlgorithmException {

		if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.FINEST))
				Log.finest(Log.FAC_ACCESSCONTROL, "{0} Group.modify({1},{2})", _groupNamespace.prefix(),
						(membersToAdd == null) ? "null" : membersToAdd.size(),
						(membersToRemove == null) ? "null" : membersToRemove.size());

		boolean addedMembers = false;
		boolean removedMembers = false;
		
		if (((null == membersToAdd) || (membersToAdd.size() == 0)) && ((null == membersToRemove) || (membersToRemove.size() == 0))) {
			return; // nothing to do
		}
		
		// You don't want to modify membership list if you dont have permission. 
		// Assume no concurrent writer.  
		
		PrincipalKeyDirectory privateKeyDirectory = privateKeyDirectory(_groupManager.getAccessManager());
		Key privateKeyWrappingKey = privateKeyDirectory.getUnwrappedKey(null);
		if (null == privateKeyWrappingKey) {
			throw new AccessDeniedException("Cannot update group membership, do not have acces rights to private key for group " + friendlyName());
		}else{
			stopPrivateKeyDirectoryEnumeration();
		}

		// Do we need to wait for data to come in? We use this to create new groups as well...
		// so in that case, don't expect any.
		
		// Get the existing membership list, if we don't have it already
		if (null == _groupMembers) membershipList();
		
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
			newGroupPublicKey(_groupMembers); 
		} else if (addedMembers) {
			// additions only. Don't have to make  a new key if one exists,
			// just rewrap it for added members.
			if (null != _groupPublicKey.publicKey()) {
				updateGroupPublicKey(privateKeyWrappingKey, membersToAdd);
			} else {
				createGroupPublicKey(_groupMembers);
			}
		}
		// Don't actually save the new membership list till we're sure we can update the
		// key.
		_groupMembers.save();
	}

	public void delete() throws IOException {
		// Deleting the group -- mark both membership list and public key as GONE.
		_groupMembers.saveAsGone();
		_groupPublicKey.saveAsGone();
	}
	
	/**
	 * Recursively constructs an ordered list of the ancestors of the group.
	 * The ancestors are the groups of which the group is a member either directly
	 * or indirectly via a chain of one or more ancestors.
	 * The order ensures that a group is always listed after all its children.
	 * @param ancestorList the ancestor list built up to this point
	 * @return the recursively updated ancestor list
	 * @throws IOException
	 */
	public ArrayList<Link> recursiveAncestorList(ArrayList<Link> ancestorList) throws IOException {
		if (ancestorList == null) ancestorList = new ArrayList<Link>();
		
		ContentName cn = GroupAccessControlProfile.groupPointerToParentGroupName(groupName());
		EnumeratedNameList parentList = new EnumeratedNameList(cn, _handle);
		parentList.waitForChildren(PARENT_GROUP_ENUMERATION_TIMEOUT);
		if (parentList.hasChildren()) {
			SortedSet<ContentName> parents = parentList.getChildren();
			for (ContentName parentLinkName : parents) {
				ContentName pln = new ContentName(cn, parentLinkName.component(0));
				LinkObject parentLinkObject = new LinkObject(pln, _handle);
				Link parentLink = parentLinkObject.link();
				// delete the link if already present in the list and re-insert it at the end of the list
				if (ancestorList.contains(parentLink)) ancestorList.remove(parentLink);
				ancestorList.add(parentLink);
				Group parentGroup = new Group(parentLink.targetName(), _handle, _groupManager);
				parentGroup.recursiveAncestorList(ancestorList);
			}
		}
		parentList.stopEnumerating();
		
		return ancestorList;
	}
	
}
