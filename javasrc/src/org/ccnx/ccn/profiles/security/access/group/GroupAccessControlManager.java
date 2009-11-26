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

package org.ccnx.ccn.profiles.security.access.group;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;

import javax.crypto.spec.SecretKeySpec;

import org.bouncycastle.crypto.InvalidCipherTextException;
import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.KeyManager;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.impl.security.crypto.ContentKeys;
import org.ccnx.ccn.impl.security.crypto.KeyDerivationFunction;
import org.ccnx.ccn.impl.support.DataUtils;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.ContentDecodingException;
import org.ccnx.ccn.io.content.ContentEncodingException;
import org.ccnx.ccn.io.content.ContentGoneException;
import org.ccnx.ccn.io.content.ContentNotReadyException;
import org.ccnx.ccn.io.content.Link;
import org.ccnx.ccn.io.content.LinkAuthenticator;
import org.ccnx.ccn.io.content.PublicKeyObject;
import org.ccnx.ccn.io.content.WrappedKey;
import org.ccnx.ccn.io.content.WrappedKey.WrappedKeyObject;
import org.ccnx.ccn.profiles.VersionMissingException;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.profiles.nameenum.EnumeratedNameList;
import org.ccnx.ccn.profiles.namespace.NamespaceManager;
import org.ccnx.ccn.profiles.namespace.NamespaceManager.Root.RootObject;
import org.ccnx.ccn.profiles.security.access.AccessDeniedException;
import org.ccnx.ccn.profiles.security.access.KeyCache;
import org.ccnx.ccn.profiles.security.access.group.ACL.ACLObject;
import org.ccnx.ccn.profiles.security.access.group.ACL.ACLOperation;
import org.ccnx.ccn.profiles.security.access.group.GroupAccessControlProfile.PrincipalInfo;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;


/**
 * This class is used in updating node keys and by #getEffectiveNodeKey(ContentName).
 * To achieve this, we walk up the tree for this node. At each point, we check to
 * see if a node key exists. If one exists, we decrypt it if we know an appropriate
 * key. Otherwise we return null.
 * 
 * We are going for a low-enumeration approach. We could enumerate node keys and
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
 * One could have the node key (NK) point to its ACL version, or vice versa, but they really
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
 * We could name the node key blocks &lt;prefix&gt;/_access_/NK/\#version/&lt;group name&gt;:&lt;group key id&gt;,
 * if we could match on partial components, but we can't.
 * 
 * We can name the node key blocks &lt;prefix&gt;/_access_/NK/\#version/&lt;group key id&gt; with
 * a link pointing to that from NK/\#version/&lt;group name&gt;. 
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
 * 
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
 *    
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
 *
 */
public class GroupAccessControlManager {
	
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
	
	private ContentName _namespace;
	private ContentName _userStorage;
	private EnumeratedNameList _userList;
	private GroupManager _groupManager = null;
	private HashSet<ContentName> _myIdentities = new HashSet<ContentName>();
	
	private KeyCache _keyCache;
	private CCNHandle _handle;
	private SecureRandom _random = new SecureRandom();
	
	/**
	 * Factory method.
	 * Eventually split between a superclass AccessControlManager that handles many
	 * access schemes and a subclass GroupBasedAccessControlManager. For now, put
	 * a factory method here that makes you an ACM based on information in a stored
	 * root object. Have to trust that object as a function of who signed it.
	 */
	public static GroupAccessControlManager createManager(RootObject policyInformation, CCNHandle handle) {
		return null; // TODO fill in 
	}
	
	public GroupAccessControlManager(ContentName namespace) throws ConfigurationException, IOException {
		this(namespace, null);
	}

	public GroupAccessControlManager(ContentName namespace, CCNHandle handle) throws ConfigurationException, IOException {
		this(namespace, GroupAccessControlProfile.groupNamespaceName(namespace), GroupAccessControlProfile.userNamespaceName(namespace), handle);
	}

	public GroupAccessControlManager(ContentName namespace, ContentName groupStorage, ContentName userStorage) throws ConfigurationException, IOException {
		this(namespace, groupStorage, userStorage, null);
	}
	
	public GroupAccessControlManager(ContentName namespace, ContentName groupStorage, ContentName userStorage, CCNHandle handle) throws ConfigurationException, IOException {
		_namespace = namespace;
		_userStorage = userStorage;
		if (null == handle) {
			_handle = CCNHandle.open();
		} else {
			_handle = handle;
		}
		_keyCache = new KeyCache(_handle.keyManager());
		
		// start enumerating users in the background
		userList();
		_groupManager = new GroupManager(this, groupStorage, _handle);
		// TODO here, check for a namespace marker, and if one not there, write it (async)
	}
	
	public GroupManager groupManager() { return _groupManager; }
	
	/**
	 * Publish my identity (i.e. my public key) under a specified CCN name
	 * @param identity the name
	 * @param myPublicKey my public key
	 * @throws InvalidKeyException
	 * @throws ContentEncodingException
	 * @throws IOException
	 * @throws ConfigurationException
	 */
	public void publishMyIdentity(ContentName identity, PublicKey myPublicKey) 
				throws InvalidKeyException, ContentEncodingException, IOException, ConfigurationException {
		KeyManager km = _handle.keyManager();
		if (null == myPublicKey) {
			myPublicKey = km.getDefaultPublicKey();
		}
		PublicKeyObject pko = new PublicKeyObject(identity, myPublicKey, handle());
		pko.saveToRepository();
		_myIdentities.add(identity);
	}
	
	/**
	 * Publish my identity (i.e. my public key) under a specified user name
	 * @param userName the user name
	 * @param myPublicKey my public key
	 * @throws InvalidKeyException
	 * @throws ContentEncodingException
	 * @throws IOException
	 * @throws ConfigurationException
	 */
	public void publishMyIdentity(String userName, PublicKey myPublicKey) 
			throws InvalidKeyException, ContentEncodingException, IOException, ConfigurationException {
		Log.finest("publishing my identity" + GroupAccessControlProfile.userNamespaceName(_userStorage, userName));
		publishMyIdentity(GroupAccessControlProfile.userNamespaceName(_userStorage, userName), myPublicKey);
	}
	
	/**
	 * Publish the specified identity (i.e. the public key) of a specified user
	 * @param userName the name of the user
	 * @param userPublicKey the public key of the user
	 * @throws ConfigurationException
	 * @throws IOException
	 * @throws MalformedContentNameStringException
	 */
	public void publishUserIdentity(String userName, PublicKey userPublicKey) 
			throws ConfigurationException, IOException, MalformedContentNameStringException {
		PublicKeyObject pko = new PublicKeyObject(ContentName.fromNative(userName), userPublicKey, handle());
		System.out.println("saving user pubkey to repo:" + userName);
		pko.saveToRepository();
	}
	
