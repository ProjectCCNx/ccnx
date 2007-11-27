package com.parc.ccn.library;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.SignatureException;
import java.sql.Timestamp;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.Library;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.content.*;
import com.parc.ccn.data.query.CCNQueryDescriptor;
import com.parc.ccn.data.query.CCNQueryListener;
import com.parc.ccn.data.query.CCNQueryListener.CCNQueryType;
import com.parc.ccn.data.security.*;
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
	 */
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

	public void put(ContentName name, byte[] contents) throws SignatureException {
		put(name, contents, getDefaultPublisher());
	}

	public void put(ContentName name, byte[] contents, 
					PublisherID publisher) throws SignatureException {

		PrivateKey signingKey = keyManager().getDefaultSigningKey();
		KeyLocator locator = keyManager().getKeyLocator(signingKey);
		try {
			put(name, contents, ContentAuthenticator.ContentType.LEAF, publisher, locator, signingKey);
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

	public void put(ContentName name, byte [] contents,
			ContentAuthenticator.ContentType type,
			PublisherID publisher, KeyLocator locator,
			PrivateKey signingKey) throws InvalidKeyException, SignatureException, NoSuchAlgorithmException {
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
				// TODO throw something sensible
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
			// TODO throw something sensible
		}

		ContentAuthenticator headerBlockAuthenticator =
			new ContentAuthenticator(name, publisher, timestamp, type, encodedHeader, false, locator, signingKey);
		try {
			put (name, headerBlockAuthenticator, encodedHeader);
		} catch (IOException e) {
			Library.logger().warning("This should not happen: we cannot put our own header!");
			Library.warningStackTrace(e);
		}
	}

	public ContentName blockName(ContentName name, int i) {
		return new ContentName(name, BLOCK_MARKER.getBytes(),(i + "").getBytes());
	}

	public void put(ContentName name, ContentAuthenticator authenticator,
			byte[] content) throws IOException {
		CCNRepositoryManager.getCCNRepositoryManager().put(name, authenticator, content);
	}

	public void cancel(CCNQueryDescriptor query) throws IOException {
		CCNRepositoryManager.getCCNRepositoryManager().cancel(query);
	}

	public CCNQueryDescriptor get(ContentName name,
			ContentAuthenticator authenticator, CCNQueryType type,
			CCNQueryListener listener, long TTL) throws IOException {
		return CCNRepositoryManager.getCCNRepositoryManager().get(name, authenticator, type, listener, TTL);
	}


	public ArrayList<ContentObject> get(ContentName name, ContentAuthenticator authenticator, CCNQueryType type) throws IOException {
		// TODO Auto-generated method stub
		return null;
	}

	public ArrayList<ContentObject> get(ContentName name, ContentAuthenticator authenticator) throws IOException {
		// TODO Auto-generated method stub
		return null;
	}

}
