package com.parc.ccn.security.access;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.sql.Timestamp;
import java.util.Collection;

import javax.jcr.AccessDeniedException;
import javax.xml.stream.XMLStreamException;

import org.bouncycastle.crypto.InvalidCipherTextException;

import com.parc.ccn.Library;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.content.CollectionData;
import com.parc.ccn.data.content.LinkReference;
import com.parc.ccn.data.content.LinkReference.LinkObject;
import com.parc.ccn.data.security.LinkAuthenticator;
import com.parc.ccn.data.security.PublicKeyObject;
import com.parc.ccn.data.security.PublisherID;
import com.parc.ccn.data.security.WrappedKey;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.library.profiles.AccessControlProfile;
import com.parc.ccn.library.profiles.VersionMissingException;
import com.parc.ccn.library.profiles.VersioningProfile;
import com.parc.ccn.security.keys.KeyManager;

public class Group {
	
	// Right now dynamically load both public key and membership list.
	// For efficiency might want to only load public key, and pull membership
	// list only when we need to.
	private ContentName _groupNamespace;
	private PublicKeyObject _groupPublicKey;
	private MembershipList _groupMembers; 
	private String _groupFriendlyName;
	private CCNLibrary _library;
	
	public Group(ContentName namespace, String groupFriendlyName, CCNLibrary library) throws IOException {
		_library = library;
		_groupNamespace = namespace;
		_groupFriendlyName = groupFriendlyName;
		_groupPublicKey = new PublicKeyObject(_library);
		_groupPublicKey.updateInBackground(
				AccessControlProfile.groupPublicKeyName(_groupNamespace, _groupFriendlyName), true);
	}
	
	public Group(ContentName groupName, CCNLibrary library) throws IOException {
		this(groupName.parent(), AccessControlProfile.groupNameToFriendlyName(groupName), library);
	}
	
	/**
	 * Package constructor.
	 * @return
	 */
	Group(ContentName namespace, String groupFriendlyName, MembershipList members, 
		  PublicKeyObject publicKey, CCNLibrary library) {
		_library = library;
		_groupNamespace = namespace;
		_groupFriendlyName = groupFriendlyName;
		_groupMembers = members;
		_groupPublicKey = publicKey;
	}
	
	/**
	 * Constructor that creates a new group and generates a first key pair for it.
	 * @return
	 */
	Group(ContentName namespace, String groupFriendlyName, MembershipList members, CCNLibrary library) {
		this(namespace, groupFriendlyName, members, null, library);
		createGroupPublicKey(members);
	}
	
	public boolean ready() {
		return _groupPublicKey.ready();
	}
	
	public KeyDirectory privateKeyDirectory(AccessControlManager manager) throws IOException {
		if (_groupPublicKey.ready())
			return new KeyDirectory(manager, 
					AccessControlProfile.groupPrivateKeyDirectory(_groupPublicKey.getName()), _library);
		Library.logger().info("Public key not ready for group: " + friendlyName());
		return null;
	}
	
	public String friendlyName() { return _groupFriendlyName; }

	public MembershipList membershipList() throws XMLStreamException, IOException { 
		if (null == _groupMembers) {
			// Read constructor. Synchronously updates.
			_groupMembers = new MembershipList(AccessControlProfile.groupMembershipListName(_groupNamespace, _groupFriendlyName), _library);
			// Keep dynamically updating.
			_groupMembers.updateInBackground(true);
		}
		return _groupMembers; 
	}
	
	public ContentName membershipListName() throws XMLStreamException, IOException { 
		return membershipList().getName(); 
	}
	
	public Timestamp membershipListVersion() throws XMLStreamException, IOException {
		ContentName name = membershipListName();
		if (VersioningProfile.isVersioned(name)) {
			try {
				return VersioningProfile.getVersionAsTimestamp(name);
			} catch (VersionMissingException e) {
				Library.logger().warning("Should not happen: VersionMissingException on name where isVersioned is true: " + name + ": " + e.getMessage());
			}
		}
		return null;
	}
	
