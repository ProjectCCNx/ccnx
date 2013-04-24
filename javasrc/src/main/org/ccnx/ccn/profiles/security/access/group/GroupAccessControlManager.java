/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008-2012 Palo Alto Research Center, Inc.
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
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.TreeMap;
import java.util.logging.Level;

import javax.crypto.spec.SecretKeySpec;

import org.bouncycastle.crypto.InvalidCipherTextException;
import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.KeyManager;
import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.impl.CCNFlowControl.SaveType;
import org.ccnx.ccn.impl.support.ByteArrayCompare;
import org.ccnx.ccn.impl.support.DataUtils;
import org.ccnx.ccn.impl.support.Log;
import static org.ccnx.ccn.impl.support.Log.*;
import static org.ccnx.ccn.io.content.KeyDirectory.PREVIOUS_KEY;

import org.ccnx.ccn.impl.support.Tuple;
import org.ccnx.ccn.io.content.ContentDecodingException;
import org.ccnx.ccn.io.content.ContentEncodingException;
import org.ccnx.ccn.io.content.ContentGoneException;
import org.ccnx.ccn.io.content.ContentNotReadyException;
import org.ccnx.ccn.io.content.KeyValueSet;
import org.ccnx.ccn.io.content.Link;
import org.ccnx.ccn.io.content.LinkAuthenticator;
import org.ccnx.ccn.io.content.PublicKeyObject;
import org.ccnx.ccn.io.content.WrappedKey.WrappedKeyObject;
import org.ccnx.ccn.profiles.VersionMissingException;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.profiles.namespace.NamespaceProfile;
import org.ccnx.ccn.profiles.namespace.ParameterizedName;
import org.ccnx.ccn.profiles.search.LatestVersionPathfinder;
import org.ccnx.ccn.profiles.search.Pathfinder;
import org.ccnx.ccn.profiles.search.Pathfinder.SearchResults;
import org.ccnx.ccn.profiles.security.access.AccessControlManager;
import org.ccnx.ccn.profiles.security.access.AccessControlPolicyMarker;
import org.ccnx.ccn.profiles.security.access.AccessControlProfile;
import org.ccnx.ccn.profiles.security.access.AccessDeniedException;
import org.ccnx.ccn.profiles.security.access.AccessControlPolicyMarker.AccessControlPolicyMarkerObject;
import org.ccnx.ccn.profiles.security.access.group.ACL.ACLObject;
import org.ccnx.ccn.profiles.security.access.group.ACL.ACLOperation;
import org.ccnx.ccn.profiles.security.access.group.GroupAccessControlProfile.PrincipalInfo;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;
import org.ccnx.ccn.protocol.SignedInfo.ContentType;


