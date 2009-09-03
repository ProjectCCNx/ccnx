package org.ccnx.ccn;

import java.io.IOException;
import java.security.Security;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.impl.CCNNetworkManager;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Interest;
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
				Log.severe("Configuration exception initializing CCN library: " + e.getMessage());
				throw e;
			} catch (IOException e) {
				Log.severe("IO exception initializing CCN library: " + e.getMessage());
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
			Log.warning("Configuration exception attempting to create library: " + e.getMessage());
			Log.warningStackTrace(e);
			throw new RuntimeException("Error in system configuration. Cannot create library.",e);
		} catch (IOException e) {
			Log.warning("IO exception attempting to create library: " + e.getMessage());
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
			Log.warning("IOException instantiating network manager: " + ex.getMessage());
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
			Log.warning("StandardCCNLibrary::setKeyManager: Key manager cannot be null!");
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

