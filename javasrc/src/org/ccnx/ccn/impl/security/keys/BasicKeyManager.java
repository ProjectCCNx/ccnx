/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008-2012 Palo Alto Research Center, Inc.
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License version 2.1
 * as published by the Free Software Foundation. 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details. You should have received
 * a copy of the GNU Lesser General Public License along with this library;
 * if not, write to the Free Software Foundation, Inc., 51 Franklin Street,
 * Fifth Floor, Boston, MA 02110-1301 USA.
 */

package org.ccnx.ccn.impl.security.keys;

import static org.ccnx.ccn.impl.support.Serial.readObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.net.URI;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.KeyManager;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.config.UserConfiguration;
import org.ccnx.ccn.impl.security.crypto.EncryptedObjectFileHelper;
import org.ccnx.ccn.impl.security.crypto.util.MinimalCertificateGenerator;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.impl.support.Tuple;
import org.ccnx.ccn.io.content.KeyValueSet;
import org.ccnx.ccn.io.content.PublicKeyObject;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.profiles.security.access.AccessControlManager;
import org.ccnx.ccn.protocol.CCNTime;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.KeyLocator;
import org.ccnx.ccn.protocol.KeyLocator.KeyLocatorType;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;


/**
 * This is a basic implementation of key manager, which reads
 * its keying information from a Java keystore. If no keystore
 * file is specified, it reads keystore information from 
 * the default keystore file location in the user's home directory
 * (under ~/.ccnx). 
 * BasicKeyManager expects to find at least a default key pair
 * under a know alias and password (change to something more sensible).
 * If the file does not exist, BasicKeyManager generates a 
 * public/private key pair and a certificate and stores them to disk
 * at the specified location.  
 * @see KeyManager
 */
public class BasicKeyManager extends KeyManager {
	
	protected String _userName;
	protected ContentName _userNamespace; // default location for publishing keys
	protected String _defaultAlias;
	protected String _keyStoreDirectory;
	protected String _keyStoreFileName;
	protected String _keyStoreType;
	protected String _configurationFileName;
	protected String _keyCacheFileName;

	protected KeyStoreInfo _keyStoreInfo;
	protected PublisherPublicKeyDigest _defaultKeyID;
	
	protected boolean _initialized = false;
	
	private char [] _password = null;
	
	/**
	 * Cache of public keys, handles key publishing, etc.
	 */
	protected PublicKeyCache _publicKeyCache = null;
	
	/**
	 * Cache of private keys, loaded from keystores.
	 */
	protected SecureKeyCache _privateKeyCache = null;
	
	/**
	 * Key server, offering up our keys, if we need one.
	 */
	protected KeyServer _keyServer = null;
	
	/**
	 * Configuration data
	 */
	protected KeyValueSet _configurationData = null;
	
	/**
	 * Handle used by key server and key retrieval.
	 * This may be null, so always access via handle() if you are going to use it.
	 */
	protected CCNHandle _handle = null;
	
	/**
	 * Registry of key locators to use. In essence, these are pointers to our
	 * primary credential for each key. Unless overridden this is what we use
	 * for each of our signing keys.
	 * 
	 * TODO consider adding a second map tracking all the available key locators for
	 * a given key, to select from them.
	 */
	protected HashMap<PublisherPublicKeyDigest, KeyLocator> _currentKeyLocators = new HashMap<PublisherPublicKeyDigest, KeyLocator>();
	
	/**
	 * Access control managers containing our state.
	 */
	protected Set<AccessControlManager> _acmList = new HashSet<AccessControlManager>(); 


	/**
	 * Subclass constructor that sets store-independent parameters.
	 */
	protected BasicKeyManager(String userName, String keyStoreType,
							  String defaultAlias, char [] password) throws ConfigurationException, IOException {
		
		_password = (null != password) ? password : UserConfiguration.keystorePassword().toCharArray();
		_keyStoreType = (null != keyStoreType) ? keyStoreType : UserConfiguration.defaultKeystoreType();
	    _defaultAlias = ((null != defaultAlias) ? defaultAlias : UserConfiguration.defaultKeyAlias()).toLowerCase();
	    
	    String defaultUserName = UserConfiguration.userName();
	    if ((null == userName) || (userName.equals(defaultUserName))) {
	    	_userNamespace = UserConfiguration.userNamespace();
	    	_userName = defaultUserName;
	    } else {
	    	_userNamespace = UserConfiguration.userNamespace(userName);
	    	_userName = userName;
	    }
	    // must call initialize
	}
		
	/** 
	 * Constructor
	 * 
	 * @throws ConfigurationException
	 * @throws IOException
	 */
	public BasicKeyManager(String userName, String keyStoreDirectory,
						   String configurationFileName,
						   String keyStoreFileName, String keyStoreType, 
						   String defaultAlias, char [] password) throws ConfigurationException, IOException {
		this(userName, keyStoreType, defaultAlias, password);
		_keyStoreFileName = (null != keyStoreFileName) ? 
				keyStoreFileName : UserConfiguration.keystoreFileName();
		_configurationFileName = (null != configurationFileName) ? 
				configurationFileName : UserConfiguration.configurationFileName();
		// Don't let people override this. Also make "confguration file" a configuration file,
		// move cached identity data to an identity file name TODO.
		_keyCacheFileName = UserConfiguration.keyCacheFileName();
	    _keyStoreDirectory = (null != keyStoreDirectory) ? keyStoreDirectory : UserConfiguration.userConfigurationDirectory();
		// must call initialize
	}
	
	/**
	 * Default constructor, takes all parameters from defaults in UserCOnfiguration.
	 * @throws IOException 
	 * @throws ConfigurationException 
	 */
	public BasicKeyManager() throws ConfigurationException, IOException {
		this(null, null, null, null, null, null, null);
	}
	
