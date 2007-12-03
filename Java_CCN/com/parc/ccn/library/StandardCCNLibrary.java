package com.parc.ccn.library;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.SignatureException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.Library;
import com.parc.ccn.config.ConfigurationException;
import com.parc.ccn.data.CompleteName;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.content.Collection;
import com.parc.ccn.data.content.Header;
import com.parc.ccn.data.content.Link;
import com.parc.ccn.data.query.CCNQueryDescriptor;
import com.parc.ccn.data.query.CCNQueryListener;
import com.parc.ccn.data.security.ContentAuthenticator;
import com.parc.ccn.data.security.KeyLocator;
import com.parc.ccn.data.security.PublisherID;
import com.parc.ccn.data.security.ContentAuthenticator.ContentType;
import com.parc.ccn.network.CCNRepositoryManager;
import com.parc.ccn.security.crypto.Digest;
import com.parc.ccn.security.crypto.MerkleTree;
import com.parc.ccn.security.keys.KeyManager;

/**
 * A basic implementation of the CCNLibrary API. This
 * rides on top of the CCNBase low-level interface. It uses
 * CCNRepositoryManager to interface with a "real" virtual CCN,
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

	public static final String MARKER = "_";
	public static final String VERSION_MARKER = MARKER + "v" + MARKER;
	public static final String BLOCK_MARKER = MARKER + "b" + MARKER;
	public static final String CLIENT_METADATA_MARKER = MARKER + "meta" + MARKER;

	/**
	 * Do we want to do this this way, or everything static?
	 */
	protected KeyManager _userKeyManager = null;

	public StandardCCNLibrary(KeyManager keyManager) {
		_userKeyManager = keyManager;
	}

	public StandardCCNLibrary() throws ConfigurationException {
		this(KeyManager.getDefaultKeyManager());
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
		return keyManager().getDefaultKeyID();
	}

	public CompleteName addCollection(ContentName name, CompleteName[] contents) throws SignatureException, IOException {
		return addCollection(name, contents, getDefaultPublisher());
	}

	public CompleteName addCollection(
			ContentName name, 
			CompleteName[] contents,
			PublisherID publisher) throws SignatureException, IOException {
		try {
			return addCollection(name, contents, publisher, null, null);
		} catch (InvalidKeyException e) {
			Library.logger().warning("Default key invalid.");
			Library.warningStackTrace(e);
			throw new SignatureException(e);
		} catch (NoSuchAlgorithmException e) {
			Library.logger().warning("Default key has invalid algorithm.");
			Library.warningStackTrace(e);
			throw new SignatureException(e);
		}
	}

	public CompleteName addCollection(
			ContentName name, 
			CompleteName[] contents,
			PublisherID publisher, KeyLocator locator,
			PrivateKey signingKey) throws InvalidKeyException, SignatureException, NoSuchAlgorithmException, IOException {
		
		Collection collectionData = new Collection(contents);

		if (null == signingKey)
			signingKey = keyManager().getDefaultSigningKey();

		if (null == locator)
			locator = keyManager().getKeyLocator(signingKey);
	
		try {
			return put(name, collectionData.canonicalizeAndEncode(signingKey), ContentType.CONTAINER, publisher);
		} catch (XMLStreamException e) {
			Library.logger().warning("Cannot canonicalize a standard container!");
			Library.warningStackTrace(e);
			throw new IOException(e);
		}
	}

	public CompleteName addToCollection(
			ContentName name,
			CompleteName[] additionalContents) {
		// TODO Auto-generated method stub
		return null;
	}

	public CompleteName removeFromCollection(
			ContentName name,
			CompleteName[] additionalContents) {
		// TODO Auto-generated method stub
		return null;
	}

	/**
	 * Links are signed by the publisher of the link. However,
	 * the content of the link is an XML document that contains
	 * a complete name, including an indication of who the linker
	 * trusts to write the linked document (or to extend the
	 * linked-to hierarchy). The type of key referred to in the
	 * linked-to name is any of the usual types (key, cert, or
	 * name), but it can play one of two roles -- SIGNER, or
	 * the direct signer of the content, or CERTIFIER, the
	 * person who must have certified whoever's key signed
	 * the linked-to content. 
	 * @param destAuthenticator can be null
	 * @throws SignatureException 
	 * @throws IOException 
	 */
	public CompleteName link(ContentName src, ContentName dest,
			ContentAuthenticator destAuthenticator) throws SignatureException, IOException {
		return link(src, dest, destAuthenticator, getDefaultPublisher());
	}

	public CompleteName link(ContentName src, ContentName dest,
			ContentAuthenticator destAuthenticator,
			PublisherID publisher) throws SignatureException, IOException {
		try {
			return link(src,dest,destAuthenticator,publisher,null,null);
		} catch (InvalidKeyException e) {
			Library.logger().warning("Default key invalid.");
			Library.warningStackTrace(e);
			throw new SignatureException(e);
		} catch (NoSuchAlgorithmException e) {
			Library.logger().warning("Default key has invalid algorithm.");
			Library.warningStackTrace(e);
			throw new SignatureException(e);
		}
	}


	/**
	 * TODO: better answer than throwing an exception on invalid
	 * args.
	 * @throws NoSuchAlgorithmException 
	 * @throws SignatureException 
	 * @throws InvalidKeyException 
	 * @throws IOException 
	 * @throws XMLStreamException 
	 */
	public CompleteName link(ContentName src, ContentName dest,
			ContentAuthenticator destAuthenticator, 
			PublisherID publisher, KeyLocator locator,
			PrivateKey signingKey) throws InvalidKeyException, SignatureException, 
						NoSuchAlgorithmException, IOException {

		if ((null == src) || (null == dest)) {
			Library.logger().info("Link: src and dest cannot be null.");
			throw new IllegalArgumentException("Link: src and dest cannot be null.");
		}
		
		if (null == signingKey)
			signingKey = keyManager().getDefaultSigningKey();

		if (null == locator)
			locator = keyManager().getKeyLocator(signingKey);
	
		Link linkData = new Link(dest, destAuthenticator);
		try {
			return put(src, linkData.canonicalizeAndEncode(signingKey), ContentType.LINK, publisher, locator, signingKey);
		} catch (XMLStreamException e) {
			Library.logger().warning("Cannot canonicalize a standard link!");
			Library.warningStackTrace(e);
			throw new IOException("Cannot canonicalize a standard link! " + e.getMessage());
		}
	}

	public CompleteName newVersion(ContentName name, int version, byte[] contents) throws SignatureException, IOException {
		return newVersion(name, version, contents, getDefaultPublisher());
	}

	public CompleteName newVersion(ContentName name, int version, byte[] contents,
			PublisherID publisher) throws SignatureException, IOException {

		try {
			return newVersion(name, version, contents, publisher, null, null);
		} catch (InvalidKeyException e) {
			Library.logger().info("InvalidKeyException using default key.");
			throw new SignatureException(e);
		} catch (SignatureException e) {
			Library.logger().info("SignatureException using default key.");
			throw e;
		} catch (NoSuchAlgorithmException e) {
			Library.logger().info("NoSuchAlgorithmException using default key.");
			throw new SignatureException(e);
		}
	}

	public ContentObject getLink(CompleteName name) {
		// TODO Auto-generated method stub
		return null;
	}

	public boolean isLink(CompleteName name) {
		// TODO Auto-generated method stub
		return false;
	}

	public CompleteName newVersion(ContentName name, int version, byte [] contents,
			PublisherID publisher, KeyLocator locator,
			PrivateKey signingKey) throws SignatureException, 
			InvalidKeyException, NoSuchAlgorithmException, IOException {

		if (null == signingKey)
			signingKey = keyManager().getDefaultSigningKey();

		if (null == locator)
			locator = keyManager().getKeyLocator(signingKey);
	
		// Construct new name
		// <name>/<VERSION_MARKER>/<version_number>
		ContentName versionedName = versionName(name, version);

		// put result
		return put(versionedName, contents, ContentAuthenticator.ContentType.LEAF, publisher, locator, signingKey);
	}

	/**
	 * TODO: check to make sure name doesn't have the
	 *   version information in it already.
	 * @param name
	 * @param version
	 * @return
	 */
	public ContentName versionName(ContentName name, int version) {
		return new ContentName(name, VERSION_MARKER.getBytes(),(version + "").getBytes());
	}

	/**
	 * Get the latest version published by this publisher.
	 */
	public int getLatestVersion(ContentName name, PublisherID publisher) {
		// TODO Auto-generated method stub
		return 1;
	}

	/**
	 * Get the latest version published by anybody.
	 * @param name
	 * @return
	 */
	public int getLatestVersion(ContentName name) {
		// TODO Auto-generated method stub
		return 1;
	}

	/**
	 * Extract the version information from this name.
	 */
	public int getVersion(ContentName name) {
		// TODO Auto-generated method stub
		return 1;		
	}

	public CompleteName put(ContentName name, byte[] contents) 
				throws SignatureException, IOException {
		return put(name, contents, getDefaultPublisher());
	}

	public CompleteName put(ContentName name, byte[] contents, 
			PublisherID publisher) throws SignatureException, IOException {
		return put(name, contents, ContentAuthenticator.ContentType.LEAF, publisher);
	}

	public CompleteName put(ContentName name, byte[] contents, 
			ContentAuthenticator.ContentType type,
			PublisherID publisher) throws SignatureException, IOException {
		try {
			return put(name, contents, type, publisher, null, null);
		} catch (InvalidKeyException e) {
			Library.logger().info("InvalidKeyException using default key.");
			throw new SignatureException(e);
		} catch (SignatureException e) {
			Library.logger().info("SignatureException using default key.");
			throw e;
		} catch (NoSuchAlgorithmException e) {
			Library.logger().info("NoSuchAlgorithmException using default key.");
			throw new SignatureException(e);
		}
	}

	/**
	 * If small enough, doesn't fragment. Otherwise, does.
	 * Return CompleteName of the thing they put (in the case
	 * of a fragmented thing, the header). That way the
	 * caller can then also easily link to that thing if
	 * it needs to, or put again with a different name.
	 * @throws IOException 
	 **/
	public CompleteName put(ContentName name, byte [] contents,
			ContentAuthenticator.ContentType type,
			PublisherID publisher, KeyLocator locator,
			PrivateKey signingKey) throws InvalidKeyException, SignatureException, NoSuchAlgorithmException, IOException {
	
		if (null == signingKey)
			signingKey = keyManager().getDefaultSigningKey();

		if (null == locator)
			locator = keyManager().getKeyLocator(signingKey);
	
		if (contents.length >= Header.DEFAULT_BLOCKSIZE) {
			return fragmentedPut(name, contents, type, publisher, locator, signingKey);
		} else {
			ContentAuthenticator authenticator = 
				new ContentAuthenticator(name, publisher, 
						type, contents, 
						locator, signingKey);
			try {
				return put(name, authenticator, contents);
			} catch (IOException e) {
				Library.logger().warning("This should not happen: put failed with an IOExceptoin.");
				Library.warningStackTrace(e);
				throw e;
			}
		}
	}

	/** 
	 * Low-level fragmentation interface.
	 * @param name
	 * @param contents
	 * @param type
	 * @param publisher
	 * @param locator
	 * @param signingKey
	 * @throws InvalidKeyException
	 * @throws SignatureException
	 * @throws NoSuchAlgorithmException
	 * @throws IOException 
	 */
	protected CompleteName fragmentedPut(ContentName name, byte [] contents,
			ContentAuthenticator.ContentType type,
			PublisherID publisher, KeyLocator locator,
			PrivateKey signingKey) throws InvalidKeyException, SignatureException, NoSuchAlgorithmException, IOException {
		// will call into CCNBase after picking appropriate credentials
		// take content, blocksize (static), divide content into array of 
		// content blocks, call hash fn for each block, call fn to build merkle
		// hash tree.   Build header, for each block, get authinfo for block,
		// (with hash tree, block identifier, timestamp -- SQLDateTime)
		// insert header using mid-level insert, low-level insert for actual blocks.
		// We should implement a non-fragmenting put.   Won't do block stuff, will need to do latest version stuff.
		int blockSize = Header.DEFAULT_BLOCKSIZE;
		int nBlocks = (contents.length + blockSize - 1) / blockSize;
		int from = 0;
		byte[][] contentBlocks = new byte[nBlocks][];
		Timestamp timestamp = new Timestamp(System.currentTimeMillis());
		for (int i = 0; i < nBlocks; i++) {
			int to = from + blockSize;
			contentBlocks[i] = Arrays.copyOfRange(contents, from, (to < contents.length) ? to : contents.length);
		}

		// Digest of complete contents
		byte [] contentDigest = Digest.hash(contents);
		MerkleTree digestTree = new MerkleTree(contentBlocks);
		ContentAuthenticator [] blockAuthenticators = 
			ContentAuthenticator.authenticatedHashTree(name, publisher, timestamp, 
					type, digestTree, locator, 
					signingKey);

		for (int i = 0; i < nBlocks; i++) {
			ContentName blockName = blockName(name, i);
			try {
				put(blockName, blockAuthenticators[i], contentBlocks[i]);
			} catch (IOException e) {
				Library.logger().warning("This should not happen: we cannot put our own blocks!");
				Library.warningStackTrace(e);
				throw e;
			}
		}
		// construct the headerBlockContents;
		Header header = new Header(contents.length, contentDigest, digestTree.root());
		byte[] encodedHeader = null;
		try {
			encodedHeader = header.encode();
		} catch (XMLStreamException e) {
			Library.logger().warning("This should not happen: we cannot encode our own header!");
			Library.warningStackTrace(e);
			throw new IOException("This should not happen: we cannot encode our own header!" + e.getMessage());
		}

		ContentAuthenticator headerBlockAuthenticator =
			new ContentAuthenticator(name, publisher, timestamp, type, encodedHeader, false, locator, signingKey);
		try {
			put (name, headerBlockAuthenticator, encodedHeader);
		} catch (IOException e) {
			Library.logger().warning("This should not happen: we cannot put our own header!");
			Library.warningStackTrace(e);
			throw e;
		}
		return new CompleteName(name, headerBlockAuthenticator);
	}

	public ContentName blockName(ContentName name, int i) {
		return new ContentName(name, BLOCK_MARKER.getBytes(),(i + "").getBytes());
	}
	
	/**
	 * Implementation of CCNBase. Pass on to repository
	 * manager.
	 */

	/**
	 * Implementation of CCNBase.put.
	 */
	public CompleteName put(ContentName name, ContentAuthenticator authenticator,
			byte[] content) throws IOException {
		return CCNRepositoryManager.getRepositoryManager().put(name, authenticator, content);
	}

	/**
	 * Have to handle un-fragmenting fragmented content.
	 */
	public ArrayList<ContentObject> get(ContentName name, ContentAuthenticator authenticator) throws IOException {
		// TODO: defragment, deref links
		return CCNRepositoryManager.getRepositoryManager().get(name, authenticator);
	}

	public CCNQueryDescriptor expressInterest(ContentName name,
			ContentAuthenticator authenticator,
			CCNQueryListener listener) throws IOException {
		return CCNRepositoryManager.getRepositoryManager().expressInterest(name, authenticator, listener);
	}

	public void cancelInterest(CCNQueryDescriptor query) throws IOException {
		CCNRepositoryManager.getRepositoryManager().cancelInterest(query);
	}

}
