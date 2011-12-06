/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008, 2009, 2011 Palo Alto Research Center, Inc.
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
import java.util.HashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.impl.CCNFlowControl.SaveType;
import org.ccnx.ccn.impl.support.DataUtils;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.ContentDecodingException;
import org.ccnx.ccn.io.content.ContentEncodingException;
import org.ccnx.ccn.io.content.ContentGoneException;
import org.ccnx.ccn.io.content.ContentNotReadyException;
import org.ccnx.ccn.io.content.KeyDirectory;
import org.ccnx.ccn.io.content.Link;
import org.ccnx.ccn.io.content.Link.LinkObject;
import org.ccnx.ccn.io.content.WrappedKey.WrappedKeyObject;
import org.ccnx.ccn.profiles.VersionMissingException;
import org.ccnx.ccn.profiles.security.access.AccessDeniedException;
import org.ccnx.ccn.profiles.security.access.group.GroupAccessControlProfile.PrincipalInfo;
import org.ccnx.ccn.protocol.ContentName;


/**
 * This structure is used for representing both node keys and group
 * (private) keys. We encapsulate functionality to walk such a directory
 * and find our target key here.
 * 
 * We store links providing additional information about how to retrieve
 * this key -- e.g. a link from a given group or principal name to a key ID-named
 * block, in case a group member does not know an earlier version of their
 * group public key. Or links to keys this key supercedes or precedes.
 */
public class PrincipalKeyDirectory extends KeyDirectory {

	GroupAccessControlManager _manager; // to get at GroupManager

	/**
	 * Maps the friendly names of principals (typically groups) to their information.
	 */
	HashMap<String, PrincipalInfo> _principals = new HashMap<String, PrincipalInfo>();
	final ReadWriteLock _principalsLock = new ReentrantReadWriteLock();

	/**
	 * Directory name should be versioned, else we pull the latest version; start
	 * enumeration.
	 * @param manager the access control manager.
	 * @param directoryName the root of the KeyDirectory.
	 * @param handle
	 * @throws IOException
	 */
	public PrincipalKeyDirectory(GroupAccessControlManager manager, ContentName directoryName, CCNHandle handle) 
	throws IOException {
		this(manager, directoryName, true, handle);
	}

	/**
	 * Directory name should be versioned, else we pull the latest version.
	 * @param manager the access control manager - must not be null
	 * @param directoryName the root of the KeyDirectory.
	 * @param handle
	 * @throws IOException
	 */
	public PrincipalKeyDirectory(GroupAccessControlManager manager, ContentName directoryName, boolean enumerate, CCNHandle handle) 
	throws IOException {
		super(directoryName, enumerate, handle);
		_manager = manager;
		
		// now that our class's variables are set up we can call the superclass's initialize method.
		super.initialize(enumerate);
	}

	/**
	 * Defer initialization until the end of our constructor since this
	 * class's variables are not set up yet, so we're not ready for callbacks.
	 * @see PrincipalKeyDirectory#PrincipalKeyDirectory(GroupAccessControlManager, ContentName, boolean, CCNHandle)
	 * @see KeyDirectory#KeyDirectory(ContentName, boolean, CCNHandle)
	 */
	@Override
	protected void initialize(boolean startEnumerating) throws IOException {
	}

	/**
	 * Called each time new data comes in, gets to parse it and load processed
	 * arrays.
	 */
	@Override
	protected void processNewChild(byte [] wkChildName) {
		if (PrincipalInfo.isPrincipalNameComponent(wkChildName)) {
				addPrincipal(wkChildName);
		} else 
			super.processNewChild(wkChildName);
	}