	public boolean haveIdentity(String userName) {
		return _myIdentities.contains(GroupAccessControlProfile.userNamespaceName(_userStorage, userName));
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
	
	CCNHandle handle() { return _handle; }
	
	KeyCache keyCache() { return _keyCache; }

	/**
	 * Enumerate users
	 * @return user enumeration
	 * @throws IOException
	 */
	public EnumeratedNameList userList() throws IOException {
		if (null == _userList) {
			_userList = new EnumeratedNameList(_userStorage, handle());
		}
		return _userList;
	}
	
	public boolean inProtectedNamespace(ContentName content) {
		return _namespace.isPrefixOf(content);
	}

	/**
	 * Get the latest key for a specified principal
	 * TODO shortcut slightly -- the principal we have cached might not meet the
	 * constraints of the link.
	 * @param principal the principal
	 * @return the public key object
	 * @throws IOException 
	 * @throws ContentDecodingException 
	 */
	public PublicKeyObject getLatestKeyForPrincipal(Link principal) throws ContentDecodingException, IOException {
		if (null == principal) {
			Log.info("Cannot retrieve key for empty principal.");
			return null;
		}
		PublicKeyObject pko = null;
		if (_groupManager.isGroup(principal)) {
			pko = _groupManager.getLatestPublicKeyForGroup(principal);
		} else {
			Log.info("Retrieving latest key for user: " + principal.targetName());
			LinkAuthenticator targetAuth = principal.targetAuthenticator();
			if (null != targetAuth) {
				pko = new PublicKeyObject(principal.targetName(), targetAuth.publisher(), handle());
			}
			else pko = new PublicKeyObject(principal.targetName(), handle());
		}
		return pko;
	}

	/**
	 * Creates the root ACL for _namespace.
	 * This initialization must be done before any other ACL or node key can be read or written.
	 * @param rootACL the root ACL
	 * @throws IOException 
	 * @throws ContentGoneException 
	 * @throws ContentNotReadyException 
	 * @throws ContentEncodingException 
	 * @throws InvalidKeyException 
	 */
	public void initializeNamespace(ACL rootACL) 
				throws InvalidKeyException, ContentEncodingException, ContentNotReadyException, 
						ContentGoneException, IOException {
		// generates the new node key		
		generateNewNodeKey(_namespace, null, rootACL);
		
		// write the root ACL
		ACLObject aclo = new ACLObject(GroupAccessControlProfile.aclName(_namespace), rootACL, handle());
		aclo.saveToRepository();
	}
	
	/**
	 * Retrieves the latest version of an ACL effective at this node, either stored
	 * here or at one of its ancestors.
	 * @param nodeName the name of the node
	 * @return the ACL object
	 * @throws IOException 
	 * @throws ContentDecodingException 
	 */
	public ACLObject getEffectiveACLObject(ContentName nodeName) throws ContentDecodingException, IOException {
		
		// Find the closest node that has a non-gone ACL
		ACLObject aclo = findAncestorWithACL(nodeName);
		if (null == aclo) {
			Log.warning("Unexpected: cannot find an ancestor of node " + nodeName + " that has an ACL.");
			throw new IOException("Unexpected: cannot find an ancestor of node " + nodeName + " that has an ACL.");	
		}
		return aclo;
	}

	private ACLObject findAncestorWithACL(ContentName dataNodeName) throws ContentDecodingException, IOException {

		ACLObject ancestorACLObject = null;
		ContentName parentName = dataNodeName;
		ContentName nextParentName = null;
		while (null == ancestorACLObject) {
			ancestorACLObject = getACLObjectForNodeIfExists(parentName);
			if ((null != ancestorACLObject) && (ancestorACLObject.isGone())) {
				Log.info("Found an ACL object at " + ancestorACLObject.getVersionedName() + " but its GONE.");
				ancestorACLObject = null;
			}
			nextParentName = parentName.parent();
			// stop looking once we're above our namespace, or if we've already checked the top level
			if (nextParentName.count() < _namespace.count() || parentName.count() == 0) {
				break;
			}
			parentName = nextParentName;
		}
		if (null == ancestorACLObject) {
			throw new IllegalStateException("No ACL available in ancestor tree for node : " + dataNodeName);
		}
		Log.info("Found ACL for " + dataNodeName + " at ancestor :" + ancestorACLObject.getVersionedName());
		return ancestorACLObject;
	}

	/**
	 * Try to pull an ACL for a particular node. If it doesn't exist, will time
	 * out. Use enumeration to decide whether to call this to avoid the timeout.
	 * @param aclNodeName the node name
	 * @return the ACL object
	 * @throws ContentDecodingException
	 * @throws IOException
	 */
	public ACLObject getACLObjectForNode(ContentName aclNodeName) 
				throws ContentDecodingException, IOException {
		
		// Get the latest version of the acl. We don't care so much about knowing what version it was.
		ACLObject aclo = new ACLObject(GroupAccessControlProfile.aclName(aclNodeName), handle());
		aclo.update();
		// if there is no update, this will probably throw an exception -- IO or XMLStream
		if (aclo.isGone()) {
			// treat as if no acl on node
			return null;
		}
		return aclo;
	}
	
	/**
	 * Try to pull an ACL for a specified node if it exists.
	 * @param aclNodeName the name of the node
	 * @return the ACL object
	 * @throws IOException 
	 * @throws ContentDecodingException 
	 */
	public ACLObject getACLObjectForNodeIfExists(ContentName aclNodeName) throws ContentDecodingException, IOException {
		
		EnumeratedNameList aclNameList = EnumeratedNameList.exists(GroupAccessControlProfile.aclName(aclNodeName), aclNodeName, handle());
		
		if (null != aclNameList) {
			ContentName aclName = new ContentName(GroupAccessControlProfile.aclName(aclNodeName));
			Log.info("Found latest version of acl for " + aclNodeName + " at " + aclName);
			ACLObject aclo = new ACLObject(aclName, handle());
			if (aclo.isGone())
				return null;
			return aclo;
		}
		Log.info("No ACL found on node: " + aclNodeName);
		return null;
	}
	
	/**
	 * Get the effective ACL for a node specified by its name
	 * @param nodeName the name of the node
	 * @return the effective ACL
	 * @throws ContentGoneException 
	 * @throws ContentNotReadyException 
	 * @throws ContentDecodingException
	 * @throws IOException
	 */
	public ACL getEffectiveACL(ContentName nodeName) 
			throws ContentNotReadyException, ContentGoneException, ContentDecodingException, IOException {
		ACLObject aclo = getEffectiveACLObject(nodeName);
		if (null != aclo) {
			return aclo.acl();
		}
		return null;
	}
	
	/**
	 * Adds an ACL to a node that doesn't have one, or replaces one that exists.
	 * Just writes, doesn't bother to look at any current ACL. Does need to pull
	 * the effective node key at this node, though, to wrap the old ENK in a new
	 * node key.
	 * 
	 * @param nodeName the name of the node
	 * @param newACL the new ACL
	 * @return
	 * @throws InvalidKeyException 
	 * @throws IOException 
	 * @throws ContentGoneException 
	 * @throws ContentNotReadyException 
	 * @throws InvalidCipherTextException 
	 */
	public ACL setACL(ContentName nodeName, ACL newACL) 
			throws AccessDeniedException, InvalidKeyException, ContentNotReadyException, ContentGoneException, IOException, InvalidCipherTextException {
		// Throws access denied exception if we can't read the old node key.
		NodeKey effectiveNodeKey = getEffectiveNodeKey(nodeName);
		// generates the new node key, wraps it under the new acl, and wraps the old node key
		generateNewNodeKey(nodeName, effectiveNodeKey, newACL);
		// write the acl
		ACLObject aclo = new ACLObject(GroupAccessControlProfile.aclName(nodeName), newACL, handle());
		aclo.saveToRepository();
		return aclo.acl();
	}
	
	/**
	 * Delete the ACL at this node if one exists, returning control to the
	 * next ACL upstream.
	 * We simply add a supserseded by block at this node, wrapping this key in the key of the upstream
	 * node. If we don't have read access at that node, throw AccessDeniedException.
	 * Then we write a GONE block here for the ACL, and a new node key version with a superseded by block.
	 * The superseded by block should probably be encrypted not with the ACL in force, but with the effective
	 * node key of the parent -- that will be derivable from the appropriate ACL, and will have the right semantics
	 * if a new ACL is interposed later. In the meantime, all the people with the newly in-force ancestor
	 * ACL should be able to read this content.
	 * @param nodeName
	 * @throws IOException 
	 * @throws ContentDecodingException 
	 * @throws InvalidKeyException 
	 * @throws InvalidCipherTextException 
	 */
	public void deleteACL(ContentName nodeName) 
			throws ContentDecodingException, IOException, InvalidKeyException, InvalidCipherTextException {
		
		// First, find ACL at this node if one exists.
		ACLObject thisNodeACL = getACLObjectForNodeIfExists(nodeName);
		if (null == thisNodeACL) {
			Log.info("Asked to delete ACL for node " + nodeName + " that doesn't have one. Doing nothing.");
			return;
		}
		Log.info("Deleting ACL for node " + nodeName + " latest version: " + thisNodeACL.getVersionedName());
		
		// Then, find the latest node key. This should not be a derived node key.
		NodeKey nk = getEffectiveNodeKey(nodeName);
		
		// Next, find the ACL that is in force after the deletion.
		ContentName parentName = nodeName.parent();
		NodeKey effectiveParentNodeKey = getLatestNodeKeyForNode(parentName);
		
		// Generate a superseded block for this node, wrapping its key in the parent.
		// TODO want to wrap key in parent's effective key, but can't point to that -- no way to name an
		// effective node key... need one.
		KeyDirectory.addSupersededByBlock(nk.storedNodeKeyName(), nk.nodeKey(), 
										  effectiveParentNodeKey.nodeName(), effectiveParentNodeKey.nodeKey(), handle());
		
		// Then mark the ACL as gone.
		thisNodeACL.saveToRepositoryAsGone();
	}
	
	/**
	 * Pulls the ACL for this node, if one exists, and modifies it to include
	 * the following changes, then stores the result using setACL, updating
	 * the node key if necessary in the process.
	 * 
	 * @param nodeName the name of the node
	 * @param ACLUpdates the updates to the ACL
	 * @return the updated ACL
	 * @throws IOException 
	 * @throws ContentDecodingException 
	 * @throws InvalidKeyException 
	 * @throws InvalidCipherTextException 
	 */
	public ACL updateACL(ContentName nodeName, ArrayList<ACL.ACLOperation> ACLUpdates) 
			throws ContentDecodingException, IOException, InvalidKeyException, InvalidCipherTextException {
		ACLObject currentACL = getACLObjectForNodeIfExists(nodeName);
		ACL newACL = null;
		
		if (null != currentACL) {
			newACL = currentACL.acl();
		}else{
			Log.info("Adding brand new ACL to node: " + nodeName);
			//TODO: if no operations is specified, then a new empty ACL is created...
			newACL = new ACL();
		}
		
		LinkedList<Link> newReaders = newACL.update(ACLUpdates);
		
		if ((null == newReaders) || (null == currentACL)) {
			// null newReaders means we revoked someone.
			// null currentACL means we're starting from scratch
			// Set the ACL and update the node key.
			return setACL(nodeName, newACL);
		}
		
		// If we get back a list of new readers, it means all we have to do
		// is add key blocks for them, not update the node key. (And it means
		// we have a node key for this node.)
		// Wait to save the new ACL till we are sure we're allowed to do this.
		KeyDirectory keyDirectory = null;
		try {
			// If we can't read the node key, we can't update. Get the effective node key.
			// Better be a node key here... and we'd better be allowed to read it.
			NodeKey latestNodeKey = getLatestNodeKeyForNode(nodeName);
			if (null == latestNodeKey) {
				Log.info("Cannot read the latest node key for " + nodeName);
				throw new AccessDeniedException("Cannot read the latest node key for " + nodeName);
			}
			
			keyDirectory = new KeyDirectory(this, latestNodeKey.storedNodeKeyName(), handle());

			for (Link principal : newReaders) {
				PublicKeyObject latestKey = getLatestKeyForPrincipal(principal);
				try {
					if (!latestKey.available()) {
						latestKey.wait(SystemConfiguration.getDefaultTimeout());
					}
				} catch (InterruptedException ex) {
					// do nothing
				}
				if (latestKey.available()) {
					Log.info("Adding wrapped key block for reader: " + latestKey.getVersionedName());
					try {
						keyDirectory.addWrappedKeyBlock(latestNodeKey.nodeKey(), latestKey.getVersionedName(), latestKey.publicKey());
					} catch (VersionMissingException e) {
						Log.warning("UNEXPECTED: latest key for prinicpal: " + latestKey.getVersionedName() + " has no version? Skipping.");
					}
				} else {
					// Do we use an old key or give up?
					Log.info("No key for " + principal + " found. Skipping.");
				}
			}
		} finally {
			if (null != keyDirectory) {
				keyDirectory.stopEnumerating();
			}
		}
		// If we got here, we got the node key we were updating, so we are allowed
		// to at least read this stuff (though maybe not write it). Save the acl.
		currentACL.saveToRepository(newACL);
		return newACL;
		
	}
	
	/**
	 * Add readers to a specified node	
	 * @param nodeName the name of the node
	 * @param newReaders the list of new readers
	 * @return the updated ACL
	 * @throws IOException 
	 * @throws InvalidCipherTextException 
	 * @throws ContentDecodingException 
	 * @throws InvalidKeyException 
	 */
	public ACL addReaders(ContentName nodeName, ArrayList<Link> newReaders) 
			throws InvalidKeyException, ContentDecodingException, InvalidCipherTextException, IOException {
		ArrayList<ACLOperation> ops = new ArrayList<ACLOperation>();
		for(Link reader : newReaders){
			ops.add(ACLOperation.addReaderOperation(reader));
		}
		return updateACL(nodeName, ops);
	}

	/**
	 * Remove readers from a specified node	
	 * @param nodeName the name of the node
	 * @param removedReaders the list of removed readers
	 * @return the updated ACL
	 * @throws IOException 
	 * @throws InvalidCipherTextException 
	 * @throws ContentDecodingException 
	 * @throws InvalidKeyException 
	 */
	public ACL removeReaders(ContentName nodeName, ArrayList<Link> removedReaders) 
			throws InvalidKeyException, ContentDecodingException, InvalidCipherTextException, IOException {
		ArrayList<ACLOperation> ops = new ArrayList<ACLOperation>();
		for(Link reader : removedReaders){
			ops.add(ACLOperation.removeReaderOperation(reader));
		}
		return updateACL(nodeName, ops);
	}

	
	/**
	 * Add writers to a specified node.
	 * @param nodeName the name of the node
	 * @param newWriters the list of new writers
	 * @return the updated ACL
	 * @throws IOException 
	 * @throws InvalidCipherTextException 
	 * @throws ContentDecodingException 
	 * @throws InvalidKeyException 
	 */
	public ACL addWriters(ContentName nodeName, ArrayList<Link> newWriters) 
			throws InvalidKeyException, ContentDecodingException, InvalidCipherTextException, IOException {
		ArrayList<ACLOperation> ops = new ArrayList<ACLOperation>();
		for(Link writer : newWriters){
			ops.add(ACLOperation.addWriterOperation(writer));
		}
		return updateACL(nodeName, ops);
	}
	
	/**
	 * Remove writers from a specified node.
	 * @param nodeName the name of the node
	 * @param removedWriters the list of removed writers
	 * @return the updated ACL
	 * @throws IOException 
	 * @throws InvalidCipherTextException 
	 * @throws ContentDecodingException 
	 * @throws InvalidKeyException 
	 */
	public ACL removeWriters(ContentName nodeName, ArrayList<Link> removedWriters) 
			throws InvalidKeyException, ContentDecodingException, InvalidCipherTextException, IOException {
		ArrayList<ACLOperation> ops = new ArrayList<ACLOperation>();
		for(Link writer : removedWriters){
			ops.add(ACLOperation.removeWriterOperation(writer));
		}
		return updateACL(nodeName, ops);
	}
	
	/**
	 * Add managers to a specified node
	 * @param nodeName the name of the node
	 * @param newManagers the list of new managers
	 * @return the updated ACL
	 * @throws IOException 
	 * @throws InvalidCipherTextException 
	 * @throws ContentDecodingException 
	 * @throws InvalidKeyException 
	 */
	public ACL addManagers(ContentName nodeName, ArrayList<Link> newManagers) 
			throws InvalidKeyException, ContentDecodingException, InvalidCipherTextException, IOException {
		ArrayList<ACLOperation> ops = new ArrayList<ACLOperation>();
		for(Link manager: newManagers){
			ops.add(ACLOperation.addManagerOperation(manager));
		}
		return updateACL(nodeName, ops);
	}
	
	/**
	 * Remove managers from a specified node
	 * @param nodeName the name of the node
	 * @param removedManagers the list of removed managers
	 * @return the updated ACL
	 * @throws IOException 
	 * @throws InvalidCipherTextException 
	 * @throws ContentDecodingException 
	 * @throws InvalidKeyException 
	 */
	public ACL removeManagers(ContentName nodeName, ArrayList<Link> removedManagers) 
			throws InvalidKeyException, ContentDecodingException, InvalidCipherTextException, IOException {
		ArrayList<ACLOperation> ops = new ArrayList<ACLOperation>();
		for(Link manager: removedManagers){
			ops.add(ACLOperation.removeManagerOperation(manager));
		}
		return updateACL(nodeName, ops);
	}
	
	/**
	 * Get the ancestor node key in force at this node (if we can decrypt it),
	 * including a key at this node itself. We use the fact that ACLs and
	 * node keys are co-located; if you have one, you have the other.
	 * @param nodeName the name of the node
	 * @return null means while node keys exist, we can't decrypt any of them --
	 *    we have no read access to this node (which implies no write access)
	 * @throws IOException 
	 * @throws ContentDecodingException 
	 * @throws InvalidCipherTextException 
	 * @throws AccessDeniedException 
	 * @throws InvalidKeyException 
	 * @throws IOException if something is wrong (e.g. no node keys at all)
	 */
	protected NodeKey findAncestorWithNodeKey(ContentName nodeName) 
			throws InvalidKeyException, AccessDeniedException, InvalidCipherTextException, 
					ContentDecodingException, IOException {
		// climb up looking for node keys, then make sure that one isn't GONE
		// if it isn't, call read-side routine to figure out how to decrypt it
		ACLObject effectiveACL = findAncestorWithACL(nodeName);
		if (null == effectiveACL) {
			Log.warning("Unexpected: could not find effective ACL for node: " + nodeName);
			throw new IOException("Unexpected: could not find effective ACL for node: " + nodeName);
		}
		Log.info("Got ACL named: " + effectiveACL.getVersionedName() + " attempting to retrieve node key from " + GroupAccessControlProfile.accessRoot(effectiveACL.getVersionedName()));
		return getLatestNodeKeyForNode(GroupAccessControlProfile.accessRoot(effectiveACL.getVersionedName()));
	}
	
	/**
	 * Write path: get the latest node key for a node.
	 * @param nodeName the name of the node
	 * @return the corresponding node key
	 * @throws IOException 
	 * @throws ContentDecodingException 
	 * @throws InvalidCipherTextException 
	 * @throws AccessDeniedException 
	 * @throws InvalidKeyException 
	 */
	public NodeKey getLatestNodeKeyForNode(ContentName nodeName) 
			throws InvalidKeyException, AccessDeniedException, InvalidCipherTextException, 
					ContentDecodingException, IOException {
		
		// Could do this using getLatestVersion...
		// First we need to figure out what the latest version is of the node key.
		ContentName nodeKeyVersionedName = 
			EnumeratedNameList.getLatestVersionName(GroupAccessControlProfile.nodeKeyName(nodeName), handle());
		// DKS TODO this may not handle ACL deletion correctly -- we need to make sure that this
		// key wasn't superseded by something that isn't a later version of itself.
		
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
	 * @return the node key
	 * @throws IOException 
	 * @throws ContentDecodingException 
	 * @throws InvalidCipherTextException 
	 * @throws AccessDeniedException 
	 * @throws InvalidKeyException 
	 */
	public NodeKey getSpecificNodeKey(ContentName nodeKeyName, byte [] nodeKeyIdentifier) 
			throws InvalidKeyException, AccessDeniedException, InvalidCipherTextException, 
					ContentDecodingException, IOException {
		
		if ((null == nodeKeyName) && (null == nodeKeyIdentifier)) {
			throw new IllegalArgumentException("Node key name and identifier cannot both be null!");
		}
		// We should know what node key to use (down to the version), but we have to find the specific
		// wrapped key copy we can decrypt. 
		NodeKey nk = getNodeKeyByVersionedName(nodeKeyName, nodeKeyIdentifier);
		if (null == nk) {
			Log.warning("No decryptable node key available at " + nodeKeyName + ", access denied.");
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
	 * @throws AccessDeniedException
	 * @throws ContentDecodingException 
	 * @throws InvalidCipherTextException 
	 * @throws InvalidKeyException 
	 */
	NodeKey getNodeKeyByVersionedName(ContentName nodeKeyName, byte [] nodeKeyIdentifier) 
			throws AccessDeniedException, InvalidKeyException, InvalidCipherTextException, 
					ContentDecodingException, IOException {

		NodeKey nk = null;
		KeyDirectory keyDirectory = null;
		try {

			keyDirectory = new KeyDirectory(this, nodeKeyName, handle());
			keyDirectory.waitForData();
			// this will handle the caching.
			Key unwrappedKey = keyDirectory.getUnwrappedKey(nodeKeyIdentifier);
			if (null != unwrappedKey) {
				nk = new NodeKey(nodeKeyName, unwrappedKey);
			} else {
				throw new AccessDeniedException("Access denied: cannot retrieve key " + DataUtils.printBytes(nodeKeyIdentifier) + " at name " + nodeKeyName);
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
	 * encrypt  content. Vertical chaining. Works if you ask for node which has
	 * a node key.
	 * TODO -- when called by writers, check to see if node key is dirty & update.
	 * @param nodeName
	 * @return
	 * @throws AccessDeniedException 
	 * @throws InvalidCipherTextException
	 * @throws ContentEncodingException 
	 * @throws ContentDecodingException
	 * @throws IOException
	 * @throws InvalidKeyException 
	 */
	public NodeKey getEffectiveNodeKey(ContentName nodeName) 
			throws AccessDeniedException, InvalidKeyException, InvalidCipherTextException, ContentEncodingException, 
					ContentDecodingException, IOException {
		// Get the ancestor node key in force at this node.
		NodeKey nodeKey = findAncestorWithNodeKey(nodeName);
		if (null == nodeKey) {
			throw new AccessDeniedException("Cannot retrieve node key for node: " + nodeName + ".");
		}
		Log.info("Found node key at " + nodeKey.storedNodeKeyName());
		NodeKey effectiveNodeKey = nodeKey.computeDescendantNodeKey(nodeName, nodeKeyLabel()); 
		Log.info("Computing effective node key for " + nodeName + " using stored node key " + effectiveNodeKey.storedNodeKeyName());
		return effectiveNodeKey;
	}
	
	/**
	 * Like #getEffectiveNodeKey(ContentName), except checks to see if node
	 * key is dirty and updates it if necessary.
	 * @param nodeName
	 * @return
	 * @throws AccessDeniedException
	 * @throws IOException 
	 * @throws ContentGoneException 
	 * @throws ContentNotReadyException 
	 * @throws ContentEncodingException 
	 * @throws ContentDecodingException 
	 * @throws AccessDeniedException, InvalidKeyException 
	 * @throws InvalidCipherTextException 
	 */
	public NodeKey getFreshEffectiveNodeKey(ContentName nodeName) 
			throws AccessDeniedException, InvalidKeyException, ContentDecodingException, 
					ContentEncodingException, ContentNotReadyException, ContentGoneException, IOException, InvalidCipherTextException {
		NodeKey nodeKey = findAncestorWithNodeKey(nodeName);
		if (null == nodeKey) {
			throw new AccessDeniedException("Cannot retrieve node key for node: " + nodeName + ".");
		}
		// This should be the latest node key; i.e. not superseded.
		if (nodeKeyIsDirty(nodeKey.storedNodeKeyName())) {
			Log.info("Found node key at " + nodeKey.storedNodeKeyName() + ", updating.");
			ContentName nodeKeyNodeName = GroupAccessControlProfile.accessRoot(nodeKey.storedNodeKeyName());
			ACLObject acl = getACLObjectForNode(nodeKeyNodeName);
			nodeKey = generateNewNodeKey(nodeKeyNodeName, nodeKey, acl.acl());
		} else {
			Log.info("Found node key at " + nodeKey.storedNodeKeyName());
		}
		NodeKey effectiveNodeKey = nodeKey.computeDescendantNodeKey(nodeName, nodeKeyLabel()); 
		Log.info("Computing effective node key for " + nodeName + " using stored node key " + effectiveNodeKey.storedNodeKeyName());
		return effectiveNodeKey;
	}
	
	/**
	 * Do we need to update this node key?
	 * First, we look to see whether or not we know the key is dirty -- i.e.
	 * does it have a superseded block (if it's gone, it will also have a 
	 * superseded block). If not, we have to really check...
	 * Basically, we look at all the entities this node key is encrypted for,
	 * and determine whether any of them have a new version of their public
	 * key. If so, the node key is dirty.
	 * 
	 * The initial implementation of this will be simple and slow -- iterating through
	 * groups and assuming the active object system will keep updating itself whenever
	 * a new key appears. Eventually, we might want an index directory of all the
	 * versions of keys, so that one name enumeration request might give us information
	 * about whether keys have been updated. (Or some kind of aggregate versioning,
	 * that tell us a) whether any groups have changed their versions, or b) just the
	 * ones we care about have.) 
	 * 
	 * This can be called by anyone -- the data about whether a node key is dirty
	 * is visible to anyone. Fixing a dirty node key requires access, though.
	 * @param theNodeKeyName this might be the name of the node where the NK is stored,
	 *    or the NK name itself.
	 *    We assume this exists -- that there at some point has been a node key here.
	 *    TODO ephemeral node key naming
	 * @return
	 * @throws IOException 
	 * @throws ContentDecodingException 
	 */
	public boolean nodeKeyIsDirty(ContentName theNodeKeyName) throws ContentDecodingException, IOException {

		// first, is this a node key name?
		if (!GroupAccessControlProfile.isNodeKeyName(theNodeKeyName)) {
			// assume it's a data node name.
			theNodeKeyName = GroupAccessControlProfile.nodeKeyName(theNodeKeyName);
		}
		// get the requested version of this node key; or if unversioned, get the latest.
		KeyDirectory nodeKeyDirectory = null;
		try {
			nodeKeyDirectory = new KeyDirectory(this, theNodeKeyName, handle());
			nodeKeyDirectory.waitForData();

			if (null == nodeKeyDirectory) {
				throw new IOException("Cannot get node key directory for : " + theNodeKeyName);
			}
			if (nodeKeyDirectory.hasSupersededBlock()) {
				return true;
			}
			for (PrincipalInfo principal : nodeKeyDirectory.getCopyOfPrincipals().values()) {
				if (principal.isGroup()) {
					Group theGroup = groupManager().getGroup(principal.friendlyName());
					if (theGroup.publicKeyVersion().after(principal.versionTimestamp())) {
						return true;
					}
				} else {
					// DKS TODO -- for now, don't handle versioning of non-group keys
					Log.info("User key for " + principal.friendlyName() + ", not checking version.");
					// Technically, we're not handling versioning for user keys, but be nice. Start
					// by seeing if we have a link to the key in our user space.
					// If the principal isn't available in our enumerated list, have to go get its key
					// from the wrapped key object.
				}
			}			
			return false;
			
		} finally {
			if (null != nodeKeyDirectory)
				nodeKeyDirectory.stopEnumerating();
		}
	}
	
	/**
	 * Would we update this data key if we were doing reencryption?
	 * This one is simpler -- what node key is the data key encrypted under, and is
	 * that node key dirty?
	 * 
	 * This can be called by anyone -- the data about whether a data key is dirty
	 * is visible to anyone. Fixing a dirty key requires access, though.
	 * 
	 * @param dataName
	 * @return
	 * @throws IOException 
	 * @throws ContentNotReadyException 
	 * @throws ContentDecodingException
	 */
	public boolean dataKeyIsDirty(ContentName dataName) throws ContentNotReadyException, IOException {
		// TODO -- do we need to check whether there *is* a key?
		// The trick: we need the header information in the wrapped key; we don't need to unwrap it.
		// ephemeral key naming
		WrappedKeyObject wrappedDataKey = new WrappedKeyObject(GroupAccessControlProfile.dataKeyName(dataName), handle());
		return nodeKeyIsDirty(wrappedDataKey.wrappedKey().wrappingKeyName());
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
	 * @throws ContentDecodingException 
	 * @throws InvalidCipherTextException 
	 * @throws InvalidKeyException 
	 */
	protected NodeKey getNodeKeyUsingInterposedACL(ContentName dataNodeName,
			ContentName wrappingKeyName, byte[] wrappingKeyIdentifier) 
			throws ContentDecodingException, IOException, InvalidKeyException, InvalidCipherTextException {
		ACLObject nearestACL = findAncestorWithACL(dataNodeName);
		
		if (null == nearestACL) {
			Log.warning("Unexpected -- node with no ancestor ACL: " + dataNodeName);
			// no dice
			return null;
		}
		
		if (nearestACL.equals(GroupAccessControlProfile.accessRoot(wrappingKeyName))) {
			Log.info("Node key: " + wrappingKeyName + " is the nearest ACL to " + dataNodeName);
			return null;
		}
		
		NodeKey nk = getLatestNodeKeyForNode(GroupAccessControlProfile.accessRoot(nearestACL.getVersionedName()));
		return nk;
	}	
	
	/**
	 * Make a new node key and encrypt it under the given ACL.
	 * If there is a previous node key (oldEffectiveNodeKey not null), it is wrapped in the new node key.
	 * Put all the blocks into the aggregating writer, but don't flush.
	 * 
	 * @param nodeName
	 * @param oldEffectiveNodeKey
	 * @param effectiveACL
	 * @return
	 * @throws IOException 
	 * @throws ContentGoneException 
	 * @throws ContentNotReadyException 
	 * @throws ContentEncodingException 
	 * @throws InvalidKeyException 
	 */
	protected NodeKey generateNewNodeKey(ContentName nodeName, NodeKey oldEffectiveNodeKey, ACL effectiveACL) 
			throws InvalidKeyException, ContentEncodingException, ContentNotReadyException, 
					ContentGoneException, IOException {
		// Get the name of the key directory; this is unversioned. Make a new version of it.
		ContentName nodeKeyDirectoryName = VersioningProfile.addVersion(GroupAccessControlProfile.nodeKeyName(nodeName));
		Log.info("Generating new node key " + nodeKeyDirectoryName);
		
		// Now, generate the node key.
		if (effectiveACL.publiclyReadable()) {
			// TODO Put something here that will represent public; need to then make it so that key-reading code will do
			// the right thing when it encounters it.
			throw new UnsupportedOperationException("Need to implement public node key representation!");
		}
		
		byte [] nodeKeyBytes = new byte[NodeKey.DEFAULT_NODE_KEY_LENGTH];
		_random.nextBytes(nodeKeyBytes);
		Key nodeKey = new SecretKeySpec(nodeKeyBytes, NodeKey.DEFAULT_NODE_KEY_ALGORITHM);
		
		// Now, wrap it under the keys listed in its ACL.
		
		// Make a key directory. If we give it a versioned name, it will start enumerating it, but won't block.
		KeyDirectory nodeKeyDirectory = null;
		NodeKey theNodeKey = null;
		try {
			nodeKeyDirectory = new KeyDirectory(this, nodeKeyDirectoryName, handle());
			theNodeKey = new NodeKey(nodeKeyDirectoryName, nodeKey);
			// Add a key block for every reader on the ACL. As managers and writers can read, they are all readers.
			// TODO -- pulling public keys here; could be slow; might want to manage concurrency over acl.
			for (Link aclEntry : effectiveACL.contents()) {
				PublicKeyObject entryPublicKey = null;
				if (groupManager().isGroup(aclEntry)) {
					entryPublicKey = groupManager().getLatestPublicKeyForGroup(aclEntry);
				} else {
					// Calls update. Will get latest version if name unversioned.
					if (aclEntry.targetAuthenticator() != null)
						entryPublicKey = new PublicKeyObject(aclEntry.targetName(), aclEntry.targetAuthenticator().publisher(), handle());
					else entryPublicKey = new PublicKeyObject(aclEntry.targetName(), handle());
				}
				try {
					nodeKeyDirectory.addWrappedKeyBlock(nodeKey, entryPublicKey.getVersionedName(), entryPublicKey.publicKey());
				} catch (VersionMissingException ve) {
					Log.logException("Unexpected version missing exception for public key " + entryPublicKey.getVersionedName(), ve);
					throw new IOException("Unexpected version missing exception for public key " + entryPublicKey.getVersionedName() + ": " + ve);
				}
			}

			// Add a superseded by block to the previous key. Two cases: old effective node key is at the same level
			// as us (we are superseding it entirely), or we are interposing a key (old key is above or below us).
			// OK, here are the options:
			// Replaced node key is a derived node key -- we are interposing an ACL
			// Replaced node key is a stored node key 
			//	 -- we are updating that node key to a new version
			// 			NK/vn replaced by NK/vn+k -- new node key will be later version of previous node key
			//   -- we don't get called if we are deleting an ACL here -- no new node key is added.
			if (oldEffectiveNodeKey != null) {
				if (oldEffectiveNodeKey.isDerivedNodeKey()) {
					// Interposing an ACL. 
					// Add a previous key block wrapping the previous key. There is nothing to link to.
					nodeKeyDirectory.addPreviousKeyBlock(oldEffectiveNodeKey.nodeKey(), nodeKeyDirectoryName, nodeKey);
				} else {
					try {
						if (!VersioningProfile.isLaterVersionOf(nodeKeyDirectoryName, oldEffectiveNodeKey.storedNodeKeyName())) {
							Log.warning("Unexpected: replacing node key stored at " + oldEffectiveNodeKey.storedNodeKeyName() + " with new node key " + 
									nodeKeyDirectoryName + " but latter is not later version of the former.");
						}
					} catch (VersionMissingException vex) {
						Log.warning("Very unexpected version missing exception when replacing node key : " + vex);
					}
					// Add a previous key link to the old version of the key.
					// TODO do we need to add publisher?
					nodeKeyDirectory.waitForData();
					nodeKeyDirectory.addPreviousKeyLink(oldEffectiveNodeKey.storedNodeKeyName(), null);
					// OK, just add superseded-by block to the old directory.
					KeyDirectory.addSupersededByBlock(oldEffectiveNodeKey.storedNodeKeyName(), oldEffectiveNodeKey.nodeKey(), 
							nodeKeyDirectoryName, nodeKey, handle());
				}
			}
		} finally {
			if (null != nodeKeyDirectory) {
				nodeKeyDirectory.stopEnumerating();
			}
		}
		// Return the key for use, along with its name.
		return theNodeKey;
	}
	
	/**
	 * 
	 * @param nodeName
	 * @param wko
	 * @return
	 * @throws ContentNotReadyException
	 * @throws ContentGoneException
	 * @throws InvalidKeyException
	 * @throws ContentEncodingException
	 * @throws ContentDecodingException
	 * @throws IOException
	 * @throws InvalidCipherTextException
	 */
	public NodeKey getNodeKeyForObject(ContentName nodeName, WrappedKeyObject wko) 
			throws ContentNotReadyException, ContentGoneException, InvalidKeyException, ContentEncodingException,
					ContentDecodingException, IOException, InvalidCipherTextException {
		
		// First, we go and look for the node key where the data key suggests
		// it should be, and attempt to decrypt it from there.
		NodeKey nk = null;
		try {
			nk = getSpecificNodeKey(wko.wrappedKey().wrappingKeyName(), 
										wko.wrappedKey().wrappingKeyIdentifier());
		} catch (AccessDeniedException ex) {
			// ignore
		}
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
	 * follow the steps in the comments to #generateAndStoreDataKey(ContentName).
	 * @param dataNodeName
	 * @return
	 * @throws IOException 
	 * @throws ContentDecodingException 
	 * @throws InvalidCipherTextException 
	 * @throws InvalidKeyException 
	 */
	public byte [] getDataKey(ContentName dataNodeName) 
			throws ContentDecodingException, IOException, InvalidKeyException, InvalidCipherTextException {
		WrappedKeyObject wdko = new WrappedKeyObject(GroupAccessControlProfile.dataKeyName(dataNodeName), handle());
		if (null == wdko.wrappedKey()) {
			Log.warning("Could not retrieve data key for node: " + dataNodeName);
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
	 * the current effective node key, and wrapping this data key in it. If the
	 * current node key is dirty, this causes a new one to be generated.
	 * @param dataNodeName
	 * @param newRandomDataKey
	 * @throws AccessDeniedException 
	 * @throws InvalidKeyException 
	 * @throws ContentEncodingException
	 * @throws IOException
	 * @throws InvalidCipherTextException 
	 */
	public void storeDataKey(ContentName dataNodeName, Key newRandomDataKey) 
			throws AccessDeniedException, InvalidKeyException, ContentEncodingException, IOException, InvalidCipherTextException {
		NodeKey effectiveNodeKey = getFreshEffectiveNodeKey(dataNodeName);
		if (null == effectiveNodeKey) {
			throw new AccessDeniedException("Cannot retrieve effective node key for node: " + dataNodeName + ".");
		}
		Log.info("Wrapping data key for node: " + dataNodeName + " with effective node key for node: " + 
							  effectiveNodeKey.nodeName() + " derived from stored node key for node: " + 
							  effectiveNodeKey.storedNodeKeyName());
		// TODO another case where we're wrapping in an effective node key but labeling it with
		// the stored node key information. This will work except if we interpose an ACL in the meantime -- 
		// we may not have the information necessary to figure out how to decrypt.
		WrappedKey wrappedDataKey = WrappedKey.wrapKey(newRandomDataKey, 
													   null, dataKeyLabel(), 
													   effectiveNodeKey.nodeKey());
		wrappedDataKey.setWrappingKeyIdentifier(effectiveNodeKey.storedNodeKeyID());
		wrappedDataKey.setWrappingKeyName(effectiveNodeKey.storedNodeKeyName());
		
		storeKeyContent(GroupAccessControlProfile.dataKeyName(dataNodeName), wrappedDataKey);
	}
	
	/**
	 * Generate a random data key, store it, and return it to use to derive keys to encrypt
	 * content. All that's left is to call
	 * byte [] randomDataKey = generateAndStoreDataKey(dataNodeName);
	 * byte [][] keyandiv = 
	 * 		KeyDerivationFunction.DeriveKeyForObject(randomDataKey, keyLabel, 
	 * 												 dataNodeName, dataPublisherPublicKeyDigest)
	 * and then give keyandiv to the segmenter to encrypt the data.
	 * @throws IOException 
	 * @throws ContentEncodingException 
	 * @throws AccessDeniedException 
	 * @throws InvalidKeyException 
	 * @throws InvalidCipherTextException 
	 **/
	public Key generateAndStoreDataKey(ContentName dataNodeName) 
			throws InvalidKeyException, AccessDeniedException, ContentEncodingException, IOException, InvalidCipherTextException {
		// Generate new random data key of appropriate length
		byte [] dataKeyBytes = new byte[DEFAULT_DATA_KEY_LENGTH];
		_random.nextBytes(dataKeyBytes);
		Key dataKey = new SecretKeySpec(dataKeyBytes, DEFAULT_DATA_KEY_ALGORITHM);
		storeDataKey(GroupAccessControlProfile.dataKeyName(dataNodeName), dataKey);
		return dataKey;
	}
	
	/**
	 * Actual output functions. 
	 * @param dataNodeName -- the content node for whom this is the data key.
	 * @param wrappedDataKey
	 * @throws IOException 
	 * @throws ContentEncodingException 
	 */
	private void storeKeyContent(ContentName dataNodeName,
								 WrappedKey wrappedKey) throws ContentEncodingException, IOException {
		WrappedKeyObject wko = new WrappedKeyObject(GroupAccessControlProfile.dataKeyName(dataNodeName), wrappedKey, handle());
		wko.saveToRepository();
	}
	
	/**
	 * add a private key to _keyCache
	 * @param keyName
	 * @param publicKeyIdentifier
	 * @param pk
	 */
	void addPrivateKey(ContentName keyName, byte [] publicKeyIdentifier, PrivateKey pk) {
		_keyCache.addPrivateKey(keyName, publicKeyIdentifier, pk);
	}

	/**
	 * add my private key to _keyCache
	 * @param publicKeyIdentifier
	 * @param pk
	 */
	void addMyPrivateKey(byte [] publicKeyIdentifier, PrivateKey pk) {
		_keyCache.addMyPrivateKey(publicKeyIdentifier, pk);
	}
	
	/**
	 * add a key to _keyCache
	 * @param name
	 * @param key
	 */
	public void addKey(ContentName name, Key key) {
		_keyCache.addKey(name, key);
	}

	
 	/**
	 * Given the name of a content stream, this function verifies that access is allowed and returns the
	 * keys required to decrypt the stream.
	 * @param dataNodeName The name of the stream, including version component, but excluding
	 * segment component.
	 * @return Returns the keys ready to be used for en/decryption, or null if the content is not encrypted.
	 * @throws IOException 
	 * @throws InvalidKeyException 
	 * @throws InvalidCipherTextException 
	 * @throws AccessDeniedException 
	 */
	public ContentKeys getContentKeys(ContentName dataNodeName, PublisherPublicKeyDigest publisher) 
        throws InvalidKeyException, InvalidCipherTextException, AccessDeniedException, IOException {
		byte [] dataKey = getDataKey(dataNodeName);
		if (null == dataKey)
			return null;
		return KeyDerivationFunction.DeriveKeysForObject(dataKey, DATA_KEY_LABEL, dataNodeName, publisher);
	}

	/**
	 * Called when a stream is opened for reading, to determine if the name is under a root ACL, and
	 * if so find or create an AccessControlManager, and get keys for access. Only called if
	 * content is encrypted.
	 * @param name name of the stream to be opened.
	 * @param publisher publisher of the stream to be opened.
	 * @param library CCN Library instance to use for any network operations.
	 * @return If the stream is under access control then keys to decrypt the data are returned if it's
	 * encrypted. If the stream is not under access control (no Root ACL block can be found) then null is
	 * returned.
	 * @throws IOException if a problem happens getting keys.
	 */
	public static ContentKeys keysForInput(ContentName name, PublisherPublicKeyDigest publisher, CCNHandle handle) 
						throws IOException {
		GroupAccessControlManager acm;
		try {
			acm = NamespaceManager.findACM(name, handle);
			if (acm != null) {
				Log.info("keysForInput: retrieving key for data node {0}", name);
				return acm.getContentKeys(name, publisher);
			}
		} catch (ConfigurationException e) {
			// TODO use 1.6 constuctors that take nested exceptions when can move off 1.5
			throw new IOException(e.getClass().getName() + ": Opening stream for input: " + e.getMessage());
		} catch (InvalidCipherTextException e) {
			// TODO use 1.6 constuctors that take nested exceptions when can move off 1.5
			throw new IOException(e.getClass().getName() + ": Opening stream for input: " + e.getMessage());
		} catch (InvalidKeyException e) {
			// TODO use 1.6 constuctors that take nested exceptions when can move off 1.5
			throw new IOException(e.getClass().getName() + ": Opening stream for input: " + e.getMessage());
		}
		return null;
	}

	/**
	 * Get keys to encrypt content as its' written, if that content is to be protected.
	 * @param name
	 * @param publisher
	 * @param handle
	 * @return
	 * @throws IOException
	 */
	public static ContentKeys keysForOutput(ContentName name, PublisherPublicKeyDigest publisher, CCNHandle handle) 
				throws IOException {
		GroupAccessControlManager acm;
		try {
			acm = NamespaceManager.findACM(name, handle);
			if ((acm != null) && (acm.isProtectedContent(name, publisher, handle))) {
				Log.info("keysForOutput: generating new data key for data node {0}", name);
				Key dataKey = acm.generateAndStoreDataKey(name);
				return KeyDerivationFunction.DeriveKeysForObject(dataKey.getEncoded(), DATA_KEY_LABEL, name, publisher);
			}
		} catch (ConfigurationException e) {
			// TODO use 1.6 constuctors that take nested exceptions when can move off 1.5
			throw new IOException(e.getClass().getName() + ": Opening stream for input: " + e.getMessage());
		} catch (InvalidCipherTextException e) {
			// TODO use 1.6 constuctors that take nested exceptions when can move off 1.5
			throw new IOException(e.getClass().getName() + ": Opening stream for input: " + e.getMessage());
		} catch (InvalidKeyException e) {
			// TODO use 1.6 constuctors that take nested exceptions when can move off 1.5
			throw new IOException(e.getClass().getName() + ": Opening stream for input: " + e.getMessage());
		}
		return null;
	}
	
	/**
	 * Allow AccessControlManagers to specify some content is not to be protected; for example,
	 * access control lists are not themselves encrypted. 
	 * TODO: should headers be exempt from encryption?
	 */
	public boolean isProtectedContent(ContentName name, PublisherPublicKeyDigest publisher, CCNHandle hande) {
		
		if (!inProtectedNamespace(name)) {
			return false;
		}
		
		if (GroupAccessControlProfile.isAccessName(name)) {
			// Don't encrypt the access control metadata itself, or we couldn't get the
			// keys to decrypt the other stuff.
			return false;
		}
		return true;
	}

}	
