package com.parc.ccn.security.access;

import java.security.InvalidKeyException;
import java.sql.Timestamp;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.data.ContentName;
import com.parc.ccn.library.profiles.AccessControlProfile;
import com.parc.ccn.library.profiles.VersionMissingException;
import com.parc.ccn.library.profiles.VersioningProfile;
import com.parc.ccn.security.crypto.CCNDigestHelper;
import com.parc.ccn.security.crypto.KeyDerivationFunction;

public class NodeKey {
	
	/**
	 * The node this key is associated with, with _access_ information stripped.
	 */
	private ContentName _nodeName;
	/**
	 * The full name of the stored node key that is either this key itself,
	 * or the ancestor node key this is derived from, including its version information.
	 */
	private ContentName _storedNodeKeyName;
	private byte [] _storedNodeKeyID;
	/**
	 * The unwrapped node key
	 */
	private byte [] _nodeKey;
	
	public NodeKey(ContentName nodeKeyName, byte [] unwrappedNodeKey) {
		if ((null == nodeKeyName) || (null == unwrappedNodeKey)) {
			throw new IllegalArgumentException("NodeKey: key name and key cannot be null!");
		}
		_storedNodeKeyName = nodeKeyName;
		_storedNodeKeyID = generateKeyID(unwrappedNodeKey);
		_nodeKey = unwrappedNodeKey;
		_nodeName = AccessControlProfile.accessRoot(nodeKeyName);
		if ((null == _nodeName) || (!AccessControlProfile.isNodeKeyName(nodeKeyName))) {
			throw new IllegalArgumentException("NodeKey: key name " + nodeKeyName + " is not a valid node key name.");			
		}
	}
	
	protected NodeKey(ContentName nodeName, byte [] derivedNodeKey, 
					  ContentName ancestorNodeKeyName, byte [] ancestorNodeKeyID) {
		_storedNodeKeyName = ancestorNodeKeyName;
		_storedNodeKeyID = ancestorNodeKeyID;
		_nodeName = nodeName;
		_nodeKey = derivedNodeKey;
	}
	
	public NodeKey computeDescendantNodeKey(ContentName descendantNodeName, String keyLabel) throws InvalidKeyException, XMLStreamException {
		if (!nodeName().isPrefixOf(descendantNodeName)) {
			throw new IllegalArgumentException("Node " + descendantNodeName + " is not a child of this node " + nodeName());
		}
		byte [] derivedKey = KeyDerivationFunction.DeriveKeyForNode(nodeName(), nodeKey(), keyLabel, descendantNodeName);
		return new NodeKey(descendantNodeName, derivedKey, storedNodeKeyName(), storedNodeKeyID());
	}
	
	public ContentName nodeName() { return _nodeName; }
	public ContentName storedNodeKeyName() { return _storedNodeKeyName; }
	public byte [] storedNodeKeyID() { return _storedNodeKeyID; }
	public byte [] nodeKey() { return _nodeKey; }
	
	public boolean isDerivedNodeKey() {
		return (!nodeName().isPrefixOf(storedNodeKeyName()));
	}
	
	public Timestamp nodeKeyVersion() throws VersionMissingException { 
		return VersioningProfile.getVersionAsTimestamp(storedNodeKeyName());
	}
	
	public static byte [] generateKeyID(byte [] key) {
		return CCNDigestHelper.digest(key);
	}
}
