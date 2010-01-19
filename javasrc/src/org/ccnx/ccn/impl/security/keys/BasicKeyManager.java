/**
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008, 2009 Palo Alto Research Center, Inc.
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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.InvalidKeyException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import org.ccnx.ccn.KeyManager;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.config.UserConfiguration;
import org.ccnx.ccn.config.SystemConfiguration.DEBUGGING_FLAGS;
import org.ccnx.ccn.impl.security.crypto.util.MinimalCertificateGenerator;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.PublicKeyObject;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.profiles.security.KeyProfile;
import org.ccnx.ccn.profiles.security.access.KeyCache;
import org.ccnx.ccn.protocol.CCNTime;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.KeyLocator;
import org.ccnx.ccn.protocol.KeyName;
import org.ccnx.ccn.protocol.PublisherID;
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
	
	public static class KeyStoreInfo {
		KeyStore _keyStore;
		CCNTime _version;
		
		public KeyStoreInfo(KeyStore keyStore, CCNTime version) {
			_keyStore = keyStore;
			_version = version;
		}
		
		public KeyStore getKeyStore() { return _keyStore; }
		public CCNTime getVersion() { return _version; }
	}
	
	protected String _userName;
	protected String _defaultAlias;
	protected String _keyStoreDirectory;
	protected String _keyStoreFileName;
	protected String _keyStoreType;
	
	protected KeyStoreInfo _keyStoreInfo;
	protected PublisherPublicKeyDigest _defaultKeyID;
	
	protected boolean _initialized = false;
	protected boolean _defaultKeysPublished = false;
	
	private char [] _password = null;
	
	/**
	 * Cache of public keys, handles key publishing, etc.
	 */
	protected KeyRepository _keyRepository = null;
	
	/**
	 * Cache of private keys, loaded from keystores.
	 */
	protected KeyCache _privateKeyCache = null;
	
	/**
	 * Subclass constructor that sets store-independent parameters.
	 */
	protected BasicKeyManager(String userName, String keyStoreType,
							  String defaultAlias, char [] password) throws ConfigurationException, IOException {
		
		_userName = (null != userName) ? userName : UserConfiguration.userName();
		_password = (null != password) ? password : UserConfiguration.keystorePassword().toCharArray();
		_keyStoreType = (null != keyStoreType) ? keyStoreType : UserConfiguration.defaultKeystoreType();
	    _defaultAlias = (null != defaultAlias) ? defaultAlias : UserConfiguration.defaultKeyAlias();
	    // must call initialize
	}
		
	/** 
	 * Constructor
	 * 
	 * @throws ConfigurationException
	 * @throws IOException
	 */
	public BasicKeyManager(String userName, String keyStoreDirectory,
						   String keyStoreFileName, String keyStoreType, 
						   String defaultAlias, char [] password) throws ConfigurationException, IOException {
		this(userName, keyStoreType, defaultAlias, password);
		_keyStoreFileName = (null != keyStoreFileName) ? 
				keyStoreFileName : UserConfiguration.keystoreFileName();
	    _keyStoreDirectory = (null != keyStoreDirectory) ? keyStoreDirectory : UserConfiguration.ccnDirectory();
		// must call initialize
	}
	
	/**
	 * Default constructor, takes all parameters from defaults in UserCOnfiguration.
	 * @throws IOException 
	 * @throws ConfigurationException 
	 */
	public BasicKeyManager() throws ConfigurationException, IOException {
		this(null, null, null, null, null, null);
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
		_keyRepository = new KeyRepository(this);
		_privateKeyCache = new KeyCache();
		_keyStoreInfo = loadKeyStore();// uses _keyRepository and _privateKeyCache
		if (!loadValuesFromKeystore(_keyStoreInfo)) {
			Log.warning("Cannot process keystore!");
		}
		_initialized = true;		
		// Can we publish keys now?
		publishDefaultKey(null);
	}
	
	@Override
	public boolean initialized() { return _initialized; }
	
	/**
	 * Close any connections we have to the network. Ideally prepare to
	 * reopen them when they are next needed.
	 */
	
	public void close() {
		keyRepository().close();
	}
	
	/**
	 * Publish our default key at a particular name.
	 */
	
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
			Log.info("Creating new CCN key store..." + keyStoreFile.getAbsolutePath());
			keyStoreInfo = createKeyStore();
			Log.info("...created key store.");
		}
		if (null == keyStoreInfo) {
			FileInputStream in = null;
			KeyStore keyStore = null;
			Log.info("Loading CCN key store from " + keyStoreFile.getAbsolutePath() + "...");
			try {
				in = new FileInputStream(keyStoreFile);
				keyStore = readKeyStore(in);
				keyStoreInfo = new KeyStoreInfo(keyStore, new CCNTime(keyStoreFile.lastModified()));
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
			Log.info("Loading CCN key store...");
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
				byte [] tmp = new byte[2048];
				int read = in.read(tmp);
				while (read > 0) {
					bais.write(tmp, 0, read);
					read = in.read(tmp);
				}
				bais.flush();
				bais.close();
			} catch (IOException e1) {
				Log.info("Another exception: " + e1);
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
			Log.info("Default key ID for user " + _userName + ": " + _defaultKeyID);
			
			_privateKeyCache.loadKeyStore(keyStoreInfo, _password, _keyRepository);

		} catch (Exception e) {
			generateConfigurationException("Cannot retrieve default user keystore entry.", e);
		}    
		return true;
	}
		
	/**
	 * Creates a CCN versioned output stream as the key storage 
	 * @throws ConfigurationException
	 */
	synchronized protected KeyStoreInfo createKeyStore() throws ConfigurationException, IOException {
		
		OutputStream out = createKeyStoreWriteStream();
	    KeyStore keyStore = createKeyStore(out);
	    CCNTime version = getKeyStoreVersion(out);
	    return new KeyStoreInfo(keyStore, version);
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
	protected OutputStream createKeyStoreWriteStream() throws ConfigurationException, IOException {
		
		File keyStoreDir = new File(_keyStoreDirectory);
		if (!keyStoreDir.exists()) {
			if (!keyStoreDir.mkdirs()) {
				throw new ConfigurationException("Cannot create keystore directory: " + keyStoreDir.getAbsolutePath(), null);
			}
		}
		
		// Alas, until 1.6, we can't set permissions on the file or directory...
		// TODO DKS when switch to 1.6, add permission settings.
		File keyStoreFile  = new File(keyStoreDir, _keyStoreFileName);
		if (keyStoreFile.exists())
			return null;

	    FileOutputStream out = null;
		try {
			out = new FileOutputStream(keyStoreFile);
		} catch (FileNotFoundException e) {
			generateConfigurationException("Cannot create keystore file: " + keyStoreFile.getAbsolutePath(), e);
		} 
	    return out;   
	}
	
	/**
	 * Generates a key pair and a certificate, and stores them to the key store
	 * @throws ConfigurationException
	 */
	synchronized protected KeyStore createKeyStore(OutputStream out) throws ConfigurationException {

		KeyStore ks = null;
	    try {
			ks = KeyStore.getInstance(_keyStoreType);
			ks.load(null, _password);
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
		KeyPair userKeyPair = kpg.generateKeyPair();
		
		// Generate a self-signed certificate.
		String subjectDN = "CN=" + _userName;
		X509Certificate ssCert = null;
		try {
			 ssCert = 
				 MinimalCertificateGenerator.GenerateUserCertificate(userKeyPair, subjectDN, MinimalCertificateGenerator.MSEC_IN_YEAR);
		} catch (Exception e) {
			generateConfigurationException("InvalidKeyException generating user internal certificate.", e);
		} 

		KeyStore.PrivateKeyEntry entry =
	        new KeyStore.PrivateKeyEntry(userKeyPair.getPrivate(), new X509Certificate[]{ssCert});

	    try {
		    ks.setEntry(_defaultAlias, entry, 
			        new KeyStore.PasswordProtection(_password));
	        ks.store(out, _password);
		} catch (NoSuchAlgorithmException e) {
			generateConfigurationException("Cannot save default keystore.", e);
		} catch (CertificateException e) {
			generateConfigurationException("Cannot save default keystore with no certificates.", e);
	    } catch (KeyStoreException e) {
	    	generateConfigurationException("Cannot set private key entry for user default key", e);
		} catch (IOException e) {
			generateConfigurationException("Cannot write keystore file: " + _keyStoreFileName, e);
		} finally {
	        if (out != null) {
	            try {
					out.close();
				} catch (IOException e) {
					Log.warning("IOException closing key store file after load.");
					Log.warningStackTrace(e);
				}
	        }
	    }
		return ks;
	}

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
		return _keyRepository.getPublicKeyFromCache(getDefaultKeyID());
	}
	
	/**
	 * Get default key locator
	 * @return default key locator
	 */
	@Override
	public KeyLocator getDefaultKeyLocator() {
		return getKeyLocator(getDefaultKeyID());
	}
	
	@Override
	public KeyLocator getKeyLocator(PrivateKey signingKey) {
		PublisherPublicKeyDigest keyID = _privateKeyCache.getPublicKeyIdentifier(signingKey);
		return getKeyLocator(keyID);
	}
	
	/**
	 * Get default key locator given a public key digest
	 * @param key public key digest
	 * @return key locator
	 */
	@Override
	public KeyLocator getKeyLocator(PublisherPublicKeyDigest keyID) {
		if (null == keyID) {
			keyID = getDefaultKeyID();
		}
		PublicKeyObject keyObject = _keyRepository.retrieve(keyID);

		if (null != keyObject) {
			try {
				if (keyObject.isSaved()) {
					return new KeyLocator(new KeyName(keyObject.getVersionedName(), new PublisherID(keyObject.getContentPublisher())));
				}
			} catch (IOException ex) {
				Log.warning("IOException checking saved status or retrieving version of key object {0}: {1}!", keyObject.getVersionedName(), ex.getMessage());
				Log.warningStackTrace(ex);
				Log.warning("Falling through and retrieving KEY type key locator for key {1}", keyID);
			}
		}
		return getKeyTypeKeyLocator(keyID);
	}
	
	/**
	 * Get private key
	 * @return private key
	 */
	@Override
	public PrivateKey getDefaultSigningKey() {
		return _privateKeyCache.getPrivateKey(getDefaultKeyID().digest());
	}
	
	/**
	 * Get signing keys
	 * @return private signing keys
	 */
	@Override
	public PrivateKey [] getSigningKeys() {
		return _privateKeyCache.getPrivateKeys();
	}
	
	/**
	 * Get private signing key for a publisher. 
	 * If I am the publisher, return signing key;
	 * otherwise, return null.
	 * @param publisher publisher public key digest
	 * @return private signing key or null
	 */
	@Override
	public PrivateKey getSigningKey(PublisherPublicKeyDigest publisher) {
		Log.finer("getSigningKey: retrieving key: " + publisher);
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
	public PublicKey getPublicKey(PublisherPublicKeyDigest desiredKeyID, KeyLocator keyLocator, long timeout) throws IOException {		
		Log.finer("getPublicKey: retrieving key: " + desiredKeyID + " located at: " + keyLocator);
		// this will try local caches, the locator itself, and if it 
		// has to, will go to the network. The result will be stored in the cache.
		// All this tells us is that the key matches the publisher. For whether
		// or not we should trust it for some reason, we have to get fancy.
		return keyRepository().getPublicKey(desiredKeyID, keyLocator, timeout);
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
	public PublicKeyObject getPublicKeyObject(PublisherPublicKeyDigest desiredKeyID, KeyLocator locator, long timeout) throws IOException {
		Log.finer("getPublicKey: retrieving key: " + desiredKeyID + " located at: " + locator);
		// this will try local caches, the locator itself, and if it 
		// has to, will go to the network. The result will be stored in the cache.
		// All this tells us is that the key matches the publisher. For whether
		// or not we should trust it for some reason, we have to get fancy.
		return keyRepository().getPublicKeyObject(desiredKeyID, locator, timeout);
	}	
	
	/**
	 * Attempt to retrieve public key from cache.
	 * @throws IOException 
	 */
	@Override
	public PublicKey getPublicKey(PublisherPublicKeyDigest desiredKeyID) {
		return keyRepository().getPublicKeyFromCache(desiredKeyID);
	}

	/**
	 * Get publisher ID
	 * @param signingKey private signing key
	 * @return publisher public key digest
	 */
	@Override
	public PublisherPublicKeyDigest getPublisherKeyID(PrivateKey signingKey) {
		return _privateKeyCache.getPublicKeyIdentifier(signingKey);
	}

	/** 
	 * Get key repository
	 * @return key repository
	 */
	@Override
	public KeyRepository keyRepository() {
		return _keyRepository;
	}

	@Override
	public synchronized void publishDefaultKey(ContentName defaultPrefix) throws ConfigurationException, IOException {
		if (!initialized()) {
			throw new IOException("Cannot publish keys, have not yet initialized KeyManager!");
		}
		// we've put together enough of this KeyManager to let the
		// KeyRepository use it to make a CCNHandle, even though we're
		// not done...
		if (_defaultKeysPublished) {
			return;
		}
		ContentName keyName = getDefaultKeyName(defaultPrefix, getDefaultKeyID().digest(), getKeyVersion(getDefaultKeyID()));
		try {
			publishKey(keyName, getDefaultKeyID(), null, null);
		} catch (InvalidKeyException e) {
			generateConfigurationException("InvalidKeyException attempting to publish default key!", e);
		}
		_defaultKeysPublished = true;
	}
	
	@Override
	public ContentName getDefaultKeyNamePrefix() {
		ContentName keyDir =
			ContentName.fromNative(UserConfiguration.defaultUserNamespace(), 
				   			UserConfiguration.defaultKeyName());
		return keyDir;
	}
	
	/**
	 * Return the key's content name for a given key id. 
	 * The default key name is the publisher ID itself,
	 * under the user's key collection. 
	 * @param keyID[] publisher ID
	 * @return content name
	 */
	@Override
	public ContentName getDefaultKeyName(ContentName keyPrefix, byte [] keyID, CCNTime keyVersion) {
		if (null == keyPrefix) {
			keyPrefix = getDefaultKeyNamePrefix();
		}
		ContentName keyName = KeyProfile.keyName(keyPrefix, keyID);
		if (null != keyVersion) {
			return VersioningProfile.addVersion(keyName, keyVersion);
		}
		return keyName;
	}
	
	public CCNTime getKeyVersion(PublisherPublicKeyDigest keyID) {
		return _keyRepository.getPublicKeyVersionFromCache(keyID);
	}
	
	/**
	 * Publish my public key to a local key server run in this JVM.
	 * @param keyName content name of the public key
	 * @param keyToPublish public key digest
	 * @param handle handle for ccn
	 * @throws IOException
	 * @throws InvalidKeyException
	 * @throws ConfigurationException
	 */
	@Override
	public void publishKey(ContentName keyName, 
						   PublisherPublicKeyDigest keyToPublish,
						   PublisherPublicKeyDigest signingKeyID,
						   KeyLocator signingKeyLocator) throws InvalidKeyException, IOException, ConfigurationException {
		if (null == keyToPublish) {
			keyToPublish = getDefaultKeyID();
		} 
		Log.info("publishKey: publishing key {0} under specified key name{1}", keyToPublish, keyName);
		if (null == keyName) {
			CCNTime version = getKeyVersion(keyToPublish);
			keyName = getDefaultKeyName(null, keyToPublish.digest(), version);
		}
		boolean resetFlag = false;
		if (SystemConfiguration.checkDebugFlag(DEBUGGING_FLAGS.DEBUG_SIGNATURES)) {
			resetFlag = true;
			SystemConfiguration.setDebugFlag(DEBUGGING_FLAGS.DEBUG_SIGNATURES, false);
		}
		_keyRepository.publishKey(keyName, keyToPublish, signingKeyID, signingKeyLocator);
		if (resetFlag) {
			SystemConfiguration.setDebugFlag(DEBUGGING_FLAGS.DEBUG_SIGNATURES, true);
		}
	}

	@Override
	public void publishKey(ContentName keyName, 
						   PublicKey keyToPublish,
						   PublisherPublicKeyDigest signingKeyID,
						   KeyLocator signingKeyLocator) throws InvalidKeyException, IOException, ConfigurationException {
		if (null == keyToPublish) {
			keyToPublish = getDefaultPublicKey();
		} 
		PublisherPublicKeyDigest keyDigest = new PublisherPublicKeyDigest(keyToPublish);
		if (null == keyName) {
			CCNTime version = getKeyVersion(keyDigest);
			keyName = getDefaultKeyName(null, keyDigest.digest(), version);
		}
		boolean resetFlag = false;
		if (SystemConfiguration.checkDebugFlag(DEBUGGING_FLAGS.DEBUG_SIGNATURES)) {
			resetFlag = true;
			SystemConfiguration.setDebugFlag(DEBUGGING_FLAGS.DEBUG_SIGNATURES, false);
		}
		_keyRepository.publishKey(keyName, keyToPublish, signingKeyID, signingKeyLocator);
		if (resetFlag) {
			SystemConfiguration.setDebugFlag(DEBUGGING_FLAGS.DEBUG_SIGNATURES, true);
		}
	}

	/**
	 * Publish my public key to repository
	 * @param keyName content name of the public key
	 * @param keyToPublish public key digest
	 * @param handle handle for ccn
	 * @throws IOException
	 * @throws InvalidKeyException
	 * @throws ConfigurationException
	 */
	@Override
	public void publishKeyToRepository(ContentName keyName, 
									   PublisherPublicKeyDigest keyToPublish) throws InvalidKeyException, IOException, ConfigurationException {
		if (null == keyToPublish) {
			keyToPublish = getDefaultKeyID();
		} 
		if (null == keyName) {
			keyName = getKeyLocator(keyToPublish).name().name();
		}
		_keyRepository.publishKeyToRepository(keyName, keyToPublish);
	}
	
	/**
	 * Publish my public key to repository
	 * @param handle handle for ccn
	 * @throws IOException
	 * @throws InvalidKeyException
	 * @throws ConfigurationException
	 */
	@Override
	public void publishKeyToRepository() throws InvalidKeyException, IOException, ConfigurationException {
		publishKeyToRepository(null, null);
	}

}