	/**
	 * This initializes and loads the key pair and certificate of the user. 
	 * If a key store file exists,
	 * reads in the key; otherwise, create a key store file and a key pair.
	 * Separate this for the usual reasons; so subclasses can get set up before it's called.
	 * Could make fake base class constructor, and call loadKeyStore in subclass constructors,
	 * but this wouldn't work past one level, and this allows subclasses to override initialize behavior.
	 * @throws ConfigurationException 
	 */
	@Override
	public synchronized void initialize() throws ConfigurationException, IOException {
		if (_initialized)
			return;
		_publicKeyCache = new PublicKeyCache();
		_privateKeyCache = new SecureKeyCache();
		_keyStoreInfo = loadKeyStore();// uses _keyRepository and _privateKeyCache
		if (!loadValuesFromKeystore(_keyStoreInfo)) {
			Log.warning("Cannot process keystore!");
		}
		// This also loads our cached keys.
		if (!loadValuesFromConfiguration(_keyStoreInfo)) {
			Log.warning("Cannot process configuration data!");
		}
		_initialized = true;		
		// If we haven't been called off, initialize the key server
		if (UserConfiguration.publishKeys()) {
			initializeKeyServer(handle());
		}
	}
	
	public synchronized void initializeKeyServer(CCNHandle handle) throws IOException {
		if (null != _keyServer) {
			return;
		}
		_keyServer = new KeyServer(handle);
		if (UserConfiguration.publishKeys()) {
			try {
				this.publishKey(getDefaultKeyName(getDefaultKeyID()), 
						getDefaultPublicKey(),
						null,
						null,
						true);
			} catch (InvalidKeyException e) {
				// in java 1.6 move to handing exception to constructor
				throw new IOException("Default key is invalid! " + e);
			}
		}
	}

	public PublicKeyObject serveKey(ContentName keyName, PublicKey keyToPublish,
   			PublisherPublicKeyDigest signingKeyID, 
   			KeyLocator signingKeyLocator) throws IOException {
		// TODO -- make use default handle, just pass in KM to do signing and have KS use separate
		// handle for publishing
		initializeKeyServer(handle());
		return _keyServer.serveKey(keyName, keyToPublish, signingKeyID, signingKeyLocator);
	}

	@Override
	public void respondToKeyRequests(ContentName keyPrefix) throws IOException {
		initializeKeyServer(handle());
		_keyServer.respondToKeyRequests(keyPrefix);
	}

	@Override
	public boolean initialized() { return _initialized; }
	
	/**
	 * Close any connections we have to the network. Ideally prepare to
	 * reopen them when they are next needed.
	 */
	public synchronized void close() {
		if( Log.isLoggable(Log.FAC_KEYS, Level.FINE) )
			Log.fine(Log.FAC_KEYS, "BasicKeyManager.close()");
		super.close();
		
		if (_handle != null) {
			_handle.close();
			_handle = null;
		}
		try {
			saveSecureKeyCache();
		} catch (Exception e) {
			if (Log.isLoggable(Log.FAC_KEYS, Level.WARNING)) {
				Log.warning("Exception saving secure key cache: {0}", e);
			}
		}
	}
	
	public synchronized CCNHandle handle() throws IOException {
		if (null == _handle)
			_handle = CCNHandle.open(this);
		return _handle;
	}
	
	protected void setPassword(char [] password) {
		_password = password;
	}
	
	/**
	 * If a key store file exists, reads in the key; 
	 * otherwise, create a key store file and a key pair.
	 * @param keyStoreFileName the file containing the keystore, if null
	 * 	uses default in user's home directory.
	 * @throws ConfigurationException
	 */
	protected KeyStoreInfo loadKeyStore() throws ConfigurationException, IOException {
		
		File keyStoreFile = new File(_keyStoreDirectory, _keyStoreFileName);
		
		KeyStoreInfo keyStoreInfo = null;
		if (!keyStoreFile.exists() || (0 == keyStoreFile.length())) {
			// If the BC configuration is screwed up, sometimes a 0-length keystore
			// gets created. If so, blow it away and make a new one.
			Log.info(Log.FAC_KEYS, "Creating new CCN key store..." + keyStoreFile.getAbsolutePath());
			keyStoreInfo = createKeyStore();
			Log.info(Log.FAC_KEYS, "...created key store. Version: {0} ({1} ms) Last modified: {2}. Will now load normally.", 
					keyStoreInfo.getVersion(), keyStoreInfo.getVersion().getTime(), keyStoreFile.lastModified());
			
			// For some reason, if we just go from here, sometimes we end up with very slightly
			// different stat times on the file. This causes havoc with versioning. So,
			// read the file back in from scratch.
			keyStoreInfo = null;
		}
		if (null == keyStoreInfo) {
			FileInputStream in = null;
			KeyStore keyStore = null;
			if (Log.isLoggable(Log.FAC_KEYS, Level.INFO))
				Log.info(Log.FAC_KEYS, "Loading CCN key store from " + keyStoreFile.getAbsolutePath() + "...last modified " + keyStoreFile.lastModified() + "(ms).");
			try {
				in = new FileInputStream(keyStoreFile);
				keyStore = readKeyStore(in);
				keyStoreInfo = new KeyStoreInfo(keyStoreFile.toURI().toString(), keyStore, new CCNTime(keyStoreFile.lastModified()));
				if (Log.isLoggable(Log.FAC_KEYS, Level.INFO))
					Log.info(Log.FAC_KEYS, "Loaded CCN key store from " + keyStoreFile.getAbsolutePath() + "...version " + keyStoreInfo.getVersion() + " ms: " + keyStoreInfo.getVersion().getTime());
			} catch (FileNotFoundException e) {
				Log.warning("Cannot open existing key store file: " + _keyStoreFileName);
				throw e;
			} 
		}
		return keyStoreInfo;
	}
	
	/**
	 * Reads in a user's private/public keys and certificate from a key store
	 * Must have set _password.
	 * @param in input stream
	 * @throws ConfigurationException
	 */
	protected KeyStore readKeyStore(InputStream in) throws ConfigurationException {
		KeyStore keyStore = null;
		try {
			if (Log.isLoggable(Log.FAC_KEYS, Level.INFO))
				Log.info(Log.FAC_KEYS, "Loading CCN key store...");
			keyStore = KeyStore.getInstance(_keyStoreType);
			keyStore.load(in, _password);
		} catch (NoSuchAlgorithmException e) {
			Log.warning("Cannot load keystore: " + e);
			throw new ConfigurationException("Cannot load default keystore: " + e);
		} catch (CertificateException e) {
			Log.warning("Cannot load keystore with no certificates.");
			throw new ConfigurationException("Cannot load keystore with no certificates.");
		} catch (IOException e) {
			Log.warning("Cannot open existing key store: " + e);
			try {
				in.reset();
				java.io.FileOutputStream bais = new java.io.FileOutputStream("KeyDump.p12");
				try {
					byte [] tmp = new byte[2048];
					int read = in.read(tmp);
					while (read > 0) {
						bais.write(tmp, 0, read);
						read = in.read(tmp);
					}
					bais.flush();
				} finally {
					bais.close();
				}
			} catch (IOException e1) {
				Log.info(Log.FAC_KEYS, "Another exception: " + e1);
			}
			throw new ConfigurationException(e);
		} catch (KeyStoreException e) {
			Log.warning("Cannot create instance of preferred key store type: " + _keyStoreType + " " + e.getMessage());
			Log.warningStackTrace(e);
			throw new ConfigurationException("Cannot create instance of default key store type: " + _keyStoreType + " " + e.getMessage());
		} finally {
			if (null != in)
				try {
					in.close();
				} catch (IOException e) {
					Log.warning("IOException closing key store file after load.");
					Log.warningStackTrace(e);
				}
		}
		return keyStore;
	}
	
