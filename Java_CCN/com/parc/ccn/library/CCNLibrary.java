package com.parc.ccn.library;

import com.parc.ccn.data.CCNBase;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.security.ContentAuthenticator;
import com.parc.ccn.data.security.PublisherID;

/**
 * Higher-level interface to CCNs.
 * @author smetters
 *
 */
public interface CCNLibrary extends CCNBase {

	public void put(ContentName name, byte [] contents);

	/**
	 * Publish a piece of content under a particular identity.
	 * @param name
	 * @param contents
	 * @param publisher selects one of our identities to publish under
	 */
	public void put(ContentName name, byte [] contents, PublisherID publisher);
	
	public void newVersion(ContentName name, byte [] contents);
	public void newVersion(ContentName name, int version, byte [] contents);
	public void newVersion(ContentName name, byte [] contents, PublisherID publisher);
	public void newVersion(ContentName name, int version, byte [] contents, PublisherID publisher);
	
	public void link(ContentName src, ContentName dest);
	public void link(ContentName src, ContentName dest, ContentAuthenticator destAuthenticator);
	public void link(ContentName src, ContentName dest, PublisherID publisher);
	public void link(ContentName src, ContentName dest, ContentAuthenticator destAuthenticator, PublisherID publisher);
	
	public void addCollection(ContentName name, ContentName [] contents);
	public void addCollection(ContentName name, ContentObject [] contents);
	public void addCollection(ContentName name, ContentName [] contents, PublisherID publisher);
	public void addCollection(ContentName name, ContentObject [] contents, PublisherID publisher);
	
	/**
	 * Use the same publisherID that we used originally.
	 */
	public void addToCollection(ContentName name, ContentName [] additionalContents);
	public void addToCollection(ContentName name, ContentObject [] additionalContents);
	public void removeFromCollection(ContentName name, ContentName [] additionalContents);
	public void removeFromCollection(ContentName name, ContentObject [] additionalContents);
}
