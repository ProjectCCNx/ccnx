package com.parc.ccn.security.access;

import java.io.IOException;
import java.security.PublicKey;
import java.sql.Timestamp;
import java.util.ArrayList;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.Library;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.content.CollectionData;
import com.parc.ccn.data.content.LinkReference;
import com.parc.ccn.data.security.PublicKeyObject;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.library.profiles.AccessControlProfile;
import com.parc.ccn.library.profiles.VersionMissingException;
import com.parc.ccn.library.profiles.VersioningProfile;

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
		_groupMembers = new MembershipList(_library);
		_groupMembers.updateInBackground(
				AccessControlProfile.groupMembershipListName(_groupNamespace, _groupFriendlyName), true);
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
	Group(ContentName namespace, String groupFriendlyName, MembershipList members, PublicKeyObject publicKey, CCNLibrary library) {
		_library = library;
		_groupNamespace = namespace;
		_groupFriendlyName = groupFriendlyName;
		_groupMembers = members;
		_groupPublicKey = publicKey;
	}
	
	public boolean ready() {
		return _groupMembers.ready() && _groupPublicKey.ready();
	}
	
	public KeyDirectory privateKeyDirectory(AccessControlManager manager) throws IOException {
		if (_groupPublicKey.ready())
			return new KeyDirectory(manager, 
					AccessControlProfile.groupPrivateKeyDirectory(_groupPublicKey.getName()), _library);
		Library.logger().info("Public key not ready for group: " + friendlyName());
		return null;
	}
	
	public String friendlyName() { return _groupFriendlyName; }

	public MembershipList membershipList() { return _groupMembers; }
	public ContentName membershipListName() { return _groupMembers.getName(); }
	public Timestamp membershipListVersion() {
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

	public void setMembershipList(ArrayList<LinkReference> newMembers) throws XMLStreamException, IOException {
		// need to figure out if we need to know private key; if we do and we don't, throw access denied.
		// We're deleting anyone that exists
		if (!_groupMembers.isGone() && _groupMembers.ready() && (_groupMembers.membershipList().contents().size() > 0)) {
			// There were already members. Remove them and make a new key.
			_groupMembers.membershipList().removeAll();
			_groupMembers.membershipList().add(newMembers);
			// Don't save till we know we can update private key.
			// If we can't update the private key, this will throw AccessDeniedException.
			newGroupPublicKey(friendlyName(), _groupMembers);
			_groupMembers.save();
		} else {
			// No existing members. Just add. Don't have to make  a new key if one exists,
			// just rewrap it for existing members.
			if (null == _groupMembers.membershipList()) {
				_groupMembers.save(new CollectionData(newMembers));
			} else {
				_groupMembers.membershipList().add(newMembers);
				_groupMembers.save();
			}
			if (null != _groupPublicKey.publicKey()) {
				_
			}
		}
	}

	public static PublicKeyObject newGroupPublicKey(String groupFriendlyName,
			MembershipList ml) {
		// TODO Auto-generated method stub
		return null;
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
		if (!_groupMembers.ready()) {
			sb.append("not ready, write to " + 
					AccessControlProfile.groupMembershipListName(_groupNamespace, friendlyName()));
		} else {
			sb.append(membershipListName());
		}
		return sb.toString();
	}

	public void modify(ArrayList<LinkReference> membersToAdd,
			ArrayList<LinkReference> membersToRemove) {
		// TODO Auto-generated method stub
		
	}

	public void delete() {
		// TODO Auto-generated method stub
		
	}
}