	/**
	 * Read data from a newly opened, or newly created keystore.
	 * @param keyStore
	 * @throws ConfigurationException 
	 */
	protected boolean loadValuesFromKeystore(KeyStoreInfo keyStoreInfo) throws ConfigurationException {
		KeyStore.PrivateKeyEntry entry = null;
		try {
			if (Log.isLoggable(Log.FAC_KEYS, Level.INFO))
				Log.info(Log.FAC_KEYS, "Loading key store {0} version {1} version component {2} millis {3}", keyStoreInfo.getKeyStoreURI(), keyStoreInfo.getVersion().toString(), 
						VersioningProfile.printAsVersionComponent(keyStoreInfo.getVersion()), keyStoreInfo.getVersion().getTime());
			// Default alias should be a PrivateKeyEntry
			entry = (KeyStore.PrivateKeyEntry)keyStoreInfo.getKeyStore().getEntry(_defaultAlias, new KeyStore.PasswordProtection(_password));
			if (null == entry) {
				Log.warning("Cannot get default key entry: " + _defaultAlias);
				generateConfigurationException("Cannot retrieve default user keystore entry.", null);
			}
		    X509Certificate certificate = (X509Certificate)entry.getCertificate();
		    if (null == certificate) {
				Log.warning("Cannot get certificate for default key entry: " + _defaultAlias);
				generateConfigurationException("Cannot retrieve certificate for default user keystore entry.", null);		    	
		    }
		    _defaultKeyID = new PublisherPublicKeyDigest(certificate.getPublicKey());
			if (Log.isLoggable(Log.FAC_KEYS, Level.INFO))
				Log.info(Log.FAC_KEYS, "Default key ID for user " + _userName + ": " + _defaultKeyID);
			
			_privateKeyCache.loadKeyStore(keyStoreInfo, _password, _publicKeyCache);

		} catch (Exception e) {
			generateConfigurationException("Cannot retrieve default user keystore entry.", e);
		}    
		return true;
	}
	
	/**
	 * Load values of relevance to a key manager. Most importantly, loads default
	 * key locator information. If the system parameter UserConfiguration.useKeyConfiguration()
	 * (settable from an environment variable, a Java property, or programmatically) is false,
	 * we do not load our saved key locators/identities, or our saved secret key cache.
	 * @return true if successful, false on error
	 * @throws ConfigurationException
	 */
	protected boolean loadValuesFromConfiguration(KeyStoreInfo keyStoreInfo) throws ConfigurationException {
		// Load key locator information. Might be in two places -- system property/environment variable,
		// or configuration file. Start with just system property, first round just specify
		// name, not publisher.
		// Starting step -- read a key name (no publisher) key locator just for our default
		// key from an environment variable/system property.
		String defaultKeyLocatorName = UserConfiguration.defaultKeyLocator();
		// Doesn't even support publisher specifications yet.
		if (null != defaultKeyLocatorName) {
			try {
				ContentName locatorName = ContentName.fromNative(defaultKeyLocatorName);
				setKeyLocator(getDefaultKeyID(), new KeyLocator(locatorName));
			} catch (MalformedContentNameStringException e) {
				generateConfigurationException("Cannot parse key locator name {0}!", e);
			}
		}

		// Load values from our configuration file, which should be read in UserConfiguration.
		// If useKeyConfiguration() is false, we do not read configured identities or cached secret/private keys.
		if (!UserConfiguration.useKeyConfiguration()) {
			if (Log.isLoggable(Log.FAC_KEYS, Level.INFO)) {
				Log.info(Log.FAC_KEYS, "Not loading key manager configuration data in response to user configuration variable.");
			}
			return true;
		}
		
		// Currently have saved data override command line, which might be bad...
		// also use that to preconfigure things like keystores and such
		// for right now, just as a super-fast trick, use java serialization to get out minmal data necessary
		if ((null == _keyStoreDirectory) || (null == _configurationFileName))  {
			if (Log.isLoggable(Log.FAC_KEYS, Level.INFO)) {
				Log.info(Log.FAC_KEYS, "loadValuesFromConfiguration: No configuration directory/file set, not loading.");
			}
			return true;
		}
		File configurationFile = new File(_keyStoreDirectory, _configurationFileName);
		if (Log.isLoggable(Log.FAC_KEYS, Level.INFO)) {
			Log.info(Log.FAC_KEYS, "loadValuesFromConfiguration: attempting to load configuration from {0}", configurationFile.getAbsolutePath());
		}
		if (configurationFile.exists()) {
			try {
				ObjectInputStream input = new ObjectInputStream(new FileInputStream(configurationFile));
				
				try {
					HashMap<PublisherPublicKeyDigest, KeyLocator> savedKeyLocators = readObject(input);
					_currentKeyLocators.putAll(savedKeyLocators);
					
					keyStoreInfo.setConfigurationFileURI(configurationFile.toURI().toString());
					
					if (Log.isLoggable(Log.FAC_KEYS, Level.INFO)) {
						Log.info(Log.FAC_KEYS, "Loaded configuration data from file {0}, got {1} key locator values.", 
								configurationFile.getAbsolutePath(), savedKeyLocators.size());
					}
				} finally {
					input.close();
				}

			} catch (FileNotFoundException e) {
				throw new ConfigurationException("Cannot read configuration file even though it claims to exist: " + configurationFile.getAbsolutePath(), e);
			} catch (IOException e) {
				throw new ConfigurationException("I/O error reading configuration file: " + configurationFile.getAbsolutePath(), e);
			} catch (ClassNotFoundException e) {
				throw new ConfigurationException("ClassNotFoundException deserializing configuration file: " + configurationFile.getAbsolutePath(), e);
			}
		} else {
			if (Log.isLoggable(Log.FAC_KEYS, Level.INFO)) {
				Log.info(Log.FAC_KEYS, "loadValuesFromConfiguration: configuration file {0} does not exist.", configurationFile.getAbsolutePath());
			}
		}
		
		return loadSavedSecureKeyCache();
	}
	
