package com.parc.ccn.security.access;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;

import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.SecretKeySpec;
import javax.xml.stream.XMLStreamException;

import org.bouncycastle.crypto.InvalidCipherTextException;

import com.parc.ccn.Library;
import com.parc.ccn.config.ConfigurationException;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.content.LinkReference;
import com.parc.ccn.data.security.PublisherPublicKeyDigest;
import com.parc.ccn.data.security.WrappedKey;
import com.parc.ccn.data.security.WrappedKey.WrappedKeyObject;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.library.EnumeratedNameList;
import com.parc.ccn.library.profiles.AccessControlProfile;
import com.parc.ccn.security.access.ACL.ACLObject;
import com.parc.ccn.security.keys.KeyManager;

/**
 * Misc notes for consolidation.
 * Used in updating node keys and by {@link #getEffectiveNodeKey(ContentName)}.
 * To achieve this, we walk up the tree for this node. At each point, we check to
 * see if a node key exists. If one exists, we decrypt it if we know an appropriate
 * key. Otherwise we return null.
 * 
 * We're going for a low-enumeration approach. We could enumerate node keys and
 * see if we have rights on the latest version; but then we need to enumerate keys
 * and figure out whether we have a copy of a key or one of its previous keys.
 * If we don't know our group memberships, even if we enumerate the node key
 * access, we don't know what groups we're a member of. 
 * 
 * Node keys and ACLs evolve in the following fashion:
 * - if ACL adds rights, by adding a group, we merely add encrypted node key blocks for
 *    the same node key version (ACL version increases, node key version does not)
 * - if an ACL removes rights, by removing a group, we version the ACL and the node key
 *    (both versions increase)
 * - if a group adds rights by adding a member, we merely add key blocks to the group key
 *   (no change to node key or ACL)
 * - if a group removes rights by removing a member, we need to evolve all the node keys
 *   that point to that node key, at the time of next write using that node key (so we don't 
 *   have to enumerate them). (node key version increases, but ACL version does not).
 *   
 * One could have the node key point to its acl version, or vice versa, but they really
 * do most efficiently evolve in parallel. One could have the ACL point to group versions,
 * and update the ACL and NK together in the last case as well. 
 * In this last case, we want to update the NK only on next write; if we never write again,
 * we never need to generate a new NK (unless we can delete). And we want to wait as long
 * as possible, to skip NK updates with no corresponding writes. 
 * But, a writer needs to determine first what the most recent node key is for a given
 * node, and then must determine whether or not that node key must be updated -- whether or
 * not the most recent versions of groups are what that node key is encrypted under.
 * Ideally we don't want to have it update the ACL, as that allows management access separation --
 * we can let writers write the node key without allowing them to write the ACL. 
 * 
 * So, we can't store the group version information in the ACL. We don't necessarily
 * want a writer to have to pull all the node key blocks to see what version of each
 * group the node key is encrypted under.
 * 
 * We could name the node key blocks <prefix>/_access_/NK/#version/<group name>:<group key id>,
 * if we could match on partial components, but we can't.
 * 
 * We can name the node key blocks <prefix>/_access_/NK/#version/<group key id> with
 * a link pointing to that from NK/#version/<group name>. 
 * 
 * For both read and write, we don't actually care what the ACL says. We only care what
 * the node key is. Most efficient option, if we have a full key cache, is to list the 
 * node key blocks by key id used to encrypt them, and then pull one for a key in our cache.
 * On the read side, we're looking at a specific version NK, and we might have rights by going
 * through its later siblings. On the write side, we're looking at the latest version NK, and
 * we should have rights to one of the key blocks, or we don't have rights.
 * If we don't have a full key cache, we have to walk the access hierarchy. In that case,
 * the most efficient thing to do is to pull the latest version of the ACL for that node
 * (if we have a NK, we have an ACL, and vice versa, so we can enumerate NK and then pull
 * ACLs). We then walk that ACL. If we know we are in one of the groups in that ACL, walk
 * back to find the group key encrypting that node key. If we don't, walk the groups in that
 * ACL to find out if we're in any of them. If we are, pull the current group key, see if
 * it works, and start working backwards through group keys, populating the cache in the process,
 * to find the relevant group key.
 * 
 * Right answer might be intentional containment. Besides the overall group key structures,
 * we make a master list that points to the current versions of all the groups. That would
 * have to be writable by anyone who is on the manage list for any group. That would let you
 * get, easily, a single list indicating what groups are available and what their versions are.
 * Unless NE lets you do that in a single pass, which would be better. (Enumerate name/latestversion,
 * not just given name, enumerate versions.)
 * 
 * 
 * Operational Process:
 * read: 
 * - look at content, find data key
 * - data key refers to specific node key and version used to encrypt it
 * - attempt to retrieve that node key from cache, if get it, done
 * - go to specific node key key directory, attempt to find a block we can decrypt using keys in cache;
 * 		if so, done
 * - (maybe) for groups that node key is encrypted under which we believe we are a member of,
 * 		attempt to retrieve the group key version we need to decrypt the node key
 * - if that node key has been superseded, find the latest version of the node key (if we're not
 *     allowed access to that, we're not allowed access to the data) and walk first the cache,
 *     then the groups we believe we're a member of, then the groups we don't know about,
 *     trying to find a key to read it (== retrieve latest version of node key process)
 * - if still can't read node key, attempt to find a new ACL interposed between the data node
 *    and the old node key node, and see if we have access to its latest node key (== retrieve
 *    latest version of node key process), and then crawl through previous key blocks till we
 *    get the one we want
 * write:
 * - find closest node key (non-gone)
 * - decrypt its latest version, if can't, have no read access, which means have no write access
 * - determine whether it's "dirty" -- needs to be superseded. ACL-changes update node key versions,
 *   what we need to do is determine whether any groups have updated their keys
 *   - if so, replace it
 * - use it to protect data key
			// We don't have a key cached. Either we don't have access, we aren't in one of the
			// relevant groups, or we are, but we haven't pulled the appropriate version of the group
			// key (because it's old, or because we don't know we're in that group).
			// We can get this node key because either we're in one of the groups it was made
			// available to, or because it's old, and we have access to one of the groups that
			// has current access. 
			// Want to get the latest version of this node key, then do the walk to figure
			// out how to read it. Need to split this code up:
			// Given specific version (potentially old):
			// - enumerate key blocks and group names
			// 	 - if we have one cached, use key
			// - for groups we believe we're a member of, pull the link and see what key it points to
			// 	 - if it's older than the group key we know, walk back from the group key we know, caching
			//		all the way (this will err on the side of reading; starting from the current group will
			//		err on the side of making access control coverage look more extensive)
			// - if we know nothing else, pull the latest version and walk that if it's newer than this one
			//   - if that results in a key, chain backwards to this key
			// Given latest version:
			// - enumerate key blocks, and group names
			// 	  - if we have one cached, just use it
			// - walk the groups, starting with the groups we believe we're a member of
			// 	  - for groups we believe we're in, check if we're still in, then check for a given key
			//    - walk the groups we don't know if we're in, see if we're in, and can pull the necessary key
			// - given that, unwrap the key and return it
			// basic flow -- flag that says whether we believe we have the latest or not, if set, walk
			// groups we don't know about, if not set, pull latest and if we get something later, make
			// recursive call saying we believe it's the latest (2-depth recursion max)
			// As we look at more stuff, we cache more keys, and fall more and more into the cache-only
			// path.

 * @author smetters
 *
 */
