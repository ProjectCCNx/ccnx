package com.parc.ccn.library;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.InvalidParameterException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Security;
import java.security.SignatureException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Iterator;

import javax.xml.stream.XMLStreamException;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

import com.parc.ccn.CCNBase;
import com.parc.ccn.Library;
import com.parc.ccn.config.ConfigurationException;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.MalformedContentNameStringException;
import com.parc.ccn.data.content.Collection;
import com.parc.ccn.data.content.Header;
import com.parc.ccn.data.content.Link;
import com.parc.ccn.data.query.ExcludeFilter;
import com.parc.ccn.data.query.Interest;
import com.parc.ccn.data.security.ContentAuthenticator;
import com.parc.ccn.data.security.KeyLocator;
import com.parc.ccn.data.security.LinkAuthenticator;
import com.parc.ccn.data.security.PublisherKeyID;
import com.parc.ccn.data.security.Signature;
import com.parc.ccn.data.security.ContentAuthenticator.ContentType;
import com.parc.ccn.network.CCNNetworkManager;
import com.parc.ccn.security.crypto.CCNDigestHelper;
import com.parc.ccn.security.crypto.CCNMerkleTree;
import com.parc.ccn.security.keys.KeyManager;

/**
 * An implementation of the basic CCN library.
 * rides on top of the CCNBase low-level interface. It uses
 * CCNNetworkManager to interface with a "real" virtual CCN,
 * and KeyManager to interface with the user's collection of
 * signing and verification keys. 
 * 
 * Need to expand get-side interface to allow querier better
 * access to signing information and trust path building.
 * 
 * @author smetters,rasmussen
 * 
 * * <META> tag under which to store metadata (either on name or on version)
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
public class CCNLibrary extends CCNBase {

	public static final String MARKER = "_";
	public static final String FRAGMENT_MARKER = MARKER + "b" + MARKER;
	public static final String VERSION_MARKER = MARKER + "v" + MARKER;
	public static final String CLIENT_METADATA_MARKER = MARKER + "meta" + MARKER;
	public static final String HEADER_NAME = ".header"; // DKS currently not used; see below.
	public enum OpenMode { O_RDONLY, O_WRONLY };
	
	static {
		Security.addProvider(new BouncyCastleProvider());
	}
	
	protected static CCNLibrary _library = null;

	/**
	 * Do we want to do this this way, or everything static?
	 */
	protected KeyManager _userKeyManager = null;
	
	/**
	 * Control whether fragments start at 0 or 1.
	 * @return
	 */
	public static final int baseFragment() { return 0; }
	
	public static CCNLibrary open() throws ConfigurationException, IOException { 
		synchronized (CCNLibrary.class) {
			try {
				return new CCNLibrary();
			} catch (ConfigurationException e) {
				Library.logger().severe("Configuration exception initializing CCN library: " + e.getMessage());
				throw e;
			} catch (IOException e) {
				Library.logger().severe("IO exception initializing CCN library: " + e.getMessage());
				throw e;
			}
		}
	}
	
	public static CCNLibrary getLibrary() { 
		if (null != _library) 
			return _library;
		try {
			return createCCNLibrary();
		} catch (ConfigurationException e) {
			Library.logger().warning("Configuration exception attempting to create library: " + e.getMessage());
			Library.warningStackTrace(e);
			throw new RuntimeException("Error in system configuration. Cannot create library.",e);
		} catch (IOException e) {
			Library.logger().warning("IO exception attempting to create library: " + e.getMessage());
			Library.warningStackTrace(e);
			throw new RuntimeException("Error in system IO. Cannot create library.",e);
		}
	}

	protected static synchronized CCNLibrary 
				createCCNLibrary() throws ConfigurationException, IOException {
		if (null == _library) {
			_library = new CCNLibrary();
		}
		return _library;
	}

	protected CCNLibrary(KeyManager keyManager) {
		_userKeyManager = keyManager;
		// force initialization of network manager
		try {
			_networkManager = new CCNNetworkManager();
		} catch (IOException ex){
			Library.logger().warning("IOException instantiating network manager: " + ex.getMessage());
			ex.printStackTrace();
			_networkManager = null;
		}
	}

	protected CCNLibrary() throws ConfigurationException, IOException {
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

	public PublisherKeyID getDefaultPublisher() {
		return keyManager().getDefaultKeyID();
	}

	public ContentObject put(ContentName name, Link [] references) throws SignatureException, IOException, InterruptedException {
		return put(name, references, getDefaultPublisher());
	}

	public ContentObject put(ContentName name, Link [] references, PublisherKeyID publisher) 
				throws SignatureException, IOException, InterruptedException {
		try {
			return put(name, references, publisher, null, null);
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

	public ContentObject put(
			ContentName name, 
			Link[] references,
			PublisherKeyID publisher, KeyLocator locator,
			PrivateKey signingKey) throws InvalidKeyException, SignatureException, NoSuchAlgorithmException, IOException, InterruptedException {
		
		Collection collectionData = new Collection(references);

		if (null == signingKey)
			signingKey = keyManager().getDefaultSigningKey();

		if (null == locator)
			locator = keyManager().getKeyLocator(signingKey);
		
		if (null == publisher) {
			publisher = keyManager().getPublisherKeyID(signingKey);
		}

		try {
			return newVersion(name, collectionData.encode(), 
								ContentType.COLLECTION, publisher);
		} catch (XMLStreamException e) {
			Library.logger().warning("Cannot canonicalize a standard container!");
			Library.warningStackTrace(e);
			throw new IOException("Cannot canonicalize a standard container!");
		}
	}

	/**
	 * Use the same publisherID that we used originally.
	 */
	public ContentObject addToCollection(
			ContentName name,
			ContentObject[] references) {
		// TODO Auto-generated method stub
		return null;
	}

	public ContentObject removeFromCollection(
			ContentName name,
			ContentObject[] references) {
		
		return null;
	}
	
	public ContentObject updateCollection(
			ContentName name,
			Link [] referencesToAdd,
			Link [] referencesToRemove) {
		return null;
	}
	
	public ContentObject updateCollection(
			ContentName name,
			Link [] references) {
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
	 * @throws InterruptedException 
	 */
	public ContentObject link(ContentName reference, ContentName target,
							 LinkAuthenticator targetAuthenticator) throws SignatureException, IOException, InterruptedException {
		return link(reference, target, targetAuthenticator, getDefaultPublisher());
	}

	public ContentObject link(ContentName reference, ContentName target,
							LinkAuthenticator targetAuthenticator,
							PublisherKeyID publisher) throws SignatureException, IOException, InterruptedException {
		try {
			return link(reference,target,targetAuthenticator,publisher,null,null);
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
	 * @throws InterruptedException 
	 * @throws XMLStreamException 
	 */
	public ContentObject link(ContentName src, ContentName target,
							 LinkAuthenticator targetAuthenticator, 
							 PublisherKeyID publisher, KeyLocator locator,
							 PrivateKey signingKey) throws InvalidKeyException, SignatureException, 
						NoSuchAlgorithmException, IOException, InterruptedException {

		if ((null == src) || (null == target)) {
			Library.logger().info("Link: src and target cannot be null.");
			throw new IllegalArgumentException("Link: src and target cannot be null.");
		}
		
		if (null == signingKey)
			signingKey = keyManager().getDefaultSigningKey();

		if (null == locator)
			locator = keyManager().getKeyLocator(signingKey);
		
		if (null == publisher) {
			publisher = keyManager().getPublisherKeyID(signingKey);
		}

		Link linkData = new Link(target, targetAuthenticator);
		try {
			return put(src, linkData.encode(), ContentType.LINK, publisher, locator, signingKey);
		} catch (XMLStreamException e) {
			Library.logger().warning("Cannot canonicalize a standard link!");
			Library.warningStackTrace(e);
			throw new IOException("Cannot canonicalize a standard link! " + e.getMessage());
		}
	}

	/**
	 * Return the link itself, not the content
	 * pointed to by a link. 
	 * @param name the identifier for the link to work on
	 * @return returns null if not a link, or name refers to more than one object
	 * @throws SignatureException
	 * @throws IOException
	 */
	public ContentObject getLink(ContentObject content) {
		if (!isLink(content))
			return null;
		// Want the low-level get.
		return null;
	}

	/**
	 * Does this specific name point to a link?
	 * Looks at local (cached) data only. 
	 * If more than one piece of content matches
	 * this ContentObject, returns false.
	 * @param name
	 * @return true if its a link, false if not. 
	 */
	public boolean isLink(ContentObject content) {
		return (content.authenticator().type() == ContentType.LINK);
	}

	/**
	 * Publishes a new version of this name with the given contents. First
	 * attempts to figure out the most recent version of that name, and
	 * then increments that to get the intended version number.
	 * 
	 * Right now have all sorts of uncertainties in versioning --
	 * do we know the latest version number of a piece of content?
	 * Even if we've read it, it isn't atomic -- by the time
	 * we write our new version, someone else might have updated
	 * the number...
	 * @throws InterruptedException 
	 */
	public ContentObject newVersion(ContentName name,
								   byte[] contents) throws SignatureException, IOException, InterruptedException {
		return newVersion(name, contents, getDefaultPublisher());
	}

	/**
	 * A specialization of newVersion that allows control of the identity to
	 * publish under. 
	 * 
	 * @param publisher Who we want to publish this as,
	 * not who published the existing version. If null, uses the default publishing
	 * identity.
	 * @throws InterruptedException 
	 */
	public ContentObject newVersion(
			ContentName name, 
			byte[] contents,
			PublisherKeyID publisher) throws SignatureException, IOException, InterruptedException {
		return newVersion(name, contents, ContentType.LEAF, publisher);
	}
	
	/**
	 * A further specialization of newVersion that allows specification of the content type,
	 * primarily to handle links and collections. Could be made protected.
	 * @param publisher Who we want to publish this as,
	 * not who published the existing version.
	 * @throws InterruptedException 
	 */
	public ContentObject newVersion(
			ContentName name, 
			byte[] contents,
			ContentType type, // handle links and collections
			PublisherKeyID publisher) throws SignatureException, IOException, InterruptedException {

		try {
			ContentName latestVersion = 
				getLatestVersionName(name, null);
		
			int currentVersion = -1;
			if (null != latestVersion)
				// will return -1 if unversioned 
				currentVersion = getVersionNumber(latestVersion);
			
			// This ends us up with version numbers starting
			// at 0. If we want version numbers starting at 1,
			// modify this and baseVersion.
			return addVersion(name, currentVersion+1, contents, type, publisher, null, null);
		
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
	 * Control whether versions start at 0 or 1.
	 * @return
	 */
	public static final int baseVersion() { return 0; }
	
	/**
	 * Generates the name and authenticator for the new version of a given name.
	 * NOTE: This currently believes it is generating the name of a piece of content,
	 *  and is only happy doing so for atomic pieces of content below the fragmentation
	 *  threshold. It will throw an exception for pieces of content larger than that.
	 *  
	 * TODO DKS do something cleverer.
	 * Generates the complete name for this piece of leaf content. 
	 * @param name The base name to version.
	 * @param version The version to publish.
	 * @param contents The (undigested) contents. Must be smaller than the fragmentation threshold for now.
	 * @param type The desired type, or null for default.
	 * @param publisher The desired publisher, or null for default.
	 * @param locator The desired key locator, or null for default.
	 * @param signingKey The desired signing key, or null for default.
	 * @return
	 * @throws SignatureException
	 * @throws InvalidKeyException
	 * @throws NoSuchAlgorithmException
	 * @throws IOException
	 * @throws InterruptedException
	 */
	public ContentObject newVersionName(
			ContentName name, int version, byte [] contents,
			ContentType type,
			PublisherKeyID publisher, KeyLocator locator,
			PrivateKey signingKey) throws SignatureException, 
			InvalidKeyException, NoSuchAlgorithmException, IOException, InterruptedException {

		if (contents.length > Header.DEFAULT_BLOCKSIZE)
			throw new IOException("newVersionName currently only handles non-fragmenting content smaller than: " + Header.DEFAULT_BLOCKSIZE);

		if (null == signingKey)
			signingKey = keyManager().getDefaultSigningKey();

		if (null == locator)
			locator = keyManager().getKeyLocator(signingKey);

		if (null == publisher) {
			publisher = keyManager().getPublisherKeyID(signingKey);
		}

		if (null == type)
			type = ContentType.LEAF;
		
		ContentName versionedName = versionName(name, version);
		ContentObject uniqueName =
			ContentObject.generateAuthenticatedName(
					versionedName, publisher, ContentAuthenticator.now(),
					type, locator, contents, signingKey);

		return uniqueName;
	}


	/**
	 * This does the actual work of generating a new version's name and doing 
	 * the corresponding put. Handles fragmentation.
	 */
	public ContentObject addVersion(
			ContentName name, int version, byte [] contents,
			ContentType type,
			PublisherKeyID publisher, KeyLocator locator,
			PrivateKey signingKey) throws SignatureException, 
			InvalidKeyException, NoSuchAlgorithmException, IOException, InterruptedException {

		if (null == signingKey)
			signingKey = keyManager().getDefaultSigningKey();

		if (null == locator)
			locator = keyManager().getKeyLocator(signingKey);
		
		if (null == publisher) {
			publisher = keyManager().getPublisherKeyID(signingKey);
		}
		
		if (null == type)
			type = ContentType.LEAF;
		
		// Construct new name
		// <name>/<VERSION_MARKER>/<version_number>
		ContentName versionedName = versionName(name, version);

		// put result
		return put(versionedName, contents, 
				 	type, publisher, locator, signingKey);
	}

	/**
	 * Because getting just the latest version number would
	 * require getting the latest version name first, 
	 * just get the latest version name and allow caller
	 * to pull number.
	 * DKS TODO return complete name -- of header? Or what...
	 * DKS TODO match on publisher key id, or full publisher options?
	 * @return If null, no existing version found.
	 */
	public ContentName getLatestVersionName(ContentName name, PublisherKeyID publisher) {
		// Challenge -- Dan's proposed latest version syntax,
		// <name>/latestversion/1/2/3... works well if there
		// are 12 versions, not if there are a million. 
		// Need to do a limited get/enumerate just to get version
		// names, without enumerating all the blocks.
		// DKS TODO general way of doing this
		// right now use list children. Should be able to do
		// it in Jackrabbit with XPath.
		ContentName baseVersionName = 
			ContentName.fromNative(versionRoot(name), VERSION_MARKER);
		try {
			// Because we're just looking at children of
			// the name -- not actual pieces of content --
			// look only at ContentNames.
			ArrayList<ContentObject> availableVersions = 
				enumerate(new Interest(baseVersionName), NO_TIMEOUT);
			
			if ((null == availableVersions) || (availableVersions.size() == 0)) {
				// No existing version.
				return null;
			}
			
			Iterator<ContentObject> vit = availableVersions.iterator();
			
			// DKS TODO
			// Need to make sure we match our publisher criteria
			// if any. Really need to do this in original query,
			// as filtering could be complex (want to be able to
			// ask for items signed by anyone whose key was signed
			// by someone in particular, not just things published
			// by a particular signer).
			int latestVersion = -1;
			ContentObject latestVersionName = null;
			while (vit.hasNext()) {
				ContentObject version = vit.next();
				int thisVersion =
					getVersionNumber(version.name());
				if (thisVersion > latestVersion) { 
					latestVersion = thisVersion;
					latestVersionName = version;
				}
			}
			// Should we rely on unique names? We only really have 
			// ContentNames here, so return just that.
			return latestVersionName.name();
			
		} catch (IOException e) {
			Library.logger().warning("IOException getting latest version number of name: " + name + ": " + e.getMessage());
			Library.warningStackTrace(e);
		}
		return null;
	}
	
	/**
	 * Things are not as simple as this. Most things
	 * are fragmented. Maybe make this a simple interface
	 * that puts them back together and returns a byte []?
	 * @throws IOException 
	 * @throws InterruptedException 
	 */
	public ContentObject getLatestVersion(ContentName name, PublisherKeyID publisher) throws IOException, InterruptedException {
		ContentName currentName = getLatestVersionName(name, publisher);
		
		if (null == currentName) // no latest version
			return null;
		
		// Need recursive get. The currentName we have here is
		// just the prefix of this version.
		return get(currentName, 0);
	}

	/**
	 * Extract the version information from this name.
	 * TODO DKS the fragment number variant of this is static to StandardCCNLibrary, they
	 * 	probably ought to both be the same.
	 * 
	 * @param name
	 * @return Version number, or -1 if not versioned.
	 */
	public int getVersionNumber(ContentName name) {
		int offset = name.containsWhere(VERSION_MARKER);
		if (offset < 0)
			return -1; // no version information.
		return Integer.valueOf(ContentName.componentPrintURI(name.component(offset+1)));
	}

	/**
	 * Compute the name of this version.
	 * @param name
	 * @param version
	 * @return
	 */
	public ContentName versionName(ContentName name, int version) {
		ContentName baseName = name;
		if (isVersioned(name))
			baseName = versionRoot(name);
		return ContentName.fromNative(baseName, VERSION_MARKER,
							   Integer.toString(version));
	}
	
	/**
	 * Does this name represent a version of the given parent?
	 * @param version
	 * @param parent
	 * @return
	 */
	public boolean isVersionOf(ContentName version, ContentName parent) {
		if (!isVersioned(version))
			return false;
		
		if (isVersioned(parent))
			parent = versionRoot(parent);
		
		return parent.isPrefixOf(version);
	}
	
	public boolean isVersioned(ContentName name) {
		return name.contains(VERSION_MARKER);
	}

	public static ContentName versionRoot(ContentName name) {
		return name.cut(VERSION_MARKER);
	}

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
	public ContentObject put(String name, String contents) throws SignatureException, MalformedContentNameStringException, IOException, InterruptedException {
		return put(ContentName.fromURI(name), contents.getBytes());
	}
	
	public ContentObject put(ContentName name, byte[] contents) 
				throws SignatureException, IOException, InterruptedException {
		return put(name, contents, getDefaultPublisher());
	}

	public ContentObject put(ContentName name, byte[] contents, 
							PublisherKeyID publisher) throws SignatureException, IOException, InterruptedException {
		return put(name, contents, ContentAuthenticator.ContentType.LEAF, publisher);
	}
	
	public ContentObject put(ContentName name, 
			ContentAuthenticator authenticator,
			byte[] content,
			Signature signature) throws IOException, InterruptedException {
		ContentObject co = new ContentObject(name, authenticator, content, signature); 
		return put(co);
	}

	public ContentObject put(ContentName name, byte[] contents, 
							ContentAuthenticator.ContentType type,
							PublisherKeyID publisher) throws SignatureException, IOException, InterruptedException {
		try {
			return put(name, contents, type, publisher, 
					   null, null);
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
	 * Return ContentObject of the thing they put (in the case
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
	 * 
	 * TODO: DKS: improve this to handle stream writes better.
	 * What happens if we want to write a block at a time.
	 *  * @param name
	 * @param authenticator
	 * @param signature
	 * @param content
	 * @return
	 * @throws IOException 
	 * @throws InterruptedException 
	 **/
	public ContentObject put(ContentName name, byte [] contents,
							ContentAuthenticator.ContentType type,
							PublisherKeyID publisher, KeyLocator locator,
							PrivateKey signingKey) throws InvalidKeyException, SignatureException, NoSuchAlgorithmException, IOException, InterruptedException {
	
		if (null == signingKey)
			signingKey = keyManager().getDefaultSigningKey();

		if (null == locator)
			locator = keyManager().getKeyLocator(signingKey);
		
		if (null == publisher) {
			publisher = keyManager().getPublisherKeyID(signingKey);
		}
		if (contents.length >= Header.DEFAULT_BLOCKSIZE) {
			return fragmentedPut(name, contents, type, publisher, locator, signingKey);
		} else {
			try {
				// Generate signature
				ContentObject co = new ContentObject(name, new ContentAuthenticator(publisher, type, locator), contents, signingKey);
				return put(co.name(), co.authenticator(), co.content(), co.signature());
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
	 * @throws InterruptedException 
	 */
	protected ContentObject fragmentedPut(ContentName name, byte [] contents,
										ContentAuthenticator.ContentType type,
										PublisherKeyID publisher, KeyLocator locator,
										PrivateKey signingKey) throws InvalidKeyException, SignatureException, NoSuchAlgorithmException, IOException, InterruptedException {
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
		
		if (isFragment(name)) {
			Library.logger().info("Asked to store fragments under fragment name: " + name + ". Stripping fragment information");
		}
		
		ContentName fragmentBaseName = fragmentBase(name);
		
		CCNMerkleTree tree = 
			putMerkleTree(fragmentBaseName, baseFragment(),
						  contentBlocks, contentBlocks.length, 
						  baseFragment(), timestamp, publisher, locator, signingKey);
		
		// construct the headerBlockContents;
		byte [] contentDigest = CCNDigestHelper.digest(contents);
		return putHeader(name, contents.length, contentDigest, tree.root(),
						 type, timestamp, publisher, locator, signingKey);
	}
	
	public ContentObject putHeader(ContentName name, int contentLength, byte [] contentDigest, 
				byte [] contentTreeAuthenticator,
				ContentAuthenticator.ContentType type,
				Timestamp timestamp, 
				PublisherKeyID publisher, KeyLocator locator,
				PrivateKey signingKey) throws IOException, InvalidKeyException, SignatureException, InterruptedException {

		if (null == signingKey)
			signingKey = keyManager().getDefaultSigningKey();

		if (null == locator)
			locator = keyManager().getKeyLocator(signingKey);
		
		if (null == publisher) {
			publisher = keyManager().getPublisherKeyID(signingKey);
		}		
		
		Header header = new Header(contentLength, contentDigest, contentTreeAuthenticator);
		byte[] encodedHeader = null;
		try {
			encodedHeader = header.encode();
		} catch (XMLStreamException e) {
			Library.logger().warning("This should not happen: we cannot encode our own header!");
			Library.warningStackTrace(e);
			throw new IOException("This should not happen: we cannot encode our own header!" + e.getMessage());
		}

		// Add another differentiator to avoid making header
		// name prefix of other valid names?
		ContentName headerName = headerName(name);
		ContentObject headerObject = new ContentObject(headerName, 
														new ContentAuthenticator(publisher, timestamp, ContentType.HEADER, locator),
														encodedHeader,
														signingKey);
		ContentObject headerResult = null;
		try {
			headerResult = 
				put(headerObject.name(), headerObject.authenticator(),
					headerObject.content(), headerObject.signature());
		} catch (IOException e) {
			Library.logger().warning("This should not happen: we cannot put our own header!");
			Library.warningStackTrace(e);
			throw e;
		}
		return headerResult;		
	}
	
	/**
	 * Might want to make headerName not prefix of  rest of
	 * name, but instead different subleaf. For example,
	 * the header name of v.6 of name <name>
	 * was originally <name>/_v_/6; could be 
	 * <name>/_v_/6/.header or <name>/_v_/6/_m_/.header;
	 * the full uniqueified names would be:
	 * <name>/_v_/6/<sha256> or <name>/_v_/6/.header/<sha256>
	 * or <name>/_v_/6/_m_/.header/<sha256>.
	 * The first version has the problem that the
	 * header name (without the unknown uniqueifier)
	 * is the prefix of the block names; so we must use the
	 * scheduler or other cleverness to get the header ahead of the blocks.
	 * The second version of this makes it impossible to easily
	 * write a reader that gets both single-block content and
	 * fragmented content (and we don't want to turn the former
	 * into always two-block content).
	 * So having tried the second route, we're moving back to the former.
	 * @param name
	 * @return
	 */
	public static ContentName headerName(ContentName name) {
		// Want to make sure we don't add a header name
		// to a fragment. Go back up to the fragment root.
		// Currently no header name added.
		if (isFragment(name)) {
			// return new ContentName(fragmentRoot(name), HEADER_NAME);
			return fragmentRoot(name);
		}
		// return new ContentName(name, HEADER_NAME);
		return name;
	}
	
	public static boolean isFragment(ContentName name) {
		return name.contains(FRAGMENT_MARKER);
	}
	
	public static ContentName fragmentRoot(ContentName name) {
		return name.cut(FRAGMENT_MARKER);
	}
	
	public static ContentName fragmentBase(ContentName name) {
		return ContentName.fromNative(fragmentRoot(name), FRAGMENT_MARKER);
	}
	
	public CCNMerkleTree putMerkleTree(
			ContentName name, int baseNameIndex,
			byte [][] contentBlocks, int blockCount, int baseBlockIndex, 
			Timestamp timestamp,
			PublisherKeyID publisher, KeyLocator locator,
			PrivateKey signingKey) throws InvalidKeyException, SignatureException, NoSuchAlgorithmException, InterruptedException, IOException {

		if (null == signingKey)
			signingKey = keyManager().getDefaultSigningKey();

		if (null == locator)
			locator = keyManager().getKeyLocator(signingKey);
		
		if (null == publisher) {
			publisher = keyManager().getPublisherKeyID(signingKey);
		}		
		
		// Digest of complete contents
		// If we're going to unique-ify the block names
		// (or just in general) we need to incorporate the names
		// and authenticators in the MerkleTree blocks. 
		// For now, this generates the root signature too, so can
		// ask for the signature for each block.
    	CCNMerkleTree tree = 
    		new CCNMerkleTree(fragmentBase(fragmentRoot(name)), baseNameIndex,
    						  new ContentAuthenticator(publisher, timestamp, ContentType.FRAGMENT, locator),
    						  contentBlocks, false, blockCount, baseBlockIndex, signingKey);

		for (int i = 0; i < blockCount; i++) {
			try {
				Library.logger().info("putMerkleTree: writing block " + i + " of " + blockCount + " to name " + tree.blockName(i));
				put(tree.blockName(i), tree.blockAuthenticator(i), 
						contentBlocks[i], tree.blockSignature(i));
			} catch (IOException e) {
				Library.logger().warning("This should not happen: we cannot put our own blocks!");
				Library.warningStackTrace(e);
				throw e;
			}
		}
		// Caller needs both root signature and root itself. For now, give back the tree.
		return tree;
	}
	
	/**
	 * DKS not currently adding a header-specific prefix. A header, however,
	 * should not be a fragment.
	 * @param headerName
	 * @return
	 */
	public static ContentName headerRoot(ContentName headerName) {
		// Do we want to handle fragment roots, etc, here too?
		if (!isHeader(headerName)) {
			Library.logger().warning("Name " + headerName + " is not a header name.");
			throw new IllegalArgumentException("Name " + headerName.toString() + " is not a header name.");
		}
		// Strip off any header-specific prefix info if we
		// add any. If not present, does nothing. Would be faster not to bother
		// calling at all.
		// return headerName.cut(HEADER_NAME); 
		return headerName;
	}
	
	public static boolean isHeader(ContentName name) {
		// with on-path header, no way to tell except
		// that it wasn't a fragment. With separate name,
		// easier to handle.
	//	return (name.contains(HEADER_NAME));
		return (!isFragment(name));
	}
	
	public static ContentName fragmentName(ContentName name, int i) {
		return ContentName.fromNative(name, FRAGMENT_MARKER,
							Integer.toString(i));
	}
	
	/**
	 * Extract the fragment information from this name.
	 */
	public static int getFragmentNumber(ContentName name) {
		int offset = name.containsWhere(FRAGMENT_MARKER);
		return Integer.valueOf(ContentName.componentPrintURI(name.component(offset+1)));
	}
	
	public ContentObject get(ContentName name, long timeout) throws IOException, InterruptedException {
		Interest interest = new Interest(name);
		return get(interest, timeout);
	}
	
	/**
	 * Return data one level below us in the hierarchy only
	 * 
	 * @param name
	 * @param timeout
	 * @return
	 * @throws IOException
	 * @throws InterruptedException
	 */
	public ContentObject getNextLevel(ContentName name, long timeout) throws IOException, InterruptedException {
		Interest interest = new Interest(name);
		interest.additionalNameComponents(1);
		return get(interest, timeout);
	}
	
	/**
	 * Enumerate matches in the local repositories.
	 * TODO: maybe filter out fragments, possibly other metadata.
	 * TODO: add in communication layer to talk just to
	 * local repositories for v 2.0 protocol.
	 * @param query At this point, always recursive.
	 * @return
	 * @throws IOException 
	 */
	public ArrayList<ContentObject> enumerate(Interest query, long timeout) throws IOException {
		ArrayList<ContentObject> result = new ArrayList<ContentObject>();
		Integer prefixCount = query.nameComponentCount() == null ? query.name().components().size() 
				: query.nameComponentCount();
		try {
			while (true) {
				ContentObject co = get(query, timeout == NO_TIMEOUT ? 5000 : timeout);
				if (co == null)
					break;
				result.add(co);
				query = Interest.next(co, prefixCount);
			}
			return result;
		} catch (InterruptedException e) {
			return null;
		}
	}
	
	/**
	 * High-level verify. Calls low-level verify, if we
	 * don't think this has been verified already. Probably
	 * need to separate to keep the two apart.
	 * @param object
	 * @param publicKey The key to use to verify the signature,
	 * 	or null if the key should be retrieved using the key 
	 *  locator.
	 * @return
	 * @throws XMLStreamException 
	 * @throws NoSuchAlgorithmException 
	 * @throws SignatureException 
	 * @throws InvalidKeyException 
	 */
	public boolean verify(ContentObject object, PublicKey publicKey) 
			throws InvalidKeyException, SignatureException, NoSuchAlgorithmException, XMLStreamException {
		
		try {
			if (null == publicKey) {
				publicKey = KeyManager.getKeyRepository().getPublicKey(object.authenticator().publisherKeyID(), object.authenticator().keyLocator());
				if (null == publicKey) {
					Library.logger().info("Cannot retrieve key for publisher " + object.authenticator().publisherKeyID() + " to verify conten: " + object.name());
					return false;
				}
			}
			if (!object.verify(publicKey)) {
				Library.logger().warning("Low-level verify failed on " + object.name());
			}
		} catch (Exception e) {
			Library.logger().warning("Exception " + e.getClass().getName() + " during verify: " + e.getMessage());
			Library.warningStackTrace(e);
		}
		return true;
		// TODO DKS
		//throw new UnsupportedOperationException("Implement me!");
	}
	
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
	 * Beginnings of file system interface. If name is not versioned,
	 * for read, finds the latest version meeting the constraints.
	 * For writes, probably also should figure out the next version
	 * and open that for writing. Might get more complicated later;
	 * a file system (e.g. FUSE) layer on top of this might get more
	 * complicated still (e.g. mechanisms for detecting what the latest
	 * version is to make a new one for writing right now can't detect
	 * that we're already in the process of writing a given version).
	 * For now, we constraint the types of open modes we know about.
	 * We can't really append to an existing file, so we really can
	 * only pretty much open for writing or reading.
	 * @return a CCNDescriptor, which contains, among other things,
	 * the actual name we are opening. It also contains things
	 * like offsets and verification information.
	 */
	/**
	 * Open this name for reading (for now). If the name
	 * is versioned, open that version. Otherwise, open the
	 * latest version. If the name is a fragment, just open that one.
	 * Implicitly implements query match.
	 * Currently suggests can query match only on publisher...
	 * need to systematize this. Some of this might want
	 * to move into CCNDescriptor.
	 * 
	 * For now, it looks like the library-level (defragmenting)
	 * get will be implemented in terms of these operations,
	 * rather than the other way 'round. So these should use
	 * the low-level (CCNBase/CCNRepository/CCNNetwork) get.
	 * @throws IOException 
	 * @throws InterruptedException 
	 * @throws XMLStreamException 
	 */
	public CCNDescriptor open(ContentObject content, OpenMode mode) throws IOException, InterruptedException, XMLStreamException {
		return new CCNDescriptor(content, mode, this); 
	}
	
	public CCNDescriptor open(ContentName name, OpenMode mode) throws IOException, InterruptedException, XMLStreamException {
		return new CCNDescriptor(new ContentObject(name, null, null, (Signature)null), mode, this); 
	}
		
	public long read(CCNDescriptor ccnObject, byte [] buf, long 
											offset, long len) throws IOException, InterruptedException {
		return ccnObject.read(buf,offset,len);
	}

	public long write(CCNDescriptor ccnObject, byte [] buf, long offset, long len) throws IOException, InterruptedException, InvalidKeyException, SignatureException, NoSuchAlgorithmException {
		return ccnObject.write(buf, offset, len);
	}

	public int seek(CCNDescriptor ccnObject, long offset, CCNDescriptor.SeekWhence whence) throws IOException, InterruptedException {
		return ccnObject.seek(offset, whence);
	}
	
	public long tell(CCNDescriptor ccnObject) {
		return ccnObject.tell();
	}
	
	public int close(CCNDescriptor ccnObject) throws InvalidKeyException, SignatureException, NoSuchAlgorithmException, InterruptedException, IOException {
		return ccnObject.close();
	}
	
	public void sync(CCNDescriptor ccnObject) throws InvalidKeyException, SignatureException, NoSuchAlgorithmException, InterruptedException, IOException {
		ccnObject.sync();
	}
	
	/**
	 * Does this name refer to a node that represents
	 * local (protected) content?
	 * @param name
	 * @return
	 */
	public boolean isLocal(ContentObject name) {
		// TODO Auto-generated method stub
		return false;
	}

	/**
	 * Medium level interface for retrieving pieces of a file
	 *
	 * getNext - get next content after specified content
	 */
	public ContentObject getNext(ContentName name, ExcludeFilter omissions, long timeout) 
			throws MalformedContentNameStringException, IOException, InterruptedException, InvalidParameterException {
		return generalGet(name, new Integer(Interest.ORDER_PREFERENCE_LEFT | Interest.ORDER_PREFERENCE_ORDER_NAME), omissions, timeout);
	}
	
	public ContentObject getNext(ContentName name, long timeout)
			throws MalformedContentNameStringException, IOException,
			InterruptedException, InvalidParameterException {
		return getNext(name, null, timeout);
	}
	
	public ContentObject getNext(ContentObject content, int prefixCount, long timeout) 
			throws InvalidParameterException, MalformedContentNameStringException, 
			IOException, InterruptedException {
		return getNext(contentObjectToContentName(content, prefixCount), null, timeout);
	}
	
	/**
	 * Get last content in content stream
	 */
	public ContentObject getLatest(ContentName name, ExcludeFilter omissions, long timeout) 
			throws MalformedContentNameStringException, IOException, InterruptedException, InvalidParameterException {
		return generalGet(name, new Integer(Interest.ORDER_PREFERENCE_RIGHT | Interest.ORDER_PREFERENCE_ORDER_NAME), omissions, timeout);
	}
	
	public ContentObject getLatest(ContentName name, long timeout) throws InvalidParameterException, MalformedContentNameStringException, 
			IOException, InterruptedException {
		return getLatest(name, null, timeout);
	}
	
	public ContentObject getLatest(ContentObject content, int prefixCount, long timeout) throws InvalidParameterException, MalformedContentNameStringException, 
			IOException, InterruptedException {
		return getLatest(contentObjectToContentName(content, prefixCount), null, timeout);
	}
	
	public ContentObject getExcept(ContentName name, ExcludeFilter omissions, long timeout) throws InvalidParameterException, MalformedContentNameStringException, 
			IOException, InterruptedException {
		return generalGet(name, null, omissions, timeout);
	}
	
	private ContentName contentObjectToContentName(ContentObject content, int prefixCount) {
		ContentName cocn = content.name();
		cocn.components().add(content.contentDigest());
		return new ContentName(cocn.count(), cocn.components(), new Integer(prefixCount));
	}
	
	private ContentObject generalGet(ContentName name, Integer orderPreference, ExcludeFilter omissions, long timeout) 
			throws IOException, InterruptedException, MalformedContentNameStringException {
		Interest interest = new Interest(name);
		if (null != orderPreference)
			interest.orderPreference(orderPreference);
		if (null != omissions)
			interest.excludeFilter(omissions);
		return get(interest, timeout);
	}
}