	/**
	 * This does not actually remove any members from the group, it just
	 * clears out our in-memory copy of the membership list.
	 */
	public void clearCachedMembershipList() {
		if (null != _groupMembers) {
			_groupMembers.cancelInterest(); // stop updating
			_groupMembers = null;
		}
	}

	public PublicKey publicKey() { return _groupPublicKey.publicKey(); }
	public ContentName publicKeyName() { return _groupPublicKey.getName(); }
	public Timestamp publicKeyVersion() {
		ContentName name = publicKeyName();
		if (VersioningProfile.isVersioned(name)) {
			try {
				return VersioningProfile.getVersionAsTimestamp(name);
			} catch (VersionMissingException e) {
				Library.logger().warning("Should not happen: VersionMissingException on name where isVersioned is true: " + name + ": " + e.getMessage());
			}
		}
		return null;
	}

	public void setMembershipList(Collection<LinkReference> newMembers) throws XMLStreamException, IOException {
		// need to figure out if we need to know private key; if we do and we don't, throw access denied.
		// We're deleting anyone that exists
		MembershipList ml = membershipList(); // force retrieval if haven't already.
		if (!ml.isGone() && ml.ready() && (ml.membershipList().contents().size() > 0)) {
			modify(newMembers, ml.membershipList().contents());
		} else {
			modify(newMembers, null);
		}
	}
	