public class AccessControlManager {
	
	/**
	 * Default data key length in bytes. No real reason this can't be bumped up to 32. It
	 * acts as the seed for a KDF, not an encryption key.
	 */
	public static final int DEFAULT_DATA_KEY_LENGTH = 16; 
	/**
	 * The keys we're wrapping are really seeds for a KDF, not keys in their own right.
	 * Eventually we'll use CMAC, so call them AES...
	 */
	public static final String DEFAULT_DATA_KEY_ALGORITHM = "AES";
	
	/**
	 * This algorithm must be capable of key wrap (RSA, ElGamal, etc).
	 */
	public static final String DEFAULT_GROUP_KEY_ALGORITHM = "RSA";
	public static final int DEFAULT_GROUP_KEY_LENGTH = 1024;

	public static final String DATA_KEY_LABEL = "Data Key";
	public static final String NODE_KEY_LABEL = "Node Key";
	public static final long DEFAULT_TIMEOUT = 1000;
	
	public static final long DEFAULT_FRESHNESS_INTERVAL = 100;
	public static final long DEFAULT_NODE_KEY_FRESHNESS_INTERVAL = DEFAULT_FRESHNESS_INTERVAL;
	public static final long DEFAULT_DATA_KEY_FRESHNESS_INTERVAL = DEFAULT_FRESHNESS_INTERVAL;
	public static final long DEFAULT_ACL_FRESHNESS_INTERVAL = DEFAULT_FRESHNESS_INTERVAL;
	public static final long DEFAULT_GROUP_PUBLIC_KEY_FRESHNESS_INTERVAL = 3600;
	public static final long DEFAULT_GROUP_PRIVATE_KEY_FRESHNESS_INTERVAL = 3600;
	public static final long DEFAULT_GROUP_ENCRYPTED_KEY_BLOCK_FRESHNESS_INTERVAL = DEFAULT_FRESHNESS_INTERVAL;

