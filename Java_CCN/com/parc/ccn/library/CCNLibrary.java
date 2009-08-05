package com.parc.ccn.library;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.InvalidParameterException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.Security;
import java.security.SignatureException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.Random;

import javax.xml.stream.XMLStreamException;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

import com.parc.ccn.CCNBase;
import com.parc.ccn.Library;
import com.parc.ccn.config.ConfigurationException;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.MalformedContentNameStringException;
import com.parc.ccn.data.content.Collection;
import com.parc.ccn.data.content.Link;
import com.parc.ccn.data.content.LinkReference;
import com.parc.ccn.data.query.ExcludeFilter;
import com.parc.ccn.data.query.Interest;
import com.parc.ccn.data.security.KeyLocator;
import com.parc.ccn.data.security.PublisherPublicKeyDigest;
import com.parc.ccn.data.security.SignedInfo.ContentType;
import com.parc.ccn.library.io.repo.RepositoryOutputStream;
import com.parc.ccn.library.profiles.SegmentationProfile;
import com.parc.ccn.library.profiles.VersioningProfile;
import com.parc.ccn.network.CCNNetworkManager;
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
	
	static {
		Security.addProvider(new BouncyCastleProvider());
	}
	
	protected static CCNLibrary _library = null;

	/**
	 * Do we want to do this this way, or everything static?
	 */
	protected KeyManager _userKeyManager = null;
	
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
	
	public static CCNLibrary open(KeyManager keyManager) { 
		synchronized (CCNLibrary.class) {
			return new CCNLibrary(keyManager);
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
	
	/*
	 * For testing only
	 */
	protected CCNLibrary(boolean useNetwork) {}
	
	public void setKeyManager(KeyManager keyManager) {
		if (null == keyManager) {
			Library.logger().warning("StandardCCNLibrary::setKeyManager: Key manager cannot be null!");
			throw new IllegalArgumentException("Key manager cannot be null!");
		}
		_userKeyManager = keyManager;
	}
	
	public KeyManager keyManager() { return _userKeyManager; }

	public PublisherPublicKeyDigest getDefaultPublisher() {
		return keyManager().getDefaultKeyID();
	}
	
	
	/**
	 * DKS -- TODO -- collection and link functions move to collection and link, respectively
	 * @throws IOException 
	 * @throws NoSuchAlgorithmException 
	 * @throws SignatureException 
	 * @throws InvalidKeyException 
	 */
	@Deprecated
	public Link put(ContentName name, LinkReference target) throws InvalidKeyException, SignatureException, NoSuchAlgorithmException, IOException {
		return put(name, target, null, null, null);
	}
	
	@Deprecated
	public Link put(
			ContentName name, 
			LinkReference target,
			PublisherPublicKeyDigest publisher, KeyLocator locator,
			PrivateKey signingKey) throws InvalidKeyException, SignatureException, NoSuchAlgorithmException, IOException {

		if (null == signingKey)
			signingKey = keyManager().getDefaultSigningKey();

		if (null == locator)
			locator = keyManager().getKeyLocator(signingKey);
		
		if (null == publisher) {
			publisher = keyManager().getPublisherKeyID(signingKey);
		}
		
		try {
			Link link = new Link(VersioningProfile.addVersion(name), target, 
					publisher, locator, signingKey);
			put(link);
			return link;
		} catch (XMLStreamException e) {
			Library.logger().warning("Cannot canonicalize a standard container!");
			Library.warningStackTrace(e);
			throw new IOException("Cannot canonicalize a standard container!");
		}
	}

	/**
	 * The following 3 methods create a Collection with the argument references,
	 * put it, and return it. Note that fragmentation is not handled.
	 * 
	 * @param name
	 * @param references
	 * @return
	 * @throws SignatureException
	 * @throws IOException
	 */
	@Deprecated
	public Collection put(ContentName name, LinkReference [] references) throws SignatureException, IOException {
		return put(name, references, getDefaultPublisher());
	}

	@Deprecated
	public Collection put(ContentName name, LinkReference [] references, PublisherPublicKeyDigest publisher) 
				throws SignatureException, IOException {
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

	@Deprecated
	public Collection put(
			ContentName name, 
			LinkReference[] references,
			PublisherPublicKeyDigest publisher, KeyLocator locator,
			PrivateKey signingKey) throws InvalidKeyException, SignatureException, NoSuchAlgorithmException, IOException {

		if (null == signingKey)
			signingKey = keyManager().getDefaultSigningKey();

		if (null == locator)
			locator = keyManager().getKeyLocator(signingKey);
		
		if (null == publisher) {
			publisher = keyManager().getPublisherKeyID(signingKey);
		}
		
		try {
			Collection collection = new Collection(VersioningProfile.addVersion(name), references, 
					publisher, locator, signingKey);
			put(collection);
			return collection;
		} catch (XMLStreamException e) {
			Library.logger().warning("Cannot canonicalize a standard container!");
			Library.warningStackTrace(e);
			throw new IOException("Cannot canonicalize a standard container!");
		}
	}
	
	@Deprecated
	public Collection put(
			ContentName name, 
			ContentName[] references) throws InvalidKeyException, SignatureException, NoSuchAlgorithmException, IOException {
		return put(name, references, null, null, null);
	}
	
	@Deprecated
	public Collection put(
			ContentName name, 
			ContentName[] references,
			PublisherPublicKeyDigest publisher) throws InvalidKeyException, SignatureException, NoSuchAlgorithmException, IOException {
		return put(name, references, publisher, null, null);
	}
	
	@Deprecated
	public Collection put(
			ContentName name, 
			ContentName[] references,
			PublisherPublicKeyDigest publisher, KeyLocator locator,
			PrivateKey signingKey) throws InvalidKeyException, SignatureException, NoSuchAlgorithmException, IOException {
		LinkReference[] lrs = new LinkReference[references.length];
		for (int i = 0; i < lrs.length; i++)
			lrs[i] = new LinkReference(references[i]);
		return put(name, lrs, publisher, locator, signingKey);
	}
	

	/**
	 * 
	 * @param name - ContentName
	 * @param timeout - milliseconds
	 * @return
	 * @throws IOException
	 * @throws XMLStreamException 
	 */
	@Deprecated
	public Collection getCollection(ContentName name, long timeout) throws IOException, XMLStreamException {
		ContentObject co = getLatestVersion(name, null, timeout);
		if (null == co)
			return null;
		if (co.signedInfo().getType() != ContentType.DATA)
			throw new IOException("Content is not data, so can't be a collection.");
		Collection collection = Collection.contentToCollection(co);
		return collection;
	}

	/**
	 * Use the same publisherID that we used originally.
	 * @throws IOException 
	 * @throws SignatureException 
	 * @throws XMLStreamException 
	 * @throws InvalidKeyException 
	 */
	@Deprecated
	public Collection createCollection(
			ContentName name,
			ContentName [] references, PublisherPublicKeyDigest publisher, KeyLocator locator,
			PrivateKey signingKey) throws IOException, SignatureException, 
			XMLStreamException, InvalidKeyException {
		LinkReference[] lrs = new LinkReference[references.length];
		for (int i = 0; i < references.length; i++) {
			lrs[i] = new LinkReference(references[i]);
		}
		return createCollection(name, lrs, publisher, locator, signingKey);
	}
	
	@Deprecated
	public Collection createCollection(
			ContentName name,
			LinkReference [] references, PublisherPublicKeyDigest publisher, KeyLocator locator,
			PrivateKey signingKey) throws IOException, SignatureException, 
			XMLStreamException, InvalidKeyException {
		if (null == signingKey)
			signingKey = keyManager().getDefaultSigningKey();

		if (null == locator)
			locator = keyManager().getKeyLocator(signingKey);
		
		if (null == publisher) {
			publisher = keyManager().getPublisherKeyID(signingKey);
		}
		return new Collection(name, references, publisher, locator, signingKey);
	}
	
	@Deprecated
	public Collection addToCollection(
			Collection collection,
			ContentName [] references) throws IOException, SignatureException, 
			XMLStreamException, InvalidKeyException {
		LinkedList<LinkReference> contents = collection.contents();
		for (ContentName reference : references)
			contents.add(new LinkReference(reference));
		return updateCollection(collection, contents, null, null, null);
	}

	@Deprecated
	public ContentObject removeFromCollection(
			Collection collection,
			ContentName [] references) throws IOException, SignatureException, 
			XMLStreamException, InvalidKeyException {
		LinkedList<LinkReference> contents = collection.contents();
		for (ContentName reference : references)
			contents.remove(new LinkReference(reference));
		return updateCollection(collection, contents, null, null, null);
	}
	
	@Deprecated
	public ContentObject updateCollection(
			Collection collection,
			ContentName [] referencesToAdd,
			ContentName [] referencesToRemove) throws IOException, SignatureException, 
			XMLStreamException, InvalidKeyException {
		LinkedList<LinkReference> contents = collection.contents();
		for (ContentName reference : referencesToAdd)
			contents.add(new LinkReference(reference));
		for (ContentName reference : referencesToRemove)
			contents.remove(new LinkReference(reference));
		return updateCollection(collection, contents, null, null, null);
	}
	
	/**
	 * Create a Collection with the next version name and the input
	 * references and put it.  Note that this can't handle fragmentation.
	 * 
	 * @param oldCollection
	 * @param references
	 * @return
	 * @throws XMLStreamException
	 * @throws IOException
	 * @throws SignatureException 
	 * @throws InvalidKeyException 
	 */
	@Deprecated
	private Collection updateCollection(Collection oldCollection, LinkedList<LinkReference> references,
			 PublisherPublicKeyDigest publisher, KeyLocator locator,
			 PrivateKey signingKey) throws XMLStreamException, IOException,
			 InvalidKeyException, SignatureException {
		LinkReference[] newReferences = new LinkReference[references.size()];
		references.toArray(newReferences);
		Collection updatedCollection = createCollection(VersioningProfile.updateVersion(oldCollection.name()),
				newReferences, publisher, locator, signingKey);
		put(updatedCollection);
		return updatedCollection;
	}
	
	@Deprecated
	public Link createLink(
			ContentName name,
			ContentName linkName, PublisherPublicKeyDigest publisher, KeyLocator locator,
			PrivateKey signingKey) throws IOException, SignatureException, 
			XMLStreamException, InvalidKeyException {
		if (null == signingKey)
			signingKey = keyManager().getDefaultSigningKey();

		if (null == locator)
			locator = keyManager().getKeyLocator(signingKey);
		
		if (null == publisher) {
			publisher = keyManager().getPublisherKeyID(signingKey);
		}
		return new Link(name, linkName, publisher, locator, signingKey);
	}

	/**
	 * Return the link itself, not the content
	 * pointed to by a link. 
	 * @param name the identifier for the link to work on
	 * @return returns null if not a link, or name refers to more than one object

	 * @throws IOException 
	 * @throws SignatureException
	 * @throws IOException
	 */
	@Deprecated
	public Link getLink(ContentName name, long timeout) throws IOException {
		ContentObject co = getLatestVersion(name, null, timeout);
		if (co.signedInfo().getType() != ContentType.LINK)
			throw new IOException("Content is not a link reference");
		Link reference = new Link();
		try {
			reference.decode(co.content());
		} catch (XMLStreamException e) {
			// Shouldn't happen
			e.printStackTrace();
		}
		return reference;
	}
	
	/**
	 * Turn ContentObject of type link into a LinkReference
	 * @param co ContentObject
	 * @return
	 * @throws IOException
	 */
	@Deprecated
	public Link decodeLinkReference(ContentObject co) throws IOException {
		if (co.signedInfo().getType() != ContentType.LINK)
			throw new IOException("Content is not a collection");
		Link reference = new Link();
		try {
			reference.decode(co.content());
		} catch (XMLStreamException e) {
			// Shouldn't happen
			e.printStackTrace();
		}
		return reference;
	}
	
	/**
	 * Deference links and collections
	 * DKS TODO -- should it dereference collections?
	 * @param content
	 * @param timeout
	 * @return
	 * @throws IOException 
	 * @throws XMLStreamException 
	 */
	@Deprecated
	public ArrayList<ContentObject> dereference(ContentObject content, long timeout) throws IOException, XMLStreamException {
		ArrayList<ContentObject> result = new ArrayList<ContentObject>();
		if (null == content)
			return null;
		if (content.signedInfo().getType() == ContentType.LINK) {
			Link link = Link.contentToLinkReference(content);
			ContentObject linkCo = dereferenceLink(link, content.signedInfo().getPublisherKeyID(), timeout);
			if (linkCo == null) {
				return null;
			}
			result.add(linkCo);
		} else if (content.signedInfo().getType() == ContentType.DATA) {
			try {
				Collection collection = Collection.contentToCollection(content);
			
				if (null != collection) {
					LinkedList<LinkReference> al = collection.contents();
					for (LinkReference lr : al) {
						ContentObject linkCo = dereferenceLink(lr, content.signedInfo().getPublisherKeyID(), timeout);
						if (linkCo != null)
							result.add(linkCo);
					}
					if (result.size() == 0)
						return null;
				} else { // else, not a collection
					result.add(content);
				}
			} catch (XMLStreamException xe) {
				// not a collection
				result.add(content);
			}
		} else {
			result.add(content);
		}
		return result;
	} 
	
	/**
	 * Try to get the content referenced by the link. If it doesn't exist directly,
	 * try to get the latest version below the name.
	 * 
	 * @param reference
	 * @param publisher
	 * @param timeout
	 * @return
	 * @throws IOException
	 */
	@Deprecated
	private ContentObject dereferenceLink(LinkReference reference, PublisherPublicKeyDigest publisher, long timeout) throws IOException {
		ContentObject linkCo = get(reference.targetName(), timeout);
		if (linkCo == null)
			linkCo = getLatestVersion(reference.targetName(), publisher, timeout);
		return linkCo;
	}
	
	@Deprecated
	private ContentObject dereferenceLink(Link reference, PublisherPublicKeyDigest publisher, long timeout) throws IOException {
		ContentObject linkCo = get(reference.getTargetName(), timeout);
		if (linkCo == null)
			linkCo = getLatestVersion(reference.getTargetName(), publisher, timeout);
		return linkCo;
	}

	
	/**
	 * Things are not as simple as this. Most things
	 * are fragmented. Maybe make this a simple interface
	 * that puts them back together and returns a byte []?
	 * DKS TODO -- doesn't use publisher
	 * DKS TODO -- specify separately latest version known?
	 * @throws IOException 
	 */
	public ContentObject getLatestVersion(ContentName name, PublisherPublicKeyDigest publisher, long timeout) throws IOException {
		
		if (VersioningProfile.hasTerminalVersion(name)) {
			return getVersionInternal(SegmentationProfile.segmentRoot(name), timeout);
		} else {
			ContentName firstVersionName = VersioningProfile.addVersion(name, VersioningProfile.baseVersion());
			return getVersionInternal(firstVersionName, timeout);
		}
	}
	
	/**
	 * We are only called by getLatestVersion, which has already ensured that we
	 * either have a user-supplied version or a terminal version marker at the end of name; we have
	 * also previously stripped any segment marker. So we know we have a name terminated
	 * by the last version we know about (which could be 0).
	 */
	private ContentObject getVersionInternal(ContentName name, long timeout) throws InvalidParameterException, IOException {
		
		byte [] versionComponent = name.lastComponent();
		// initially exclude name components just before the first version, whether that is the
		// 0th version or the version passed in
		byte [] start = null;
		if (VersioningProfile.isBaseVersionComponent(versionComponent)) {
			start = new byte [] { VersioningProfile.VERSION_MARKER, VersioningProfile.OO, VersioningProfile.FF, VersioningProfile.FF, VersioningProfile.FF, VersioningProfile.FF, VersioningProfile.FF };
		} else {
			start = versionComponent;
		}
		while (true) {
			ContentObject co = getLatest(name, VersioningProfile.acceptVersions(start), timeout);
			if (co == null) {
				Library.logger().info("Null returned from getLatest for name: " + name);
				return null;
			}
			// What we get should be a block representing a later version of name. It might
			// be an actual segment of a versioned object, but it might also be an ancillary
			// object - e.g. a repo message -- which starts with a particular version of name.
			if (VersioningProfile.startsWithLaterVersionOf(co.name(), name)) {
				// we got a valid version! 
				// DKS TODO should we see if it's actually later than name?
				Library.logger().info("Got latest version: " + co.name());
				return co;
			} else {
				Library.logger().info("Rejected potential candidate version: " + co.name() + " not a later version of " + name);
			}
			start = co.name().component(name.count()-1);
		}
	}

	public ContentObject get(ContentName name, long timeout) throws IOException {
		Interest interest = new Interest(name);
		return get(interest, timeout);
	}
	
	/**
	 * Return data the specified number of levels below us in the
	 * hierarchy
	 * 
	 * @param name
	 * @param level
	 * @param timeout
	 * @return
	 * @throws IOException
	 */
	public ContentObject getLower(ContentName name, int level, long timeout) throws IOException {
		Interest interest = new Interest(name);
		interest.additionalNameComponents(level);
		return get(interest, timeout);
	}
	
	/**
	 * Return data the specified number of levels below us in the
	 * hierarchy, with order preference leftmost.
	 * DKS -- this might need to move to Interest.
	 * @param name
	 * @param level
	 * @param timeout
	 * @return
	 * @throws IOException
	 */
	public ContentObject getLeftmostLower(ContentName name, int level, long timeout) throws IOException {
		Interest interest = new Interest(name);
		interest.additionalNameComponents(level);
		interest.orderPreference(Interest.ORDER_PREFERENCE_ORDER_NAME | Interest.ORDER_PREFERENCE_LEFT);
		return get(interest, timeout);
	}

	/**
	 * Enumerate matches below query name in the hierarchy
	 * TODO: maybe filter out fragments, possibly other metadata.
	 * TODO: add in communication layer to talk just to
	 * local repositories for v 2.0 protocol.
	 * @param query
	 * @param timeout - microseconds
	 * @return
	 * @throws IOException 
	 */
	public ArrayList<ContentObject> enumerate(Interest query, long timeout) throws IOException {
		ArrayList<ContentObject> result = new ArrayList<ContentObject>();
		Integer prefixCount = query.nameComponentCount() == null ? query.name().components().size() 
				: query.nameComponentCount();
		// This won't work without a correct order preference
		query.orderPreference(Interest.ORDER_PREFERENCE_ORDER_NAME | Interest.ORDER_PREFERENCE_LEFT);
		while (true) {
			ContentObject co = null;
			co = get(query, timeout == NO_TIMEOUT ? 5000 : timeout);
			if (co == null)
				break;
			Library.logger().info("enumerate: retrieved " + co.name() + 
					" digest: " + ContentName.componentPrintURI(co.contentDigest()) + " on query: " + query.name() + " prefix count: " + prefixCount);
			result.add(co);
			query = Interest.next(co, prefixCount);
		}
		Library.logger().info("enumerate: retrieved " + result.size() + " objects.");
		return result;
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
	 * 
	 * Nothing uses this method for anything that couldn't easily be replaced.
	 */
	@Deprecated
	public RepositoryOutputStream repoOpen(ContentName name, 
			KeyLocator locator, PublisherPublicKeyDigest publisher) 
				throws IOException, XMLStreamException {
		return new RepositoryOutputStream(name, locator, publisher, this); 
	}
	

	/**
	 * Medium level interface for retrieving pieces of a file
	 *
	 * getNext - get next content after specified content
	 *
	 * @param name - ContentName for base of get
	 * @param prefixCount - next follows components of the name
	 * 						through this count.
	 * @param omissions - ExcludeFilter
	 * @param timeout - milliseconds
	 * @return
	 * @throws MalformedContentNameStringException
	 * @throws IOException
	 * @throws InvalidParameterException
	 */
	public ContentObject getNext(ContentName name, byte[][] omissions, long timeout) 
			throws IOException {
		return get(Interest.next(name, omissions, null), timeout);
	}
	
	public ContentObject getNext(ContentName name, long timeout)
			throws IOException, InvalidParameterException {
		return getNext(name, null, timeout);
	}
	
	public ContentObject getNext(ContentName name, int prefixCount, long timeout)
			throws IOException, InvalidParameterException {
		return get(Interest.next(name, prefixCount), timeout);
	}
	
	public ContentObject getNext(ContentObject content, int prefixCount, byte[][] omissions, long timeout) 
			throws IOException {
		return getNext(contentObjectToContentName(content, prefixCount), omissions, timeout);
	}
	
	/**
	 * Get last content that follows name in similar manner to
	 * getNext
	 * 
	 * @param name
	 * @param omissions
	 * @param timeout
	 * @return
	 * @throws MalformedContentNameStringException
	 * @throws IOException
	 * @throws InvalidParameterException
	 */
	public ContentObject getLatest(ContentName name, ExcludeFilter exclude, long timeout) 
			throws IOException, InvalidParameterException {
		return get(Interest.last(name, exclude), timeout);
	}
	
	public ContentObject getLatest(ContentName name, long timeout) throws InvalidParameterException, 
			IOException {
		return getLatest(name, null, timeout);
	}
	
	public ContentObject getLatest(ContentName name, int prefixCount, long timeout) throws InvalidParameterException, 
			IOException {
		return get(Interest.last(name, prefixCount), timeout);
	}
	
	public ContentObject getLatest(ContentObject content, int prefixCount, long timeout) throws InvalidParameterException, 
			IOException {
		return getLatest(contentObjectToContentName(content, prefixCount), null, timeout);
	}
	
	/**
	 * 
	 * @param name
	 * @param omissions
	 * @param timeout
	 * @return
	 * @throws InvalidParameterException
	 * @throws MalformedContentNameStringException
	 * @throws IOException
	 */
	public ContentObject getExcept(ContentName name, byte[][] omissions, long timeout) throws InvalidParameterException, MalformedContentNameStringException, 
			IOException {
		return get(Interest.exclude(name, omissions), timeout);
	}
	
	private ContentName contentObjectToContentName(ContentObject content, int prefixCount) {
		ContentName cocn = content.name().clone();
		cocn.components().add(content.contentDigest());
		return new ContentName(prefixCount, cocn.components());
	}
	
	/**
	 * Shutdown the library and it's associated resources
	 */
	public void close() {
		if (null != _networkManager)
			_networkManager.shutdown();
		_networkManager = null;
	}
}
