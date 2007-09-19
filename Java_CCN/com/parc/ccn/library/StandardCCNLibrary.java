package com.parc.ccn.library;

import java.io.IOException;

import com.parc.ccn.Library;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.query.CCNQueryDescriptor;
import com.parc.ccn.data.query.CCNQueryListener;
import com.parc.ccn.data.query.CCNQueryListener.CCNQueryType;
import com.parc.ccn.data.security.ContentAuthenticator;
import com.parc.ccn.data.security.PublisherID;
import com.parc.ccn.network.RepositoryManager;
import com.parc.ccn.security.keys.KeyManager;

/**
 * A basic implementation of the CCNLibrary API. This
 * rides on top of the CCNBase low-level interface. It uses
 * RepositoryManager to interface with a "real" virtual CCN,
 * and KeyManager to interface with the user's collection of
 * signing and verification keys. 
 * 
 * Need to expand get-side interface to allow querier better
 * access to signing information and trust path building.
 * 
 * @author smetters
 *
 */
public class StandardCCNLibrary implements CCNLibrary {
	
	/**
	 * Do we want to do this this way, or everything static?
	 */
	protected KeyManager _userKeyManager = null;
	
	public StandardCCNLibrary(KeyManager keyManager) {
		_userKeyManager = keyManager;
	}
	
	public void setKeyManager(KeyManager keyManager) {
		if (null == keyManager) {
			Library.logger().warning("StandardCCNLibrary::setKeyManager: Key manager cannot be null!");
			throw new IllegalArgumentException("Key manager cannot be null!");
		}
		_userKeyManager = keyManager;
	}
	
	public KeyManager keyManager() { return _userKeyManager; }
	
	protected PublisherID getDefaultPublisher() {
		return _userKeyManager.getDefaultKeyID();
	}
	
	/**
	 * Generate a collection where name maps to contents,
	 * with no specification about who published contents or
	 * what they contain.
	 */
	public void addCollection(ContentName name, ContentName[] contents) {
		addCollection(name, contents, getDefaultPublisher());
	}

	public void addCollection(ContentName name, ContentObject[] contents) {
		addCollection(name, contents, getDefaultPublisher());
	}

	public void addCollection(ContentName name, ContentName[] contents,
			PublisherID publisher) {
		// TODO Auto-generated method stub

	}

	public void addCollection(ContentName name, ContentObject[] contents,
			PublisherID publisher) {
		// TODO Auto-generated method stub

	}

	public void addToCollection(ContentName name,
			ContentName[] additionalContents) {
		// TODO Auto-generated method stub

	}

	public void addToCollection(ContentName name,
			ContentObject[] additionalContents) {
		// TODO Auto-generated method stub

	}

	public void removeFromCollection(ContentName name,
			ContentName[] additionalContents) {
		// TODO Auto-generated method stub

	}

	public void removeFromCollection(ContentName name,
			ContentObject[] additionalContents) {
		// TODO Auto-generated method stub

	}

	public void link(ContentName src, ContentName dest) {
		link(src, dest, getDefaultPublisher());
	}

	public void link(ContentName src, ContentName dest,
			ContentAuthenticator destAuthenticator) {
		link(src, dest, destAuthenticator, getDefaultPublisher());
	}

	public void link(ContentName src, ContentName dest, PublisherID publisher) {
		// TODO Auto-generated method stub

	}

	public void link(ContentName src, ContentName dest,
			ContentAuthenticator destAuthenticator, PublisherID publisher) {
		// TODO Auto-generated method stub

	}

	public void newVersion(ContentName name, byte[] contents) {
		newVersion(name, contents, getDefaultPublisher());
	}

	public void newVersion(ContentName name, int version, byte[] contents) {
		newVersion(name, version, contents, getDefaultPublisher());
	}

	public void newVersion(ContentName name, byte[] contents,
			PublisherID publisher) {
		// TODO Auto-generated method stub

	}

	public void newVersion(ContentName name, int version, byte[] contents,
			PublisherID publisher) {
		// TODO Auto-generated method stub

	}

	public void put(ContentName name, byte[] contents) {
		put(name, contents, getDefaultPublisher());
	}

	public void put(ContentName name, byte[] contents, PublisherID publisher) {
		// TODO Auto-generated method stub

	}

	public void cancel(CCNQueryDescriptor query) throws IOException {
		RepositoryManager.getCCNRepositoryManager().cancel(query);
	}

	public CCNQueryDescriptor get(ContentName name,
			ContentAuthenticator authenticator, CCNQueryType type,
			CCNQueryListener listener, long TTL) throws IOException {
		return RepositoryManager.getCCNRepositoryManager().get(name, authenticator, type, listener, TTL);
	}

	public void put(ContentName name, ContentAuthenticator authenticator,
			byte[] content) throws IOException {
		RepositoryManager.getCCNRepositoryManager().put(name, authenticator, content);
	}

}