	public void newGroupPublicKey(AccessControlManager manager, MembershipList ml) {
		KeyDirectory oldPrivateKeyDirectory = privateKeyDirectory(manager);
		Key oldPrivateKeyWrappingKey = oldPrivateKeyDirectory.getUnwrappedKey(null);
		if (null == oldPrivateKeyWrappingKey) {
			throw new AccessDeniedException("Cannot update group membership, do not have acces rights to private key for group " + friendlyName());
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
		LinkReference lr = new LinkReference(_groupPublicKey.getName(), new LinkAuthenticator(new PublisherID(KeyManager.getKeyManager().getDefaultKeyID())));
		LinkObject precededByBlock = new LinkObject(KeyDirectory.getPreviousKeyBlockName(publicKeyName()), lr, _library);
		precededByBlock.save();
	}
	
	/**
	 * We don't expect there to be an existing key. So we just write it.
	 * If we're not supposed to be a member, this is tricky... we just live
	 * with the fact that we know it, and forget it.
	 * @param ml
	 */
	public Key createGroupPublicKey(AccessControlManager manager, MembershipList ml) {
		
		KeyPairGenerator kpg = KeyPairGenerator.getInstance(AccessControlManager.DEFAULT_GROUP_KEY_ALGORITHM);
		kpg.initialize(AccessControlManager.DEFAULT_GROUP_KEY_LENGTH);
		KeyPair pair = kpg.generateKeyPair();
		
		_groupPublicKey.save(pair.getPublic());
		KeyDirectory newPrivateKeyDirectory = privateKeyDirectory(manager); // takes from new public key
		
		Key privateKeyWrappingKey = WrappedKey.generateNonceKey();
		
		// write the private key
		newPrivateKeyDirectory.addPrivateKeyBlock(pair.getPrivate(), privateKeyWrappingKey);
		
		for (LinkReference lr : ml.membershipList().contents()) {
			try {
				// DKS TODO verify target public key against publisher, etc in link
				PublicKeyObject latestPublicKey = new PublicKeyObject(lr.targetName(), _library);
				if (!latestPublicKey.ready()) {
					Library.logger().warning("Could not retrieve public key for " + lr.targetName() + ". Gone? " + latestPublicKey.isGone());
					continue;
				}
				// Need to write wrapped key block and linking principal name.
				newPrivateKeyDirectory.addWrappedKeyBlock(
						privateKeyWrappingKey, 
						latestPublicKey.getName(), 
						latestPublicKey.publicKey());
			} catch (XMLStreamException e) {
				Library.logger().warning("Could not retrieve public key for principal " + lr.targetName() + ", skipping.");
			}
		}
		return privateKeyWrappingKey;
		
	}
	
	/**
	 * We need to wrap the group public key wrapping key in the latest public
	 * keys of the members to add.
	 * @param membersToAdd
	 * @throws IOException 
	 * @throws XMLStreamException 
	 * @throws InvalidCipherTextException 
	 * @throws InvalidKeyException 
	 */
	public void updateGroupPublicKey(AccessControlManager manager, Collection<LinkReference> membersToAdd) throws IOException, InvalidKeyException, InvalidCipherTextException, XMLStreamException {
		
		if ((null == membersToAdd) || (membersToAdd.size() == 0))
			return;
		
		KeyDirectory privateKeyDirectory = privateKeyDirectory(manager);
		Key privateKeyWrappingKey = privateKeyDirectory.getUnwrappedKey(null);
		if (null == privateKeyWrappingKey) {
			throw new AccessDeniedException("Cannot update group membership, do not have acces rights to private key for group " + friendlyName());
		}
		for (LinkReference lr : membersToAdd) {
			try {
				// DKS TODO verify target public key against publisher, etc in link
				PublicKeyObject latestPublicKey = new PublicKeyObject(lr.targetName(), _library);
				if (!latestPublicKey.ready()) {
					Library.logger().warning("Could not retrieve public key for " + lr.targetName() + ". Gone? " + latestPublicKey.isGone());
					continue;
				}
				// Need to write wrapped key block and linking principal name.
				privateKeyDirectory.addWrappedKeyBlock(
						privateKeyWrappingKey, 
						latestPublicKey.getName(), 
						latestPublicKey.publicKey());
			} catch (XMLStreamException e) {
				Library.logger().warning("Could not retrieve public key for principal " + lr.targetName() + ", skipping.");
			}
		}
	}
	
	@Override
	public String toString() {
		// Print useful name and version information.
		StringBuffer sb = new StringBuffer("Group ");
		sb.append(friendlyName());
		sb.append(": public key: ");
		if (!_groupPublicKey.ready()) {
			sb.append("not ready, write to " + 
					AccessControlProfile.groupPublicKeyName(_groupNamespace, friendlyName()));
		} else {
			sb.append(publicKeyName());
		}
		sb.append(" membership list: ");
		if ((null == _groupMembers) || (!_groupMembers.ready())) {
			sb.append("not ready, will write to " + 
					AccessControlProfile.groupMembershipListName(_groupNamespace, friendlyName()));
		} else {
			try {
				sb.append(membershipListName());
			} catch (Exception e) {
				Library.logger().warning("Unexpected " + e.getClass().getName() + " exception in getMembershipListName(): " + e.getMessage());
				sb.append("Membership list name unavailable!");
			} 
		}
		return sb.toString();
	}

	public void modify(Collection<LinkReference> membersToAdd,
					   Collection<LinkReference> membersToRemove) throws XMLStreamException, IOException {
		
		boolean addedMembers = false;
		boolean removedMembers = false;
		
		if (((null == membersToAdd) || (membersToAdd.size() == 0)) && ((null == membersToRemove) || (membersToRemove.size() == 0))) {
			return; // nothing to do
		}

		// Do we need to wait for data to come in? We use this to create new groups as well...
		// so in that case, don't expect any.
		
		// Add before remove so that remove overrides adds.
		if ((null != membersToAdd) && (!membersToAdd.isEmpty())) {
			if (null == _groupMembers.membershipList()) {
				_groupMembers.setData(new CollectionData(membersToAdd));
				addedMembers = true;
			} else {
				// Optimization: check to see if any were already in there before adding them....
				addedMembers = _groupMembers.membershipList().contents().addAll(membersToAdd);
			}
		}
		if ((null != membersToRemove) && (!membersToRemove.isEmpty()) &&
			(!_groupMembers.isGone()) && 
			_groupMembers.ready() && // do we wait if it's not ready? we know one exists.
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
				updateGroupPublicKey(membersToAdd);
			} else {
				createGroupPublicKey( _groupMembers);
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
}
