package org.ccnx.ccn.profiles.access;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Comparator;
import java.util.HashMap;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import javax.xml.stream.XMLStreamException;

import org.bouncycastle.crypto.InvalidCipherTextException;
import org.bouncycastle.util.Arrays;
import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.impl.support.ByteArrayCompare;
import org.ccnx.ccn.impl.support.DataUtils;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.Link;
import org.ccnx.ccn.io.content.LinkAuthenticator;
import org.ccnx.ccn.io.content.WrappedKey;
import org.ccnx.ccn.io.content.Link.LinkObject;
import org.ccnx.ccn.io.content.WrappedKey.WrappedKeyObject;
import org.ccnx.ccn.profiles.VersionMissingException;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.profiles.access.AccessControlProfile.PrincipalInfo;
import org.ccnx.ccn.profiles.nameenum.EnumeratedNameList;
import org.ccnx.ccn.protocol.CCNTime;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.PublisherID;


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
	
	static Comparator<byte[]> byteArrayComparator = new ByteArrayCompare();
	
	// time spent waiting for new data (in ms) before responding to read queries 
	static final long DEFAULT_TIMEOUT = 5000;	
	
	AccessControlManager _manager; // to get at key cache
	HashMap<String, PrincipalInfo> _principals = new HashMap<String, PrincipalInfo>();
	private final ReadWriteLock _principalsLock = new ReentrantReadWriteLock();
	TreeSet<byte []> _keyIDs = new TreeSet<byte []>(byteArrayComparator);
	private final ReadWriteLock _keyIDLock = new ReentrantReadWriteLock();
	TreeSet<byte []> _otherNames = new TreeSet<byte []>(byteArrayComparator);
	private final ReadWriteLock _otherNamesLock = new ReentrantReadWriteLock();
	/**
	 * Directory name should be versioned, else we pull the latest version
	 * @param manager
	 * @param directoryName
	 * @param library
	 * @throws IOException
	 */
	public KeyDirectory(AccessControlManager manager, ContentName directoryName, CCNHandle library) 
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
		if (!VersioningProfile.hasTerminalVersion(_namePrefix)) {
			getNewData();
			ContentName latestVersionName = getLatestVersionChildName();
			if (null == latestVersionName) {
				Log.info("Unexpected: can't get a latest version for key directory name : " + _namePrefix);
				getNewData();
				latestVersionName = getLatestVersionChildName();
				if (null == latestVersionName) {
					Log.info("Unexpected: really can't get a latest version for key directory name : " + _namePrefix);
					throw new IOException("Unexpected: really can't get a latest version for key directory name : " + _namePrefix);
				}
			}
			Log.finer("KeyDirectory, got latest version of {0}, name {1}", _namePrefix, latestVersionName);
			synchronized (_childLock) {
				stopEnumerating();
				_children.clear();
				_newChildren = null;
				if (latestVersionName.count() > 1) {
					Log.warning("Unexpected: NE protocol gave back more than one component!");
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
					try{
						_keyIDLock.writeLock().lock();
						_keyIDs.add(keyid);
					}finally{
						_keyIDLock.writeLock().unlock();
					}
				} catch (IOException e) {
					Log.info("Unexpected " + e.getClass().getName() + " parsing key id " + DataUtils.printHexBytes(wkChildName) + ": " + e.getMessage());
					// ignore and go on
				}
			} else if (AccessControlProfile.isPrincipalNameComponent(wkChildName)) {
				addPrincipal(wkChildName);
			} else {
				try{
					_otherNamesLock.writeLock().lock();
					_otherNames.add(wkChildName);
				}finally{
					_otherNamesLock.writeLock().unlock();
				}
			}
		}
	}
	
	private void waitForDataBeforeReading() {
		long timeRemaining = DEFAULT_TIMEOUT;
		while (timeRemaining>0) {
			long startTime = System.currentTimeMillis();
			this.getNewData(timeRemaining);
			timeRemaining -= System.currentTimeMillis() - startTime;
		}
	}
	
	/**
	 * Waits for new data, then
	 * Returns a copy to avoid synchronization problems
	 * 
	 */
	public TreeSet<byte []> getCopyOfWrappingKeyIDs() {
		waitForDataBeforeReading();
		TreeSet<byte []> copy = new TreeSet<byte []>(byteArrayComparator);
		try {
			_keyIDLock.readLock().lock();
			for (byte[] elt: _keyIDs) copy.add(elt);
		} finally {
			_keyIDLock.readLock().unlock();
		}
		return copy; 	
	}
	
	/**
	 * Waits for new data, then
	 * Return a copy to avoid synchronization problems
	 */
	public HashMap<String, PrincipalInfo> getCopyOfPrincipals() {
		waitForDataBeforeReading();
		HashMap<String, PrincipalInfo> copy = new HashMap<String, PrincipalInfo>();
		try {
			_principalsLock.readLock().lock();
			for (String key: _principals.keySet()) {
				PrincipalInfo value = _principals.get(key);
				copy.put(key, value);
			}
		} finally {
			_principalsLock.readLock().unlock();
		}
		return copy; 	
	}
	
	/**
	 * Waits for new data, then returns principal info
	 */
	public PrincipalInfo getPrincipalInfo(String principal) {
		waitForDataBeforeReading();
		PrincipalInfo pi = null;
		try {
			_principalsLock.readLock().lock();
			pi = _principals.get(principal);
		} finally {
			_principalsLock.readLock().unlock();
		}
		return pi;
	}
	
	/**
	 * Waits for new data, then 
	 * Returns a copy to avoid synchronization problems
	 */
	public TreeSet<byte []> getCopyOfOtherNames() {
		waitForDataBeforeReading();
		TreeSet<byte []> copy = new TreeSet<byte []>(byteArrayComparator);
		try {
			_otherNamesLock.readLock().lock();
			for (byte[] elt: _otherNames) copy.add(elt);
		} finally {
			_otherNamesLock.readLock().unlock();
		}
		return copy;	
	}
	
	protected void addPrincipal(byte [] wkChildName) {
		PrincipalInfo pi = AccessControlProfile.parsePrincipalInfoFromNameComponent(wkChildName);
		try{
				_principalsLock.writeLock().lock();
				_principals.put(pi.friendlyName(), pi);
		}finally{
			_principalsLock.writeLock().unlock();
		}
	}
	
	/**
	 * Waits for new data, then returns wrapped key object
	 */
	public WrappedKeyObject getWrappedKeyForKeyID(byte [] keyID) throws XMLStreamException, IOException, ConfigurationException {
		waitForDataBeforeReading();
		try{
			_keyIDLock.readLock().lock();
			if (!_keyIDs.contains(keyID)) {
				return null;
			}
		}finally{
			_keyIDLock.readLock().unlock();
		}
		ContentName wrappedKeyName = getWrappedKeyNameForKeyID(keyID);
		return getWrappedKey(wrappedKeyName);
	}
	
	public ContentName getWrappedKeyNameForKeyID(byte [] keyID) {
		return new ContentName(_namePrefix, AccessControlProfile.targetKeyIDToNameComponent(keyID));
	}
	
	/**
	 * Waits for new data then returns wrapped key object
	 */
	public WrappedKeyObject getWrappedKeyForPrincipal(String principalName) throws IOException, XMLStreamException, ConfigurationException {
		waitForDataBeforeReading();
		
		PrincipalInfo pi = null;
		try{
			_principalsLock.readLock().lock();
			if (!_principals.containsKey(principalName)) {
				return null;
			}
			pi = _principals.get(principalName);
		}finally{
			_principalsLock.readLock().unlock();
		}
		if (null == pi) {
			Log.info("No block available for principal: " + principalName);
			return null;
		}
		ContentName principalLinkName = getWrappedKeyNameForPrincipal(pi.isGroup(), pi.friendlyName(), pi.versionTimestamp());
		// This should be a link to the actual key block
		// TODO DKS should wait on link data...
		LinkObject principalLink = new LinkObject(principalLinkName, _manager.library());
		Log.info("Retrieving wrapped key for principal " + principalName + " at " + principalLink.getTargetName());
		ContentName wrappedKeyName = principalLink.getTargetName();
		return getWrappedKey(wrappedKeyName);
	}
	
	public ContentName getWrappedKeyNameForPrincipal(boolean isGroup, String principalName, CCNTime principalVersion) {
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

	/**
	 * Waits for new data before checking for the existence of a superseded block
	 */
	public boolean hasSupersededBlock() {
		waitForDataBeforeReading();
		boolean b = false;
		try{
			_otherNamesLock.readLock().lock();
			b = _otherNames.contains(AccessControlProfile.SUPERSEDED_MARKER.getBytes());
		}finally{
			_otherNamesLock.readLock().unlock();
		}
		return b;
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
	 * This method waits for new data before checking for the existence of a superseded block
	 * @return
	 * @throws XMLStreamException
	 * @throws IOException
	 * @throws ConfigurationException 
	 */
	public WrappedKeyObject getSupersededWrappedKey() throws XMLStreamException, IOException, ConfigurationException {
		waitForDataBeforeReading();
		if (!hasSupersededBlock())
			return null;
		return getWrappedKey(getSupersededBlockName());
	}
	
	public WrappedKeyObject getWrappedKey(ContentName wrappedKeyName) throws XMLStreamException, IOException, ConfigurationException {
		WrappedKeyObject wrappedKey = new WrappedKeyObject(wrappedKeyName, _manager.library());
		wrappedKey.update();
		return wrappedKey;		
	}
	
	/**
	 * Waits for new data before checking for the existence of a previous key block
	 */
	public boolean hasPreviousKeyBlock() {
		waitForDataBeforeReading();
		boolean b;
		try{
			_otherNamesLock.readLock().lock();
			b = _otherNames.contains(AccessControlProfile.PREVIOUS_KEY_NAME.getBytes());
		}finally{
			_otherNamesLock.readLock().unlock();
		}
		return b;
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
	public Link getPreviousKey() throws XMLStreamException, IOException {
		if (!hasPreviousKeyBlock())
			return null;
		LinkObject previousKey = new LinkObject(getPreviousKeyBlockName(), _manager.library());
		previousKey.waitForData(); // TODO timeout?
		if (!previousKey.available()) {
			Log.info("Unexpected: no previous key link at " + getPreviousKeyBlockName());
			return null;
		}
		return previousKey.link();
	}

	/**
	 * We store a private key as a single block wrapped under a nonce key, which is
	 * then wrapped under the public keys of various principals. The WrappedKey structure
	 * would allow us to do this (wrap private in public) in a single object, with
	 * an inline nonce key, but this option is more efficient.
	 * Waits for new data before checking for the existence of a private key block
	 * @return
	 */
	public boolean hasPrivateKeyBlock() {
		waitForDataBeforeReading();
		boolean b;
		try{
			_otherNamesLock.readLock().lock();
			b = _otherNames.contains(AccessControlProfile.GROUP_PRIVATE_KEY_NAME.getBytes());
		}finally{
			_otherNamesLock.readLock().unlock();
		}
		return b;
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
	 * First, wait for new data.
	 * Then find a copy of the key block in this directory that we can unwrap (either the private
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
		
		waitForDataBeforeReading();
		try{
			_keyIDLock.readLock().lock();
			for (byte [] keyid : _keyIDs) {
				if (_manager.keyCache().containsKey(keyid)) {
					// We have it, pull the block, unwrap the node key.
					wko = getWrappedKeyForKeyID(keyid);
					if (null != wko.wrappedKey()) {
						unwrappedKey = wko.wrappedKey().unwrapKey(_manager.keyCache().getPrivateKey(keyid));
					}
				}
			}
		}finally{
			_keyIDLock.readLock().unlock();
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
					Log.info("Attempting to retrieve key " + getName() + " by retrieving superseding key " + supersededKeyBlock.wrappedKey().wrappingKeyName());
					
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
						Log.info("Unable to retrieve superseding key " + supersededKeyBlock.wrappedKey().wrappingKeyName());
					}
				}

			} else {
				// This is the current key. Enumerate principals and see if we can get a key to unwrap.
				Log.info("At latest version of key " + getName() + ", attempting to unwrap.");
				// Assumption: if this key was encrypted directly for me, I would have had a cache
				// hit already. The assumption is that I pre-load my cache with my own private key(s).
				// So I don't care about principal entries if I get here, I only care about groups.
				// Groups may come in three types: ones I know I am a member of, but don't have this
				// particular key version for, ones I don't know anything about, and ones I believe
				// I'm not a member of but someone might have added me.
				if (_manager.groupManager().haveKnownGroupMemberships()) {
					try{
						_principalsLock.readLock().lock();
						for (String principal : _principals.keySet()) {
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
					}finally{
						_principalsLock.readLock().unlock();
					}
				}
				if (null == unwrappedKey) {
					// OK, we don't have any groups we know we are a member of. Do the other ones.
					// Slower, as we crawl the groups tree.
					try{
							_principalsLock.readLock().lock();
							for (String principal : _principals.keySet()) {
								if ((!_manager.groupManager().isGroup(principal)) || (_manager.groupManager().amKnownGroupMember(principal))) {
									// On this pass, only do groups that I don't think I'm a member of
									continue;
								}
								if (_manager.groupManager().amCurrentGroupMember(principal)) {
									try {
										Key principalKey = _manager.groupManager().getVersionedPrivateKeyForGroup(this, principal);
										unwrappedKey = unwrapKeyForPrincipal(principal, principalKey);
										if (null == unwrappedKey) {
											Log.warning("Unexpected: we are a member of group " + principal + " but get a null key.");
											continue;
										}
									} catch (AccessDeniedException aex) {
										Log.warning("Unexpected: we are a member of group " + principal + " but get an access denied exception when we try to get its key: " + aex.getMessage());
										continue;
									}
								}
							}
					}finally{
						_principalsLock.readLock().unlock();
					}
				}
			}
		}
		
		if (null != unwrappedKey) {
			_manager.keyCache().addKey(getName(), unwrappedKey);

			if (null != expectedKeyID) {
				retrievedKeyID = NodeKey.generateKeyID(unwrappedKey);
				if (!Arrays.areEqual(expectedKeyID, retrievedKeyID)) {
					Log.warning("Retrieved and decrypted wrapped key, but it was the wrong key. We wanted " + 
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
			Log.info("Null unwrapping key. Cannot unwrap.");
			return null;
		}
		WrappedKeyObject wko = getWrappedKeyForPrincipal(principal);
		if (null != wko.wrappedKey()) {
			unwrappedKey = wko.wrappedKey().unwrapKey(unwrappingKey);
		} else {
			try{
				_principalsLock.readLock().lock();
				Log.info("Unexpected: retrieved version " + _principals.get(principal) + " of " + principal + " group key, but cannot retrieve wrapped key object.");
			}finally{
				_principalsLock.readLock().unlock();
			}
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
			Log.info("No private key block exists with name " + getPrivateKeyBlockName());
			return null;
		}
		WrappedKeyObject wko = getPrivateKeyObject();
		if ((null == wko) || (null == wko.wrappedKey())) {
			Log.info("Cannot retrieve wrapped private key for " + getPrivateKeyBlockName());
			return null;
		}
		// This should throw AccessDeniedException...
		Key wrappingKey = getUnwrappedKey(wko.wrappedKey().wrappingKeyIdentifier());
		if (null == wrappingKey) {
			Log.info("Cannot get key to unwrap private key " + getPrivateKeyBlockName());
			throw new AccessDeniedException("Cannot get key to unwrap private key " + getPrivateKeyBlockName());
		}
		
		Key unwrappedPrivateKey = wko.wrappedKey().unwrapKey(wrappingKey);
		if (!(unwrappedPrivateKey instanceof PrivateKey)) {
			Log.info("Unwrapped private key is not an instance of PrivateKey! Its an " + unwrappedPrivateKey.getClass().getName());
		} else {
			Log.info("Unwrapped private key is a private key, in fact it's a " + unwrappedPrivateKey.getClass().getName());
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
		LinkObject lo = new LinkObject(getWrappedKeyNameForPrincipal(publicKeyName), new Link(wko.getVersionedName()), _manager.library());
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
											ContentName supersedingKeyName, Key supersedingKey, CCNHandle library) throws IOException, InvalidKeyException {
		
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
			Log.warning("Unexpected, already have previous key block : " + getPreviousKeyBlockName());
		}
		LinkAuthenticator la = (null != previousKeyPublisher) ? new LinkAuthenticator(previousKeyPublisher) : null;
		LinkObject pklo = new LinkObject(getPreviousKeyBlockName(), new Link(previousKey,la), _manager.library());
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