	private ContentName _namespace;
	private ContentName _userStorage;
	private EnumeratedNameList _userList;
	private GroupManager _groupManager = null;
	private HashSet<ContentName> _myIdentities = new HashSet<ContentName>();
	
	private KeyCache _keyCache = new KeyCache();
	private CCNLibrary _library;
	private SecureRandom _random = new SecureRandom();
	
	public AccessControlManager(ContentName namespace) throws ConfigurationException, IOException {
		this(namespace, AccessControlProfile.groupNamespaceName(namespace), AccessControlProfile.userNamespaceName(namespace));
	}
	
	public AccessControlManager(ContentName namespace, ContentName groupStorage, ContentName userStorage) throws ConfigurationException, IOException {
		_namespace = namespace;
		_userStorage = userStorage;
		_library = CCNLibrary.open();
		// start enumerating users in the background
		userList();
		_groupManager = new GroupManager(this, groupStorage, _library);
		// DKS TODO here, check for a namespace marker, and if one not there, write it (async)
	}
	
	public GroupManager groupManager() { return _groupManager; }
	
	public void publishIdentity(ContentName identity, PublisherPublicKeyDigest myPublicKey) throws InvalidKeyException, IOException, ConfigurationException {
		KeyManager km = KeyManager.getKeyManager();
		if (null == myPublicKey) {
			myPublicKey = km.getDefaultKeyID();
		}
		km.publishKey(identity, myPublicKey);
		_myIdentities.add(identity);
	}
	
	public void publishIdentity(String userName, PublisherPublicKeyDigest myPublicKey) throws InvalidKeyException, IOException, ConfigurationException {
		publishIdentity(AccessControlProfile.userNamespaceName(_userStorage, userName), myPublicKey);
	}
	
	public boolean haveIdentity(String userName) {
		return _myIdentities.contains(AccessControlProfile.userNamespaceName(_userStorage, userName));
	}
	
	public boolean haveIdentity(ContentName userName) {
		return _myIdentities.contains(userName);
	}
	
	/**
	 * Labels for deriving various types of keys.
	 * @return
	 */
	public String dataKeyLabel() {
		return DATA_KEY_LABEL;
	}
	
	public String nodeKeyLabel() {
		return NODE_KEY_LABEL;
	}
	
	CCNLibrary library() { return _library; }
	
