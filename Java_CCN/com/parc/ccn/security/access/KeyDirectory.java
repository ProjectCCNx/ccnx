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
import com.parc.ccn.data.util.DataUtils;
import com.parc.ccn.library.CCNLibrary;
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
	HashMap<String, Timestamp> _principals = new HashMap<String, Timestamp>();
	ArrayList<byte []> _keyIDs = new ArrayList<byte []>();
	ArrayList<byte []> _otherNames = new ArrayList<byte []>();
	
	/**
	 * Directory name should be versioned, else we pull the latest version
	 * @param manager
	 * @param directoryName
	 * @param library
	 * @throws IOException
	 */
	public KeyDirectory(AccessControlManager manager, ContentName directoryName, CCNLibrary library) throws IOException {
		super(directoryName, library);
		if (null == manager) {
			stopEnumerating();
			throw new IllegalArgumentException("Manager cannot be null.");
		}	
		_manager = manager;
		initialize();
	}

	private void initialize() throws IOException {
		if (!VersioningProfile.isVersioned(_namePrefix)) {
			getNewData();
			ContentName latestVersionName = getLatestVersionChildName();
			if (null == latestVersionName) {
				Library.logger().info("Unexpected: can't get a latest version for key directory name : " + _namePrefix);
				getNewData();
				latestVersionName = getLatestVersionChildName();
				if (null == latestVersionName) {
					Library.logger().info("Unexpected: really can't get a latest version for key directory name : " + _namePrefix);
					throw new IOException("Unexpected: really can't get a latest version for key directory name : " + _namePrefix);
				}
			}
			synchronized (_childLock) {
				stopEnumerating();
				_children.clear();
				_newChildren = null;
				_namePrefix = latestVersionName;
				_enumerator.registerPrefix(_namePrefix);
			}
		}
	}
	
	/**
	 * Called each time new data comes in, gets to parse it and load processed
	 * arrays.
	 */
	protected void processNewChildren(ArrayList<ContentName> newChildren) {
		for (ContentName childName : newChildren) {
			// currently encapsulated in single-component ContentNames
			byte [] wkChildName = childName.lastComponent();
			if (AccessControlProfile.isWrappedKeyNameComponent(wkChildName)) {
				byte[] keyid;
				try {
					keyid = AccessControlProfile.getTargetKeyIDFromNameComponent(wkChildName);
					_keyIDs.add(keyid);
				} catch (IOException e) {
					Library.logger().info("Unexpected " + e.getClass().getName() + " parsing key id " + DataUtils.printHexBytes(wkChildName) + ": " + e.getMessage());
					// ignore and go on
				}
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
		
		ContentName wrappedKeyName = new ContentName(_namePrefix, AccessControlProfile.targetKeyIDToNameComponent(keyID));
		return getWrappedKey(wrappedKeyName);
	}
	
	WrappedKeyObject getWrappedKeyForPrincipal(String principalName) throws IOException, XMLStreamException {
		
		if (!_principals.containsKey(principalName)) {
			return null;
		}
		
		ContentName principalLinkName = new ContentName(_namePrefix, AccessControlProfile.principalInfoToNameComponent(principalName, _principals.get(principalName)));
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
		return ContentName.fromNative(_namePrefix, AccessControlProfile.SUPERSEDED_MARKER);
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
		return ContentName.fromNative(_namePrefix, AccessControlProfile.PREVIOUS_KEY_NAME);
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
		return ContentName.fromNative(_namePrefix, AccessControlProfile.GROUP_PUBLIC_KEY_NAME);
	}
	
	ContentObject getPublicKeyObject() throws IOException {
		if (!hasPublicKeyBlock())
			return null;
		
		ContentObject publicKeyObject = _manager.library().get(getPublicKeyBlockName(), AccessControlManager.DEFAULT_TIMEOUT);
		return publicKeyObject;
	}
}
