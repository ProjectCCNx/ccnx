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
 * <META> tag under which to store metadata (either on name or on version)
 * <V> tag under which to put versions
 * n/<V>/<number> -> points to header
 * <B> tag under which to put actual fragments
 * n/<V>/<number>/<B>/<number> -> fragments
 * n/<latest>/1/2/... has pointer to latest version
 *  -- use latest to get header of latest version, otherwise get via <v>/<n>
 * configuration parameters:
 * blocksize -- size of chunks to fragment into
 * 
 * get always reconstructs fragments and traverses links
 * can getLink to get link info
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
	
	// internal functions about fragmentation - may be exposed, or in std impl
	
	public void newVersion(ContentName name, byte [] contents);
	public void newVersion(ContentName name, int version, byte [] contents);
	public void newVersion(ContentName name, byte [] contents, PublisherID publisher);
	public void newVersion(ContentName name, int version, byte [] contents, PublisherID publisher);
	
	// islink
	// getlink ( no deref)
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