	KeyCache keyCache() { return _keyCache; }
	
	public EnumeratedNameList userList() throws IOException {
		if (null == _userList) {
			_userList = new EnumeratedNameList(_userStorage, _library);
		}
		return _userList;
	}
	
	public boolean inProtectedNamespace(ContentName content) {
		return _namespace.isPrefixOf(content);
	}


	/**
	 * Retrieves the latest version of an ACL effective at this node, either stored
	 * here or at one of its ancestors.
	 * @param nodeName
	 * @return
	 * @throws ConfigurationException 
	 * @throws IOException 
	 * @throws XMLStreamException 
	 */
	public ACLObject getEffectiveACLObject(ContentName nodeName) throws XMLStreamException, IOException {
		
		// Find the closest node that has a non-gone ACL
		ACLObject aclo = findAncestorWithACL(nodeName);
		if (null == aclo) {
			Library.logger().warning("Unexpected: cannot find an ancestor of node " + nodeName + " that has an ACL.");
			throw new IOException("Unexpected: cannot find an ancestor of node " + nodeName + " that has an ACL.");	
		}
		return aclo;
	}

	private ACLObject findAncestorWithACL(ContentName dataNodeName) throws XMLStreamException, IOException {

		ACLObject ancestorACLObject = null;
		ContentName parentName = dataNodeName;
		ContentName nextParentName = null;
		while (null == ancestorACLObject) {
			ancestorACLObject = getACLObjectForNodeIfExists(parentName);
			if ((null != ancestorACLObject) && (ancestorACLObject.isGone())) {
				Library.logger().info("Found an ACL object at " + ancestorACLObject.getName() + " but its GONE.");
				ancestorACLObject = null;
			}
			nextParentName = parentName.parent();
			if (nextParentName.equals(parentName)) {
				break;
			}
			parentName = nextParentName;
		}
		if (null == ancestorACLObject) {
			throw new IllegalStateException("No ACL available in ancestor tree for node : " + dataNodeName);
		}
		Library.logger().info("Found ACL for " + dataNodeName + " at ancestor :" + ancestorACLObject.getName());
		return ancestorACLObject;
	}

	/**
	 * Try to pull an acl for a particular node. If it doesn't exist, will time
	 * out. Use enumeration to decide whether to call this to avoid the timeout.
	 * @param aclNodeName
	 * @return
	 * @throws IOException 
	 * @throws XMLStreamException 
	 */
	public ACLObject getACLObjectForNode(ContentName aclNodeName) throws XMLStreamException, IOException {
		
		// Get the latest version of the acl. We don't care so much about knowing what version it was.
		ACLObject aclo = new ACLObject(AccessControlProfile.aclName(aclNodeName), _library);
		aclo.update();
		// if there is no update, this will probably throw an exception -- IO or XMLStream
		if (aclo.isGone()) {
			// treat as if no acl on node
			return null;
		}
		return aclo;
	}
	
	public ACLObject getACLObjectForNodeIfExists(ContentName aclNodeName) throws XMLStreamException, IOException {
		
		EnumeratedNameList aclNameList = EnumeratedNameList.exists(AccessControlProfile.aclName(aclNodeName), aclNodeName, _library);
		
		if (null != aclNameList) {
			ContentName aclName = new ContentName(AccessControlProfile.aclName(aclNodeName),
												  aclNameList.getLatestVersionChildName().lastComponent());
			Library.logger().info("Found latest version of acl for " + aclNodeName + " at " + aclName);
			ACLObject aclo = new ACLObject(aclName, _library);
			aclo.update();
			if (aclo.isGone())
				return null;
			return aclo;
		}
		Library.logger().info("No ACL found on node: " + aclNodeName);
		return null;
	}
	
	public ACL getEffectiveACL(ContentName nodeName) throws XMLStreamException, IOException {
		ACLObject aclo = getEffectiveACLObject(nodeName);
		if (null != aclo) {
			return aclo.acl();
		}
		return null;
	}
	
