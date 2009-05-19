package com.parc.ccn.security.access;

import java.io.IOException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.Library;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.content.Link;
import com.parc.ccn.data.security.WrappedKey.WrappedKeyObject;
import com.parc.ccn.library.EnumeratedNameList;
import com.parc.ccn.library.profiles.AccessControlProfile;
import com.parc.ccn.library.profiles.VersioningProfile;
import com.sun.tools.javac.util.Pair;

/**
 * A key directory is a versioned key object which contains, under
 * the version, a set of key blocks wrapped under different target keys,
 * plus some potential supporting information pointing to previous
 * or subsequent versions of this key. This structure is used for
 * representing both node keys and group (private) keys. We
 * encapsulate functionality to walk such a directory and find our
 * target key here.
 * 
 * @author smetters
 *
 */
public class KeyDirectory extends EnumeratedNameList {
	
	AccessControlManager _manager; // to get at key cache
	ContentName _directoryName; // should be versioned, otherwise we pull the latest version
	HashMap<String, Timestamp> _principals = new HashMap<String, Timestamp>();
	ArrayList<byte []> _keyIDs = new ArrayList<byte []>();
	ArrayList<byte []> _otherNames = new ArrayList<byte []>();
	EnumeratedNameList _keyDirectory = null;
	
	public KeyDirectory(AccessControlManager manager, ContentName directoryName) throws IOException {
		if ((null == manager) || (null == directoryName)) {
			throw new IllegalArgumentException("Manager and directory cannot be null.");
		}
	
		_manager = manager;
		_directoryName = directoryName;
		initialize();
	}

	private void initialize() throws IOException {
		if (!VersioningProfile.isVersioned(_directoryName)) {
			ContentName newDirectoryName = EnumeratedNameList.getLatestVersionName(_directoryName);
			if (null == newDirectoryName) {
				Library.logger().info("Unexpected: can't get a latest version for key directory name : " + _directoryName);
			} else {
				_directoryName = newDirectoryName;
			}
		}
		// Quick path, if cache is full -- enumerate node keys, pull the one we can decrypt.
		// Name node keys by both wrapping key ID and group. To differentiate, prefix
		// node key IDs 
		_keyDirectory = new EnumeratedNameList(_directoryName, _manager.library());
		loadMoreData();
	}
	
	public synchronized boolean hasMoreData() {
		return _keyDirectory.hasNewData();
	}

	/**
	 * Will block until data is available. 
	 */
	public synchronized void loadMoreData() {
		// Will block until an answer comes back or timeout.
		ArrayList <byte []> children = _keyDirectory.getNewData();

		// We have at least one answer. Pass through it, and for the keys,
		// check to see if we know the key already.
		for (byte [] wkChildName : children) {
			if (AccessControlProfile.isWrappedKeyNameComponent(wkChildName)) {
				byte [] keyid = AccessControlProfile.getTargetKeyIDFromNameComponent(wkChildName);
				_keyIDs.add(keyid);
			} else if (AccessControlProfile.isPrincipalNameComponent(wkChildName)) {
				addPrincipal(wkChildName);
			} else {
				_otherNames.add(wkChildName);
			}
		}
	}
	
	public ArrayList<byte []> getWrappingKeyIDs() { return _keyIDs; }
	
	public HashMap<String, Timestamp> getPrincipals() { return _principals; }
	
	public ArrayList<byte []> otherNames() { return _otherNames; }
	
	public void addPrincipal(byte [] wkChildName) {
		Pair<String, Timestamp> pair = AccessControlProfile.parsePrincipalInfoFromNameComponent(wkChildName);
		_principals.put(pair.fst, pair.snd);
	}
	
	WrappedKeyObject getWrappedKeyForKeyID(byte [] keyID) throws XMLStreamException, IOException {
		if (!_keyIDs.contains(keyID)) {
			return null;
		}
		
		ContentName wrappedKeyName = new ContentName(_directoryName, AccessControlProfile.targetKeyIDToNameComponent(keyID));
		return getWrappedKey(wrappedKeyName);
	}
	
	WrappedKeyObject getWrappedKeyForPrincipal(String principalName) throws IOException, XMLStreamException {
		
		if (!_principals.containsKey(principalName)) {
			return null;
		}
		
		ContentName principalLinkName = new ContentName(_directoryName, AccessControlProfile.principalInfoToNameComponent(principalName, _principals.get(principalName)));
		// This should be a link to the actual key block
		// TODO DKS replace link handling
		Link principalLink = _manager.library().getLink(principalLinkName, AccessControlManager.DEFAULT_TIMEOUT);
		Library.logger().info("Retrieving wrapped key for principal " + principalName + " at " + principalLink.getTargetName());
		ContentName wrappedKeyName = principalLink.getTargetName();
		return getWrappedKey(wrappedKeyName);
	}
	
	public boolean hasSupersededBlock() {
		return _otherNames.contains(AccessControlProfile.SUPERSEDED_MARKER);
	}
	
	public ContentName getSupersededBlockName() {
		return ContentName.fromNative(_directoryName, AccessControlProfile.SUPERSEDED_MARKER);
	}
	
	WrappedKeyObject getWrappedKeyForSupersedingKey() throws XMLStreamException, IOException {
		if (!hasSupersededBlock())
			return null;
		return getWrappedKey(getSupersededBlockName());
	}
	
	WrappedKeyObject getWrappedKey(ContentName wrappedKeyName) throws XMLStreamException, IOException {
		WrappedKeyObject wrappedKey = new WrappedKeyObject(wrappedKeyName, _manager.library());
		wrappedKey.update();
		return wrappedKey;		
	}
	
	public boolean hasPreviousKeyBlock() {
		return _otherNames.contains(AccessControlProfile.PREVIOUS_KEY_NAME);
	}

	public ContentName getPreviousKeyBlockName() {
		return ContentName.fromNative(_directoryName, AccessControlProfile.PREVIOUS_KEY_NAME);
	}
	
	WrappedKeyObject getWrappedKeyForPreviousKey() throws XMLStreamException, IOException {
		if (!hasSupersededBlock())
			return null;
		return getWrappedKey(getPreviousKeyBlockName());
	}

	public boolean hasPublicKeyBlock() {
		return _otherNames.contains(AccessControlProfile.GROUP_PUBLIC_KEY_NAME);
	}

	public ContentName getPublicKeyBlockName() {
		return ContentName.fromNative(_directoryName, AccessControlProfile.GROUP_PUBLIC_KEY_NAME);
	}
	
	ContentObject getPublicKeyObject() throws IOException {
		if (!hasPublicKeyBlock())
			return null;
		
		ContentObject publicKeyObject = _manager.library().get(getPublicKeyBlockName(), AccessControlManager.DEFAULT_TIMEOUT);
		return publicKeyObject;
	}
}
