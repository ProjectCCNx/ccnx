package com.parc.ccn.library;

import java.io.IOException;
import java.security.InvalidParameterException;
import java.security.Security;
import java.util.ArrayList;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

import com.parc.ccn.CCNBase;
import com.parc.ccn.Library;
import com.parc.ccn.config.ConfigurationException;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.MalformedContentNameStringException;
import com.parc.ccn.data.query.ExcludeFilter;
import com.parc.ccn.data.query.Interest;
import com.parc.ccn.data.security.ContentVerifier;
import com.parc.ccn.data.security.PublisherPublicKeyDigest;
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
public class CCNLibrary extends CCNBase implements ContentVerifier {
	
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
	 * Gets the latest version using a single interest/response. There may be newer versions available
	 * if you ask again passing in the version found.
	 *  
	 * @param name If the name ends in a version then this method explicitly looks for a newer version
	 * than that. If the name does not end in a version then this call just looks for the latest version.
	 * @param publisher Currently unused
	 * @param timeout
	 * @return A ContentObject with the latest version, or null if the query timed out. Note - the content
	 * returned could be any name under this new version - the last (rightmost) name is asked for, but
	 * depending on where the answer came from it may not necessarily be the last (rightmost) available.
	 * @throws IOException
	 * DKS TODO -- doesn't use publisher
	 * DKS TODO -- specify separately latest version known?
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
		while (true) {
			ContentObject co = getLatest(name, VersioningProfile.acceptVersions(versionComponent), timeout);
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
			versionComponent = co.name().component(name.count()-1);
		}
	}

	public ContentObject get(ContentName name, long timeout) throws IOException {
		Interest interest = new Interest(name);
		return get(interest, timeout);
	}
	
	/**
	 * TODO -- ignores publisher.
	 * @param name
	 * @param publisher
	 * @param timeout
	 * @return
	 * @throws IOException
	 */
	public ContentObject get(ContentName name, PublisherPublicKeyDigest publisher, long timeout) throws IOException {
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
		// This won't work without a correct order preference
		query.orderPreference(Interest.ORDER_PREFERENCE_ORDER_NAME | Interest.ORDER_PREFERENCE_LEFT);
		while (true) {
			ContentObject co = null;
			co = get(query, timeout == NO_TIMEOUT ? 5000 : timeout);
			if (co == null)
				break;
			Library.logger().info("enumerate: retrieved " + co.name() + 
					" digest: " + ContentName.componentPrintURI(co.contentDigest()) + " on query: " + query.name());
			result.add(co);
			query = Interest.next(co);
		}
		Library.logger().info("enumerate: retrieved " + result.size() + " objects.");
		return result;
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

	/* (non-Javadoc)
	 * @see com.parc.ccn.data.security.ContentVerifier#verifyBlock(com.parc.ccn.data.ContentObject)
	 */
	public boolean verifyBlock(ContentObject block) {
		boolean result = false;
		try {
			if (null == block)
				return false;
			result = block.verify(null);
		} catch (Exception ex) {
			// DKS TODO -- maybe do something more significant, but will minimize use of the default verifier.
			Library.logger().warning("Caught exception of type: " + ex.getClass().getName() + " in verify: " + ex.getMessage());
			result = false;
		}
		return result;
	}
	
	/**
	 * Allow default verification behavior to be replaced.
	 * @return
	 */
	public ContentVerifier defaultVerifier() {
		return this;
	}
}