	/**
	 * @throws InvalidKeyException 
	 * Adds an ACL to a node that doesn't have one, or replaces one that exists.
	 * Just writes, doesn't bother to look at any current ACL. Does need to pull
	 * the effective node key at this node, though, to wrap the old ENK in a new
	 * node key.
	 * @throws IOException 
	 * @throws XMLStreamException 
	 * @throws  
	 */
	public ACL setACL(ContentName nodeName, ACL newACL) throws XMLStreamException, IOException, InvalidKeyException {
		NodeKey effectiveNodeKey = getEffectiveNodeKey(nodeName);
		// generates the new node key, wraps it under the new acl, and wraps the old node key
		generateNewNodeKey(nodeName, effectiveNodeKey, newACL);
		// write the acl
		ACLObject aclo = new ACLObject(AccessControlProfile.aclName(nodeName), newACL, _library);
		// DKS FIX REPO WRITE
		aclo.save();
		return aclo.acl();
	}
	
	/**
	 * Pulls the ACL for this node, if one exists, and modifies it to include
	 * the following changes, then stores the result using setACL.
	 * @throws IOException 
	 * @throws XMLStreamException 
	 * @throws InvalidKeyException 
	 */
	public ACL updateACL(ContentName nodeName, 
						ArrayList<LinkReference> addReaders, ArrayList<LinkReference> removeReaders,
						ArrayList<LinkReference> addWriters, ArrayList<LinkReference> removeWriters,
						ArrayList<LinkReference> addManagers, ArrayList<LinkReference> removeManagers) throws XMLStreamException, IOException, InvalidKeyException {
		
		ACLObject currentACL = getACLObjectForNodeIfExists(nodeName);
		ACL newACL = null;
		if (null != currentACL) {
			newACL = currentACL.acl();
		} else {
			newACL = new ACL();
		}
		// TODO Now update ACL to add and remove values.
		// Managers are a subset of writers are a subset of readers. So if you remove someone
		// as a reader, you remove them whether they are a reader, manager or writer.
		// If you remove someone as a writer, you remove them whether they are a manager or a writer.
		
		
		// Set the ACL and update the node key.
		return setACL(nodeName, newACL);
	}
		
	public ACL addReaders(ContentName nodeName, ArrayList<LinkReference> newReaders) throws InvalidKeyException, XMLStreamException, IOException {
		return updateACL(nodeName, newReaders, null, null, null, null, null);
	}
	
	public ACL addWriters(ContentName nodeName, ArrayList<LinkReference> newWriters) throws InvalidKeyException, XMLStreamException, IOException {
		return updateACL(nodeName, null, null, newWriters, null, null, null);
	}
	
	public ACL addManagers(ContentName nodeName, ArrayList<LinkReference> newManagers) throws InvalidKeyException, XMLStreamException, IOException {
		return updateACL(nodeName, null, null, null, null, newManagers, null);
	}
	
	
	/**
	 * 
	 * Get the ancestor node key in force at this node (if we can decrypt it).
	 * @param nodeName
	 * @return null means while node keys exist, we can't decrypt any of them --
	 *    we have no read access to this node (which implies no write access)
	 * @throws IOException if something is wrong (e.g. no node keys at all)
	 */
	protected NodeKey findAncestorWithNodeKey(ContentName nodeName) throws IOException {
		// TODO Auto-generated method stub
		// climb up looking for node keys, then make sure that one isn't GONE
		// if it isn't, call read-side routine to figure out how to decrypt it
		return null;
	}
	
	/**
	 * Write path: get the latest node key.
	 * @param nodeName
	 * @return
	 * @throws IOException
	 * @throws InvalidKeyException
	 * @throws InvalidCipherTextException
	 * @throws XMLStreamException
	 */
	public NodeKey getLatestNodeKeyForNode(ContentName nodeName) throws IOException, InvalidKeyException, InvalidCipherTextException, XMLStreamException {
		
		// First we need to figure out what the latest version is of the node key.
		ContentName nodeKeyVersionedName = 
			EnumeratedNameList.getLatestVersionName(AccessControlProfile.nodeKeyName(nodeName), _library);
		// then, pull the node key we can decrypt
		return getNodeKeyByVersionedName(nodeKeyVersionedName, null);
	}
	