	/**
	 * Return a copy to avoid synchronization problems.
	 * @throws ContentNotReadyException 
	 */
	public HashMap<String, PrincipalInfo> getCopyOfPrincipals() throws ContentNotReadyException {
		if (!hasChildren()) {
			throw new ContentNotReadyException("Need to call waitForData(); assuming directory known to be non-empty!");
		}
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
	 * Adds a principal name
	 * @param wkChildName the principal name
	 */
	protected void addPrincipal(byte [] wkChildName) {
		PrincipalInfo pi = new PrincipalInfo(wkChildName);
		_principalsLock.writeLock().lock();
		try{
			_principals.put(pi.friendlyName(), pi);
		}finally{
			_principalsLock.writeLock().unlock();
		}
	}

	/**
	 * Store an additional link object pointing to the wrapped key object
	 * in the KeyDirectory. The link object is named with the Principal's name
	 * to allow searching the KeyDirectory by Principal name rather than KeyID.
	 */
	@Override
	public WrappedKeyObject addWrappedKeyBlock(Key secretKeyToWrap,
			ContentName publicKeyName, PublicKey publicKey)
			throws ContentEncodingException, IOException, InvalidKeyException,
			VersionMissingException {
		WrappedKeyObject wko = super.addWrappedKeyBlock(secretKeyToWrap, publicKeyName, publicKey);
		LinkObject lo = new LinkObject(getWrappedKeyNameForPrincipal(publicKeyName), new Link(wko.getVersionedName()), SaveType.REPOSITORY, _handle);
		lo.save();
		return wko;
	}

	@Override
	protected KeyDirectory factory(ContentName name) throws IOException {
		return new PrincipalKeyDirectory(_manager, name, _handle);
	}

	/**
	 * Returns the wrapped key object corresponding to a specified principal.
	 * @param principalName the principal.
	 * @return the corresponding wrapped key object.
	 * @throws IOException 
	 * @throws ContentNotReadyException
	 * @throws ContentDecodingException 
	 */
	protected WrappedKeyObject getWrappedKeyForPrincipal(String principalName) 
	throws ContentNotReadyException, ContentDecodingException, IOException {
		if (!hasChildren()) {
			throw new ContentNotReadyException("Need to call waitForData(); assuming directory known to be non-empty!");
		}

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
			if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.INFO)) {
				Log.info(Log.FAC_ACCESSCONTROL, "No block available for principal: {0}", principalName);
			}
			return null;
		}
		ContentName principalLinkName = getWrappedKeyNameForPrincipal(pi);
		// This should be a link to the actual key block
		// TODO DKS should wait on link data...
		LinkObject principalLink = new LinkObject(principalLinkName, _handle);
		if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.INFO)) {
			Log.info(Log.FAC_ACCESSCONTROL, "Retrieving wrapped key for principal {0} at {1}", principalName, principalLink.getTargetName());
		}
		ContentName wrappedKeyName = principalLink.getTargetName();
		return getWrappedKey(wrappedKeyName);
	}

	/**
	 * Returns the wrapped key name for a specified principal.
	 * @param isGroup whether the principal is a group.
	 * @param principalName the name of the principal.
	 * @param principalVersion the version of the principal.
	 * @return the corresponding wrapped key name. 
	 */
	protected ContentName getWrappedKeyNameForPrincipal(PrincipalInfo pi) {
		ContentName principalLinkName = new ContentName(_namePrefix, pi.toNameComponent());
		return principalLinkName;
	}

	/**
	 * Returns the wrapped key name for a principal specified by the name of its public key. 
	 * @param principalPublicKeyName the name of the public key of the principal.
	 * @return the corresponding wrapped key name.
	 * @throws VersionMissingException
	 * @throws ContentEncodingException 
	 */
	protected ContentName getWrappedKeyNameForPrincipal(ContentName principalPublicKeyName) throws VersionMissingException, ContentEncodingException {
		PrincipalInfo info = new PrincipalInfo(_manager, principalPublicKeyName);
		return getWrappedKeyNameForPrincipal(info);
	}

	@Override
	protected Key findUnwrappedKey(byte[] expectedKeyID) throws IOException,
			ContentNotReadyException, InvalidKeyException,
			ContentDecodingException, NoSuchAlgorithmException {

		if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.FINEST)) {
			Log.finest(Log.FAC_ACCESSCONTROL, "PrincipalKeyDirectory.findUnwrappedKey({0})", DataUtils.printHexBytes(expectedKeyID));
		}
		Key unwrappedKey = super.findUnwrappedKey(expectedKeyID);

		if (unwrappedKey == null) {
			// This is the current key. Enumerate principals and see if we can get a key to unwrap.
			if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.INFO)) {
				Log.info(Log.FAC_ACCESSCONTROL, "PrincipalKeyDirectory.findUnwrappedKey: at latest version of key {0}, attempting to unwrap.", getName());
			}
			// Assumption: if this key was encrypted directly for me, I would have had a cache
			// hit already. The assumption is that I pre-load my cache with my own private key(s).
			// So I don't care about principal entries if I get here, I only care about groups.
			// Groups may come in three types: ones I know I am a member of, but don't have this
			// particular key version for, ones I don't know anything about, and ones I believe
			// I'm not a member of but someone might have added me.
			if (_manager.haveKnownGroupMemberships()) {
				unwrappedKey = unwrapKeyViaKnownGroupMembership();
			}
			if (unwrappedKey == null) {
				// OK, we don't have any groups we know we are a member of. Do the other ones.
				// Slower, as we crawl the groups tree.
				unwrappedKey = unwrapKeyViaNotKnownGroupMembership();
			}
			return unwrappedKey;
		}
		
		return unwrappedKey;
	}

	protected Key unwrapKeyViaKnownGroupMembership() throws InvalidKeyException, ContentDecodingException, IOException, NoSuchAlgorithmException {
		Key unwrappedKey = null;
		try{
			_principalsLock.readLock().lock();
			if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.INFO)) {
				Log.info(Log.FAC_ACCESSCONTROL, "PrincipalKeyDirectory.unwrapKeyViaKnownGroupMembership: the directory has {0} principals.", _principals.size());
			}
			for (String principal : _principals.keySet()) {
				PrincipalInfo pInfo = _principals.get(principal);
				GroupManager pgm = _manager.groupManager(pInfo.distinguishingHash());
				if ((pgm == null) || (! pgm.isGroup(principal, SystemConfiguration.EXTRA_LONG_TIMEOUT)) || 
						(! pgm.amKnownGroupMember(principal))) {
					// On this pass, only do groups that I think I'm a member of. Do them
					// first as it is likely faster.
					continue;
				}
				// I know I am a member of this group, or at least I was last time I checked.
				// Attempt to get this version of the group private key as I don't have it in my cache.
				try {
					Key principalKey = pgm.getVersionedPrivateKeyForGroup(pInfo);
					unwrappedKey = unwrapKeyForPrincipal(principal, principalKey);
					if (null == unwrappedKey)
						continue;
				} catch (AccessDeniedException aex) {
					// we're not a member
					continue;
				}
			}
		} finally {
			_principalsLock.readLock().unlock();
		}
		return unwrappedKey;
	}

	protected Key unwrapKeyViaNotKnownGroupMembership() throws InvalidKeyException, ContentDecodingException, IOException, NoSuchAlgorithmException {
		Key unwrappedKey = null;
		try{
			_principalsLock.readLock().lock();
			for (PrincipalInfo pInfo : _principals.values()) {
				String principal = pInfo.friendlyName();

				if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.INFO)) {
					Log.info(Log.FAC_ACCESSCONTROL, "PrincipalKeyDirectory.unwrapKeyViaNotKnownGroupMembership: the KD secret key is wrapped under the key of principal {0}", 
							pInfo);
				}
				GroupManager pgm = _manager.groupManager(pInfo.distinguishingHash());
				if ((pgm == null) || (! pgm.isGroup(principal, SystemConfiguration.EXTRA_LONG_TIMEOUT)) || 
						(pgm.amKnownGroupMember(principal))) {
					// On this pass, only do groups that I don't think I'm a member of.
					if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.FINER)) {
						Log.finer(Log.FAC_ACCESSCONTROL, "PrincipalKeyDirectory.unwrapKeyViaNotKnownGroupMembership: skipping principal {0}.", principal);
					}
					continue;
				}

				if (pgm.amCurrentGroupMember(principal)) {
					if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.FINER)) {
						Log.finer(Log.FAC_ACCESSCONTROL, "PrincipalKeyDirectory.unwrapKeyViaNotKnownGroupMembership: I am a member of group {0} ", principal);
					}
					try {
						Key principalKey = pgm.getVersionedPrivateKeyForGroup(pInfo);
						unwrappedKey = unwrapKeyForPrincipal(principal, principalKey);
						if (null == unwrappedKey) {
							if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.WARNING)) {
								Log.warning(Log.FAC_ACCESSCONTROL, "Unexpected: we are a member of group {0} but get a null key.", principal);
							}
							continue;
						}
					} catch (AccessDeniedException aex) {
						if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.WARNING)) {
							Log.warning(Log.FAC_ACCESSCONTROL, "Unexpected: we are a member of group " + principal + " but get an access denied exception when we try to get its key: " + aex.getMessage());
						}
						continue;
					}
				}
				else {
					if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.INFO)) {
						Log.info(Log.FAC_ACCESSCONTROL, "PrincipalKeyDirectory.unwrapKeyViaNotKnownGroupMembership: I am not a member of group {0} ", principal);
					}
				}
			}
		} finally {
			_principalsLock.readLock().unlock();	
		}
		return unwrappedKey;
	}

	/**
	 * Unwrap the key wrapped under a specified principal, with a specified unwrapping key.
	 * @param principal
	 * @param unwrappingKey
	 * @return
	 * @throws ContentGoneException 
	 * @throws ContentNotReadyException 
	 * @throws ContentDecodingException
	 * @throws InvalidKeyException 
	 * @throws IOException
	 * @throws NoSuchAlgorithmException 
	 */
	protected Key unwrapKeyForPrincipal(String principal, Key unwrappingKey) 
	throws InvalidKeyException, ContentNotReadyException, 
	ContentDecodingException, ContentGoneException, IOException, NoSuchAlgorithmException {		
		Key unwrappedKey = null;
		if (null == unwrappingKey) {
			if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.INFO)) {
				Log.info(Log.FAC_ACCESSCONTROL, "Null unwrapping key. Cannot unwrap.");
			}
			return null;
		}
		WrappedKeyObject wko = getWrappedKeyForPrincipal(principal); // checks hasChildren
		if (null != wko.wrappedKey()) {
			unwrappedKey = wko.wrappedKey().unwrapKey(unwrappingKey);
		} else {
			try{
				_principalsLock.readLock().lock();
				if (Log.isLoggable(Log.FAC_ACCESSCONTROL, Level.INFO)) {
					Log.info(Log.FAC_ACCESSCONTROL, "Unexpected: retrieved version {0} of {1} group key, but cannot retrieve wrapped key object.",
							_principals.get(principal), principal);
				}
			}finally{
				_principalsLock.readLock().unlock();
			}
		}
		return unwrappedKey;
	}
}
