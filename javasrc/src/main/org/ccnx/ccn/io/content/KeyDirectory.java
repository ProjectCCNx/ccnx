/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008-2013 Palo Alto Research Center, Inc.
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

package org.ccnx.ccn.io.content;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Comparator;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;

import org.bouncycastle.util.Arrays;
import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.impl.CCNFlowControl.SaveType;
import org.ccnx.ccn.impl.security.keys.SecureKeyCache;
import org.ccnx.ccn.impl.support.ByteArrayCompare;
import org.ccnx.ccn.impl.support.DataUtils;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.Link.LinkObject;
import org.ccnx.ccn.io.content.WrappedKey.WrappedKeyObject;
import org.ccnx.ccn.profiles.VersionMissingException;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.profiles.nameenum.EnumeratedNameList;
import org.ccnx.ccn.profiles.security.KeyProfile;
import org.ccnx.ccn.profiles.security.access.AccessDeniedException;
import org.ccnx.ccn.profiles.security.access.group.NodeKey;
import org.ccnx.ccn.protocol.Component;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.PublisherID;


/**
 * A key directory holds a key (secret or private), distributed to entities
 * (represented by public keys), by a set of key blocks each of which
 * wrapping that key under different target keys. If the key to be distributed
 * is a private key, it is first wrapped under a nonce key, and that nonce
 * key is stored encrypted under the keys of the receiving entitites.
 *
 * Essentially a KeyDirectory is a software wrapper for managing a set of content
 * stored in CCNx (writing and reading portions of that content); that content
 * consists of a set of key blocks used to give one key to a number of target
 * entities.
 *
 * Key blocks
 * are implemented as a set of wrapped key objects
 * all stored in one directory. Wrapped key objects are typically short
 * and only need one segment. The directory the keys are stored in
 * is prefixed by a version, to allow the contents to evolve. In addition
 * some potential supporting information pointing to previous
 * or subsequent versions of this key is kept. A particular wrapped key
 * entry's name would look like:
 *
 * <pre><keyname>/#version/xxx/s0</pre>
 * <br>Where xxx is the identifier of the wrapped key.
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

	public static final Component SUPERSEDED_MARKER = new Component("SupersededBy");
	public static final Component PREVIOUS_KEY = new Component("PreviousKey");
	public static final Component GROUP_PUBLIC_KEY = new Component("Key");
	public static final Component GROUP_PRIVATE_KEY = new Component("PrivateKey");

	protected static final Comparator<byte[]> byteArrayComparator = new ByteArrayCompare();

	protected CCNHandle _handle;
	protected boolean cacheHit; // true if one of the unwrapping keys is in our cache

	/**
	 * The set _keyIDs contains the digests of the (public) wrapping keys
	 * of the wrapped key objects stored in the KeyDirectory.
	 */
	TreeSet<byte []> _keyIDs = new TreeSet<byte []>(byteArrayComparator);
	final ReadWriteLock _keyIDLock = new ReentrantReadWriteLock();

	/**
	 * The set _otherNames records the presence of superseded keys, previous keys,
	 * group private keys, etc.
	 */
	TreeSet<byte []> _otherNames = new TreeSet<byte []>(byteArrayComparator);
	final ReadWriteLock _otherNamesLock = new ReentrantReadWriteLock();

	/**
	 * Directory name should be versioned, else we pull the latest version; start
	 * enumeration.
	 * @param manager the access control manager.
	 * @param directoryName the root of the KeyDirectory.
	 * @param handle
	 * @throws IOException
	 */
	public KeyDirectory(ContentName directoryName, CCNHandle handle)
	throws IOException {
		this(directoryName, true, handle);
	}

	/**
	 * Directory name should be versioned, else we pull the latest version.
	 * @param directoryName the root of the KeyDirectory.
	 * @param handle
	 * @throws IOException
	 */
	public KeyDirectory(ContentName directoryName, boolean enumerate, CCNHandle handle)
	throws IOException {
		super(directoryName, false, handle);
		_handle = handle;
		cacheHit = false;
		initialize(enumerate);
	}

	/**
	 * We don't start enumerating until we get here.
	 * @throws IOException
	 */
	protected void initialize(boolean startEnumerating) throws IOException {
		if (!VersioningProfile.hasTerminalVersion(_namePrefix)) {
			ContentObject latestVersionObject =
				VersioningProfile.getLatestVersion(_namePrefix,
						null, SystemConfiguration.MAX_TIMEOUT, _enumerator.handle().defaultVerifier(), _enumerator.handle());

			if (null == latestVersionObject) {
				throw new IOException("Cannot find content for any version of " + _namePrefix + "!");
			}

			ContentName versionedNamePrefix =
				latestVersionObject.name().subname(0, _namePrefix.count() + 1);

			if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.FINE)) {
				Log.fine(Log.FAC_ACCESSCONTROL, "KeyDirectory: initialize: {0} is the latest version found for {1}.", versionedNamePrefix, _namePrefix);
			}
			_namePrefix = versionedNamePrefix;
		}

		// We don't register prefix in constructor anymore; don't start enumerating till we finish
		// initialize. Note that if you subclass KeyDirectory, will need to override initialize().
		if (startEnumerating) {
			synchronized(_childLock) {
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
			processNewChild(wkChildName);
		}
	}

	protected void processNewChild(byte[] wkChildName) {
		if (KeyProfile.isKeyNameComponent(wkChildName)) {
			byte[] keyid = KeyProfile.getKeyIDFromNameComponent(wkChildName);
			try{
				_keyIDLock.writeLock().lock();
				_keyIDs.add(keyid);
			} finally {
				_keyIDLock.writeLock().unlock();
			}
			if (_handle.keyManager().getSecureKeyCache().containsKey(keyid)) cacheHit = true;
		} else {
			try{
				_otherNamesLock.writeLock().lock();
				_otherNames.add(wkChildName);
			}finally{
				_otherNamesLock.writeLock().unlock();
			}
		}
	}

	@Override
	public boolean hasResult() {
		// For now, we only report a result if we have an unwrapping key that is in our key cache.
		// TODO: also consider reporting results if we have an unwrapping key for a group that we know we're a member of.
		return cacheHit;
	}

	/**
	 * Return a copy to avoid synchronization problems.
	 * @throws ContentNotReadyException
	 *
	 */
	public TreeSet<byte []> getCopyOfWrappingKeyIDs() throws ContentNotReadyException {
		if (!hasChildren()) {
			throw new ContentNotReadyException("Need to call waitForData(); assuming directory known to be non-empty!");
		}
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
	 * Returns a copy to avoid synchronization problems
	 * @throws ContentNotReadyException
	 */
	public TreeSet<byte []> getCopyOfOtherNames() throws ContentNotReadyException {
		if (!hasChildren()) {
			throw new ContentNotReadyException("Need to call waitForData(); assuming directory known to be non-empty!");
		}
		TreeSet<byte []> copy = new TreeSet<byte []>(byteArrayComparator);
		try {
			_otherNamesLock.readLock().lock();
			for (byte[] elt: _otherNames) copy.add(elt);
		} finally {
			_otherNamesLock.readLock().unlock();
		}
		return copy;
	}

	/**
	 * Returns the wrapped key object corresponding to a public key specified by its digest.
	 * Up to caller to decide when this is reasonable to call; should call available() on result.
	 * @param keyID the digest of the specified public key.
	 * @return the corresponding wrapped key object.
	 * @throws ContentDecodingException
	 * @throws IOException
	 */
	public WrappedKeyObject getWrappedKeyForKeyID(byte [] keyID) throws ContentDecodingException, IOException {

		ContentName wrappedKeyName = getWrappedKeyNameForKeyID(keyID);
		return getWrappedKey(wrappedKeyName);
	}

	/**
	 * Returns the wrapped key name for a public key specified by its digest.
	 * @param keyID the digest of the public key.
	 * @return the corresponding wrapped key name.
	 */
	public ContentName getWrappedKeyNameForKeyID(byte [] keyID) {
		return KeyProfile.keyName(_namePrefix, keyID);
	}

	/**
	 * Checks for the existence of a superseded block.
	 * @throws ContentNotReadyException
	 */
	public boolean hasSupersededBlock() throws ContentNotReadyException {
		if (!hasChildren()) {
			throw new ContentNotReadyException("Need to call waitForData(); assuming directory known to be non-empty!");
		}
		boolean b = false;
		try{
			_otherNamesLock.readLock().lock();
			b = _otherNames.contains(SUPERSEDED_MARKER.getComponent());
		} finally {
			_otherNamesLock.readLock().unlock();
		}
		return b;
	}

	public ContentName getSupersededBlockName() {
		return getSupersededBlockNameForKey(_namePrefix);
	}

	public static ContentName getSupersededBlockNameForKey(ContentName versionedKeyName) {
		return new ContentName(versionedKeyName, SUPERSEDED_MARKER);
	}

	/**
	 * We have several choices for how to represent superseded and previous keys.
	 * Ignoring for now the case where we might have to have more than one per key directory
	 * (e.g. if we represent removal of several interposed ACLs), we could have the
	 * wrapped key block stored in the superseded block location, and the previous
	 * key block be a link, or the previous key block be a wrapped key and the superseded
	 * location be a link. Or we could store wrapped key blocks in both places. Because
	 * the wrapped key blocks can contain the name of the key that wrapped them (but
	 * not the key being wrapped), they are in essence a pointer forward to the replacing
	 * key. So, the superseded block, if it contains a wrapped key, is both a key and a link.
	 * If the block was stored at the previous key, it would not be both a key and a link,
	 * as its wrapping key is indicated by where it is. So it should indeed be a link --
	 * except in the case of an interposed ACL, where there is nothing to link to;
	 * and it instead stores a wrapped key block containing the effective node key that
	 * was the previous key.
	 * This method checks for the existence of a superseded block.
	 * @return
	 * @throws ContentNotReadyException
	 * @throws ContentDecodingException
	 * @throws IOException
	 */
	public WrappedKeyObject getSupersededWrappedKey() throws ContentDecodingException, ContentNotReadyException, IOException {
		if (!hasChildren()) {
			throw new ContentNotReadyException("Need to call waitForData(); assuming directory known to be non-empty!");
		}
		if (!hasSupersededBlock())
			return null;
		return getWrappedKey(getSupersededBlockName());
	}

	/**
	 * Returns the wrapped key object corresponding to the specified wrapped key name. We know
	 * there is only one version of this object, so avoid getLatestVersion.
	 * @param wrappedKeyName
	 * @return
	 * @throws IOException
	 * @throws ContentDecodingException
	 */
	public WrappedKeyObject getWrappedKey(ContentName wrappedKeyName) throws ContentDecodingException, IOException {
		WrappedKeyObject wrappedKey = null;

		if (VersioningProfile.hasTerminalVersion(wrappedKeyName)) {
			wrappedKey = new WrappedKeyObject(wrappedKeyName, _handle);
			if (!wrappedKey.available()) { // for some reason we timed out, try again.
				wrappedKey.update();
			}
		} else {
			wrappedKey = new WrappedKeyObject(wrappedKeyName, null, null, _handle);
			wrappedKey.updateAny();
			if (!wrappedKey.available()) { // for some reason we timed out, try again.
				wrappedKey.updateAny();
			}
		}
		return wrappedKey;
	}

	/**
	 * Checks for the existence of a previous key block
	 * @throws ContentNotReadyException
	 */
	public boolean hasPreviousKeyBlock() throws ContentNotReadyException {
		if (!hasChildren()) {
			throw new ContentNotReadyException("Need to call waitForData(); assuming directory known to be non-empty!");
		}
		boolean b;
		try{
			_otherNamesLock.readLock().lock();
			b = _otherNames.contains(PREVIOUS_KEY.getComponent());
		}finally{
			_otherNamesLock.readLock().unlock();
		}
		return b;
	}

	public ContentName getPreviousKeyBlockName() {
		return getPreviousKeyBlockName(_namePrefix);
	}

	public static ContentName getPreviousKeyBlockName(ContentName keyDirectoryName) {
		return new ContentName(keyDirectoryName, PREVIOUS_KEY);
	}

	/**
	 * Returns a link to the previous key.
	 * Previous key might be a link, if we're a simple newer version, or it might
	 * be a wrapped key, if we're an interposed node key.
	 * DKS TODO
	 * @return
	 * @throws IOException
	 * @throws ContentNotReadyException
	 * @throws ContentDecodingException
	 */
	public Link getPreviousKey(long timeout) throws ContentNotReadyException, IOException {
		if (!hasChildren()) {
			throw new ContentNotReadyException("Need to call waitForData(); assuming directory known to be non-empty!");
		}
		if (!hasPreviousKeyBlock())
			return null;
		LinkObject previousKey = new LinkObject(getPreviousKeyBlockName(), _handle);
		previousKey.waitForData(timeout);
		if (!previousKey.available()) {
			if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.INFO)) {
				Log.info(Log.FAC_ACCESSCONTROL, "Unexpected: no previous key link at {0}", getPreviousKeyBlockName());
			}
			return null;
		}
		return previousKey.link();
	}

	/**
	 * We store a private key as a single block wrapped under a nonce key, which is
	 * then wrapped under the public keys of various principals. The WrappedKey structure
	 * would allow us to do this (wrap private in public) in a single object, with
	 * an inline nonce key, but this option is more efficient.
	 * Checks for the existence of a private key block
	 * @return
	 * @throws ContentNotReadyException
	 */
	public boolean hasPrivateKeyBlock() throws ContentNotReadyException {
		if (!hasChildren()) {
			throw new ContentNotReadyException("Need to call waitForData(); assuming directory known to be non-empty!");
		}
		boolean b;
		try{
			_otherNamesLock.readLock().lock();
			b = _otherNames.contains(GROUP_PRIVATE_KEY.getComponent());
		}finally{
			_otherNamesLock.readLock().unlock();
		}
		return b;
	}

	public ContentName getPrivateKeyBlockName() {
		return new ContentName(_namePrefix, GROUP_PRIVATE_KEY);
	}

	/**
	 * Returns the private key object, if one exists as a wrapped key object. Does
	 * not check to see if we have a private key block; simply sends a request for it
	 * (saves the requirement to do enumeration). Callers should check available() on the
	 * result to see if we actually got one. In general, callers will know whether
	 * one should exist or not. hasPrivateKeyBlock can be used to test (after enumeration)
	 * whether one exists if you don't know.
	 * @return
	 * @throws IOException
	 * @throws ContentGoneException
	 * @throws ContentDecodingException
	 */
	protected WrappedKeyObject getPrivateKeyObject() throws ContentGoneException, IOException {

		return new WrappedKey.WrappedKeyObject(getPrivateKeyBlockName(), _handle);
	}

	/**
	 * Unwrap and return the key wrapped in a wrapping key specified by its digest.
	 * Find a copy of the key block in this directory that we can unwrap (either the private
	 * key wrapping key block or a wrapped raw symmetric key). Chase superseding keys if
	 * we have to. This mechanism should be generic, and should work for node keys
	 * as well as private key wrapping keys in directories following this structure.
	 * @return
	 * @throws InvalidKeyException
	 * @throws ContentDecodingException
	 * @throws IOException
	 * @throws NoSuchAlgorithmException
	 */
	public Key getUnwrappedKey(byte [] expectedKeyID)
	throws InvalidKeyException, ContentDecodingException, IOException, NoSuchAlgorithmException {

		byte[] retrievedKeyID;
		if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.FINEST)) {
			Log.finest(Log.FAC_ACCESSCONTROL, "getUnwrappedKey({0})", DataUtils.printHexBytes(expectedKeyID));
		}
		Key unwrappedKey = findUnwrappedKey(expectedKeyID);

		if (null != unwrappedKey) {
			_handle.keyManager().getSecureKeyCache().addKey(getName(), unwrappedKey);

			if (null != expectedKeyID) {
				retrievedKeyID = NodeKey.generateKeyID(unwrappedKey);
				if (!Arrays.areEqual(expectedKeyID, retrievedKeyID)) {
					if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.WARNING)) {
						Log.warning(Log.FAC_ACCESSCONTROL, "Retrieved and decrypted wrapped key, but it was the wrong key. We wanted " +
								DataUtils.printBytes(expectedKeyID) + ", we got " + DataUtils.printBytes(retrievedKeyID));
					}
				}
			}
		}
		return unwrappedKey;
	}

	protected Key findUnwrappedKey(byte[] expectedKeyID) throws IOException,
			ContentNotReadyException, InvalidKeyException,
			ContentDecodingException, NoSuchAlgorithmException {
		if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.FINER)) {
			if (expectedKeyID == null) {
				Log.finer(Log.FAC_ACCESSCONTROL, "KeyDirectory.findUnwrappedKey: at {0} unwrapping key without expectedKeyID", this._namePrefix);
			}
			else {
				Log.finer(Log.FAC_ACCESSCONTROL, "KeyDirectory.findUnwrappedKey: at {0} unwrapping key with expectedKeyID {1} ",
						this._namePrefix,
						DataUtils.printHexBytes(expectedKeyID));
			}
		}

		Key unwrappedKey = null;

		// Do we have the unwrapped key in our cache?
		// First, look up the desired keyID in the cache.
		// If it's not in the cache, look up the desired key by name
		SecureKeyCache skc = _handle.keyManager().getSecureKeyCache();
		if ((null != expectedKeyID) && (skc.containsKey(expectedKeyID))) {
			unwrappedKey = skc.getKey(expectedKeyID);
			Log.info(Log.FAC_ACCESSCONTROL, "KeyDirectory getUnwrappedKey: found desired unwrapped keyID in our cache.");
		}
		if ((null == unwrappedKey) && (skc.containsKey(getName()))) {
			unwrappedKey = skc.getKey(skc.getKeyID(getName()));
			Log.info(Log.FAC_ACCESSCONTROL, "KeyDirectory getUnwrappedKey: found desired unwrapped key name in our cache.");
		}

		if (null == unwrappedKey) {
			// If we've never enumerated, now might be the time.
			if (!hasEnumerated()) {
				startEnumerating();
			}
			waitForNoUpdatesOrResult(SystemConfiguration.getDefaultTimeout());

			// Only test here if we didn't get it via the cache.
			if (!hasChildren()) {
				throw new ContentNotReadyException("Need to call waitForData(); assuming directory known to be non-empty!");
			}

			// Do we have one of the wrapping keys already in our cache?
			unwrappedKey = unwrapKeyViaCache();

			if (null == unwrappedKey) {

				// Not in cache. Is it superseded?
				if (hasSupersededBlock()) {
					unwrappedKey = unwrapKeyViaSupersededKey();
				}
			}
		}
		return unwrappedKey;
	}

	/**
	 * Fast path -- once we have an idea which of our keys will unwrap this key,
	 * get it. Can be called after enumeration, or if we have a guess of what key to
	 * use.
	 * @param keyIDOfCachedKeytoUse
	 * @return
	 * @throws IOException
	 * @throws ContentDecodingException
	 * @throws NoSuchAlgorithmException
	 * @throws InvalidKeyException
	 */
	protected Key unwrapKeyViaCache(byte [] keyIDOfCachedKeytoUse) throws ContentDecodingException, IOException, InvalidKeyException, NoSuchAlgorithmException {

		WrappedKeyObject wko = getWrappedKeyForKeyID(keyIDOfCachedKeytoUse);
		if ((null == wko) || (!wko.available()) || (null == wko.wrappedKey())) {
			return null;
		}
		return wko.wrappedKey().unwrapKey(_handle.keyManager().getSecureKeyCache().getKey(keyIDOfCachedKeytoUse));
	}

	protected Key unwrapKeyViaCache() throws InvalidKeyException, ContentDecodingException, IOException, NoSuchAlgorithmException {
		Key unwrappedKey = null;
		try {
			_keyIDLock.readLock().lock();
			if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.INFO)) {
				Log.info(Log.FAC_ACCESSCONTROL, "KeyDirectory getUnwrappedKey: the directory has {0} wrapping keys.", _keyIDs.size());
			}
			for (byte [] keyid : _keyIDs) {
				if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.INFO)) {
					Log.info(Log.FAC_ACCESSCONTROL,
							"KeyDirectory getUnwrappedKey: the KD secret key is wrapped under a key whose id is {0}, name component {1}",
							DataUtils.printHexBytes(keyid), KeyProfile.keyIDToNameComponentAsString(keyid));
				}
				if (_handle.keyManager().getSecureKeyCache().containsKey(keyid)) {
					// We have it, pull the block, unwrap the node key.
					unwrappedKey = unwrapKeyViaCache(keyid);
				}
			}
		} finally {
			_keyIDLock.readLock().unlock();
		}
		return unwrappedKey;
	}

	protected Key unwrapKeyViaSupersededKey() throws InvalidKeyException, ContentDecodingException, IOException, NoSuchAlgorithmException {
		Key unwrappedKey = null;
		// OK, is the superseding key just a newer version of this key? If it is, roll
		// forward to the latest version and work back from there.
		WrappedKeyObject supersededKeyBlock = getSupersededWrappedKey();
		if (null != supersededKeyBlock) {
			// We could just walk up superseding key hierarchy, and then walk back with
			// decrypted keys. Or we could attempt to jump all the way to the end and then
			// walk back. Not sure there is a significant win in doing the latter, so start
			// with the former... have to touch intervening versions in both cases.
			if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.INFO)) {
				Log.info(Log.FAC_ACCESSCONTROL, "Attempting to retrieve key {0} by retrieving superseding key {1}",
						getName(), supersededKeyBlock.wrappedKey().wrappingKeyName());
			}

			Key unwrappedSupersedingKey = null;
			KeyDirectory supersedingKeyDirectory = null;
			try {
				supersedingKeyDirectory = factory(supersededKeyBlock.wrappedKey().wrappingKeyName());
				supersedingKeyDirectory.waitForNoUpdates(SystemConfiguration.SHORT_TIMEOUT);
				// This wraps the key we actually want.
				unwrappedSupersedingKey = supersedingKeyDirectory.getUnwrappedKey(supersededKeyBlock.wrappedKey().wrappingKeyIdentifier());
			} finally {
				if (null != supersedingKeyDirectory)
					supersedingKeyDirectory.stopEnumerating();
			}
			if (null != unwrappedSupersedingKey) {
				_handle.keyManager().getSecureKeyCache().addKey(supersedingKeyDirectory.getName(), unwrappedSupersedingKey);
				unwrappedKey = supersededKeyBlock.wrappedKey().unwrapKey(unwrappedSupersedingKey);
			} else {
				if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.INFO)) {
					Log.info(Log.FAC_ACCESSCONTROL, "Unable to retrieve superseding key {0}", supersededKeyBlock.wrappedKey().wrappingKeyName());
				}
			}
		}
		return unwrappedKey;
	}

	protected KeyDirectory factory(ContentName name) throws IOException {
		return new KeyDirectory(name, _handle);
	}

	/**
	 * @return true if the private key is in the secure key cache.
	 */
	public boolean isPrivateKeyInCache() {
		return _handle.keyManager().getSecureKeyCache().containsKey(getPrivateKeyBlockName());
	}

	@SuppressWarnings("serial")
	public static class NoPrivateKeyException extends IOException {}

	/**
	 * Returns the private key stored in the KeyDirectory.
	 * The private key is wrapped in a wrapping key, which is itself wrapped.
	 * So the unwrapping proceeds in two steps.
	 * First, we unwrap the wrapping key for the private key.
	 * Then, we unwrap the private key itself.
	 * Relies on the caller, who presumably knows the public key, to add the result to the
	 * cache.
	 * @return
	 * @throws AccessDeniedException
	 * @throws ContentGoneException
	 * @throws ContentNotReadyException
	 * @throws InvalidKeyException
	 * @throws ContentDecodingException
	 * @throws IOException
	 * @throws NoSuchAlgorithmException
	 */
	public Key getPrivateKey()
	throws AccessDeniedException, InvalidKeyException,
	ContentNotReadyException, ContentGoneException, ContentDecodingException,
	IOException, NoSuchAlgorithmException {

		// is the private key already in the cache?
		SecureKeyCache skc = _handle.keyManager().getSecureKeyCache();
		if (skc.containsKey(getPrivateKeyBlockName())) {
			Log.info(Log.FAC_ACCESSCONTROL, "KeyDirectory getPrivateKey: found private key in cache.");
			return skc.getPrivateKey(getPrivateKeyBlockName());
		}

		// Skip checking enumeration results. Assume we know we have one or not. Just
		// as fast to do the get as to enumerate and then do the get.
		WrappedKeyObject wko = getPrivateKeyObject();
		if ((null == wko) || (!wko.available()) || (null == wko.wrappedKey())) {
			if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.INFO)) {
				Log.info(Log.FAC_ACCESSCONTROL, "Cannot retrieve wrapped private key for {0}", getPrivateKeyBlockName());
			}
			throw new NoPrivateKeyException();
		}

		// This will pull from the cache if it's in our cache, and otherwise
		// will start enumerating if necessary.
		// This throws AccessDeniedException...
		Key wrappingKey = getUnwrappedKey(wko.wrappedKey().wrappingKeyIdentifier());
		if (null == wrappingKey) {
			if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.INFO)) {
				Log.info(Log.FAC_ACCESSCONTROL, "Cannot get key to unwrap private key {0}", getPrivateKeyBlockName());
			}
			throw new AccessDeniedException("Cannot get key to unwrap private key " + getPrivateKeyBlockName());
		}

		Key unwrappedPrivateKey = wko.wrappedKey().unwrapKey(wrappingKey);
		if (!(unwrappedPrivateKey instanceof PrivateKey)) {
			if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.INFO)) {
				Log.info(Log.FAC_ACCESSCONTROL, "Unwrapped private key is not an instance of PrivateKey! Its an {0}", unwrappedPrivateKey.getClass().getName());
			}
		} else {
			if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.INFO)) {
				Log.info(Log.FAC_ACCESSCONTROL, "Unwrapped private key is a private key, in fact it's a {0}", unwrappedPrivateKey.getClass().getName());
			}
		}
		return unwrappedPrivateKey;
	}

	/**
	 * Writes a wrapped key block to the repository.
	 * Eventually aggregate signing and repo stream operations at the very
	 * least across writing paired objects and links, preferably across larger
	 * swaths of data.
	 * @param secretKeyToWrap either a node key, a data key, or a private key wrapping key
	 * @param publicKeyName the name of the public key.
	 * @param publicKey the public key.
	 * @throws ContentEncodingException
	 * @throws IOException
	 * @throws InvalidKeyException
	 * @throws VersionMissingException
	 * @throws VersionMissingException
	 */
	public WrappedKeyObject addWrappedKeyBlock(Key secretKeyToWrap,
			ContentName publicKeyName, PublicKey publicKey)
	throws ContentEncodingException, IOException, InvalidKeyException, VersionMissingException {
		WrappedKey wrappedKey = WrappedKey.wrapKey(secretKeyToWrap, null, null, publicKey);
		wrappedKey.setWrappingKeyIdentifier(publicKey);
		wrappedKey.setWrappingKeyName(publicKeyName);
		WrappedKeyObject wko =
			new WrappedKeyObject(getWrappedKeyNameForKeyID(WrappedKey.wrappingKeyIdentifier(publicKey)),
					wrappedKey,SaveType.REPOSITORY, _handle);
		wko.save();
		if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.FINER)) {
			Log.finer(Log.FAC_ACCESSCONTROL, "KeyDirectory addWrappedKeyBlock: wrapped secret key {0} under public key named {1} whose id is {2} for key directory {3}",
					DataUtils.printHexBytes(secretKeyToWrap.getEncoded()), publicKeyName, DataUtils.printHexBytes(publicKey.getEncoded()), this._namePrefix);
		}
		return wko;
	}

	/**
	 * Writes a private key block to the repository.
	 * @param privateKey the private key.
	 * @param privateKeyWrappingKey the wrapping key used to wrap the private key.
	 * @throws ContentEncodingException
	 * @throws IOException
	 * @throws InvalidKeyException
	 */
	public void addPrivateKeyBlock(PrivateKey privateKey, Key privateKeyWrappingKey)
	throws ContentEncodingException, IOException, InvalidKeyException {

		WrappedKey wrappedKey = WrappedKey.wrapKey(privateKey, null, null, privateKeyWrappingKey);
		wrappedKey.setWrappingKeyIdentifier(privateKeyWrappingKey);
		WrappedKeyObject wko = new WrappedKeyObject(getPrivateKeyBlockName(), wrappedKey, SaveType.REPOSITORY, _handle);
		wko.save();
	}

	/**
	 * Add a superseded-by block to our key directory.
	 * @param oldPrivateKeyWrappingKey
	 * @param supersedingKeyName
	 * @param newPrivateKeyWrappingKey
	 * @throws ContentEncodingException
	 * @throws IOException
	 * @throws InvalidKeyException
	 */
	public void addSupersededByBlock(Key oldPrivateKeyWrappingKey,
			ContentName storedSupersedingKeyName, byte [] storedSupersedingKeyID, Key newPrivateKeyWrappingKey)
	throws InvalidKeyException, ContentEncodingException, IOException {

		addSupersededByBlock(_namePrefix, oldPrivateKeyWrappingKey,
				storedSupersedingKeyName, storedSupersedingKeyID, newPrivateKeyWrappingKey, _handle);
	}

	/**
	 * Add a superseded-by block to another node key, where we may have only its name, not its enumeration.
	 * Use as a static method to add our own superseded-by blocks as well.
	 * @throws ContentEncodingException
	 * @throws IOException
	 * @throws InvalidKeyException
	 */
	public static void addSupersededByBlock(ContentName oldKeyVersionedNameToAddBlockTo, Key oldKeyToBeSuperseded,
			ContentName storedSupersedingKeyName, byte [] storedSupersedingKeyID,
			Key supersedingKey, CCNHandle handle)
	throws ContentEncodingException, IOException, InvalidKeyException {

		WrappedKey wrappedKey = WrappedKey.wrapKey(oldKeyToBeSuperseded, null, null, supersedingKey);
		wrappedKey.setWrappingKeyIdentifier(
				((null == storedSupersedingKeyID) ? WrappedKey.wrappingKeyIdentifier(supersedingKey) :
					storedSupersedingKeyID));
		wrappedKey.setWrappingKeyName(storedSupersedingKeyName);
		WrappedKeyObject wko = new WrappedKeyObject(getSupersededBlockNameForKey(oldKeyVersionedNameToAddBlockTo),
				wrappedKey, SaveType.REPOSITORY, handle);
		wko.save();
	}

	/**
	 * Writes a link to a previous key to the repository.
	 * @param previousKey
	 * @param previousKeyPublisher
	 * @throws ContentEncodingException
	 * @throws IOException
	 */
	public void addPreviousKeyLink(ContentName previousKey, PublisherID previousKeyPublisher)
	throws ContentEncodingException, IOException {

		if (hasPreviousKeyBlock()) {
			if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.WARNING)) {
				Log.warning(Log.FAC_ACCESSCONTROL, "Unexpected, already have previous key block : {0}", getPreviousKeyBlockName());
			}
		}
		LinkAuthenticator la = (null != previousKeyPublisher) ? new LinkAuthenticator(previousKeyPublisher) : null;
		LinkObject pklo = new LinkObject(getPreviousKeyBlockName(), new Link(previousKey,la), SaveType.REPOSITORY, _handle);
		pklo.save();
	}

	/**
	 * Writes a previous key block to the repository.
	 * @param oldPrivateKeyWrappingKey
	 * @param supersedingKeyName
	 * @param newPrivateKeyWrappingKey
	 * @throws InvalidKeyException
	 * @throws ContentEncodingException
	 * @throws IOException
	 */
	public void addPreviousKeyBlock(Key oldPrivateKeyWrappingKey,
			ContentName supersedingKeyName, Key newPrivateKeyWrappingKey)
	throws InvalidKeyException, ContentEncodingException, IOException {
		// DKS TODO -- do we need in the case of deletion of ACLs to allow for multiple previous key blocks simultaneously?
		// Then need to add previous key id to previous key block name.
		WrappedKey wrappedKey = WrappedKey.wrapKey(oldPrivateKeyWrappingKey, null, null, newPrivateKeyWrappingKey);
		wrappedKey.setWrappingKeyIdentifier(newPrivateKeyWrappingKey);
		wrappedKey.setWrappingKeyName(supersedingKeyName);
		WrappedKeyObject wko = new WrappedKeyObject(getPreviousKeyBlockName(), wrappedKey,SaveType.REPOSITORY,  _handle);
		wko.save();
		if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.FINER)) {
			Log.finer(Log.FAC_ACCESSCONTROL, "KeyDirectory addPreviousKeyBlock: old wrapping key is {0} and superseding key name is {1} and new wrapping key is {2}.",
					DataUtils.printHexBytes(oldPrivateKeyWrappingKey.getEncoded()),
					supersedingKeyName,
					DataUtils.printHexBytes(newPrivateKeyWrappingKey.getEncoded())
			);
		}
	}
}