	/**
	 * Read path:
	 * Retrieve a specific node key from a given location, as specified by a
	 * key it was used to wrap, and, if possible, find a key we can use to
	 * unwrap the node key.
	 * 
	 * Throw an exception if there is no node key block at the appropriate name.
	 * @param nodeKeyName
	 * @param nodeKeyIdentifier
	 * @return
	 * @throws IOException 
	 * @throws XMLStreamException 
	 * @throws InvalidCipherTextException 
	 * @throws InvalidKeyException 
	 */
	public NodeKey getSpecificNodeKey(ContentName nodeKeyName, byte [] nodeKeyIdentifier) throws InvalidKeyException, InvalidCipherTextException, XMLStreamException, IOException {
		
		if ((null == nodeKeyName) && (null == nodeKeyIdentifier)) {
			throw new IllegalArgumentException("Node key name and identifier cannot both be null!");
		}
		// We should know what node key to use (down to the version), but we have to find the specific
		// wrapped key copy we can decrypt. 
		NodeKey nk = getNodeKeyByVersionedName(nodeKeyName, nodeKeyIdentifier);
		if (null == nk) {
			Library.logger().warning("No decryptable node key available at " + nodeKeyName + ", access denied.");
			return null;
		}
	
		return nk;
	}
	
	/**
	 * We have the name of a specific version of a node key. Now we just need to figure
	 * out which of our keys can be used to decrypt it.
	 * @param nodeKeyName
	 * @param nodeKeyIdentifier
	 * @return
	 * @throws IOException 
	 * @throws XMLStreamException 
	 * @throws InvalidKeyException 
	 * @throws InvalidCipherTextException 
	 */
	NodeKey getNodeKeyByVersionedName(ContentName nodeKeyName, byte [] nodeKeyIdentifier) throws XMLStreamException, IOException, InvalidKeyException, InvalidCipherTextException {

		NodeKey nk = null;
		KeyDirectory keyDirectory = null;
		try {

			keyDirectory = new KeyDirectory(this, nodeKeyName, _library);
			// this will handle the caching.
			Key unwrappedKey = keyDirectory.getUnwrappedKey(nodeKeyIdentifier);
			if (null != unwrappedKey) {
				nk = new NodeKey(nodeKeyName, unwrappedKey);
			}
		} finally {
			if (null != keyDirectory) {
				keyDirectory.stopEnumerating();
			}
		}
		return nk;
	}
	
	/**
	 * Write path:
	 * Get the effective node key in force at this node, used to derive keys to 
	 * encrypt  content. Vertical chaining.
	 * @throws XMLStreamException 
	 * @throws InvalidKeyException 
	 * @throws IOException 
	 */
	public NodeKey getEffectiveNodeKey(ContentName nodeName) throws InvalidKeyException, XMLStreamException, IOException {
		// Get the ancestor node key in force at this node.
		NodeKey nodeKey = findAncestorWithNodeKey(nodeName);
		if (null == nodeKey) {
			// TODO no access
			throw new IllegalStateException("Cannot retrieve node key for node: " + nodeName + ".");
		}
		NodeKey effectiveNodeKey = nodeKey.computeDescendantNodeKey(nodeName, nodeKeyLabel()); 
		Library.logger().info("Computing effective node key for " + nodeName + " using stored node key " + effectiveNodeKey.storedNodeKeyName());
		return effectiveNodeKey;
	}
	
	/**
	 * Do we need to update this node key?
	 * @param theNodeKey
	 * @return
	 */
	public boolean nodeKeyIsDirty(NodeKey theNodeKey) {
		// TODO
		return true;
	}
	