	public synchronized boolean loadSavedSecureKeyCache() throws ConfigurationException {
		// Load values from our configuration file, which should be read in UserConfiguration.
		if (!UserConfiguration.saveAndLoadKeyCache()) {
			if (Log.isLoggable(Log.FAC_KEYS, Level.INFO)) {
				Log.info(Log.FAC_KEYS, "Not loading key cache in response to user configuration variable.");
			}
			return true;
		}
		
		// Currently have saved data override command line, which might be bad...
		// also use that to preconfigure things like keystores and such
		// for right now, just as a super-fast trick, use java serialization to get out minmal data necessary
		if ((null == _keyStoreDirectory) || (null == _configurationFileName))  {
			if (Log.isLoggable(Log.FAC_KEYS, Level.INFO)) {
				Log.info(Log.FAC_KEYS, "loadValuesFromConfiguration: No configuration directory/file set, not loading.");
			}
			return true;
		}

		File keyCacheFile = new File(_keyStoreDirectory, _keyCacheFileName);
		if (Log.isLoggable(Log.FAC_KEYS, Level.INFO)) {
			Log.info(Log.FAC_KEYS, "loadSecureKeyCache: attempting to load saved key cache from {0}", keyCacheFile.getAbsolutePath());
		}
		if (keyCacheFile.exists()) {
			try {
				
				// Try to load the key cache.
				SecureKeyCache keyCache = EncryptedObjectFileHelper.readEncryptedObject(keyCacheFile, getDefaultSigningKey());
				
				if (Log.isLoggable(Log.FAC_KEYS, Level.INFO)) {
					Log.info(Log.FAC_KEYS, "Loaded saved key cached data from file {0}, got {1} keys values.", 
							keyCacheFile.getAbsolutePath(), keyCache.size());
					keyCache.printContents();
				}
				
				// merge key caches
				if (null != keyCache) {
					keyCache.merge(_privateKeyCache);
					_privateKeyCache = keyCache;
				}

			} catch (FileNotFoundException e) {
				Log.warning("Proceeding without cached keys -- cannot read key cache file even though it claims to exist: " + keyCacheFile.getAbsolutePath(), e);
			} catch (IOException e) {
				Log.warning("Proceeding without cached keys -- I/O error reading key cache file: " + keyCacheFile.getAbsolutePath(), e);
			} catch (ClassNotFoundException e) {
				Log.warning("Proceeding without cached keys -- ClassNotFoundException deserializing encrypted key cache file: " + keyCacheFile.getAbsolutePath(), e);
			}
		} else {
			if (Log.isLoggable(Log.FAC_KEYS, Level.INFO)) {
				Log.info(Log.FAC_KEYS, "loadSavedSecureKeyCache: key cache file {0} does not exist.", keyCacheFile.getAbsolutePath());
			}
		}
		
		return true;
	}
	
	/**
	 * As a very initial pass, save key cache as encrypted Java serialization. Later
	 * we'll clean this up... (And encryption still needs to be hooked up.)
	 * @throws IOException 
	 * @throws FileNotFoundException 
	 */
	@Override
	public void saveSecureKeyCache() throws FileNotFoundException, IOException {

		// This prevents us from writing the data out to a file, where it could interact badly with 
		// user state (e.g. if we're a unit test). It will still be changed in the runtime data,
		// allowing unit tests to use it within a single execution.
		if (!UserConfiguration.saveAndLoadKeyCache()) {
			if (Log.isLoggable(Log.FAC_KEYS, Level.INFO)) {
				Log.info(Log.FAC_KEYS, "Not saving key manager secure key cache data in response to user configuration variable.");
			}
			return;
		}
		
		getSecureKeyCache().validateForWriting();
		
		File keyCacheFile = new File(_keyStoreDirectory, _keyCacheFileName); 
		
		EncryptedObjectFileHelper.writeEncryptedObject(keyCacheFile, getSecureKeyCache(), getDefaultPublicKey());

	}
	