/**
 * This class implements a basic Group-based access control scheme. For full details,
 * see the CCNx Access Control Specification. Management of Groups and Group members is
 * handled by the GroupManager and Group classes. This class handles management and updating
 * of access control (node) keys stored in the name tree, and any operation that requires 
 * crawling that tree -- looking at more than one node at once. Operations on single nodes
 * are handled by individual component classes (e.g. NodeKey).
 * 
 * TODO refactor this class and its use slightly; right now it is capable of handling multpile
 * group and user namespaces, but it itself is limited to a single content namespace. This
 * means that we have multiple GACMs, one per protected namespace; we should refactor
 * so that we have one ACM of each type, and each manages multiple namespaces. This is
 * not a large change, and might be more efficient.
 * 
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
 * We could name the node key blocks &lt;prefix&gt;/<access marker>/NK/\#version/&lt;group name&gt;:&lt;group key id&gt;,
 * if we could match on partial components, but we can't.
 * 
 * We can name the node key blocks &lt;prefix&gt;/<access marker>/NK/\#version/&lt;group key id&gt; with
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
public class GroupAccessControlManager extends AccessControlManager {

	/**
	 * Marker in a Root object that this is the profile we want.
	 */
	public static final String PROFILE_NAME_STRING = "/ccnx.org/ccn/profiles/security/access/group/GroupAccessControlProfile";

	/**
	 * This algorithm must be capable of key wrap (RSA, ElGamal, etc).
	 */
	public static final String DEFAULT_GROUP_KEY_ALGORITHM = "RSA";
	public static final int DEFAULT_GROUP_KEY_LENGTH = 1024;

	public static final String NODE_KEY_LABEL = "Node Key";

	private ArrayList<ParameterizedName> _userStorage = new ArrayList<ParameterizedName>();
	private TreeMap<byte[], ParameterizedName> _hashToUserStorageMap = new TreeMap<byte[], ParameterizedName>(byteArrayComparator);
	private ArrayList<GroupManager> _groupManager = new ArrayList<GroupManager>();
	protected static Comparator<byte[]> byteArrayComparator = new ByteArrayCompare();
	private TreeMap<byte[], GroupManager> hashToGroupManagerMap = new TreeMap<byte[], GroupManager>(byteArrayComparator);
	private HashMap<ContentName, GroupManager> prefixToGroupManagerMap = new HashMap<ContentName, GroupManager>();
	private HashSet<ContentName> _myIdentities = new HashSet<ContentName>();

	public GroupAccessControlManager() {
		// must call initialize
	}

	public GroupAccessControlManager(ContentName namespace) throws IOException {
		this(namespace, null);	
	}

	public GroupAccessControlManager(ContentName namespace, CCNHandle handle) throws IOException {
		this(namespace, GroupAccessControlProfile.groupNamespaceName(namespace), 
				GroupAccessControlProfile.userNamespaceName(namespace), handle);
	}

	public GroupAccessControlManager(ContentName namespace, ContentName groupStorage, ContentName userStorage) throws IOException {
		this(namespace, groupStorage, userStorage, null);
	}

	public GroupAccessControlManager(ContentName namespace, ContentName groupStorage, 
									 ContentName userStorage, CCNHandle handle) 
			throws IOException {		
		this(namespace, new ContentName[]{groupStorage}, new ContentName[]{userStorage}, handle);
	}

	public GroupAccessControlManager(ContentName namespace, ContentName[] groupStorage, 
									  ContentName[] userStorage, CCNHandle handle) 
				throws IOException {
		initialize(namespace, groupStorage, userStorage, handle);
	}
	
	/**
	 * Type-specific method to initialize group-based access control for a namespace.
	 * Other subclasses of AccessControlManager, including subtypes of this one, should
	 * roll their own if necessary.
	 * This creates the type of access control manager specified in the policy; as long
  	 * as it is a subtype of GroupAccessControlManager it will work fine. The subtype
	 * can specialize initializeNamespace to do additional setup if necessary.
	 * @param namespace
	 * @param groupStorage
	 * @param userStorage
	 * @param handle
	 * @throws IOException
	 */
	public static AccessControlManager create(ContentName name, ContentName profileName, 
			ACL acl, ArrayList<ParameterizedName> parameterizedNames,
			KeyValueSet parameters, SaveType saveType, CCNHandle handle) throws IOException, InvalidKeyException {
		
		GroupAccessControlManager gacm = (GroupAccessControlManager)AccessControlPolicyMarker.create(
				name, profileName, parameterizedNames, parameters, saveType, handle);
		
		// create ACL and NK at the root of the namespace under access control
		gacm.initializeNamespace(acl);
		return gacm;
	}
	
	
	private void initialize(ContentName namespace, ContentName[] groupStorage, 
							ContentName[] userStorage, CCNHandle handle) 
				throws IOException {
		ArrayList<ParameterizedName> parameterizedNames = new ArrayList<ParameterizedName>();
		for (ContentName uStorage: userStorage) {
			if (null == uStorage)
				continue;
			ParameterizedName pName = new ParameterizedName(GroupAccessControlProfile.USER_LABEL, uStorage, null);
			parameterizedNames.add(pName);
		}
		for (ContentName gStorage: groupStorage) {
			if (null == gStorage)
				continue;
			ParameterizedName pName = new ParameterizedName(GroupAccessControlProfile.GROUP_LABEL, gStorage, null);
			parameterizedNames.add(pName);
		}
		if (null == handle) handle = CCNHandle.getHandle();
		AccessControlPolicyMarker r;
		try {
			r = new AccessControlPolicyMarker(ContentName.fromNative(GroupAccessControlManager.PROFILE_NAME_STRING), parameterizedNames, null);
		} catch (MalformedContentNameStringException e) {
			throw new IOException("MalformedContentNameStringException parsing built-in profile name: " + GroupAccessControlManager.PROFILE_NAME_STRING + ": " + e);
		}
		ContentName policyPrefix = NamespaceProfile.policyNamespace(namespace);
		ContentName policyMarkerName = AccessControlProfile.getAccessControlPolicyName(policyPrefix);
		AccessControlPolicyMarkerObject policyInformation = new AccessControlPolicyMarkerObject(policyMarkerName, r, SaveType.REPOSITORY, handle);
		initialize(policyInformation, handle);				
	}

	@Override
	public boolean initialize(AccessControlPolicyMarkerObject policyInformation, CCNHandle handle) 
				throws IOException {
		if (null == handle) {
			_handle = CCNHandle.getHandle();
		} else {
			_handle = handle;
		}

		_policy = policyInformation;
		
		// set up information based on contents of policy
		// also need a static method/command line program to create a Root with the right types of information
		// for this access control manager type
		int componentCount = policyInformation.getBaseName().count();
		componentCount -= NamespaceProfile.policyPostfix().count(); 
		componentCount -= 1;
		_namespace = policyInformation.getBaseName().cut(componentCount);

		ArrayList<ParameterizedName> parameterizedNames = policyInformation.policy().parameterizedNames();
		for (ParameterizedName pName: parameterizedNames) {
			String label = pName.label();
			if (label.equals(GroupAccessControlProfile.GROUP_LABEL)) {
				registerGroupStorage(pName);
			} else if (label.equals(GroupAccessControlProfile.USER_LABEL)) {
				_userStorage.add(pName);
			}
		}
		return true;
	}

	public GroupManager groupManager() {
		if (_groupManager.size() > 1) throw new RuntimeException("A group manager can only be retrieved by name when there are more than one.");
		return _groupManager.get(0); 	
	}
	
	public void registerGroupStorage(ContentName groupStorage) throws IOException {
		ParameterizedName pName = new ParameterizedName(GroupAccessControlProfile.GROUP_LABEL, groupStorage, null);
		registerGroupStorage(pName);
	}
	
	public void registerGroupStorage(ParameterizedName pName) {
		GroupManager gm = new GroupManager(this, pName, _handle);
		_groupManager.add(gm);
		byte[] distinguishingHash = GroupAccessControlProfile.PrincipalInfo.contentPrefixToDistinguishingHash(pName.prefix());
		hashToGroupManagerMap.put(distinguishingHash, gm);
		prefixToGroupManagerMap.put(pName.prefix(), gm);			
	}
	
	public void registerUserStorage(ContentName userStorage) throws ContentEncodingException {
		ParameterizedName pName = new ParameterizedName(GroupAccessControlProfile.USER_LABEL, userStorage, null);
		registerUserStorage(pName);
	}
	
	public void registerUserStorage(ParameterizedName userStorage) {
		_userStorage.add(userStorage);
		_hashToUserStorageMap.put(PrincipalInfo.contentPrefixToDistinguishingHash(userStorage.prefix()), userStorage);
	}

	public ParameterizedName getUserStorage(ContentName userName) {
		for (ParameterizedName storage : _userStorage) {
			if (storage.prefix().isPrefixOf(userName)) {
				if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.INFO)){
					Log.info(Log.FAC_ACCESSCONTROL, "Found user storage {0} for user name {1}.", storage, userName);
				}
				return storage;
			}
		}
		return null;
	}
	
	public ParameterizedName getUserStorage(byte [] distinguisingHash) {
		return _hashToUserStorageMap.get(distinguisingHash);
	}
	
	public GroupManager groupManager(byte[] distinguishingHash) {
		GroupManager gm = hashToGroupManagerMap.get(distinguishingHash);
		if (gm == null) {
			if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.INFO)) {
				Log.info(Log.FAC_ACCESSCONTROL, "Failed to retrieve a group manager with distinguishing hash " + DataUtils.printHexBytes(distinguishingHash));
			}
		}
		return gm;
	}

	public GroupManager groupManager(ContentName prefixName) {
		GroupManager gm = prefixToGroupManagerMap.get(prefixName);
		if (gm == null) {
			if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.INFO)) {
				Log.info(Log.FAC_ACCESSCONTROL, "GroupAccessControlManager: failed to retrieve a group manager with prefix " + prefixName);
			}
		}
		return gm;		
	}

	public boolean isGroupName(ContentName principalPublicKeyName) {
		for (GroupManager gm: _groupManager) {
			if (gm.isGroup(principalPublicKeyName)) return true;
		}
		return false;
	}
	
	public ContentName groupPublicKeyName(ContentName principalName) {
		for (GroupManager gm: _groupManager) {
			if (gm.isGroup(principalName)) {
				return GroupAccessControlProfile.groupPublicKeyName(gm.getGroupStorage(), principalName);
			}
		}
		return null;
	}
	
	public ContentName userPublicKeyName(ContentName principalName) {
		for (ParameterizedName storage : _userStorage) {
			if (storage.prefix().isPrefixOf(principalName)) {
				// MLAC should return parameterized user key name.
				return GroupAccessControlProfile.userPublicKeyName(storage, principalName);
			}
		}
		return null;
	}

	public Tuple<ContentName, String> parsePrefixAndFriendlyNameFromPublicKeyName(ContentName principalPublicKeyName) {
		// for each group manager/user namespace, there is a prefix, then a "friendly" name component,
		// then optionally a postfix that gets tacked on to go from the prefix to the public key
		// e.g. Alice's key in the user namespace might be:
		// /parc.com/Users/Alice/Keys/EncryptionKey
		// where the distinguising prefix would be computed from /parc.com/Users, Alice is the friendly
		// name, and the rest is stored in the group manager for /parc.com/Users, generated out of the 
		// relevant entry in the Roots spec, and just used in a function to go from (distinguishing hash, friendly name)
		// to key name

		// loop through the group managers, see if the prefix matches, if so, pull that prefix, and take
		// the component after that prefix as the friendly name

		for (GroupManager gm: _groupManager) {
			if (gm.isGroup(principalPublicKeyName)) {
				ContentName prefix = gm.getGroupStorage().prefix();
				String friendlyName = principalPublicKeyName.postfix(prefix).stringComponent(0);
				return new Tuple<ContentName, String>(prefix, friendlyName);
			}
		}

		// NOTE: as there are multiple user prefixes, each with a distinguishing hash and an optional
		// suffix, you have to have a list of those and iterate over them as well.
		for (ParameterizedName userStorage: _userStorage) {
			if (userStorage.prefix().isPrefixOf(principalPublicKeyName)) {
				String friendlyName = principalPublicKeyName.postfix(userStorage.prefix()).stringComponent(0);
				return new Tuple<ContentName, String>(userStorage.prefix(), friendlyName);
			}
		}

		return null;
	}

	// TODO
	public ContentName getPrincipalPublicKeyName(byte [] distinguishingHash, String friendlyName) {
		// look up the distinguishing hash in the user and group maps
		// pull the name prefix and postfix from the tables
		// make the key name as <prefix>/friendlyName/<postfix>
		return null;
	}

	/**
	 * Publish my identity (i.e. my public key) under a specified CCN name
	 * @param identity the name
	 * @param myPublicKey my public key
	 * @throws InvalidKeyException
	 * @throws ContentEncodingException
	 * @throws IOException
	 */
	public void publishMyIdentity(ContentName identity, PublicKey myPublicKey) 
	throws InvalidKeyException, ContentEncodingException, IOException {
		KeyManager km = _handle.keyManager();
		if (null == myPublicKey) {
			myPublicKey = km.getDefaultPublicKey();
		}
		PublicKeyObject pko = new PublicKeyObject(identity, myPublicKey, SaveType.REPOSITORY, handle());
		pko.save();
		Log.finer(Log.FAC_ACCESSCONTROL, "Published identity {0}", identity);
		_myIdentities.add(identity);
	}


	/**
	 * Add an identity to my set. Assume the key is already published.
	 */
	public void addMyIdentity(ContentName identity) {
		Log.finer(Log.FAC_ACCESSCONTROL, "Adding identity {0}", identity);
		_myIdentities.add(identity);
	}

	/**
	 * Publish the specified identity (i.e. the public key) of a specified user
	 * @param userName the name of the user
	 * @param userPublicKey the public key of the user
	 * @throws IOException
	 * @throws MalformedContentNameStringException
	 */
	public void publishUserIdentity(String userName, PublicKey userPublicKey) 
	throws IOException, MalformedContentNameStringException {
		PublicKeyObject pko = new PublicKeyObject(ContentName.fromNative(userName), userPublicKey, SaveType.REPOSITORY, handle());
		System.out.println("saving user pubkey to repo:" + userName);
		pko.save();
	}

	public boolean haveIdentity(ContentName userName) {
		if (_myIdentities.contains(userName)) {
			return true;
		}
		for (ContentName identity : _myIdentities) {
			if (identity.isPrefixOf(userName)) {
				if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.INFO)) {
					Log.info(Log.FAC_ACCESSCONTROL, "haveIdentity: {0} is not exactly one of my identities, but my identity {1} is its prefix. Claiming it as mine. ",
							userName, identity);
				}
				return true;
			}
		}
		return false;
	}

	public String nodeKeyLabel() {
		return NODE_KEY_LABEL;
	}

	/**
	 * Get the latest key for a specified principal
	 * TODO shortcut slightly -- the principal we have cached might not meet the
	 * constraints of the link.
	 * TODO principal is a link to a group or user name, need to use prefix/suffix
	 * to get actual public key
	 * @param principal the principal
	 * @return the public key object
	 * @throws IOException 
	 * @throws ContentDecodingException 
	 */
	public PublicKeyObject getLatestKeyForPrincipal(Link principal) throws ContentDecodingException, IOException {
		if (null == principal) {
			if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.INFO)) {
				Log.info(Log.FAC_ACCESSCONTROL, "Cannot retrieve key for empty principal.");
			}
			return null;
		}
		PublicKeyObject pko = null;
		boolean isGroup = false;
		for (GroupManager gm: _groupManager) {
			if (gm.isGroup(principal)) {
				// MLAC this should load the correct key for the group
				pko = gm.getLatestPublicKeyForGroup(principal);
				isGroup = true;
				break;
			}
		}
		if (!isGroup) {
			if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.INFO)) {
				Log.info(Log.FAC_ACCESSCONTROL, "Retrieving latest key for user: " + principal.targetName());
			}
			// MLAC this should use the user storage to find the right public key for the user
			ContentName publicKeyName = userPublicKeyName(principal.targetName());
			LinkAuthenticator targetAuth = principal.targetAuthenticator();
			if (null != targetAuth) {
				pko = new PublicKeyObject(publicKeyName, targetAuth.publisher(), handle());
			}
			else pko = new PublicKeyObject(publicKeyName, handle());
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
	public ACLObject initializeNamespace(ACL rootACL) 
	throws InvalidKeyException, ContentEncodingException, ContentNotReadyException, 
	ContentGoneException, IOException {
		// generates the new node key		
		generateNewNodeKey(_namespace, null, rootACL);

		// write the root ACL
		ACLObject aclo = new ACLObject(GroupAccessControlProfile.aclName(_namespace), rootACL, handle());
		aclo.save();
		return aclo;
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
		ACLObject aclo = findAncestorWithACL(nodeName, null);
		if (null != aclo) {
			// parallel find doesn't get us the latest version. Serial does,
			// but it's kind of an artifact.
			aclo.update();
		} else {
			if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.INFO)) {
				Log.info(Log.FAC_ACCESSCONTROL, "No ACL found between node {0} and namespace root {1}. Returning root ACL.",
						nodeName, getNamespaceRoot());
			}
			return getACLObjectForNode(getNamespaceRoot());
		}
		return aclo;
	}

	private ACLObject findAncestorWithACL(ContentName dataNodeName, ContentName stopPoint) throws ContentDecodingException, IOException {
		// selector method, remove when pick faster one.
		return findAncestorWithACLInParallel(dataNodeName, stopPoint);
	}

	/**
	 * Look for an ACL that is on dataNodeName or above, but below stopPoint. If stopPoint is
	 * null, then take it to be the root for this AccessControlManager (which we assume to have
	 * an ACL). This is the old serial search, which has been replaced tyb the parallel search
	 * below. Keep it here temporarily.
	 * @param dataNodeName
	 * @param stopPoint
	 * @return
	 * @throws ContentDecodingException
	 * @throws IOException
	 */
	@SuppressWarnings("unused")
	private ACLObject findAncestorWithACLSerial(ContentName dataNodeName, ContentName stopPoint) throws ContentDecodingException, IOException {

		// If dataNodeName is the root of this AccessControlManager, there can be no ACL between
		// dataNodeName (inclusive) and the root (exclusive), so return null.
		if (getNamespaceRoot().equals(dataNodeName)) return null;

		if (null == stopPoint)  {
			stopPoint = getNamespaceRoot();
		} else if (!getNamespaceRoot().isPrefixOf(stopPoint)) {
			if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.WARNING)) {
				Log.warning(Log.FAC_ACCESSCONTROL, "findAncestorWithACL: Stopping point {0} must be an ancestor of the starting point {1}!", 
						stopPoint, dataNodeName);
			}
			throw new IOException("findAncestorWithACL: invalid search space: stopping point " + stopPoint + " must be an ancestor of the starting point " +
					dataNodeName + "!");
		}
		if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.INFO)) {
			Log.info(Log.FAC_ACCESSCONTROL, "findAncestorWithACL: start point {0}, stop before {1}", dataNodeName, stopPoint);
		}
		int stopCount = stopPoint.count();

		ACLObject ancestorACLObject = null;
		ContentName parentName = dataNodeName;
		ContentName nextParentName = null;
		while (null == ancestorACLObject) {
			ancestorACLObject = getACLObjectForNodeIfExists(parentName);
			if (null != ancestorACLObject) {
				if (ancestorACLObject.isGone()) {
					if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.INFO)) {
						Log.info(Log.FAC_ACCESSCONTROL, "Found an ACL object at {0} but its GONE.", ancestorACLObject.getVersionedName());
					}
					ancestorACLObject = null;
				} else {
					// got one
					if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.INFO)) {
						Log.info(Log.FAC_ACCESSCONTROL, "Found an ACL object at {0}", ancestorACLObject.getVersionedName());
					}
					break;
				}
			}
			nextParentName = parentName.parent();
			if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.INFO)) {
				Log.info(Log.FAC_ACCESSCONTROL, "findAncestorWithACL: no ACL object at node {0}, looking next at {1}", parentName, nextParentName);
			}
			// stop looking once we're above our namespace, or if we've already checked the top level
			if (parentName.count() == stopCount) {
				if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.INFO)) {
					Log.info(Log.FAC_ACCESSCONTROL, "findAncestorWithACL: giving up, next search point would be {0}, stop point is {1}, no ACL found",
							parentName, stopPoint);
				}
				break;
			}
			parentName = nextParentName;
		}
		if (null == ancestorACLObject) {
			if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.INFO)) {
				Log.info(Log.FAC_ACCESSCONTROL,
						"No ACL available in ancestor tree between {0} and {1} (not-inclusive)  out of namespace rooted at {2}.",
						dataNodeName, stopPoint, getNamespaceRoot());
			}
			return null;
		}
		if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.INFO)) {
			Log.info(Log.FAC_ACCESSCONTROL, "Found ACL for {0} at ancestor {1}: ", dataNodeName, ancestorACLObject.getVersionedName());
		}
		return ancestorACLObject;
	}

	/**
	 * Implement a parallel findAncestorWithACL; drop it in separately so we can 
	 * switch back and forth in testing.
	 */
	private ACLObject findAncestorWithACLInParallel(ContentName dataNodeName, ContentName stopPoint) throws ContentDecodingException, IOException {

		// If dataNodeName is the root of this AccessControlManager, there can be no ACL between
		// dataNodeName (inclusive) and the root (exclusive), so return null.
		if (getNamespaceRoot().equals(dataNodeName)) return null;

		if (null == stopPoint)  {
			stopPoint = getNamespaceRoot();
		} else if (!getNamespaceRoot().isPrefixOf(stopPoint)) {
			if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.WARNING)) {
				Log.warning(Log.FAC_ACCESSCONTROL, "findAncestorWithACLInParallel: Stopping point {0} must be an ancestor of the starting point {1}!", 
						stopPoint, dataNodeName);
			}
			throw new IOException("findAncestorWithACLInParallel: invalid search space: stopping point " + stopPoint + " must be an ancestor of the starting point " +
					dataNodeName + "!");
		}
		if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.INFO)) {
			Log.info(Log.FAC_ACCESSCONTROL, "findAncestorWithACLInParallel: start point {0}, stop before {1}", dataNodeName, stopPoint);
		}
		int stopCount = stopPoint.count();

		// Pathfinder searches from start point to stop point inclusive, want exclusive, so hand
		// it one level down from stop point.
		Pathfinder pathfinder = new LatestVersionPathfinder(dataNodeName, dataNodeName.cut(stopCount+1), 
				GroupAccessControlProfile.aclPostfix(), true, false, SystemConfiguration.EXTRA_LONG_TIMEOUT,
				null, handle());

		SearchResults searchResults = pathfinder.waitForResults();
		if (null != searchResults.getResult()) {
			if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.INFO)) {
				Log.info(Log.FAC_ACCESSCONTROL, "findAncestorWithACLInParallel: found {0}", searchResults.getResult().name());
			}
			ACLObject aclo = new ACLObject(searchResults.getResult(), handle());
			return aclo;
		}
		return null;
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
			if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.INFO)) {
				Log.info(Log.FAC_ACCESSCONTROL, "getACLObjectForNode: Asked to get an ACL object for specific node {0}, but ACL there is GONE! Returning null.", 
						aclNodeName);
			}
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

		// TODO really want to check simple existence here, but need to integrate with verifier
		// use. GLV too slow for negative results. Given that we need latest version, at least
		// use the segment we get, and don't pull it twice.
		ContentName aclName = GroupAccessControlProfile.aclName(aclNodeName);
		ContentObject aclNameList = VersioningProfile.getLatestVersion(aclName, 
				null, SystemConfiguration.MAX_TIMEOUT, handle().defaultVerifier(), handle()); 

		if (null != aclNameList) {
			if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.INFO)) {
				Log.info(Log.FAC_ACCESSCONTROL, "Found latest version of acl for {0} at {1} type: {2}", 
						aclNodeName, aclName, aclNameList.signedInfo().getTypeName());
			}
			ACLObject aclo = new ACLObject(aclNameList, handle());
			if (aclo.isGone()) {
				if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.INFO)) {
					Log.info(Log.FAC_ACCESSCONTROL, "ACL object is GONE, returning anyway {0}", aclo.getVersionedName());
				}
			}
			return aclo;
		}
		if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.INFO)) {
			Log.info(Log.FAC_ACCESSCONTROL, "No ACL found on node: " + aclNodeName);
		}
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
	 * @throws NoSuchAlgorithmException 
	 */
	public ACL setACL(ContentName nodeName, ACL newACL) 
	throws AccessDeniedException, InvalidKeyException, ContentNotReadyException, ContentGoneException, IOException, NoSuchAlgorithmException {
		// Throws access denied exception if we can't read the old node key.
		NodeKey effectiveNodeKey = getEffectiveNodeKey(nodeName);
		// generates the new node key, wraps it under the new acl, and wraps the old node key
		generateNewNodeKey(nodeName, effectiveNodeKey, newACL);
		// write the acl
		ACLObject aclo = new ACLObject(GroupAccessControlProfile.aclName(nodeName), newACL, handle());
		aclo.save();
		return aclo.acl();
	}
	
	/**
	 * Adds an ACL to a node that doesn't have one, or replaces one that exists.
	 * Just writes, doesn't bother to look at any current ACL. Does need to pull
	 * the effective node key at this node, though, to wrap the old ENK in a new
	 * node key. Gets handed in effective ACL, to avoid having to search.
	 * 
	 * @param nodeName the name of the node
	 * @param newACL the new ACL
	 * @return
	 * @throws InvalidKeyException 
	 * @throws IOException 
	 * @throws ContentGoneException 
	 * @throws ContentNotReadyException 
	 * @throws NoSuchAlgorithmException 
	 */
	public ACL setACL(ContentName nodeName, ACL newACL, ACLObject effectiveACLObject) 
	throws AccessDeniedException, InvalidKeyException, ContentNotReadyException, ContentGoneException, IOException, NoSuchAlgorithmException {
		// Throws access denied exception if we can't read the old node key.
		NodeKey effectiveNodeKey = getEffectiveNodeKey(nodeName, effectiveACLObject);
		// generates the new node key, wraps it under the new acl, and wraps the old node key
		generateNewNodeKey(nodeName, effectiveNodeKey, newACL);
		// write the acl
		ACLObject aclo = new ACLObject(GroupAccessControlProfile.aclName(nodeName), newACL, handle());
		aclo.save();
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
	 * @throws NoSuchAlgorithmException 
	 */
	public void deleteACL(ContentName nodeName) 
	throws ContentDecodingException, IOException, InvalidKeyException, NoSuchAlgorithmException {

		// First, find ACL at this node if one exists.
		ACLObject thisNodeACL = getACLObjectForNodeIfExists(nodeName);
		if (null == thisNodeACL) {
			if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.INFO)) {
				Log.info(Log.FAC_ACCESSCONTROL, "Asked to delete ACL for node {0} that doesn't have one. Doing nothing.", nodeName);
			}
			return;
		} else if (thisNodeACL.isGone()) {
			if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.INFO)) {
				Log.info(Log.FAC_ACCESSCONTROL, "Asked to delete ACL for node {0} that has already been deleted. Doing nothing.", nodeName);
			}
			return;			
		}
		if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.INFO)) {
			Log.info(Log.FAC_ACCESSCONTROL, "Deleting ACL for node {0} latest version: {1}",  nodeName,  thisNodeACL.getVersionedName());
		}

		// We know we have an ACL at this node. So we know we have a node key at this
		// node. Get the latest version of this node key.
		NodeKey nk = getLatestNodeKeyForNode(nodeName);

		// Next, find the node key that would be in force here after this deletion. 
		// Do that by getting the effective node key at the parent
		ContentName parentName = nodeName.parent();
		NodeKey effectiveParentNodeKey = getEffectiveNodeKey(parentName);
		// And then deriving what the effective node key would be here, if
		// we inherited from the parent
		NodeKey ourEffectiveNodeKeyFromParent = 
			effectiveParentNodeKey.computeDescendantNodeKey(nodeName, nodeKeyLabel()); 

		// Generate a superseded block for this node, wrapping its key in the parent.
		// TODO want to wrap key in parent's effective key, but can't point to that -- no way to name an
		// effective node key... need one.

		// need to mangl stored key name into superseded block name, need to wrap
		// in ourEffNodeKeyFromParent, make sure stored key id points up to our ENKFP.storedKeyName()
		PrincipalKeyDirectory.addSupersededByBlock(nk.storedNodeKeyName(), nk.nodeKey(), 
				ourEffectiveNodeKeyFromParent.storedNodeKeyName(), 
				ourEffectiveNodeKeyFromParent.storedNodeKeyID(),
				effectiveParentNodeKey.nodeKey(), handle());

		// Then mark the ACL as gone.
		thisNodeACL.saveAsGone();
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
	 * @throws NoSuchAlgorithmException 
	 */
	public ACL updateACL(ContentName nodeName, ArrayList<ACL.ACLOperation> ACLUpdates) 
	throws ContentDecodingException, IOException, InvalidKeyException, NoSuchAlgorithmException {
		ACLObject currentACL = getACLObjectForNodeIfExists(nodeName);
		ACL newACL = null;

		if (null != currentACL) {
			newACL = currentACL.acl();
		} else {
			if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.INFO)) {
				Log.info(Log.FAC_ACCESSCONTROL, "Adding brand new ACL to node {0}", nodeName);
			}
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
		PrincipalKeyDirectory keyDirectory = null;
		try {
			// If we can't read the node key, we can't update. Get the effective node key.
			// Better be a node key here... and we'd better be allowed to read it.
			NodeKey latestNodeKey = getLatestNodeKeyForNode(nodeName);
			if (null == latestNodeKey) {
				if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.INFO)) {
					Log.info(Log.FAC_ACCESSCONTROL, "Cannot read the latest node key for {0}", nodeName);
				}
				throw new AccessDeniedException("Cannot read the latest node key for " + nodeName);
			}

			keyDirectory = new PrincipalKeyDirectory(this, latestNodeKey.storedNodeKeyName(), handle());

			for (Link principal : newReaders) {
				PublicKeyObject latestKey = getLatestKeyForPrincipal(principal);
				if (!latestKey.available()) {
					latestKey.waitForData(SystemConfiguration.getDefaultTimeout());
				}
				if (latestKey.available()) {
					if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.INFO)) {
						Log.info(Log.FAC_ACCESSCONTROL, "updateACL: Adding wrapped key block for reader: " + latestKey.getVersionedName());
					}
					try {
						keyDirectory.addWrappedKeyBlock(latestNodeKey.nodeKey(), latestKey.getVersionedName(), latestKey.publicKey());
					} catch (VersionMissingException e) {
						if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.WARNING)) {
							Log.warning(Log.FAC_ACCESSCONTROL, "UNEXPECTED: latest key for principal: " + latestKey.getVersionedName() + " has no version? Skipping.");
						}
					}
				} else {
					// Do we use an old key or give up?
					if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.INFO)) {
						Log.info(Log.FAC_ACCESSCONTROL, "updateACL: No key for {0} found. Skipping.", principal);
					}
				}
			}
		} finally {
			if (null != keyDirectory) {
				keyDirectory.stopEnumerating();
			}
		}
		// If we got here, we got the node key we were updating, so we are allowed
		// to at least read this stuff (though maybe not write it). Save the acl.
		currentACL.save(newACL);
		return newACL;

	}

	/**
	 * Add readers to a specified node	
	 * @param nodeName the name of the node
	 * @param newReaders the list of new readers
	 * @return the updated ACL
	 * @throws IOException 
	 * @throws ContentDecodingException 
	 * @throws InvalidKeyException 
	 * @throws NoSuchAlgorithmException 
	 */
	public ACL addReaders(ContentName nodeName, ArrayList<Link> newReaders) 
	throws InvalidKeyException, ContentDecodingException, IOException, NoSuchAlgorithmException {
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
	 * @throws ContentDecodingException 
	 * @throws InvalidKeyException 
	 * @throws NoSuchAlgorithmException 
	 */
	public ACL removeReaders(ContentName nodeName, ArrayList<Link> removedReaders) 
	throws InvalidKeyException, ContentDecodingException, IOException, NoSuchAlgorithmException {
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
	 * @throws ContentDecodingException 
	 * @throws InvalidKeyException 
	 * @throws NoSuchAlgorithmException 
	 */
	public ACL addWriters(ContentName nodeName, ArrayList<Link> newWriters) 
	throws InvalidKeyException, ContentDecodingException, IOException, NoSuchAlgorithmException {
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
	 * @throws ContentDecodingException 
	 * @throws InvalidKeyException 
	 * @throws NoSuchAlgorithmException 
	 */
	public ACL removeWriters(ContentName nodeName, ArrayList<Link> removedWriters) 
	throws InvalidKeyException, ContentDecodingException, IOException, NoSuchAlgorithmException {
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
	 * @throws ContentDecodingException 
	 * @throws InvalidKeyException 
	 * @throws NoSuchAlgorithmException 
	 */
	public ACL addManagers(ContentName nodeName, ArrayList<Link> newManagers) 
	throws InvalidKeyException, ContentDecodingException, IOException, NoSuchAlgorithmException {
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
	 * @throws ContentDecodingException 
	 * @throws InvalidKeyException 
	 * @throws NoSuchAlgorithmException 
	 */
	public ACL removeManagers(ContentName nodeName, ArrayList<Link> removedManagers) 
	throws InvalidKeyException, ContentDecodingException, IOException, NoSuchAlgorithmException {
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
	 * @throws NoSuchAlgorithmException 
	 */
	protected NodeKey findAncestorWithNodeKey(ContentName nodeName) 
	throws InvalidKeyException, AccessDeniedException, 
	ContentDecodingException, IOException, NoSuchAlgorithmException {
		// climb up looking for node keys, then make sure that one isn't GONE
		// if it isn't, call read-side routine to figure out how to decrypt it
		ACLObject effectiveACL = findAncestorWithACL(nodeName, null);

		if (null != effectiveACL) {
			if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.INFO)) {
				Log.info(Log.FAC_ACCESSCONTROL, "Got ACL named: {0} attempting to retrieve node key from {1}", 
						effectiveACL.getVersionedName(), AccessControlProfile.accessRoot(effectiveACL.getVersionedName()));
			}
		} else {
			// We're not searching at the namespace root; because we assume we have
			// already made sure we have an ACL there. So if we get back NULL, we
			// go stratight to our namespace root.
			if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.INFO)) {
				Log.info(Log.FAC_ACCESSCONTROL, "No ACL found between node {0} and namespace root {1}, assume ACL is at namespace root.",
						nodeName, getNamespaceRoot());
			}
		}
		return getLatestNodeKeyForNode(
				(null != effectiveACL) ? AccessControlProfile.accessRoot(effectiveACL.getBaseName()) : getNamespaceRoot());
	}

	/**
	 * Write path: get the latest node key for a node.
	 * @param nodeName the name of the node
	 * @return the corresponding node key
	 * @throws IOException 
	 * @throws ContentDecodingException 
	 * @throws AccessDeniedException 
	 * @throws InvalidKeyException 
	 * @throws NoSuchAlgorithmException 
	 */
	public NodeKey getLatestNodeKeyForNode(ContentName nodeName) 
	throws InvalidKeyException, AccessDeniedException, 
	ContentDecodingException, IOException, NoSuchAlgorithmException {

		ContentName nodeKeyPrefix = GroupAccessControlProfile.nodeKeyName(nodeName);
		ContentObject co = VersioningProfile.getLatestVersion(nodeKeyPrefix, 
				null, SystemConfiguration.MAX_TIMEOUT, handle().defaultVerifier(), handle());
		ContentName nodeKeyVersionedName = null;
		if (co != null) {
			nodeKeyVersionedName = co.name().subname(0, nodeKeyPrefix.count() + 1);
			if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.FINE)) {
				Log.fine(Log.FAC_ACCESSCONTROL, "getLatestNodeKeyForNode: {0} is the latest version found for {1}.", nodeKeyVersionedName, nodeName);
			}
		} else {
			if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.FINE)) {
				Log.fine(Log.FAC_ACCESSCONTROL, "getLatestNodeKeyForNode: no latest version found for {0}.", nodeName);
			}
			return null;
		}

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
	 * @throws AccessDeniedException 
	 * @throws InvalidKeyException 
	 * @throws NoSuchAlgorithmException 
	 */
	public NodeKey getSpecificNodeKey(ContentName nodeKeyName, byte [] nodeKeyIdentifier) 
	throws InvalidKeyException, AccessDeniedException, 
	ContentDecodingException, IOException, NoSuchAlgorithmException {

		if ((null == nodeKeyName) && (null == nodeKeyIdentifier)) {
			throw new IllegalArgumentException("Node key name and identifier cannot both be null!");
		}
		// We should know what node key to use (down to the version), but we have to find the specific
		// wrapped key copy we can decrypt. 
		NodeKey nk = getNodeKeyByVersionedName(nodeKeyName, nodeKeyIdentifier);
		if (null == nk) {
			if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.WARNING)) {
				Log.warning(Log.FAC_ACCESSCONTROL, "No decryptable node key available at {0}, access denied.", nodeKeyName);
			}
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
	 * @throws InvalidKeyException 
	 * @throws NoSuchAlgorithmException 
	 */
	NodeKey getNodeKeyByVersionedName(ContentName nodeKeyName, byte [] nodeKeyIdentifier) 
	throws AccessDeniedException, InvalidKeyException, 
	ContentDecodingException, IOException, NoSuchAlgorithmException {

		NodeKey nk = null;
		PrincipalKeyDirectory keyDirectory = null;
		try {
			
			// First thing to do -- try cache directly before we even consider enumerating.
			Key targetKey = handle().keyManager().getSecureKeyCache().getKey(nodeKeyName, nodeKeyIdentifier);

			if (null == targetKey) {
				// start enumerating; if we get a cache hit don't need it, but just in case.
				keyDirectory = new PrincipalKeyDirectory(this, nodeKeyName, handle());

				// this will handle the caching.
				targetKey = keyDirectory.getUnwrappedKey(nodeKeyIdentifier);
			}

			if (null != targetKey) {
				nk = new NodeKey(nodeKeyName, targetKey);
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
	 * @param nodeName
	 * @return
	 * @throws AccessDeniedException 
	 * @throws ContentEncodingException 
	 * @throws ContentDecodingException
	 * @throws IOException
	 * @throws InvalidKeyException 
	 * @throws NoSuchAlgorithmException 
	 */
	public NodeKey getEffectiveNodeKey(ContentName nodeName) 
	throws AccessDeniedException, InvalidKeyException, ContentEncodingException, 
	ContentDecodingException, IOException, NoSuchAlgorithmException {
		// Get the ancestor node key in force at this node.
		NodeKey nodeKey = findAncestorWithNodeKey(nodeName);
		if (null == nodeKey) {
			throw new AccessDeniedException("Cannot retrieve node key for node: " + nodeName + ".");
		}
		if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.INFO)) {
			Log.info(Log.FAC_ACCESSCONTROL, "Found node key at {0}", nodeKey.storedNodeKeyName());
		}
		NodeKey effectiveNodeKey = nodeKey.computeDescendantNodeKey(nodeName, nodeKeyLabel());
		if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.INFO)) {
			Log.info(Log.FAC_ACCESSCONTROL, "Computing effective node key for {0} using stored node key {1}", nodeName, effectiveNodeKey.storedNodeKeyName());
		}
		return effectiveNodeKey;
	}
	
	/**
	 * Write path:
	 * Get the effective node key in force at this node, used to derive keys to 
	 * encrypt  content. Vertical chaining. Works if you ask for node which has
	 * a node key. Hand in node with existing node key to avoid search.
	 * @param nodeName
	 * @return
	 * @throws AccessDeniedException 
	 * @throws ContentEncodingException 
	 * @throws ContentDecodingException
	 * @throws IOException
	 * @throws InvalidKeyException 
	 * @throws NoSuchAlgorithmException 
	 */
	public NodeKey getEffectiveNodeKey(ContentName nodeName, ACLObject effectiveACL) 
	throws AccessDeniedException, InvalidKeyException, ContentEncodingException, 
	ContentDecodingException, IOException, NoSuchAlgorithmException {
		// Get the ancestor node key in force at this node.
		NodeKey nodeKey = getLatestNodeKeyForNode(
				AccessControlProfile.accessRoot(effectiveACL.getBaseName()));
		if (null == nodeKey) {
			throw new AccessDeniedException("Cannot retrieve node key for node: " + nodeName + ".");
		}
		if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.INFO)) {
			Log.info(Log.FAC_ACCESSCONTROL, "Found node key at {0}", nodeKey.storedNodeKeyName());
		}
		NodeKey effectiveNodeKey = nodeKey.computeDescendantNodeKey(nodeName, nodeKeyLabel());
		if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.INFO)) {
			Log.info(Log.FAC_ACCESSCONTROL, "Computing effective node key for {0} using stored node key {1}", nodeName, effectiveNodeKey.storedNodeKeyName());
		}
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
	 * @throws NoSuchAlgorithmException 
	 */
	public NodeKey getFreshEffectiveNodeKey(ContentName nodeName) 
	throws AccessDeniedException, InvalidKeyException, ContentDecodingException, 
	ContentEncodingException, ContentNotReadyException, ContentGoneException, IOException, NoSuchAlgorithmException {
		Log.finest(FAC_ACCESSCONTROL, "GACM.getFreshEffectiveNodeKey");

		NodeKey nodeKey = findAncestorWithNodeKey(nodeName);
		if (null == nodeKey) {
			throw new AccessDeniedException("Cannot retrieve node key for node: " + nodeName + ".");
		}
		// This should be the latest node key; i.e. not superseded.
		if (nodeKeyIsDirty(nodeKey.storedNodeKeyName())) {
			if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.INFO)) {
				Log.info(Log.FAC_ACCESSCONTROL, "getFreshEffectiveNodeKey: Found node key at {0}, updating.", nodeKey.storedNodeKeyName());
			}
			ContentName nodeKeyNodeName = GroupAccessControlProfile.accessRoot(nodeKey.storedNodeKeyName());
			ACLObject acl = getACLObjectForNode(nodeKeyNodeName);
			nodeKey = generateNewNodeKey(nodeKeyNodeName, nodeKey, acl.acl());
		} else {
			if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.INFO)) {
				Log.info(Log.FAC_ACCESSCONTROL, "getFreshEffectiveNodeKey: Found node key at {0}", nodeKey.storedNodeKeyName());
			}
		}
		if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.INFO)) {
			Log.info(Log.FAC_ACCESSCONTROL, "getFreshEffectiveNodeKey: retrieved node key for node {0} label {1}: {2}", nodeName, nodeKeyLabel(), nodeKey);
		}
		NodeKey effectiveNodeKey = nodeKey.computeDescendantNodeKey(nodeName, nodeKeyLabel()); 
		if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.INFO)) {
			Log.info(Log.FAC_ACCESSCONTROL, "getFreshEffectiveNodeKey: computed effective node key for node {0} label {1}: {2} using stored node key {3}"
					, nodeName, nodeKeyLabel(), effectiveNodeKey, effectiveNodeKey.storedNodeKeyName());
		}
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
		if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.FINE)) {
			Log.fine(Log.FAC_ACCESSCONTROL, "NodeKeyIsDirty({0}) called.", theNodeKeyName);
		}

		// first, is this a node key name?
		if (!GroupAccessControlProfile.isNodeKeyName(theNodeKeyName)) {
			// assume it's a data node name.
			theNodeKeyName = GroupAccessControlProfile.nodeKeyName(theNodeKeyName);
		}
		// get the requested version of this node key; or if unversioned, get the latest.
		PrincipalKeyDirectory nodeKeyDirectory = null;
		try {
			nodeKeyDirectory = new PrincipalKeyDirectory(this, theNodeKeyName, handle());
			nodeKeyDirectory.waitForChildren();

			if (nodeKeyDirectory.hasSupersededBlock()) {
				return true;
			}
			for (PrincipalInfo principal : nodeKeyDirectory.getCopyOfPrincipals().values()) {
				if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.FINE)) {
					Log.fine(Log.FAC_ACCESSCONTROL, "NodeKeyIsDirty: found principal called {0}", principal.friendlyName());
				}
				if (principal.isGroup()) {
					Group theGroup = groupManager(principal.distinguishingHash()).getGroup(principal.friendlyName(), SystemConfiguration.EXTRA_LONG_TIMEOUT);
					if (theGroup.publicKeyVersion().after(principal.versionTimestamp())) {
						if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.FINE)) {
							Log.fine(Log.FAC_ACCESSCONTROL, "NodeKeyIsDirty: the key of principal {0} is out of date", principal.friendlyName());
						}
						return true;
					} else {
						if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.FINE)) {
							Log.fine(Log.FAC_ACCESSCONTROL, "NodeKeyIsDirty: the key of principal {0} is up to date", principal.friendlyName());
						}						
					}
				} else {
					// DKS TODO -- for now, don't handle versioning of non-group keys
					if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.INFO)) {
						Log.info(Log.FAC_ACCESSCONTROL, "User key for {0}, not checking version.", principal.friendlyName());
					}
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
	 * Find the key to use to wrap a data key at this node for encryption. This requires
	 * the current effective node key, and wrapping this data key in it. If the
	 * current node key is dirty, this causes a new one to be generated.
	 * If data at the current node is public, this returns null. Does not check
	 * to see whether content is excluded from encryption (e.g. by being access
	 * control data).
	 * @param dataNodeName the node for which to find a data key wrapping key
	 * @param publisher in case output key retrieval needs to be specialized by publisher
	 * @return if null, the data is to be unencrypted.
	 * @param newRandomDataKey
	 * @throws AccessDeniedException 
	 * @throws InvalidKeyException 
	 * @throws ContentEncodingException
	 * @throws IOException
	 * @throws NoSuchAlgorithmException 
	 */
	@Override
	public NodeKey getDataKeyWrappingKey(ContentName dataNodeName, PublisherPublicKeyDigest publisher)
	throws AccessDeniedException, InvalidKeyException,
	ContentEncodingException, IOException, NoSuchAlgorithmException {
		NodeKey effectiveNodeKey = getFreshEffectiveNodeKey(dataNodeName);
		if (null == effectiveNodeKey) {
			throw new AccessDeniedException("Cannot retrieve effective node key for node: " + dataNodeName + ".");
		}
		return effectiveNodeKey;
	}

	/**
	 * Retrieve the node key wrapping this data key for decryption.
	 * @throws IOException 
	 * @throws ContentDecodingException 
	 * @throws ContentEncodingException 
	 * @throws ContentGoneException 
	 * @throws ContentNotReadyException 
	 * @throws InvalidKeyException 
	 * @throws NoSuchAlgorithmException 
	 */
	@Override
	public Key getDataKeyWrappingKey(ContentName dataNodeName, WrappedKeyObject wrappedDataKeyObject) 
	throws InvalidKeyException, ContentNotReadyException, ContentGoneException, ContentEncodingException, 
	ContentDecodingException, IOException, NoSuchAlgorithmException {
		NodeKey enk = getNodeKeyForObject(dataNodeName, wrappedDataKeyObject);
		if (null != enk) {
			return enk.nodeKey();
		} 
		return null;
	}

	/**
	 * Get the data key wrapping key if we happened to have cached a copy of the decryption key.
	 * @param dataNodeName
	 * @param wrappedDataKeyObject
	 * @param cachedWrappingKey
	 * @return
	 * @throws ContentEncodingException 
	 * @throws InvalidKeyException 
	 */
	@Override 
	public Key getDataKeyWrappingKey(ContentName dataNodeName, ContentName wrappingKeyName, Key cachedWrappingKey) throws InvalidKeyException, ContentEncodingException {
		NodeKey cachedWrappingKeyNK = new NodeKey(wrappingKeyName, cachedWrappingKey);
		if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.INFO)) {
			Log.info(Log.FAC_ACCESSCONTROL, "getDataKeyWrappingKey: retrieved cached stored node key for node {0} label {1}: {2}", dataNodeName, nodeKeyLabel(), cachedWrappingKeyNK);
		}
		NodeKey enk = cachedWrappingKeyNK.computeDescendantNodeKey(dataNodeName, nodeKeyLabel());
		if (null != enk) {
			if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.INFO)) {
				Log.info(Log.FAC_ACCESSCONTROL, "getDataKeyWrappingKey: used cache to compute effective node key for node {0} label {1}: {2}", dataNodeName, nodeKeyLabel(), enk);
			}
			return enk.nodeKey();
		} else {
			if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.INFO)) {
				Log.finer(Log.FAC_ACCESSCONTROL, "getDataKeyWrappingKey: cannot compute effective node key for node {0} label {1} from cached key {2}", 
						dataNodeName, nodeKeyLabel(), cachedWrappingKeyNK);
			}
		}
		return null;
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
	 * @throws InvalidKeyException 
	 * @throws NoSuchAlgorithmException 
	 */
	protected NodeKey getNodeKeyUsingInterposedACL(ContentName dataNodeName,
			ContentName wrappingKeyName, byte[] wrappingKeyIdentifier) 
	throws ContentDecodingException, IOException, InvalidKeyException, NoSuchAlgorithmException {

		ContentName stopPoint = AccessControlProfile.accessRoot(wrappingKeyName);
		if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.INFO)) {
			Log.info(Log.FAC_ACCESSCONTROL, "getNodeKeyUsingInterposedACL: looking for an ACL above {0} but below {1}",
					dataNodeName, stopPoint);
		}
		ACLObject nearestACL = findAncestorWithACL(dataNodeName, stopPoint);
		// TODO update to make sure non-gone....
		if (null == nearestACL) {
			if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.INFO)) {
				Log.info(Log.FAC_ACCESSCONTROL, "Node key {0} is the nearest ACL to {1}", wrappingKeyName , dataNodeName);
			}
			return null;
		}

		NodeKey currentNodeKey = getLatestNodeKeyForNode(GroupAccessControlProfile.accessRoot(nearestACL.getVersionedName()));

		// We have retrieved the current node key at the node where the ACL was interposed.
		// But the data key is wrapped in the previous node key that was at this node prior to the ACL interposition.
		// So we need to retrieve the previous node key, which was wrapped with KeyDirectory.addPreviousKeyBlock 
		// at the time the ACL was interposed.
		ContentName previousKeyName = new ContentName(currentNodeKey.storedNodeKeyName(), PREVIOUS_KEY);
		if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.FINER)) {
			Log.finer(Log.FAC_ACCESSCONTROL, "getNodeKeyUsingInterposedACL: retrieving previous key at {0}", previousKeyName);
		}
		WrappedKeyObject wrappedPreviousNodeKey = new WrappedKeyObject(previousKeyName, _handle);
		wrappedPreviousNodeKey.update();
		Key pnk = wrappedPreviousNodeKey.wrappedKey().unwrapKey(currentNodeKey.nodeKey());
		if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.FINER)) {
			Log.finer(Log.FAC_ACCESSCONTROL, "getNodeKeyUsingInterposedACL: returning previous node key for node {0}", currentNodeKey.storedNodeKeyName());
		}
		NodeKey previousNodeKey = new NodeKey(currentNodeKey.storedNodeKeyName(), pnk);

		return previousNodeKey;
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
		if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.INFO)) {
			Log.info(Log.FAC_ACCESSCONTROL, "GenerateNewNodeKey: generating new node key {0}", nodeKeyDirectoryName);
			Log.info(Log.FAC_ACCESSCONTROL, "GenerateNewNodeKey: for node {0} with old effective node key {1}", nodeName, oldEffectiveNodeKey);
		}

		// Now, generate the node key.
		if (effectiveACL.publiclyReadable()) {
			// TODO Put something here that will represent public; need to then make it so that key-reading code will do
			// the right thing when it encounters it.
			throw new UnsupportedOperationException("Need to implement public node key representation!");
		}

		byte [] nodeKeyBytes = new byte[NodeKey.DEFAULT_NODE_KEY_LENGTH];
		_random.nextBytes(nodeKeyBytes);
		Key nodeKey = new SecretKeySpec(nodeKeyBytes, NodeKey.DEFAULT_NODE_KEY_ALGORITHM);
		if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.FINER)) {
			Log.finer(Log.FAC_ACCESSCONTROL, "GenerateNewNodeKey: for node {0} the new node key is {1}", nodeName, DataUtils.printHexBytes(nodeKey.getEncoded()));
		}

		// Now, wrap it under the keys listed in its ACL.

		// Make a key directory. If we give it a versioned name. Don't start enumerating; we don't need to.
		PrincipalKeyDirectory nodeKeyDirectory = new PrincipalKeyDirectory(this, nodeKeyDirectoryName, false, handle());
		NodeKey theNodeKey = new NodeKey(nodeKeyDirectoryName, nodeKey);

		// Add a key block for every reader on the ACL. As managers and writers can read, they are all readers.
		// TODO -- pulling public keys here; could be slow; might want to manage concurrency over acl.
		for (Link aclEntry : effectiveACL.contents()) {
			PublicKeyObject entryPublicKey = null;

			boolean isInGroupManager = false;
			for (GroupManager gm: _groupManager) {
				if (gm.isGroup(aclEntry)) {
					// MLAC this should load the correct key for the group
					entryPublicKey = gm.getLatestPublicKeyForGroup(aclEntry);
					isInGroupManager = true;
					break;
				}
			}
			
			if (! isInGroupManager) {
				// MLAC should pull parameterized user key name
				// Calls update. Will get latest version if name unversioned.
				ContentName principalKeyName = userPublicKeyName(aclEntry.targetName());
				if (aclEntry.targetAuthenticator() != null) {
					entryPublicKey = new PublicKeyObject(principalKeyName, aclEntry.targetAuthenticator().publisher(), handle());
				} else {
					entryPublicKey = new PublicKeyObject(principalKeyName, handle());
				}
			}
			entryPublicKey.waitForData(SystemConfiguration.getDefaultTimeout());
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
			if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.FINER)) {
				Log.finer(Log.FAC_ACCESSCONTROL, "GenerateNewNodeKey: old effective node key is not null.");
			}
			if (oldEffectiveNodeKey.isDerivedNodeKey()) {
				if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.FINER)) {
					Log.finer(Log.FAC_ACCESSCONTROL, "GenerateNewNodeKey: old effective node key is derived node key.");
				}
				// Interposing an ACL. 
				// Add a previous key block wrapping the previous key. There is nothing to link to.
				nodeKeyDirectory.addPreviousKeyBlock(oldEffectiveNodeKey.nodeKey(), nodeKeyDirectoryName, nodeKey);
			} else {
				// We're replacing a previous version of this key. New version should have a previous key
				// entry 
				if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.FINER)) {
					Log.finer(Log.FAC_ACCESSCONTROL, "GenerateNewNodeKey: old effective node key is not a derived node key.");					
				}
				try {
					if (!VersioningProfile.isLaterVersionOf(nodeKeyDirectoryName, oldEffectiveNodeKey.storedNodeKeyName())) {
						if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.WARNING)) {
							Log.warning(Log.FAC_ACCESSCONTROL, "GenerateNewNodeKey: Unexpected: replacing node key stored at {0} with new node key {1}" + 
									" but latter is not later version of the former.", oldEffectiveNodeKey.storedNodeKeyName(), nodeKeyDirectoryName);
						}
					}
				} catch (VersionMissingException vex) {
					if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.WARNING)) {
						Log.warning(Log.FAC_ACCESSCONTROL, "Very unexpected version missing exception when replacing node key : {0}", vex);
					}
					// Add a previous key link to the old version of the key.
					// TODO do we need to add publisher?
					nodeKeyDirectory.waitForChildren();
					nodeKeyDirectory.addPreviousKeyLink(oldEffectiveNodeKey.storedNodeKeyName(), null);
					// OK, just add superseded-by block to the old directory.
					PrincipalKeyDirectory.addSupersededByBlock(
							oldEffectiveNodeKey.storedNodeKeyName(), oldEffectiveNodeKey.nodeKey(), 
							theNodeKey.storedNodeKeyName(), theNodeKey.storedNodeKeyID(), theNodeKey.nodeKey(), handle());
				}
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
	 * @throws NoSuchAlgorithmException 
	 */
	public NodeKey getNodeKeyForObject(ContentName nodeName, WrappedKeyObject wko) 
	throws ContentNotReadyException, ContentGoneException, InvalidKeyException, ContentEncodingException,
	ContentDecodingException, IOException, NoSuchAlgorithmException {

		// First, we go and look for the node key where the data key suggests
		// it should be, and attempt to decrypt it from there.
		NodeKey nk = null;
		try {
			if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.INFO)) {
				Log.info(Log.FAC_ACCESSCONTROL, "getNodeKeyForObject: trying to get specific node key at {0}", wko.wrappedKey().wrappingKeyName());
			}
			nk = getSpecificNodeKey(wko.wrappedKey().wrappingKeyName(), 
					wko.wrappedKey().wrappingKeyIdentifier());
			if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.INFO)) {
				Log.info(Log.FAC_ACCESSCONTROL, "getNodeKeyForObject: got specific node key {0} at {1}", nk, wko.wrappedKey().wrappingKeyName());
			}
		} catch (AccessDeniedException ex) {
			// ignore
			if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.INFO)) {
				Log.info(Log.FAC_ACCESSCONTROL, "getNodeKeyForObject: ignoring access denied exception as we're gong to try harder: {0}", ex.getMessage());
			}
		}
		if (null == nk) {
			if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.INFO)) {
				Log.info(Log.FAC_ACCESSCONTROL, "getNodeKeyForObject: trying to get node key using interposed ACL for {0}", wko.wrappedKey().wrappingKeyName());
			}
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
		if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.INFO)) {
			Log.info(Log.FAC_ACCESSCONTROL, "getNodeKeyForObject: retrieved stored node key for node {0} label {1}: {2}", nodeName, nodeKeyLabel(), nk);
		}
		NodeKey enk = nk.computeDescendantNodeKey(nodeName, nodeKeyLabel());
		if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.INFO)) {
			Log.info(Log.FAC_ACCESSCONTROL, "getNodeKeyForObject: computed effective node key for node {0} label {1}: {2}", nodeName, nodeKeyLabel(), enk);
		}
		return enk;
	}

	/**
	 * Overrides the method of the same name in AccessControlManager. 
	 * GroupAccessControlManager specifies additional content that is not to be protected,
	 * such as group metadata.
	 */
	public boolean isProtectedContent(ContentName name, PublisherPublicKeyDigest publisher, ContentType contentType, CCNHandle handle) {
		if (isGroupName(name)) {
			// Don't encrypt the group metadata
			return false;
		}
		return super.isProtectedContent(name, publisher, contentType, handle);
	}

	public boolean haveKnownGroupMemberships() {
		for (GroupManager gm: _groupManager) {
			if (gm.haveKnownGroupMemberships()) return true;
		}
		return false;
	}

}	