	/**
	 * Would we update this data key if we were doing reencryption?
	 */
	public boolean dataKeyIsDirty(ContentName dataName) {
		// TODO
		return true;
	}
	
	/**
	 * We've looked for a node key we can decrypt at the expected node key location,
	 * but no dice. See if a new ACL has been interposed granting us rights at a lower
	 * portion of the tree.
	 * @param dataNodeName
	 * @param wrappingKeyName
	 * @param wrappingKeyIdentifier
	 * @return
	 * @throws IOException 
	 * @throws XMLStreamException 
	 * @throws InvalidCipherTextException 
	 * @throws InvalidKeyException 
	 */
	protected NodeKey getNodeKeyUsingInterposedACL(ContentName dataNodeName,
			ContentName wrappingKeyName, byte[] wrappingKeyIdentifier) throws XMLStreamException, IOException, InvalidKeyException, InvalidCipherTextException {
		ACLObject nearestACL = findAncestorWithACL(dataNodeName);
		
		if (null == nearestACL) {
			Library.logger().warning("Unexpected -- node with no ancestor ACL: " + dataNodeName);
			// no dice
			return null;
		}
		
		if (nearestACL.equals(AccessControlProfile.accessRoot(wrappingKeyName))) {
			Library.logger().info("Node key: " + wrappingKeyName + " is the nearest ACL to " + dataNodeName);
			return null;
		}
		
		NodeKey nk = getLatestNodeKeyForNode(AccessControlProfile.accessRoot(nearestACL.getName()));
		return nk;
	}

	/**
	 * Make a new node key, encrypt it under the given ACL, and wrap its previous node key.
	 * Put all the blocks into the aggregating writer, but don't flush.
	 * @param nodeName
	 * @param effectiveNodeKey
	 * @param newACL
	 */
	protected void generateNewNodeKey(ContentName nodeName, NodeKey effectiveNodeKey, ACL effectiveACL) {
		// TODO Auto-generated method stub
		
	}
	
	public NodeKey getNodeKeyForObject(ContentName nodeName, WrappedKeyObject wko) throws InvalidKeyException, XMLStreamException, InvalidCipherTextException, IOException {
		
		// First, we go and look for the node key where the data key suggests
		// it should be, and attempt to decrypt it from there.
		NodeKey nk = getSpecificNodeKey(wko.wrappedKey().wrappingKeyName(), 
										wko.wrappedKey().wrappingKeyIdentifier());
		if (null == nk) {
			// OK, we will have gotten an exception if the node key simply didn't exist
			// there, so this means that we don't have rights to read it there.
			// The only way we might have rights not visible from this link is if an
			// ACL has been interposed between where we are and the node key, and that
			// ACL does give us rights.
			nk = getNodeKeyUsingInterposedACL(nodeName, wko.wrappedKey().wrappingKeyName(), 
					wko.wrappedKey().wrappingKeyIdentifier());
			if (null == nk) {
				// Still can't find one we can read. Give up. Return null, and allow caller to throw the 
				// access exception.
				return null;
			}
		}
		NodeKey enk = nk.computeDescendantNodeKey(nodeName, dataKeyLabel());
		return enk;
	}
	
	/**
	 * Used by content reader to retrieve the keys necessary to decrypt this content
	 * under this access control model.
	 * Given a data location, pull the data key block and decrypt it using
	 * whatever node keys are necessary.
	 * To turn the result of this into a key for decrypting content,
	 * follow the steps in the comments to {@link #generateAndStoreDataKey(ContentName)}.
	 * @param dataNodeName
	 * @return
	 * @throws IOException 
	 * @throws XMLStreamException 
	 * @throws InvalidKeyException 
	 * @throws InvalidCipherTextException 
	 */
	public byte [] getDataKey(ContentName dataNodeName) throws XMLStreamException, IOException, InvalidKeyException, InvalidCipherTextException {
		WrappedKeyObject wdko = new WrappedKeyObject(AccessControlProfile.dataKeyName(dataNodeName), _library);
		wdko.update();
		if (null == wdko.wrappedKey()) {
			Library.logger().warning("Could not retrieve data key for node: " + dataNodeName);
			return null;
		}
		NodeKey enk = getNodeKeyForObject(dataNodeName, wdko);
		if (null != enk) {
			Key dataKey = wdko.wrappedKey().unwrapKey(enk.nodeKey());
			return dataKey.getEncoded();
		} 
		return null;
	}
	
