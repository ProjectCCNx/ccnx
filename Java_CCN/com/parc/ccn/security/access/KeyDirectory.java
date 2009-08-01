package com.parc.ccn.security.access;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.SortedSet;

import javax.jcr.AccessDeniedException;
import javax.xml.stream.XMLStreamException;

import org.bouncycastle.crypto.InvalidCipherTextException;
import org.bouncycastle.util.Arrays;

import com.parc.ccn.Library;
import com.parc.ccn.config.ConfigurationException;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.content.Link;
import com.parc.ccn.data.content.LinkReference;
import com.parc.ccn.data.content.LinkReference.LinkObject;
import com.parc.ccn.data.security.LinkAuthenticator;
import com.parc.ccn.data.security.PublisherID;
import com.parc.ccn.data.security.WrappedKey;
import com.parc.ccn.data.security.WrappedKey.WrappedKeyObject;
import com.parc.ccn.data.util.DataUtils;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.library.EnumeratedNameList;
import com.parc.ccn.library.profiles.AccessControlProfile;
import com.parc.ccn.library.profiles.VersionMissingException;
import com.parc.ccn.library.profiles.VersioningProfile;
import com.parc.ccn.library.profiles.AccessControlProfile.PrincipalInfo;

/**
 * A key directory holds a set of keys, wrapped under different
 * target keys. It is implemented as a set of wrapped key objects
 * all stored in one directory. Wrapped key objects are typically short
 * and only need one segment. The directory the keys are stored in
 * is prefixed by a version, to allow the contents to evolve. In addition
 * some potential supporting information pointing to previous
 * or subsequent versions of this key is kept. A particular wrapped key
 * entry's name would look like:
 *
 * <pre>.../v123/xxx/s0</pre>
 * <br>Where xxx is the identifier of the wrapped key.
 *
 * This structure is used for representing both node keys and group
 * (private) keys. We encapsulate functionality to walk such a directory
 * and find our target key here.
 * 
 * Our model is that higher-level function may use this interface
 * to try many ways to get a given key. Some will work (access is
 * allowed), some may not -- the latter does not mean that the
 * principal doesn't have access, just that the principal doesn't
 * have access by this route. So for the moment, we return null
 * when we don't conclusively know that this principal doesn't
 * have access to this data somehow, rather than throwing
 * AccessDeniedException.
 */
public class KeyDirectory extends EnumeratedNameList {
	
	AccessControlManager _manager; // to get at key cache
	HashMap<String, PrincipalInfo> _principals = new HashMap<String, PrincipalInfo>();
	ArrayList<byte []> _keyIDs = new ArrayList<byte []>();
	ArrayList<byte []> _otherNames = new ArrayList<byte []>();
	
