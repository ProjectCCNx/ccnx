package com.parc.ccn.security.access;

import java.io.IOException;
import java.security.PublicKey;
import java.sql.Timestamp;

import com.parc.ccn.Library;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.security.PublicKeyObject;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.library.profiles.AccessControlProfile;
import com.parc.ccn.library.profiles.VersionMissingException;
import com.parc.ccn.library.profiles.VersioningProfile;

public class Group {
	
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
	
	public boolean ready() {
		return _groupMembers.ready() && _groupPublicKey.ready();
	}
	
	public KeyDirectory privateKeyDirectory(AccessControlManager manager) throws IOException {
		if (_groupPublicKey.ready())
			return new KeyDirectory(manager, AccessControlProfile.groupPrivateKeyDirectory(_groupPublicKey.getName()), _library);
		Library.logger().info("Public key not ready for group: " + name());
		return null;
	}
	
	public String name() { return _groupFriendlyName; }

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
}
