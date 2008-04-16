package com.parc.ccn.library;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.SignatureException;
import java.util.ArrayList;

import com.parc.ccn.CCNBase;
import com.parc.ccn.data.CompleteName;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.MalformedContentNameStringException;
import com.parc.ccn.data.content.Link;
import com.parc.ccn.data.query.CCNInterestListener;
import com.parc.ccn.data.query.Interest;
import com.parc.ccn.data.security.ContentAuthenticator;
import com.parc.ccn.data.security.KeyLocator;
import com.parc.ccn.data.security.LinkAuthenticator;
import com.parc.ccn.data.security.PublisherKeyID;
import com.parc.ccn.data.security.ContentAuthenticator.ContentType;
import com.parc.ccn.security.keys.KeyManager;

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

	public void setKeyManager(KeyManager keyManager);

	public KeyManager keyManager();
		
	public PublisherKeyID getDefaultPublisher();

	public CompleteName put(ContentName name, byte [] contents) throws SignatureException, IOException, InterruptedException;

	/**
	 * Publish a piece of content under a particular identity.
	 * All of these automatically make the final name unique.
	 * @param name
	 * @param contents
	 * @param publisher selects one of our identities to publish under
	 * @throws SignatureException 
	 * @throws IOException 
	 * @throws InterruptedException 
	 */
	public CompleteName put(ContentName name, byte [] contents,
							PublisherKeyID publisher) throws SignatureException, IOException, InterruptedException;
	
	public CompleteName put(String name, String contents) throws SignatureException, MalformedContentNameStringException, IOException, InterruptedException;
	
	public CompleteName put(
			ContentName name, 
			byte[] contents, 
			ContentAuthenticator.ContentType type,
			PublisherKeyID publisher) throws SignatureException, IOException, InterruptedException;

	public CompleteName put(
			ContentName name, 
			byte [] contents,
			ContentAuthenticator.ContentType type,
			PublisherKeyID publisher, KeyLocator locator,
			PrivateKey signingKey) throws InvalidKeyException, SignatureException, NoSuchAlgorithmException, IOException, InterruptedException;
	
	// internal functions about fragmentation - may be exposed, or in std impl
	
	public CompleteName newVersion(ContentName name,
								   byte [] contents) throws SignatureException, IOException, InterruptedException;
	public CompleteName newVersion(ContentName name,
								   byte [] contents, 
								   PublisherKeyID publisher) throws SignatureException, IOException, InterruptedException;
	public CompleteName newVersion(
			ContentName name, 
			byte[] contents,
			ContentType type, // handle links and collections
			PublisherKeyID publisher) throws SignatureException, IOException, InterruptedException;
	
	public CompleteName addVersion(
			ContentName name, 
			int version, 
			byte [] contents,
			ContentType type,
			PublisherKeyID publisher, KeyLocator locator,
			PrivateKey signingKey) throws SignatureException, InvalidKeyException, NoSuchAlgorithmException, IOException, InterruptedException;
	
	public ArrayList<ContentObject> get(String name) throws MalformedContentNameStringException, IOException, InterruptedException;

	/**
	 * Get the latest version published by this publisher,
	 * or by anybody if publisher is null.
	 */
	public ContentName getLatestVersionName(ContentName name, PublisherKeyID publisher);

	/**
	 * Return the numeric version associated with this
	 * name.
	 * @param name
	 * @return version or -1 if no recognizable version information.
	 */
	public int getVersionNumber(ContentName name);
	
	/**
	 * Compute the name of this version.
	 * @param name
	 * @param version
	 * @return
	 */
	public ContentName versionName(ContentName name, int version);

	/**
	 * Does this name represent a version of the given parent?
	 * @param version
	 * @param parent
	 * @return
	 */
	public boolean isVersionOf(ContentName version, ContentName parent);
	
	public boolean isVersioned(ContentName name);
	
	/**
	 * Things are not as simple as this. Most things
	 * are fragmented. Maybe make this a simple interface
	 * that puts them back together and returns a byte []?
	 * @throws IOException 
	 * @throws InterruptedException 
	 */
	public ContentObject getLatestVersion(ContentName name, 
										  PublisherKeyID publisher) throws IOException, InterruptedException;

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
	
	public CompleteName link(ContentName src, ContentName dest, 
							 LinkAuthenticator destAuthenticator) throws SignatureException, IOException, InterruptedException;
	public CompleteName link(ContentName src, ContentName dest, 
							 LinkAuthenticator destAuthenticator, PublisherKeyID publisher) throws SignatureException, IOException, InterruptedException;
	public CompleteName link(ContentName src, ContentName dest,
			LinkAuthenticator destAuthenticator, 
			PublisherKeyID publisher, KeyLocator locator,
			PrivateKey signingKey) throws InvalidKeyException, SignatureException, NoSuchAlgorithmException, IOException, InterruptedException;
	
	public CompleteName addCollection(ContentName name, Link [] contents) throws SignatureException, IOException, InterruptedException;
	public CompleteName addCollection(ContentName name, Link [] contents, 
									  PublisherKeyID publisher) throws SignatureException, IOException, InterruptedException;
	public CompleteName addCollection(ContentName name, 
			Link[] contents,
			PublisherKeyID publisher, KeyLocator locator,
			PrivateKey signingKey) throws InvalidKeyException, SignatureException, NoSuchAlgorithmException, IOException, InterruptedException;
	
	/**
	 * Use the same publisherID that we used originally.
	 */
	public CompleteName addToCollection(ContentName name, CompleteName [] additionalContents);
	public CompleteName removeFromCollection(ContentName name, CompleteName [] additionalContents);

	public void expressInterest(
			Interest interest,
			CCNInterestListener listener) throws IOException;
	
	public void cancelInterest(Interest interest, CCNInterestListener listener) throws IOException;
	
	public ArrayList<CompleteName> enumerate(Interest interest) throws IOException;

	/**
	 * Approaches to read and write content. Low-level CCNBase returns
	 * a specific piece of content from the repository (e.g.
	 * if you ask for a fragment, you get a fragment). Library
	 * customers want the actual content, independent of
	 * fragmentation. Can implement this in a variety of ways;
	 * could verify fragments and reconstruct whole content
	 * and return it all at once. Could (better) implement
	 * file-like API -- open opens the header for a piece of
	 * content, read verifies the necessary fragments to return
	 * that much data and reads the corresponding content.
	 * Open read/write or append does?
	 * 
	 * DKS: TODO -- state-based put() analogous to write()s in
	 * blocks; also state-based read() that verifies. Start
	 * with state-based read.
	 */
	
	/**
	 * Beginnings of read interface. If name is not versioned,
	 * finds the latest version meeting the constraints. 
	 * @return a CCNDescriptor, which contains, among other things,
	 * the actual name we are opening. It also contains things
	 * like offsets and verification information.
	 * @throws IOException 
	 * @throws InterruptedException 
	 */
	public CCNDescriptor open(CompleteName name) throws IOException, InterruptedException;
	
	public long read(CCNDescriptor ccnObject, byte [] buf, long offset, long len);

	/**
	 * Does this name refer to a node that represents
	 * local (protected) content?
	 * @param name
	 * @return
	 */
	public boolean isLocal(CompleteName name);

}