	/**
	 * Take a randomly generated data key and store it. This requires finding
	 * the current effective node key, and wrapping this data key in it.
	 * @param dataNodeName
	 * @param newRandomDataKey
	 * @throws XMLStreamException 
	 * @throws InvalidKeyException 
	 * @throws IllegalBlockSizeException 
	 * @throws NoSuchPaddingException 
	 * @throws NoSuchAlgorithmException 
	 * @throws InvalidKeyException 
	 * @throws IOException 
	 */
	public void storeDataKey(ContentName dataNodeName, byte [] newRandomDataKey) throws InvalidKeyException, XMLStreamException, IOException {
		NodeKey effectiveNodeKey = getEffectiveNodeKey(dataNodeName);
		if (null == effectiveNodeKey) {
			throw new IllegalStateException("Cannot retrieve effective node key for node: " + dataNodeName + ".");
		}
		Library.logger().info("Wrapping data key for node: " + dataNodeName + " with effective node key for node: " + 
							  effectiveNodeKey.nodeName() + " derived from stored node key for node: " + 
							  effectiveNodeKey.storedNodeKeyName());
		WrappedKey wrappedDataKey = WrappedKey.wrapKey(new SecretKeySpec(newRandomDataKey, DEFAULT_DATA_KEY_ALGORITHM), 
													   null, dataKeyLabel(), 
													   effectiveNodeKey.nodeKey());
		wrappedDataKey.setWrappingKeyIdentifier(effectiveNodeKey.storedNodeKeyID());
		wrappedDataKey.setWrappingKeyName(effectiveNodeKey.storedNodeKeyName());
		
		storeKeyContent(AccessControlProfile.dataKeyName(dataNodeName), wrappedDataKey);
	}
	
	/**
	 * Generate a random data key, store it, and return it to use to derive keys to encrypt
	 * content. All that's left is to call
	 * byte [] randomDataKey = generateAndStoreDataKey(dataNodeName);
	 * byte [][] keyandiv = 
	 * 		KeyDerivationFunction.DeriveKeyForObject(randomDataKey, keyLabel, 
	 * 												 dataNodeName, dataPublisherPublicKeyDigest)
	 * and then give keyandiv to the segmenter to encrypt the data.
	 * @throws InvalidKeyException 
	 * @throws XMLStreamException 
	 * @throws IOException 
	 **/
	public byte [] generateAndStoreDataKey(ContentName dataNodeName) throws InvalidKeyException, XMLStreamException, IOException {
		// Generate new random data key of appropriate length
		byte [] dataKey = new byte[DEFAULT_DATA_KEY_LENGTH];
		_random.nextBytes(dataKey);
		storeDataKey(AccessControlProfile.dataKeyName(dataNodeName), dataKey);
		return dataKey;
	}
	
	/**
	 * Actual output functions. Needs to get this into the repo.
	 * @param dataNodeName -- the content node for whom this is the data key.
	 * @param wrappedDataKey
	 * @throws IOException 
	 * @throws XMLStreamException 
	 */
	private void storeKeyContent(ContentName dataNodeName,
								 WrappedKey wrappedKey) throws XMLStreamException, IOException {
		// DKS FIX FOR REPO
		WrappedKeyObject wko = new WrappedKeyObject(AccessControlProfile.dataKeyName(dataNodeName), wrappedKey, _library);
		wko.save();
	}

}	