	/**
	 * Directory name should be versioned, else we pull the latest version
	 * @param manager
	 * @param directoryName
	 * @param library
	 * @throws IOException
	 */
	public KeyDirectory(AccessControlManager manager, ContentName directoryName, CCNLibrary library) 
					throws IOException {
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
				if (latestVersionName.count() > 1) {
					Library.logger().warning("Unexpected: NE protocol gave back more than one component!");
				}
				_namePrefix = new ContentName(_namePrefix, latestVersionName.component(0));
				_enumerator.registerPrefix(_namePrefix);
			}
		} 
	}
	
	/**
	 * Called each time new data comes in, gets to parse it and load processed
	 * arrays.
	 */
	@Override
	protected void processNewChildren(SortedSet<ContentName> newChildren) {
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
	
	public HashMap<String, PrincipalInfo> getPrincipals() { return _principals; }
	
	public ArrayList<byte []> otherNames() { return _otherNames; }
	
	protected void addPrincipal(byte [] wkChildName) {
		PrincipalInfo pi = AccessControlProfile.parsePrincipalInfoFromNameComponent(wkChildName);
		_principals.put(pi.friendlyName(), pi);
	}
	
	public WrappedKeyObject getWrappedKeyForKeyID(byte [] keyID) throws XMLStreamException, IOException, ConfigurationException {
		if (!_keyIDs.contains(keyID)) {
			return null;
		}
		ContentName wrappedKeyName = getWrappedKeyNameForKeyID(keyID);
		return getWrappedKey(wrappedKeyName);
	}
	
	public ContentName getWrappedKeyNameForKeyID(byte [] keyID) {
		return new ContentName(_namePrefix, AccessControlProfile.targetKeyIDToNameComponent(keyID));
	}
	
	public WrappedKeyObject getWrappedKeyForPrincipal(String principalName) throws IOException, XMLStreamException, ConfigurationException {
		
		if (!_principals.containsKey(principalName)) {
			return null;
		}
		
		PrincipalInfo pi = _principals.get(principalName);
		if (null == pi) {
			Library.logger().info("No block available for principal: " + principalName);
			return null;
		}
		ContentName principalLinkName = getWrappedKeyNameForPrincipal(pi.isGroup(), pi.friendlyName(), pi.versionTimestamp());
		// This should be a link to the actual key block
		// TODO DKS replace link handling
		Link principalLink = _manager.library().getLink(principalLinkName, AccessControlManager.DEFAULT_TIMEOUT);
		Library.logger().info("Retrieving wrapped key for principal " + principalName + " at " + principalLink.getTargetName());
		ContentName wrappedKeyName = principalLink.getTargetName();
		return getWrappedKey(wrappedKeyName);
	}
	
	public ContentName getWrappedKeyNameForPrincipal(boolean isGroup, String principalName, Timestamp principalVersion) {
		ContentName principalLinkName = new ContentName(_namePrefix, 
				AccessControlProfile.principalInfoToNameComponent(isGroup,
																  principalName,
																  principalVersion));
		return principalLinkName;
	}
	
	public ContentName getWrappedKeyNameForPrincipal(ContentName principalPublicKeyName) throws VersionMissingException {
		PrincipalInfo info = AccessControlProfile.parsePrincipalInfoFromPublicKeyName(_manager.groupManager().isGroup(principalPublicKeyName),
																					  principalPublicKeyName);
		return getWrappedKeyNameForPrincipal(info.isGroup(), info.friendlyName(), info.versionTimestamp());
	}
	
	public boolean hasSupersededBlock() {
		return _otherNames.contains(AccessControlProfile.SUPERSEDED_MARKER);
	}
	
	public ContentName getSupersededBlockName() {
		return ContentName.fromNative(_namePrefix, AccessControlProfile.SUPERSEDED_MARKER);
	}
	
	/**
	 * We have several choices for how to represent superseded and previous keys.
	 * Ignoring for now the case where we might have to have more than one per key directory
	 * (e.g. if we represent removal of several interposed ACLs), we could have the
	 * wrapped key block stored in the superseded block location, and the previous
	 * key block be a link, or the previous key block be a wrapped key and the superseded
	 * location be a link. Or we could store wrapped key blocks in both places. Because
	 * the wrapped key blocks can contain the name of the key that wrapped them (but
	 * not the key being wrapped), they in essence are a pointer forward to the replacing
	 * key. So, the superseded block, if it contains a wrapped key, is both a key and a link.
	 * If the block was stored at the previous key, it would not be both a key and a link,
	 * as its wrapping key is indicated by where it is. So it should indeed be a link -- 
	 * except in the case of an interposed ACL, where there is nothing to link to; 
	 * and it instead stores a wrapped key block containing the effective node key that
	 * was the previous key.
	 * @return
	 * @throws XMLStreamException
	 * @throws IOException
	 * @throws ConfigurationException 
	 */
	public WrappedKeyObject getSupersededWrappedKey() throws XMLStreamException, IOException, ConfigurationException {
		if (!hasSupersededBlock())
			return null;
		return getWrappedKey(getSupersededBlockName());
	}
	
	WrappedKeyObject getWrappedKey(ContentName wrappedKeyName) throws XMLStreamException, IOException, ConfigurationException {
		WrappedKeyObject wrappedKey = new WrappedKeyObject(wrappedKeyName, _manager.library());
		wrappedKey.update();
		return wrappedKey;		
	}
	
	public boolean hasPreviousKeyBlock() {
		return _otherNames.contains(AccessControlProfile.PREVIOUS_KEY_NAME);
	}

	public ContentName getPreviousKeyBlockName() {
		return getPreviousKeyBlockName(_namePrefix);
	}
	
	public static ContentName getPreviousKeyBlockName(ContentName keyDirectoryName) {
		return ContentName.fromNative(keyDirectoryName, AccessControlProfile.PREVIOUS_KEY_NAME);		
	}
	
	/**
	 * Previous key might be a link, if we're a simple newer version, or it might
	 * be a wrapped key, if we're an interposed node key. 
	 * DKS TODO
	 * @return
	 * @throws XMLStreamException
	 * @throws IOException
	 */
	public LinkReference getPreviousKey() throws XMLStreamException, IOException {
		if (!hasPreviousKeyBlock())
			return null;
		Link previousKey = _manager.library().getLink(getPreviousKeyBlockName(), AccessControlManager.DEFAULT_TIMEOUT);
		if (null == previousKey) {
			Library.logger().info("Unexpected: no previous key link at " + getPreviousKeyBlockName());
			return null;
		}
		return previousKey.getReference();
	}

	/**
	 * We store a private key as a single block wrapped under a nonce key, which is
	 * then wrapped under the public keys of various principals. The WrappedKey structure
	 * would allow us to do this (wrap private in public) in a single object, with
	 * an inline nonce key, but this option is more efficient.
	 * @return
	 */
	public boolean hasPrivateKeyBlock() {
		return _otherNames.contains(AccessControlProfile.GROUP_PRIVATE_KEY_NAME);
	}

	public ContentName getPrivateKeyBlockName() {
		return ContentName.fromNative(_namePrefix, AccessControlProfile.GROUP_PRIVATE_KEY_NAME);
	}
	
	public WrappedKeyObject getPrivateKeyObject() throws IOException, XMLStreamException, ConfigurationException {
		if (!hasPrivateKeyBlock())
			return null;
		
		return new WrappedKey.WrappedKeyObject(getPrivateKeyBlockName(), _manager.library());
	}
	
	/**
	 * Find a copy of the key block in this directory that we can unwrap (either the private
	 * key wrapping key block or a wrapped raw symmetric key). Chase superseding keys if
	 * we have to. This mechanism should be generic, and should work for node keys
	 * as well as private key wrapping keys in directories following this structure.
	 * @return
	 * @throws IOException 
	 * @throws XMLStreamException 
	 * @throws InvalidCipherTextException 
	 * @throws InvalidKeyException 
	 * @throws ConfigurationException 
	 */
	public Key getUnwrappedKey(byte [] expectedKeyID) throws XMLStreamException, IOException, InvalidKeyException, InvalidCipherTextException, ConfigurationException {
		
		WrappedKeyObject wko = null;
		Key unwrappedKey = null;
		byte [] retrievedKeyID = null;
		// Do we have one of the wrapping keys already in our cache?
		// (This list will be empty only if this key is GONE. If it is, we'll move on
		// to a superseding key below if there is one.)
		// Do we have one of the wrapping keys in our cache?
		for (byte [] keyid : getWrappingKeyIDs()) {
			if (_manager.keyCache().containsKey(keyid)) {
				// We have it, pull the block, unwrap the node key.
				wko = getWrappedKeyForKeyID(keyid);
				if (null != wko.wrappedKey()) {
					unwrappedKey = wko.wrappedKey().unwrapKey(_manager.keyCache().getPrivateKey(keyid));
				}
			}
		}
		
		if (null == unwrappedKey) {
			// Not in cache. Is it superseded?
			if (hasSupersededBlock()) {
				// OK, is the superseding key just a newer version of this key? If it is, roll
				// forward to the latest version and work back from there.
				WrappedKeyObject supersededKeyBlock = getSupersededWrappedKey();
				if (null != supersededKeyBlock) {
					// We could just walk up superseding key hierarchy, and then walk back with
					// decrypted keys. Or we could attempt to jump all the way to the end and then
					// walk back. Not sure there is a significant win in doing the latter, so start
					// with the former... have to touch intervening versions in both cases.
					Library.logger().info("Attempting to retrieve key " + getName() + " by retrieving superseding key " + supersededKeyBlock.wrappedKey().wrappingKeyName());
					
					Key unwrappedSupersedingKey = null;
					KeyDirectory supersedingKeyDirectory = null;
					try {
						supersedingKeyDirectory = new KeyDirectory(_manager, supersededKeyBlock.wrappedKey().wrappingKeyName(), _manager.library());
						// This wraps the key we actually want.
						unwrappedSupersedingKey = supersedingKeyDirectory.getUnwrappedKey(supersededKeyBlock.wrappedKey().wrappingKeyIdentifier());
					} finally {
						supersedingKeyDirectory.stopEnumerating();
					}
					if (null != unwrappedSupersedingKey) {
						_manager.keyCache().addKey(supersedingKeyDirectory.getName(), unwrappedSupersedingKey);
						unwrappedKey = supersededKeyBlock.wrappedKey().unwrapKey(unwrappedSupersedingKey);
					} else {
						Library.logger().info("Unable to retrieve superseding key " + supersededKeyBlock.wrappedKey().wrappingKeyName());
					}
				}

			} else {
				// This is the current key. Enumerate principals and see if we can get a key to unwrap.
				Library.logger().info("At latest version of key " + getName() + ", attempting to unwrap.");
				// Assumption: if this key was encrypted directly for me, I would have had a cache
				// hit already. The assumption is that I pre-load my cache with my own private key(s).
				// So I don't care about principal entries if I get here, I only care about groups.
				// Groups may come in three types: ones I know I am a member of, but don't have this
				// particular key version for, ones I don't know anything about, and ones I believe
				// I'm not a member of but someone might have added me.
				if (_manager.groupManager().haveKnownGroupMemberships()) {
					for (String principal : getPrincipals().keySet()) {
						if ((!_manager.groupManager().isGroup(principal)) || (!_manager.groupManager().amKnownGroupMember(principal))) {
							// On this pass, only do groups that I think I'm a member of. Do them
							// first as it is likely faster.
							continue;
						}
						// I know I am a member of this group, or at least I was last time I checked.
						// Attempt to get this version of the group private key as I don't have it in my cache.
						try {
							Key principalKey = _manager.groupManager().getVersionedPrivateKeyForGroup(this, principal);
							unwrappedKey = unwrapKeyForPrincipal(principal, principalKey);
							if (null == unwrappedKey)
								continue;
						} catch (AccessDeniedException aex) {
							// we're not a member
							continue;
						}
					}
				}
				if (null == unwrappedKey) {
					// OK, we don't have any groups we know we are a member of. Do the other ones.
					// Slower, as we crawl the groups tree.
					for (String principal : getPrincipals().keySet()) {
						if ((!_manager.groupManager().isGroup(principal)) || (_manager.groupManager().amKnownGroupMember(principal))) {
							// On this pass, only do groups that I don't think I'm a member of
							continue;
						}
						if (_manager.groupManager().amCurrentGroupMember(principal)) {
							try {
								Key principalKey = _manager.groupManager().getVersionedPrivateKeyForGroup(this, principal);
								unwrappedKey = unwrapKeyForPrincipal(principal, principalKey);
								if (null == unwrappedKey) {
									Library.logger().warning("Unexpected: we are a member of group " + principal + " but get a null key.");
									continue;
								}
							} catch (AccessDeniedException aex) {
								Library.logger().warning("Unexpected: we are a member of group " + principal + " but get an access denied exception when we try to get its key: " + aex.getMessage());
								continue;
							}
						}
					}
				}
			}
		}
		
		if (null != unwrappedKey) {
			_manager.keyCache().addKey(getName(), unwrappedKey);

			if (null != expectedKeyID) {
				retrievedKeyID = NodeKey.generateKeyID(unwrappedKey);
				if (!Arrays.areEqual(expectedKeyID, retrievedKeyID)) {
					Library.logger().warning("Retrieved and decrypted wrapped key, but it was the wrong key. We wanted " + 
							DataUtils.printBytes(expectedKeyID) + ", we got " + DataUtils.printBytes(retrievedKeyID));
				}
			}
		}
		// DKS TODO -- throw AccessDeniedException?
		return unwrappedKey;
	}
		
	protected Key unwrapKeyForPrincipal(String principal, Key unwrappingKey) 
			throws IOException, XMLStreamException, InvalidKeyException, InvalidCipherTextException, ConfigurationException {
		
		Key unwrappedKey = null;
		if (null == unwrappingKey) {
			Library.logger().info("Null unwrapping key. Cannot unwrap.");
			return null;
		}
		WrappedKeyObject wko = getWrappedKeyForPrincipal(principal);
		if (null != wko.wrappedKey()) {
			unwrappedKey = wko.wrappedKey().unwrapKey(unwrappingKey);
		} else {
			Library.logger().info("Unexpected: retrieved version " + getPrincipals().get(principal) + " of " + principal + " group key, but cannot retrieve wrapped key object.");
		}
		return unwrappedKey;
	}
		
	/**
	 * Relies on caller, who presumably knows the public key, to add the result to the
	 * cache.
	 * @return
	 * @throws AccessDeniedException
	 * @throws IOException
	 * @throws XMLStreamException
	 * @throws InvalidKeyException
	 * @throws InvalidCipherTextException
	 * @throws AccessDeniedException 
	 * @throws ConfigurationException 
	 */
	public PrivateKey getPrivateKey() throws IOException, XMLStreamException, 
				InvalidKeyException, InvalidCipherTextException, AccessDeniedException, ConfigurationException {
		if (!hasPrivateKeyBlock()) {
			Library.logger().info("No private key block exists with name " + getPrivateKeyBlockName());
			return null;
		}
		WrappedKeyObject wko = getPrivateKeyObject();
		if ((null == wko) || (null == wko.wrappedKey())) {
			Library.logger().info("Cannot retrieve wrapped private key for " + getPrivateKeyBlockName());
			return null;
		}
		// This should throw AccessDeniedException...
		Key wrappingKey = getUnwrappedKey(wko.wrappedKey().wrappingKeyIdentifier());
		if (null == wrappingKey) {
			Library.logger().info("Cannot get key to unwrap private key " + getPrivateKeyBlockName());
			throw new AccessDeniedException("Cannot get key to unwrap private key " + getPrivateKeyBlockName());
		}
		
		Key unwrappedPrivateKey = wko.wrappedKey().unwrapKey(wrappingKey);
		if (!(unwrappedPrivateKey instanceof PrivateKey)) {
			Library.logger().info("Unwrapped private key is not an instance of PrivateKey! Its an " + unwrappedPrivateKey.getClass().getName());
		} else {
			Library.logger().info("Unwrapped private key is a private key, in fact it's a " + unwrappedPrivateKey.getClass().getName());
		}
		return (PrivateKey)unwrappedPrivateKey;
	}

	/**
	 * Eventually aggregate signing and repo stream operations at the very
	 * least across writing paired objects and links, preferably across larger
	 * swaths of data.
	 * @param secretKeyToWrap either a node key, a data key, or a private key wrapping key
	 * @param publicKeyName
	 * @param publicKey
	 * @throws VersionMissingException
	 * @throws XMLStreamException
	 * @throws IOException
	 * @throws InvalidKeyException
	 * @throws ConfigurationException 
	 * @throws VersionMissingException 
	 */
	public void addWrappedKeyBlock(Key secretKeyToWrap, 
								   ContentName publicKeyName, PublicKey publicKey) throws XMLStreamException, IOException, InvalidKeyException, ConfigurationException, VersionMissingException {
		WrappedKey wrappedKey = WrappedKey.wrapKey(secretKeyToWrap, null, null, publicKey);
		wrappedKey.setWrappingKeyIdentifier(publicKey);
		wrappedKey.setWrappingKeyName(publicKeyName);
		WrappedKeyObject wko = 
			new WrappedKeyObject(getWrappedKeyNameForKeyID(WrappedKey.wrappingKeyIdentifier(publicKey)),
								 wrappedKey, _manager.library());
		wko.saveToRepository();
		LinkObject lo = new LinkObject(getWrappedKeyNameForPrincipal(publicKeyName), new LinkReference(wko.getName()), _manager.library());
		lo.saveToRepository();
	}
	
	public void addPrivateKeyBlock(PrivateKey privateKey, Key privateKeyWrappingKey) throws InvalidKeyException, XMLStreamException, IOException, ConfigurationException {
		
		WrappedKey wrappedKey = WrappedKey.wrapKey(privateKey, null, null, privateKeyWrappingKey);	
		wrappedKey.setWrappingKeyIdentifier(privateKeyWrappingKey);
		WrappedKeyObject wko = new WrappedKeyObject(getPrivateKeyBlockName(), wrappedKey, _manager.library());
		wko.saveToRepository();
	}

	/**
	 * Add a superseded-by block to our key directory.
	 * @param oldPrivateKeyWrappingKey
	 * @param supersedingKeyName
	 * @param newPrivateKeyWrappingKey
	 * @throws XMLStreamException
	 * @throws IOException
	 * @throws InvalidKeyException
	 * @throws ConfigurationException
	 */
	public void addSupersededByBlock(Key oldPrivateKeyWrappingKey,
			ContentName supersedingKeyName, Key newPrivateKeyWrappingKey) throws XMLStreamException, IOException, InvalidKeyException, ConfigurationException {
		
		addSupersededByBlock(getSupersededBlockName(), oldPrivateKeyWrappingKey,
						     supersedingKeyName, newPrivateKeyWrappingKey, _manager.library());
	}
	
	/**
	 * Add a superseded-by block to another node key, where we may have only its name, not its enumeration.
	 * Use as a static method to add our own superseded-by blocks as well.
	 * @throws IOException 
	 * @throws InvalidKeyException 
	 */
	public static void addSupersededByBlock(ContentName oldKeySupersededBlockName, Key oldKeyToBeSuperseded, 
											ContentName supersedingKeyName, Key supersedingKey, CCNLibrary library) throws IOException, InvalidKeyException {
		
		WrappedKey wrappedKey = WrappedKey.wrapKey(oldKeyToBeSuperseded, null, null, supersedingKey);
		wrappedKey.setWrappingKeyIdentifier(supersedingKey);
		wrappedKey.setWrappingKeyName(supersedingKeyName);
		WrappedKeyObject wko = new WrappedKeyObject(oldKeySupersededBlockName, wrappedKey, library);
		wko.saveToRepository();
	}

	/**
	 * 
	 * @param previousKey
	 * @param previousKeyPublisher
	 * @throws XMLStreamException
	 * @throws IOException
	 * @throws ConfigurationException
	 */ 
	public void addPreviousKeyLink(ContentName previousKey, PublisherID previousKeyPublisher) throws XMLStreamException, IOException, ConfigurationException {
		
		if (hasPreviousKeyBlock()) {
			Library.logger().warning("Unexpected, already have previous key block : " + getPreviousKeyBlockName());
		}
		LinkAuthenticator la = (null != previousKeyPublisher) ? new LinkAuthenticator(previousKeyPublisher) : null;
		LinkObject pklo = new LinkObject(getPreviousKeyBlockName(), new LinkReference(previousKey,la), _manager.library());
		pklo.saveToRepository();
	}
	
	public void addPreviousKeyBlock(Key oldPrivateKeyWrappingKey,
									ContentName supersedingKeyName, Key newPrivateKeyWrappingKey) throws InvalidKeyException, XMLStreamException, IOException, ConfigurationException {
		// DKS TODO -- do we need in the case of deletion of ACLs to allow for multiple previous key blocks simultaneously?
		// Then need to add previous key id to previous key block name.
		WrappedKey wrappedKey = WrappedKey.wrapKey(oldPrivateKeyWrappingKey, null, null, newPrivateKeyWrappingKey);
		wrappedKey.setWrappingKeyIdentifier(newPrivateKeyWrappingKey);
		wrappedKey.setWrappingKeyName(supersedingKeyName);
		WrappedKeyObject wko = new WrappedKeyObject(getPreviousKeyBlockName(), wrappedKey, _manager.library());
		wko.saveToRepository();
	}
}
