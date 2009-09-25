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
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import org.ccnx.ccn.KeyManager;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.config.UserConfiguration;
import org.ccnx.ccn.config.SystemConfiguration.DEBUGGING_FLAGS;
import org.ccnx.ccn.impl.security.crypto.util.MinimalCertificateGenerator;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
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
	
	protected String _userName;
	protected String _defaultAlias;
	protected String _keyStoreDirectory;
	protected String _keyStoreFileName;
	protected String _keyStoreType;
	
	protected KeyStore _keyStore;
	protected PublisherPublicKeyDigest _defaultKeyID;
	protected X509Certificate _certificate;
	protected PrivateKey _privateKey;
	protected KeyLocator _keyLocator;
	protected boolean _initialized = false;
	
	protected KeyRepository _keyRepository = null;
	
	private char [] _password = null;
	
	/**
	 * Subclass constructor that sets store-independent parameters.
	 */
	protected BasicKeyManager(String userName, String keyStoreType,
							  String defaultAlias, char [] password) throws ConfigurationException, IOException {
		
		_keyRepository = new KeyRepository();
		_userName = (null != userName) ? userName : UserConfiguration.userName();
		_password = (null != password) ? password : UserConfiguration.keystorePassword().toCharArray();
		_keyStoreType = (null != keyStoreType) ? keyStoreType : UserConfiguration.defaultKeystoreType();
	    _defaultAlias = (null != defaultAlias) ? defaultAlias : UserConfiguration.defaultKeyAlias();
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
		_userName = UserConfiguration.userName();
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
	public synchronized void initialize() throws ConfigurationException {
		if (_initialized)
			return;
		loadKeyStore();
		// we've put together enough of this KeyManager to let the
		// KeyRepository use it to make a CCNHandle, even though we're
		// not done...
		_keyRepository = new KeyRepository(this);
		try {
			publishDefaultKey();
		} catch (IOException e) {
			throw new ConfigurationException("IOException publishing default key: " + e.getMessage(), e);
		}
		_initialized = true;
	}
	
	protected boolean initialized() { return _initialized; }
	
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
	protected void loadKeyStore() throws ConfigurationException {
		
		File keyStoreFile = new File(_keyStoreDirectory, _keyStoreFileName);
		if (!keyStoreFile.exists()) {
			Log.info("Creating new CCN key store..." + keyStoreFile.getAbsolutePath());
			_keyStore = createKeyStore();
			Log.info("...created key store.");
		}
		if (null == _keyStore) {
			FileInputStream in = null;
			Log.info("Loading CCN key store from " + keyStoreFile.getAbsolutePath() + "...");
			try {
				in = new FileInputStream(keyStoreFile);
				readKeyStore(in);
			} catch (FileNotFoundException e) {
				Log.warning("Cannot open existing key store file: " + _keyStoreFileName);
				throw new ConfigurationException("Cannot open existing key store file: " + _keyStoreFileName);
			} 
		}
		// Overriding classes must call this.
		if (!loadValuesFromKeystore(_keyStore)) {
			Log.warning("Cannot process keystore!");
		}
	}
	
	/**
	 * Reads in a user's private/public keys and certificate from a key store
	 * Must have set _password.
	 * @param in input stream
	 * @throws ConfigurationException
	 */
	protected void readKeyStore(InputStream in) throws ConfigurationException {
		if (null == _keyStore) {
			try {
				Log.info("Loading CCN key store...");
				_keyStore = KeyStore.getInstance(_keyStoreType);
				_keyStore.load(in, _password);
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
		}
	}
	
	/**
	 * Read data from a newly opened, or newly created keystore.
	 * @param keyStore
	 * @throws ConfigurationException 
	 */
	protected boolean loadValuesFromKeystore(KeyStore keyStore) throws ConfigurationException {
		
		KeyStore.PrivateKeyEntry entry = null;
		try {
			entry = (KeyStore.PrivateKeyEntry)_keyStore.getEntry(_defaultAlias, new KeyStore.PasswordProtection(_password));
			if (null == entry) {
				Log.warning("Cannot get default key entry: " + _defaultAlias);
			}
		    _privateKey = entry.getPrivateKey();
		    _certificate = (X509Certificate)entry.getCertificate();
		    _defaultKeyID = new PublisherPublicKeyDigest(_certificate.getPublicKey());
			Log.info("Default key ID for user " + _userName + ": " + _defaultKeyID);

		    // Check to make sure we've published information about
		    // this key. (e.g. in testing, we may frequently
		    // nuke the contents of our repository even though the
		    // key remains, so need to republish). Or the first
		    // time we load this keystore, we need to publish.
		    ContentName keyName = getDefaultKeyName(_defaultKeyID.digest());
		    _keyLocator = new KeyLocator(keyName, new PublisherID(_defaultKeyID));
			Log.info("Default key locator for user " + _userName + ": " + _keyLocator);

		} catch (Exception e) {
			generateConfigurationException("Cannot retrieve default user keystore entry.", e);
		}    
		return true;
	}
	
	/**
	 * Creates a key store file
	 * @throws ConfigurationException
	 */
	synchronized protected KeyStore createKeyStore() throws ConfigurationException {
		
		File keyStoreDir = new File(_keyStoreDirectory);
		if (!keyStoreDir.exists()) {
			if (!keyStoreDir.mkdirs()) {
				generateConfigurationException("Cannot create keystore directory: " + keyStoreDir.getAbsolutePath(), null);
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
	    return createKeyStore(out);	    
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
		return _certificate.getPublicKey();
	}
	
	/**
	 * Get default key locator
	 * @return default key locator
	 */
	@Override
	public KeyLocator getDefaultKeyLocator() {
		return _keyLocator;
	}
	
	/**
	 * Get key locator given a public key digest
	 * @param key public key digest
	 * @return key locator
	 */
	@Override
	public KeyLocator getKeyLocator(PublisherPublicKeyDigest key) {
		if ((null == key) || (key.equals(_defaultKeyID)))
			return _keyLocator;
		ContentObject keyObject = _keyRepository.retrieve(key);
		if (null != keyObject) {
			return new KeyLocator(new KeyName(keyObject.fullName(), new PublisherID(keyObject.signedInfo().getPublisherKeyID())));
		}
		Log.info("Cannot find key locator for key: " + key);
		return null;
	}
	
	/**
	 * Get private key
	 * @return private key
	 */
	@Override
	public PrivateKey getDefaultSigningKey() {
		return _privateKey;
	}
	
	/**
	 * Return the key's content name for a given key id. 
	 * The default key name is the publisher ID itself,
	 * under the user's key collection. 
	 * @param keyID[] publisher ID
	 * @return content name
	 */
	@Override
	public ContentName getDefaultKeyName(byte [] keyID) {
		ContentName keyDir =
			ContentName.fromNative(UserConfiguration.defaultUserNamespace(), 
				   			UserConfiguration.defaultKeyName());
		return new ContentName(keyDir, keyID);
	}
	
	/** 
	 * Get public key given a string alias
	 * @param alias alias for certificate
	 * @return public key
	 */
	@Override
	public PublicKey getPublicKey(String alias) {
		Certificate cert = null;;
		try {
			cert = _keyStore.getCertificate(alias);
		} catch (KeyStoreException e) {
			Log.info("No certificate for alias " + alias + " in BasicKeymManager keystore.");
			return null;
		}
		return cert.getPublicKey();
	}
	
	/**
	 * Get signing key given string alias
	 * @param alias certificate alias
	 * @return private signing key
	 */
	@Override
	public PrivateKey getSigningKey(String alias) {
		PrivateKey key = null;
		try {
			key = (PrivateKey)_keyStore.getKey(alias, _password);
		} catch (Exception e) {
			Log.info("No key for alias " + alias + " in BasicKeymManager keystore. " + 
						e.getClass().getName() + ": " + e.getMessage());
			return null;
		}
		return key;
	}

	/**
	 * Get signing keys
	 * @return private signing keys
	 */
	@Override
	public PrivateKey [] getSigningKeys() {
		// For now just return our default key. Eventually return multiple identity keys.
		return new PrivateKey[]{getDefaultSigningKey()};
	}
	
	/**
	 * Get public key for publisher
	 * @param publisher publisher public key digest
	 * @return public key
	 * @throws IOException
	 */
	@Override
	public PublicKey getPublicKey(PublisherPublicKeyDigest publisher) throws IOException {
		// TODO Auto-generated method stub
		Log.finer("getPublicKey: retrieving key: " + publisher);
		
		if (_defaultKeyID.equals(publisher))
			return _certificate.getPublicKey();
		return keyRepository().getPublicKey(publisher);
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
		// TODO Auto-generated method stub
		Log.finer("getSigningKey: retrieving key: " + publisher);
		if (_defaultKeyID.equals(publisher))
			return _privateKey;
		return null;
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
	public PublicKey getPublicKey(PublisherPublicKeyDigest publisherID, KeyLocator keyLocator, long timeout) throws IOException {		
		Log.finer("getPublicKey: retrieving key: " + publisherID + " located at: " + keyLocator);
		// this will try local caches, the locator itself, and if it 
		// has to, will go to the network. The result will be stored in the cache.
		// All this tells us is that the key matches the publisher. For whether
		// or not we should trust it for some reason, we have to get fancy.
		return keyRepository().getPublicKey(publisherID, keyLocator, timeout);
	}

	/**
	 * Get publisher ID
	 * @param signingKey private signing key
	 * @return publisher public key digest
	 */
	@Override
	public PublisherPublicKeyDigest getPublisherKeyID(PrivateKey signingKey) {
		if (_privateKey.equals(signingKey))
			return _defaultKeyID;
		return null;
	}
	
	/** 
	 * Get key locator for publisher
	 * @param signingKey private signing key
	 * @return key locator
	 */
	@Override
	public KeyLocator getKeyLocator(PrivateKey signingKey) {
		if (signingKey.equals(_privateKey))
			return getDefaultKeyLocator();
		
		// DKS TODO
		return null;
	}

	/** 
	 * Get key repository
	 * @return key repository
	 */
	@Override
	public KeyRepository keyRepository() {
		return _keyRepository;
	}

	/**
	 * Make the public key available to other ccn agents
	 * @param keyName content name of the public key
	 * @param keyToPublish public key digest
	 * @throws IOException
	 * @throws InvalidKeyException
	 * @throws ConfigurationException 
	 */
	@Override
	public void publishKey(ContentName keyName,
						   PublisherPublicKeyDigest keyToPublish) throws IOException, InvalidKeyException, ConfigurationException {
		PublicKey key = null;
		if (null == keyToPublish) {
			key = getDefaultPublicKey();
		} else {
			key = getPublicKey(keyToPublish);
			if (null == key) {
				throw new InvalidKeyException("Cannot retrieive key " + keyToPublish);
			}
		}
		keyRepository().publishKey(keyName, key, getDefaultKeyID(), getDefaultSigningKey());
	}
	
	
	protected void publishDefaultKey() throws ConfigurationException, IOException {
	    if (null == getPublicKey(_defaultKeyID, _keyLocator, SystemConfiguration.SHORT_TIMEOUT)) {
	    	boolean resetFlag = false;
	    	if (SystemConfiguration.checkDebugFlag(DEBUGGING_FLAGS.DEBUG_SIGNATURES)) {
	    		resetFlag = true;
	    		SystemConfiguration.setDebugFlag(DEBUGGING_FLAGS.DEBUG_SIGNATURES, false);
	    	}
	    	keyRepository().publishKey(_keyLocator.name().name(), _certificate.getPublicKey(), 
	    								_defaultKeyID, _privateKey);
	    	if (resetFlag) {
	    		SystemConfiguration.setDebugFlag(DEBUGGING_FLAGS.DEBUG_SIGNATURES, true);
	    	}
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
		publishKeyToRepository(_keyLocator.name().name(), null);
	}

}

