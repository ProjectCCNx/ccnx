package org.ccnx.ccn;

import java.io.IOException;
import java.security.InvalidParameterException;
import java.security.Security;
import java.util.ArrayList;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.impl.CCNNetworkManager;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.ExcludeFilter;
import org.ccnx.ccn.protocol.Interest;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;
import org.ccnx.ccn.protocol.ContentObject.SimpleVerifier;


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
public class CCNHandle extends CCNBase {
	
	static {
		Security.addProvider(new BouncyCastleProvider());
	}
	
	protected static CCNHandle _library = null;

	/**
	 * Do we want to do this this way, or everything static?
	 */
	protected KeyManager _userKeyManager = null;
	
	public static CCNHandle open() throws ConfigurationException, IOException { 
		synchronized (CCNHandle.class) {
			try {
				return new CCNHandle();
			} catch (ConfigurationException e) {
				Log.logger().severe("Configuration exception initializing CCN library: " + e.getMessage());
				throw e;
			} catch (IOException e) {
				Log.logger().severe("IO exception initializing CCN library: " + e.getMessage());
				throw e;
			}
		}
	}
	
	public static CCNHandle open(KeyManager keyManager) { 
		synchronized (CCNHandle.class) {
			return new CCNHandle(keyManager);
		}
	}
	
	public static CCNHandle getLibrary() { 
		if (null != _library) 
			return _library;
		try {
			return createCCNLibrary();
		} catch (ConfigurationException e) {
			Log.logger().warning("Configuration exception attempting to create library: " + e.getMessage());
			Log.warningStackTrace(e);
			throw new RuntimeException("Error in system configuration. Cannot create library.",e);
		} catch (IOException e) {
			Log.logger().warning("IO exception attempting to create library: " + e.getMessage());
			Log.warningStackTrace(e);
			throw new RuntimeException("Error in system IO. Cannot create library.",e);
		}
	}

	protected static synchronized CCNHandle 
				createCCNLibrary() throws ConfigurationException, IOException {
		if (null == _library) {
			_library = new CCNHandle();
		}
		return _library;
	}

	protected CCNHandle(KeyManager keyManager) {
		_userKeyManager = keyManager;
		// force initialization of network manager
		try {
			_networkManager = new CCNNetworkManager();
		} catch (IOException ex){
			Log.logger().warning("IOException instantiating network manager: " + ex.getMessage());
			ex.printStackTrace();
			_networkManager = null;
		}
	}

	protected CCNHandle() throws ConfigurationException, IOException {
		this(KeyManager.getDefaultKeyManager());
	}
	
	/*
	 * For testing only
	 */
	protected CCNHandle(boolean useNetwork) {}
	
	public void setKeyManager(KeyManager keyManager) {
		if (null == keyManager) {
			Log.logger().warning("StandardCCNLibrary::setKeyManager: Key manager cannot be null!");
			throw new IllegalArgumentException("Key manager cannot be null!");
		}
		_userKeyManager = keyManager;
	}
	
	public KeyManager keyManager() { return _userKeyManager; }

	public PublisherPublicKeyDigest getDefaultPublisher() {
		return keyManager().getDefaultKeyID();
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
		Interest interest = new Interest(name, publisher);
		return get(interest, timeout);
	}

	/**
	 * Return data the specified number of levels below us in the
	 * hierarchy, with order preference of leftmost.
	 * 
	 * @param name
	 * @param level
	 * @param timeout
	 * @return
	 * @throws IOException
	 */
	public ContentObject getLower(ContentName name, int level, PublisherPublicKeyDigest publisher, long timeout) throws IOException {
		Interest interest = new Interest(name, publisher);
		interest.maxSuffixComponents(level);
		interest.minSuffixComponents(level);
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
		int count = query.name().count();
		while (true) {
			ContentObject co = null;
			co = get(query, timeout == NO_TIMEOUT ? 5000 : timeout);
			if (co == null)
				break;
			Log.logger().info("enumerate: retrieved " + co.name() + 
					" digest: " + ContentName.componentPrintURI(co.contentDigest()) + " on query: " + query.name());
			result.add(co);
			for (int i = co.name().count() - 1; i > count; i--) {
				result.addAll(enumerate(new Interest(new ContentName(i, co.name().components())), timeout));
			}
			query = Interest.next(co, count);
		}
		Log.logger().info("enumerate: retrieved " + result.size() + " objects.");
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
		return get(Interest.last(name, exclude, name.count() - 1), timeout);
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

	/**
	 * Allow default verification behavior to be replaced.
	 * @return
	 */
	public ContentVerifier defaultVerifier() {
		return SimpleVerifier.getDefaultVerifier();
	}
}