	/**
	 * As a very initial pass, save configuration state as Java serialization. Later
	 * we'll clean this up...
	 * @throws IOException 
	 * @throws FileNotFoundException 
	 */
	@Override
	public void saveConfigurationState() throws FileNotFoundException, IOException {

		// This prevents us from writing the data out to a file, where it could interact badly with 
		// user state (e.g. if we're a unit test). It will still be changed in the runtime data,
		// allowing unit tests to use it within a single execution.
		if (!UserConfiguration.useKeyConfiguration()) {
			if (Log.isLoggable(Log.FAC_KEYS, Level.INFO)) {
				Log.info(Log.FAC_KEYS, "Not saving key manager configuration data in response to user configuration variable.");
			}
			return;
		}
		
		File configurationFile = new File(_keyStoreDirectory, _configurationFileName); 
		
		// Update configuration data:
		ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(configurationFile));
		try {
			oos.writeObject(_currentKeyLocators);
		} finally {
			oos.close();
		}
	}
	
	/**
	 * Return a file URI pointing at our configuration data directory.
	 */
	@Override
	public URI getConfigurationDataURI() {
		File configurationFile = new File(_keyStoreDirectory, _configurationFileName); 
		return configurationFile.toURI();
	}
	
	/**
	 * Need a way to clear this programmatically. Call this before initialize(). This
	 * deletes our saved configuration state, key files and stored key cache.
	 */
	@Override 
	public void clearSavedConfigurationState() throws FileNotFoundException, IOException {
		File configurationFile = new File(_keyStoreDirectory, _configurationFileName); 
		if (configurationFile.exists()) {
			if (Log.isLoggable(Log.FAC_KEYS, Level.INFO)) {
				Log.info(Log.FAC_KEYS, "Deleting configuration state file {0}.", configurationFile.getAbsolutePath());
			}
			if (!configurationFile.delete()) {
				Log.warning("Unable to delete configuration state file {0}.", configurationFile.getAbsolutePath());
			}
		}
		clearStoredKeyLocator(null);
		
		File keyCacheFile = new File(_keyStoreDirectory, _keyCacheFileName); 
		if (keyCacheFile.exists()) {
			if (Log.isLoggable(Log.FAC_KEYS, Level.INFO)) {
				Log.info(Log.FAC_KEYS, "Deleting secret/private key cache file {0}.", keyCacheFile.getAbsolutePath());
			}
			if (!keyCacheFile.delete()) {
				Log.warning("Unable to delete secret/private key cache file {0}.", keyCacheFile.getAbsolutePath());
			}
		}
	}
			
	/**
	 * Generate our key store if we don't have one. Use createKeyStoreWriteStream to determine where
	 * to put it.
	 * @throws ConfigurationException
	 */
	synchronized protected KeyStoreInfo createKeyStore() 
				throws ConfigurationException, IOException {
		
		Tuple<KeyStoreInfo, OutputStream> streamInfo = createKeyStoreWriteStream();
	    KeyStore keyStore = createKeyStore(streamInfo.second());
	    
	    KeyStoreInfo storeInfo = streamInfo.first();
	    storeInfo.setKeyStore(keyStore);
	    if (null == storeInfo.getVersion()) {
	    	storeInfo.setVersion(getKeyStoreVersion(streamInfo.second()));
	    }
	    return storeInfo;
	}

	protected CCNTime getKeyStoreVersion(OutputStream out) throws IOException {
		// in our case, our output stream should be a file output stream...
		if (!(out instanceof FileOutputStream)) {
			throw new IOException("Unexpected output stream type in getKeyStoreVersion: " + out.getClass().getName());
		}
		File keyStoreFile = new File(_keyStoreDirectory, _keyStoreFileName);
		if (!keyStoreFile.exists()) {
			throw new IOException("KeyStore doesn't exist in getKeyStoreVersion: " + keyStoreFile.getAbsolutePath());
		}
		return new CCNTime(keyStoreFile.lastModified());
	}
	
	/**
	 * Creates a key store file
	 * @throws ConfigurationException
	 */
	protected Tuple<KeyStoreInfo, OutputStream> createKeyStoreWriteStream() throws ConfigurationException,
		IOException {
		return createKeyStoreWriteStream(_keyStoreDirectory, _keyStoreFileName);
	}
	
	/**
	 * Creates a key store file
	 * @throws ConfigurationException
	 */
	protected static Tuple<KeyStoreInfo, OutputStream> createKeyStoreWriteStream(
			String keyStoreDirectory, String keyStoreFileName) throws ConfigurationException, IOException {
		
		File keyStoreDir = new File(keyStoreDirectory);
		if (!keyStoreDir.exists()) {
			if (!keyStoreDir.mkdirs()) {
				throw new ConfigurationException("Cannot create keystore directory: " + keyStoreDir.getAbsolutePath(), null);
			}
		}
		
		// Alas, until 1.6, we can't set permissions on the file or directory...
		// TODO DKS when switch to 1.6, add permission settings.
		File keyStoreFile  = new File(keyStoreDir, keyStoreFileName);
		if (keyStoreFile.exists()) {
			Log.warning("Key store file {0} already exists (length {1}), overrwriting.", keyStoreFile.getAbsolutePath(), keyStoreFile.length());
		}

	    FileOutputStream out = null;
		try {
			if (Log.isLoggable(Log.FAC_KEYS, Level.FINEST))
				Log.finest(Log.FAC_KEYS, "Creating FileOutputStream to write keystore to file " + keyStoreFile.getAbsolutePath());
			out = new FileOutputStream(keyStoreFile);
		} catch (FileNotFoundException e) {
			generateConfigurationException("Cannot create keystore file: " + keyStoreFile.getAbsolutePath(), e);
		} 
		
		KeyStoreInfo storeInfo = new KeyStoreInfo(keyStoreFile.toURI().toString(), null, new CCNTime(keyStoreFile.lastModified()));
	    return new Tuple<KeyStoreInfo, OutputStream>(storeInfo, out);   
	}
	
	protected KeyStore createKeyStore(OutputStream keystoreWriteStream) 
			throws ConfigurationException, IOException {
		return createKeyStore(keystoreWriteStream, _keyStoreType, _defaultAlias, _password, _userName);
	}
	
	/**
	 * Generates a key pair and a certificate, and stores them to the key store using the specified
	 * alias, password, and other information.
	 * @param keystoreWriteStream The output stream to write the keystore to (file stream, ccn stream, ...)
	 * @param keyStoreType The keystore type to use. If null, uses UserConfiguration.defaultKeyStoreType()
	 * @param keyAlias The key alias to use. If null, uses UserConfiguration.defaultKeyAlias(). Note
	 * 	 that toLower is called on the alias before it is used, as OSes vary in their handling of
	 * 		case in keystore aliases (some treat it as significant, some don't).
	 * @param password The password to use for the key and keystore, if null uses 
	 * 		UserConfiguration.keystorePassword()
	 * @param userName The user name to use. If null, uses UserConfiguration.userName().
	 * @return
	 * @throws ConfigurationException
	 * @throws IOException
	 */
	public static KeyStore createKeyStore(OutputStream keystoreWriteStream, 
											 String keyStoreType, String keyAlias,
											 char [] password,
											 String userName) throws ConfigurationException, IOException {

		if (null == keyStoreType) {
			keyStoreType = UserConfiguration.defaultKeystoreType();
		}
		
		if (null == keyAlias) {
			keyAlias = UserConfiguration.defaultKeyAlias();
		}
		keyAlias = keyAlias.toLowerCase();
		
		if (null == password) {
			password = UserConfiguration.keystorePassword().toCharArray();
		}

		if (null == userName) {
			userName = UserConfiguration.userName();
		}

		KeyStore ks = null;
	    try {
			if (Log.isLoggable(Log.FAC_KEYS, Level.FINEST))
				Log.finest(Log.FAC_KEYS, "createKeyStore: getting instance of keystore type " + keyStoreType);
			ks = KeyStore.getInstance(keyStoreType);
			if (Log.isLoggable(Log.FAC_KEYS, Level.FINEST))
				Log.finest(Log.FAC_KEYS, "createKeyStore: loading key store.");
			ks.load(null, password);
			if (Log.isLoggable(Log.FAC_KEYS, Level.FINEST))
				Log.finest(Log.FAC_KEYS, "createKeyStore: key store loaded.");
		} catch (NoSuchAlgorithmException e) {
			generateConfigurationException("Cannot load empty default keystore.", e);
		} catch (CertificateException e) {
			generateConfigurationException("Cannot load empty default keystore with no certificates.", e);
		} catch (KeyStoreException e) {
			generateConfigurationException("Cannot create instance of default key store type.", e);
		} catch (IOException e) {
			generateConfigurationException("Cannot initialize instance of default key store type.", e);
		}
		
		KeyPairGenerator kpg = null;
		try {
			kpg = KeyPairGenerator.getInstance(UserConfiguration.defaultKeyAlgorithm());
		} catch (NoSuchAlgorithmException e) {
			generateConfigurationException("Cannot generate key using default algorithm: " + UserConfiguration.defaultKeyAlgorithm(), e);
		}
		kpg.initialize(UserConfiguration.defaultKeyLength());
		
		if (Log.isLoggable(Log.FAC_KEYS, Level.FINEST))
			Log.finest(Log.FAC_KEYS, "createKeyStore: generating " + UserConfiguration.defaultKeyLength() + "-bit " + UserConfiguration.defaultKeyAlgorithm() + " key.");
		KeyPair userKeyPair = kpg.generateKeyPair();
		if (Log.isLoggable(Log.FAC_KEYS, Level.FINEST))
			Log.finest(Log.FAC_KEYS, "createKeyStore: key generated, generating certificate for user " + userName);
		
		// Generate a self-signed certificate.
		String subjectDN = "CN=" + userName;
		X509Certificate ssCert = null;
		try {
			 ssCert = 
				 MinimalCertificateGenerator.GenerateUserCertificate(userKeyPair, subjectDN, 
						 											 MinimalCertificateGenerator.MSEC_IN_YEAR);
			if (Log.isLoggable(Log.FAC_KEYS, Level.FINEST))
				Log.finest(Log.FAC_KEYS, "createKeyStore: certificate generated.");
			 
		} catch (Exception e) {
			generateConfigurationException("InvalidKeyException generating user internal certificate.", e);
		} 

		KeyStore.PrivateKeyEntry entry =
	        new KeyStore.PrivateKeyEntry(userKeyPair.getPrivate(), new X509Certificate[]{ssCert});

	    try {
			if (Log.isLoggable(Log.FAC_KEYS, Level.FINEST))
				Log.finest(Log.FAC_KEYS, "createKeyStore: setting private key entry.");
		    ks.setEntry(keyAlias, entry, 
			        new KeyStore.PasswordProtection(password));
		    
			if (Log.isLoggable(Log.FAC_KEYS, Level.FINEST))
				Log.finest(Log.FAC_KEYS, "createKeyStore: storing key store.");
	        ks.store(keystoreWriteStream, password);
			if (Log.isLoggable(Log.FAC_KEYS, Level.FINEST))
				Log.finest(Log.FAC_KEYS, "createKeyStore: wrote key store.");

		} catch (NoSuchAlgorithmException e) {
			generateConfigurationException("Cannot save default keystore.", e);
		} catch (CertificateException e) {
			generateConfigurationException("Cannot save default keystore with no certificates.", e);
	    } catch (KeyStoreException e) {
	    	generateConfigurationException("Cannot set private key entry for user default key", e);
		} finally {
	        if (keystoreWriteStream != null) {
	            try {
					keystoreWriteStream.close();
				} catch (IOException e) {
					Log.warning("IOException closing key store file after load.");
					Log.warningStackTrace(e);
				}
	        }
	    }
		return ks;
	}
	
	public KeyStoreInfo getKeyStoreInfo() { return _keyStoreInfo; }

	/**
	 * Helper method to turn low-level errors into ConfigurationExceptions
	 * @param message explanatory message
	 * @param e original error
	 * @throws ConfigurationException
	 */
	static void generateConfigurationException(String message, Exception e) throws ConfigurationException {
		if (null == e) {
			Log.warning("Throwing ConfigurationException: {0}", message);
			throw new ConfigurationException(message);
		} else {
			Log.warning(message + " " + e.getClass().getName() + ": " + e.getMessage());
			Log.warningStackTrace(e);
			throw new ConfigurationException(message, e);
		}
	}

	/**
	 * Get default key id
	 * @return default key id
	 */
	@Override
	public PublisherPublicKeyDigest getDefaultKeyID() {
		return _defaultKeyID;
	}

	/**
	 * Get default public key
	 * @return default public key 
	 */
	@Override
	public PublicKey getDefaultPublicKey() {
		return _publicKeyCache.getPublicKeyFromCache(getDefaultKeyID());
	}
		
	@Override
	public ContentName getDefaultKeyNamePrefix() {
		ContentName keyDir = new ContentName(_userNamespace, UserConfiguration.defaultKeyNamespaceMarker());
		return keyDir;
	}
	
	@Override
	public ContentName getDefaultKeyName(PublisherPublicKeyDigest keyID) {
		if (null == keyID)
			keyID = getDefaultKeyID();
		return getDefaultKeyName(getDefaultKeyNamePrefix(), keyID, getKeyVersion(keyID));
	}
		
	/**
	 * Get the key locator to use for a given key. If a value has been stored
	 * by calling setKeyLocator, that value will be used. Such values can
	 * also be initialized using command-line properties, environment variables,
	 * or configuration files. Usually it refers to content already published.
	 * As we don't know where the key might be published, if no value is
	 * specified, we return a locator of type KEY. We have deprecated the
	 * previous behavior of trying to look at objects we have published
	 * containing this key; this does not allow the user enough control over
	 * what key locator will be used.
	 * @return key locator
	 */
	@Override
	public KeyLocator getKeyLocator(PublisherPublicKeyDigest keyID) {
		if (null == keyID) {
			keyID = getDefaultKeyID();
		}
		KeyLocator keyLocator = getStoredKeyLocator(keyID);
		if (null == keyLocator) {
			keyLocator = getKeyTypeKeyLocator(keyID);
		}
		if (Log.isLoggable(Log.FAC_KEYS, Level.INFO))
			Log.info(Log.FAC_KEYS, "getKeyLocator: returning locator {0} for key {1}", keyLocator, keyID);
		return keyLocator;
	}
	
	@Override 
	public KeyLocator getStoredKeyLocator(PublisherPublicKeyDigest keyID) {
		if (null == keyID) {
			keyID = getDefaultKeyID();
		}
		return _currentKeyLocators.get(keyID);
	}

	@Override 
	public boolean haveStoredKeyLocator(PublisherPublicKeyDigest keyID) {
		if (null == keyID) {
			keyID = getDefaultKeyID();
		}
		return _currentKeyLocators.containsKey(keyID);
	}
	
	@Override 
	public void clearStoredKeyLocator(PublisherPublicKeyDigest keyID) {
		if (null == keyID) {
			keyID = getDefaultKeyID();
		}
		_currentKeyLocators.remove(keyID);
	}

	/**
	 * Helper method to get the key locator for one of our signing keys.
	 */
	@Override
	public KeyLocator getKeyLocator(Key signingKey) {
		PublisherPublicKeyDigest keyID = _privateKeyCache.getPublicKeyIdentifier(signingKey);
		return getKeyLocator(keyID);
	}
	
	/**
	 * Remember the key locator to use for a given key. Use
	 * this to publish this key in the future if not overridden by method
	 * calls. If no key locator stored for this key, and no override
	 * given, compute a KEY type key locator if this key has not been
	 * published, and the name given to it when published if it has.
	 * @param publisherKeyID the key whose locator to set
	 * @param keyLocator the new key locator for this key; overrides any previous value.
	 * 	If null, erases previous value and defaults will be used.
	 */
	public void setKeyLocator(PublisherPublicKeyDigest publisherKeyID, KeyLocator keyLocator) {
		if (null == publisherKeyID)
			publisherKeyID = getDefaultKeyID();;
		_currentKeyLocators.put(publisherKeyID, keyLocator);
	}

	
	@Override
	public CCNTime getKeyVersion(PublisherPublicKeyDigest keyID) {
		return _publicKeyCache.getPublicKeyVersionFromCache(keyID);
	}
	
	
	/**
	 * Get private key
	 * @return private key
	 */
	@Override
	public Key getDefaultSigningKey() {
		return _privateKeyCache.getPrivateKey(getDefaultKeyID().digest());
	}
	
	/**
	 * Get signing keys
	 * @return private signing keys
	 * TODO bug -- currently returns all the private keys in our cache, which includes decryption
	 * keys. Replace with getPrivateKeys with getMyPrivateKeys once we're sure it won't cause trouble.
	 */
	@Override
	public Key [] getSigningKeys() {
		return _privateKeyCache.getPrivateKeys();
	}
	
	/**
	 * Get the public key digests corresponding to our available signing keys
	 * 
	 */
	@Override
	public PublisherPublicKeyDigest [] getAvailableIdentities() {
		PrivateKey [] pks = _privateKeyCache.getMyPrivateKeys();
		PublisherPublicKeyDigest [] ids = new PublisherPublicKeyDigest[pks.length];
		int i=0;
		for (PrivateKey pk : pks) {
			PublisherPublicKeyDigest ppkd = _privateKeyCache.getPublicKeyIdentifier(pk);
			if (Log.isLoggable(Log.FAC_KEYS, Level.INFO)) {
				Log.info(Log.FAC_KEYS, "KeyManager: have identity {0}", ppkd);
			}
			ids[i++] = ppkd; // a little dangerous; with low probability could be null, which
				// caller might not expect.
		}
		return ids;
	}
	
	/**
	 * Get private signing key for a publisher. 
	 * If I am the publisher, return signing key;
	 * otherwise, return null.
	 * @param publisher publisher public key digest
	 * @return private signing key or null
	 */
	@Override
	public Key getSigningKey(PublisherPublicKeyDigest publisher) {
		if (Log.isLoggable(Log.FAC_KEYS, Level.FINER) )
			Log.finer(Log.FAC_KEYS, "getSigningKey: retrieving key: " + publisher);
		if (null == publisher)
			return null;
		return _privateKeyCache.getPrivateKey(publisher.digest());
	}

	/**
	 * Get public key for a publisher, given a key locator.
	 * Times out after timeout amount of time elapsed 
	 * @param publisherID publisher public key digest
	 * @param keyLocator key locator
	 * @param timeout timeout value
	 * @return public key
	 */
	@Override
	public Key getVerificationKey(
			PublisherPublicKeyDigest desiredKeyID, KeyLocator keyLocator, 
			long timeout) throws IOException {		
		
		if (Log.isLoggable(Log.FAC_KEYS, Level.FINER))
			Log.finer(Log.FAC_KEYS, "getVerificationKey: retrieving key: " + desiredKeyID + " located at: " + keyLocator);
		if (null == keyLocator) {
			// Presumably this means that the key is a symmetric key
			return getSecureKeyCache().getPrivateKey(desiredKeyID.digest());
		}
		// this will try local caches, the locator itself, and if it 
		// has to, will go to the network. The result will be stored in the cache.
		// All this tells us is that the key matches the publisher. For whether
		// or not we should trust it for some reason, we have to get fancy.
		return (Key)getPublicKeyCache().getPublicKey(desiredKeyID, keyLocator, timeout, handle());
	}
	
	/**
	 * Get a public key object for this key locator and publisher, if there is one.
	 * This is less general than the method above, which retrieves keys we have cached
	 * but which have never been published -- our keys, keys listed explicitly in locators,
	 * etc.
	 * @param desiredKeyID
	 * @param locator
	 * @param timeout
	 * @return
	 * @throws IOException
	 */
	@Override 
	public PublicKeyObject getPublicKeyObject(
			PublisherPublicKeyDigest desiredKeyID, KeyLocator locator, 
			long timeout) throws IOException {
		
		if( Log.isLoggable(Log.FAC_KEYS, Level.FINER) )
			Log.finer(Log.FAC_KEYS, "getPublicKeyObject: retrieving key: {0} located at: {1}", desiredKeyID, locator);
		// this will try local caches, the locator itself, and if it 
		// has to, will go to the network. The result will be stored in the cache.
		// All this tells us is that the key matches the publisher. For whether
		// or not we should trust it for some reason, we have to get fancy.
		return getPublicKeyCache().getPublicKeyObject(desiredKeyID, locator, timeout, handle());
	}	
	
	/**
	 * Attempt to retrieve public key from cache.
	 * @throws IOException 
	 */
	@Override
	public PublicKey getPublicKey(PublisherPublicKeyDigest desiredKeyID) {
		return getPublicKeyCache().getPublicKeyFromCache(desiredKeyID);
	}

	/**
	 * Get publisher ID
	 * @param signingKey private signing key
	 * @return publisher public key digest
	 */
	@Override
	public PublisherPublicKeyDigest getPublisherKeyID(Key signingKey) {
		return _privateKeyCache.getPublicKeyIdentifier(signingKey);
	}

	/** 
	 * Get key repository
	 * @return key repository
	 */
	@Override
	public PublicKeyCache getPublicKeyCache() {
		return _publicKeyCache;
	}
	
	@Override
	public SecureKeyCache getSecureKeyCache() {
		return _privateKeyCache;
	}

	@Override
	public PublicKeyObject publishKey(ContentName keyName, 
						   PublicKey keyToPublish,
						   PublisherPublicKeyDigest signingKeyID,
						   KeyLocator signingKeyLocator,
						   boolean learnKeyLocator) throws InvalidKeyException, IOException {
		if (null == keyToPublish) {
			keyToPublish = getDefaultPublicKey();
		} 
		PublisherPublicKeyDigest keyDigest = new PublisherPublicKeyDigest(keyToPublish);
		
		if (null == keyName) {
			keyName = getDefaultKeyName(keyDigest);
		}

		PublicKeyObject keyObject =  
			serveKey(keyName, keyToPublish, signingKeyID, signingKeyLocator);
		
		if (Log.isLoggable(Log.FAC_KEYS, Level.INFO))
			Log.info(Log.FAC_KEYS, "publishKey: published key {0}\n under specified key name {1}\n key locator: {2}", 
					keyDigest, keyObject.getVersionedName(), keyObject.getPublisherKeyLocator());

		if (learnKeyLocator && !haveStoredKeyLocator(keyDigest) && (null != keyObject)) {
			// So once we publish self-signed key object, we store a pointer to that
			// to use. Don't override any manually specified values.
			KeyLocator newKeyLocator = new KeyLocator(keyObject.getVersionedName(), keyObject.getContentPublisher());
			setKeyLocator(keyDigest, newKeyLocator);
			if (Log.isLoggable(Log.FAC_KEYS, Level.INFO))
				Log.info(Log.FAC_KEYS, "publishKey: storing key locator {0} for key {1}", keyDigest, newKeyLocator);
		}
		return keyObject;
	}

	@Override
	public PublicKeyObject publishDefaultKey(ContentName keyName)
			throws IOException, InvalidKeyException {
		if (!initialized()) {
			throw new IOException("KeyServer: cannot publish keys, have not yet initialized KeyManager!");
		}
		return publishKey(keyName, getDefaultKeyID(), null, null);
	}

	/**
	 * Publish a key at a certain name, ensuring that it is stored in a repository. Will throw an
	 * exception if no repository available. Usually used to publish our own keys, but can specify
	 * any key known to our key cache.
	 * @param keyName Name under which to publish the key. Currently added under existing version, or version
	 * 	included in keyName.
	 * @param keyToPublish can be null, in which case we publish our own default public key.
	 * @param handle the handle to use for network requests
	 * @throws InvalidKeyException
	 * @throws IOException
	 */
	@Override
	public PublicKeyObject publishKeyToRepository(ContentName keyName,
			PublisherPublicKeyDigest keyToPublish,
			long timeToWaitForPreexisting) throws InvalidKeyException,
			IOException {
		if (null == keyToPublish) {
			keyToPublish = getDefaultKeyID();
		}
		
		PublicKey theKey = getPublicKeyCache().getPublicKeyFromCache(keyToPublish);
		if (null == theKey) {
			throw new InvalidKeyException("Key " + keyToPublish + " not available in cache, cannot publish!");
		}
		
		if (null == keyName) {
			KeyLocator locator = getKeyLocator(keyToPublish);
			if (locator.type() != KeyLocatorType.NAME) {
				// can't get a name from here, pull from the default namespace.
				keyName = getDefaultKeyName(keyToPublish);
			} else {
				keyName = locator.name().name();
			}
		}
		return KeyManager.publishKeyToRepository(keyName, theKey, getDefaultKeyID(), 
												 getDefaultKeyLocator(), timeToWaitForPreexisting, 
												 false, handle());
	}
	
	/**
	 * Publish a key at a certain name, ensuring that it is stored in a repository. Require
	 * that the key be self-signed. Will throw an
	 * exception if no repository available. Usually used to publish our own keys, but can specify
	 * any key known to our key cache.
	 * @param keyName Name under which to publish the key. Currently added under existing version, or version
	 * 	included in keyName.
	 * @param theKey the public key to publish. Provide both this and keyToPublish to avoid recomputing them
	 * 	if the caller has them already; otherwise they'll be retrieved from cache.
	 * @param keyToPublish can be null, in which case we publish our own default public key.
	 * @param handle the handle to use for network requests
	 * @throws InvalidKeyException
	 * @throws IOException
	 */
	@Override
	public PublicKeyObject publishSelfSignedKeyToRepository(ContentName keyName,
			PublicKey theKey,
			PublisherPublicKeyDigest keyToPublish,
			long timeToWaitForPreexisting) throws InvalidKeyException,
			IOException {
		if (null == keyToPublish) {
			keyToPublish = getDefaultKeyID();
		}
		
		if (null == theKey) {
			theKey = getPublicKeyCache().getPublicKeyFromCache(keyToPublish);
			if (null == theKey) {
				throw new InvalidKeyException("Key " + keyToPublish + " not available in cache, cannot publish!");
			}
		}
		
		return KeyManager.publishKeyToRepository(keyName, theKey, keyToPublish, 
												 KeyManager.SELF_SIGNED_KEY_LOCATOR, 
												 timeToWaitForPreexisting, 
												 false, handle());
	}

	@Override
	public AccessControlManager getAccessControlManagerForName(
			ContentName contentName) {
		synchronized (_acmList) {
			for (AccessControlManager acm : _acmList){
				if (acm.inProtectedNamespace(contentName)) {
					return acm;
				}
			}
			return null;
		}
	}

	@Override
	public void rememberAccessControlManager(AccessControlManager acm) {
		synchronized (_acmList) {
			_acmList.add(acm);
		}
	}
}
