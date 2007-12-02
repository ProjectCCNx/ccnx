package com.parc.ccn.library;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.SignatureException;

import com.parc.ccn.CCNBase;
import com.parc.ccn.data.CompleteName;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.security.ContentAuthenticator;
import com.parc.ccn.data.security.KeyLocator;
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

	public CompleteName put(ContentName name, byte [] contents) throws SignatureException, IOException;

	/**
	 * Publish a piece of content under a particular identity.
	 * @param name
	 * @param contents
	 * @param publisher selects one of our identities to publish under
	 * @throws SignatureException 
	 * @throws IOException 
	 */
	public CompleteName put(ContentName name, byte [] contents, PublisherID publisher) throws SignatureException, IOException;
	
	public CompleteName put(ContentName name, byte[] contents, 
			ContentAuthenticator.ContentType type,
			PublisherID publisher) throws SignatureException, IOException;

	public CompleteName put(ContentName name, byte [] contents,
			ContentAuthenticator.ContentType type,
			PublisherID publisher, KeyLocator locator,
			PrivateKey signingKey) throws InvalidKeyException, SignatureException, NoSuchAlgorithmException, IOException;
	
	// internal functions about fragmentation - may be exposed, or in std impl
	
	public CompleteName newVersion(ContentName name, int version, byte [] contents) throws SignatureException, IOException;
	public CompleteName newVersion(ContentName name, int version, byte [] contents, PublisherID publisher) throws SignatureException, IOException;
	public CompleteName newVersion(ContentName name, int version, byte [] contents,
			PublisherID publisher, KeyLocator locator,
			PrivateKey signingKey) throws SignatureException, InvalidKeyException, NoSuchAlgorithmException, IOException;
	
	/**
	 * Get the latest version published by this publisher.
	 */
	public int getLatestVersion(ContentName name, PublisherID publisher);

	/**
	 * Get the latest version published by anybody.
	 * @param name
	 * @return
	 */
	public int getLatestVersion(ContentName name);

	/**
	 * Return the numeric version associated with this
	 * name.
	 * @param name
	 * @return version or -1 if no recognizable version information.
	 */
	public int getVersion(ContentName name);
	
	/**
	 * Does this specific name point to a link?
	 * Looks at local (cached) data only. 
	 * If more than one piece of content matches
	 * this CompleteName, returns false.
	 * @param name
	 * @return true if its a link, false if not. 
	 */
	public boolean isLink(CompleteName name);
	
	/**
	 * Return the link itself, not the content
	 * pointed to by a link. 
	 * @param name the identifier for the link to work on
	 * @return returns null if not a link, or name refers to more than one object
	 * @throws SignatureException
	 * @throws IOException
	 */
	public ContentObject getLink(CompleteName name);
	
	public CompleteName link(ContentName src, ContentName dest, ContentAuthenticator destAuthenticator) throws SignatureException, IOException;
	public CompleteName link(ContentName src, ContentName dest, ContentAuthenticator destAuthenticator, PublisherID publisher) throws SignatureException, IOException;
	public CompleteName link(ContentName src, ContentName dest,
			ContentAuthenticator destAuthenticator, 
			PublisherID publisher, KeyLocator locator,
			PrivateKey signingKey) throws InvalidKeyException, SignatureException, NoSuchAlgorithmException, IOException;
	
	public CompleteName addCollection(ContentName name, CompleteName [] contents) throws SignatureException, IOException;
	public CompleteName addCollection(ContentName name, CompleteName [] contents, PublisherID publisher) throws SignatureException, IOException;
	public CompleteName addCollection(ContentName name, 
			CompleteName[] contents,
			PublisherID publisher, KeyLocator locator,
			PrivateKey signingKey) throws InvalidKeyException, SignatureException, NoSuchAlgorithmException, IOException;
	
	/**
	 * Use the same publisherID that we used originally.
	 */
	public CompleteName addToCollection(ContentName name, CompleteName [] additionalContents);
	public CompleteName removeFromCollection(ContentName name, CompleteName [] additionalContents);
}
