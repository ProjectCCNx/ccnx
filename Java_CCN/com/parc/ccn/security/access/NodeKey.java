package com.parc.ccn.security.access;

import com.parc.ccn.data.ContentName;

public class NodeKey {
	
	/**
	 * The node this key is associated with, with _access_ information stripped.
	 */
	private ContentName _nodeName;
	/**
	 * The full name of this node key, including its version information.
	 */
	private ContentName _nodeKeyName;
	/**
	 * The unwrapped node key
	 */
	private byte [] _nodeKey;
	
	public NodeKey(ContentName nodeKeyName, byte [] unwrappedNodeKey) {
		_nodeKeyName = nodeKeyName;
		_nodeKey = unwrappedNodeKey;
		_nodeName = AccessControlProfile.accessRoot(nodeKeyName);
	}
}
