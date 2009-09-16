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

import java.security.InvalidKeyException;
import java.security.Key;

import javax.crypto.spec.SecretKeySpec;
import javax.xml.stream.XMLStreamException;

import org.ccnx.ccn.impl.security.crypto.CCNDigestHelper;
import org.ccnx.ccn.impl.security.crypto.KeyDerivationFunction;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.profiles.VersionMissingException;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.protocol.CCNTime;
import org.ccnx.ccn.protocol.ContentName;

/**
 * This class represents node keys.
 * It includes methods for computing derived node keys for descendant nodes
 * using a key derivation function.
 */

public class NodeKey {
	
	/**
	 * Default data key length in bytes. No real reason this can't be bumped up to 32. It
	 * acts as the seed for a KDF, not an encryption key.
	 */
	public static final int DEFAULT_NODE_KEY_LENGTH = 16; 
	/**
	 * The keys we're wrapping are really seeds for a KDF, not keys in their own right.
	 * Eventually we'll use CMAC, so call them AES...
	 */
	public static final String DEFAULT_NODE_KEY_ALGORITHM = "AES";
	
	/**
	 * Default key label for key derivation function. 
	 */
	public static final String DEFAULT_KEY_LABEL = "NodeKey";
	
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
	private Key _nodeKey;
	
	/**
	 * Constructor for a node key specified by its name and key bytes
	 * interpreted as a key for DEFAULT_NODE_KEY_ALGORITHM.
	 * @param nodeKeyName
	 * @param unwrappedNodeKey
	 */
	public NodeKey(ContentName nodeKeyName, byte [] unwrappedNodeKey) {
		this(nodeKeyName, new SecretKeySpec(unwrappedNodeKey, DEFAULT_NODE_KEY_ALGORITHM));
	}
	
	/**
	 * Constructor for a node key specified by its name and key. 
	 * @param nodeKeyName
	 * @param unwrappedNodeKey
	 */
	public NodeKey(ContentName nodeKeyName, Key unwrappedNodeKey) {
		if ((null == nodeKeyName) || (null == unwrappedNodeKey)) {
			throw new IllegalArgumentException("NodeKey: key name and key cannot be null!");
		}
		// DKS TODO make sure the version is of the NK, not the blocks underneath it.
		if (!VersioningProfile.hasTerminalVersion(nodeKeyName)) {
			throw new IllegalArgumentException("Expect stored node key name to be versioned: " + nodeKeyName);
		}
		_storedNodeKeyName = nodeKeyName;
		_storedNodeKeyID = generateKeyID(unwrappedNodeKey.getEncoded());
		_nodeKey = unwrappedNodeKey;
		_nodeName = AccessControlProfile.accessRoot(nodeKeyName);
		if ((null == _nodeName) || (!AccessControlProfile.isNodeKeyName(nodeKeyName))) {
			throw new IllegalArgumentException("NodeKey: key name " + nodeKeyName + " is not a valid node key name.");			
		}		
	}
	
	/**
	 * Constructor for a node key derived (via a key derivation function)
	 * from an ancestor node key. 
	 * @param nodeName
	 * @param derivedNodeKey
	 * @param ancestorNodeKeyName
	 * @param ancestorNodeKeyID
	 */
	protected NodeKey(ContentName nodeName, byte [] derivedNodeKey, 
					  ContentName ancestorNodeKeyName, byte [] ancestorNodeKeyID) {
		if (!VersioningProfile.hasTerminalVersion(ancestorNodeKeyName)) {
			// DKS TODO make sure the version is of the NK, not the blocks underneath it.
			throw new IllegalArgumentException("Expect stored node key name to be versioned: " + ancestorNodeKeyName);
		}
		_storedNodeKeyName = ancestorNodeKeyName;
		_storedNodeKeyID = ancestorNodeKeyID;
		_nodeName = nodeName;
		_nodeKey = new SecretKeySpec(derivedNodeKey, DEFAULT_NODE_KEY_ALGORITHM);
	}
	
	/**
	 * Computes the descendant node key for a specified descendant node
	 * using the key derivation function. 
	 * @param descendantNodeName
	 * @param keyLabel
	 * @return
	 * @throws InvalidKeyException
	 * @throws XMLStreamException
	 */
	public NodeKey computeDescendantNodeKey(ContentName descendantNodeName, String keyLabel) throws InvalidKeyException, XMLStreamException {
		if (nodeName().equals(descendantNodeName)) {
			Log.info("Asked to compute ourselves as our own descendant (node key " + nodeName() +"), returning this.");
			return this;
		}
		if (!nodeName().isPrefixOf(descendantNodeName)) {
			throw new IllegalArgumentException("Node " + descendantNodeName + " is not a child of this node " + nodeName());
		}
		byte [] derivedKey = KeyDerivationFunction.DeriveKeyForNode(nodeName(), nodeKey().getEncoded(), keyLabel, descendantNodeName);
		return new NodeKey(descendantNodeName, derivedKey, storedNodeKeyName(), storedNodeKeyID());
	}
	
	public NodeKey computeDescendantNodeKey(ContentName descendantNodeName) throws InvalidKeyException, XMLStreamException {
		return computeDescendantNodeKey(descendantNodeName, DEFAULT_KEY_LABEL);
	}
	
	public ContentName nodeName() { return _nodeName; }
	public ContentName storedNodeKeyName() { return _storedNodeKeyName; }
	public byte [] storedNodeKeyID() { return _storedNodeKeyID; }
	public Key nodeKey() { return _nodeKey; }
	
	public boolean isDerivedNodeKey() {
		return (!nodeName().isPrefixOf(storedNodeKeyName()));
	}
	
	public CCNTime nodeKeyVersion() { 
		try {
			return VersioningProfile.getLastVersionAsTimestamp(storedNodeKeyName());
		} catch (VersionMissingException e) {
			Log.warning("Unexpected: name that was confirmed to have a version on construction throws a VersionMissingException: " + storedNodeKeyName());
			throw new IllegalStateException("Unexpected: name that was confirmed to have a version on construction throws a VersionMissingException: " + storedNodeKeyName());
		}
	}
	
	/**
	 * Returns a hash of the node key.
	 * @return
	 */
	public byte [] generateKeyID() { 
		return generateKeyID(nodeKey().getEncoded());
	}
	
	public static byte [] generateKeyID(byte [] key) {
		return CCNDigestHelper.digest(key);
	}

	public static byte [] generateKeyID(Key key) {
		if (null == key)
			return null;
		return CCNDigestHelper.digest(key.getEncoded());
	}

	public NodeKey getPreviousKey() {
		// TODO Auto-generated method stub
		return null;
	}
}
