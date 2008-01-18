package com.parc.ccn.library;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.Security;
import java.security.SignatureException;
import java.sql.Timestamp;
import java.util.ArrayList;

import javax.xml.stream.XMLStreamException;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

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
import com.parc.ccn.security.crypto.CCNMerkleTree;
import com.parc.ccn.security.crypto.Digest;
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
	
	static {
		Security.addProvider(new BouncyCastleProvider());
	}

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

	public PublisherID getDefaultPublisher() {
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
			throw new IOException("Cannot canonicalize a standard container!");
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

	public ContentObject getLink(CompleteName name) {
		// TODO Auto-generated method stub
		return null;
	}

	public boolean isLink(CompleteName name) {
		// TODO Auto-generated method stub
		return false;
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

	/**
	 * TODO: check to make sure name doesn't have the
	 *   version information in it already.
	 * @param name
	 * @param version
	 * @return
	 */
	public ContentName versionName(ContentName name, int version) {
		return new ContentName(name, 
							   ContentName.componentParse(VERSION_MARKER),
							   ContentName.componentParse(Integer.toString(version)));
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
	 * 
	 * We want to generate a unique name (just considering
	 * the name part) for transport and routing layer efficiency. 
	 * We want to do this in a way that
	 * gives us the following properties:
	 * <ol>
	 * <li>General CCN nodes do not need to understand any
	 *   name components.
	 * <li>General CCN nodes can verify content signatures if
	 * 	 they feel so inclined. That means that any components
	 *   added to the name to make it unique must be signed
	 *   along with the rest of the name.
	 * <li>General CCN nodes need to know as few algorithms
	 *   for verifying content signatures as possible; at
	 *   minimum one for leaf content and one for fragmented
	 *   content (probably also one for streamed content).
	 * <li>If a particular CCN node wishes to interpret the
	 * 	 content of the additional component (or query over it),
	 * 	 they can, but we don't require them to be able to.
	 * <li>Making content names unique shouldn't interfere with
	 * 	 making names or content private. Content can be encrypted
	 *   before hashing; name components could be encrypted even
	 *   after uniquification (so no one can tell that two blocks
	 *   have the same content, or even anything about the block
	 *   that maps to a name).
	 * </ol>
	 * Requiring the result to be unique means that the additional
	 * component added can't simply be the content digest, or
	 * the publisher ID. Either of these could be useful, but
	 * neither is guaranteed to be unique. The signature is guaranteed
	 * to be unique, but including the signature in the name itself
	 * (or the digest of the signature, etc) means that the name
	 * cannot be completely signed -- as the signature can't be
	 * included in the name for signing. At least the user-intended
	 * part of the name must signed, and including the signature
	 * in a distinguished component of the name means that CCN
	 * nodes must understand what parts of the name are signed
	 * and what aren't. While this is necessarily true, e.g. for
	 * fragmented data (see below), you either need a way to
	 * verify the remainder of the name (which is possible for
	 * fragmented data), or only require users to sign name prefixes.
	 * It is much better to require verification of the entire
	 * name, either by signing it completely (for unfragmented data),
	 * or by including the fragment names in the block information
	 * incorporated in the hash tree for signing (see below).
	 * So, what we use for unfragmented data is the digest of 
	 * the content authenticator without the signature in it; 
	 * which in turn contains the digest
	 * of the content itself, as well as the publisher ID and
	 * the timestamp (which will make it unique). When we generate
	 * the signature, we still sign the name, the content authenticator,
	 * and the content, as we cannot guarantee that the content
	 * authenticator digest has been incorporated in the name.
	 * 
	 * For fragmented data, we only generate one signature,
	 * on the root of the Merkle hash tree. For that we use
	 * this same process to generate a unique name from the
	 * content name and content information. However, we then
	 * decorate that name to create the individual block names;
	 * rather than have CCN nodes understand how to separate
	 * that decoration and verify it, we incorporate the block
	 * names into the Merkle hash tree.
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
			// We need to generate unique name, and 
			// generate signed ContentAuthenticator.
			CompleteName uniqueName =
				ContentAuthenticator.generateAuthenticatedName(
						name, publisher, ContentAuthenticator.now(),
						type, contents, false,
						locator, signingKey);
			try {
				return put(uniqueName.name(), uniqueName.authenticator(), contents);
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
		// This will call into CCNBase after picking appropriate credentials
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
			// nice Arrays operations not in 1.5
			int end = (to < contents.length) ? to : contents.length;
//			contentBlocks[i] = Arrays.copyOfRange(contents, from, (to < contents.length) ? to : contents.length);
			contentBlocks[i] = new byte[end-from];
			System.arraycopy(contents, from, contentBlocks[i], 0, (to < contents.length) ? to : contents.length);
		}

		// Digest of complete contents
		// If we're going to unique-ify the block names
		// (or just in general) we need to incorporate the names
		// in the MerkleTree blocks. 
    	CCNMerkleTree tree = 
    		new CCNMerkleTree(name, publisher, timestamp, 
    						  contentBlocks, locator, signingKey);

		for (int i = 0; i < nBlocks; i++) {
			try {
				CompleteName blockCompleteName = tree.getBlockCompleteName(i);
				put(blockCompleteName.name(), blockCompleteName.authenticator(), contentBlocks[i]);
			} catch (IOException e) {
				Library.logger().warning("This should not happen: we cannot put our own blocks!");
				Library.warningStackTrace(e);
				throw e;
			}
		}
		// construct the headerBlockContents;
		byte [] contentDigest = Digest.hash(contents);
		Header header = new Header(contents.length, contentDigest, tree.root());
		byte[] encodedHeader = null;
		try {
			encodedHeader = header.encode();
		} catch (XMLStreamException e) {
			Library.logger().warning("This should not happen: we cannot encode our own header!");
			Library.warningStackTrace(e);
			throw new IOException("This should not happen: we cannot encode our own header!" + e.getMessage());
		}

		CompleteName headerBlockInformation =
			ContentAuthenticator.generateAuthenticatedName(
					name, publisher, timestamp, type, 
					encodedHeader, false, locator, signingKey);
		try {
			put (headerBlockInformation.name(), headerBlockInformation.authenticator(), encodedHeader);
		} catch (IOException e) {
			Library.logger().warning("This should not happen: we cannot put our own header!");
			Library.warningStackTrace(e);
			throw e;
		}
		return headerBlockInformation;
	}

	public ContentName blockName(ContentName name, int i) {
		return new ContentName(name, 
							ContentName.componentParse(BLOCK_MARKER),
							ContentName.componentParse(Integer.toString(i)));
	}
	
	/**
	 * Implementation of CCNBase. Pass on to repository
	 * manager.
	 */

	/**
	 * Implementation of CCNBase.put.
	 */
	public CompleteName put(ContentName name, 
							ContentAuthenticator authenticator,
							byte[] content) throws IOException {
		return CCNRepositoryManager.getRepositoryManager().put(name, authenticator, content);
	}

	/**
	 * Have to handle un-fragmenting fragmented content, and
	 * reading (and verifying) partial content.
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
	
	/**
	 * Enumerate matches in the local repositories.
	 * @param query
	 * @return
	 * @throws IOException 
	 */
	public ArrayList<CompleteName> enumerate(CompleteName query) throws IOException {
		return CCNRepositoryManager.getRepositoryManager().enumerate(query);		
	}

}
