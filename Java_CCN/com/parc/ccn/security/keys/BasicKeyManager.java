package com.parc.ccn.security.keys;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SignatureException;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.util.ArrayList;
import java.util.Iterator;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.Library;
import com.parc.ccn.config.ConfigurationException;
import com.parc.ccn.config.UserConfiguration;
import com.parc.ccn.data.CompleteName;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.security.ContentAuthenticator;
import com.parc.ccn.data.security.KeyLocator;
import com.parc.ccn.data.security.PublisherID;
import com.parc.ccn.network.CCNRepositoryManager;
import com.parc.ccn.security.crypto.certificates.BCX509CertificateGenerator;
import com.parc.ccn.security.crypto.certificates.CryptoUtil;

public class BasicKeyManager extends KeyManager {
	
	protected KeyStore _keystore = null;
	protected String _defaultAlias = null;
	protected PublisherID _defaultKeyID = null;
	protected X509Certificate _certificate = null;
	protected PrivateKey _privateKey = null;
	protected KeyLocator _keyLocator = null;
	private char [] _password = null;
	
	public BasicKeyManager() throws ConfigurationException {
		loadKeyStore();
	}
	
	protected void loadKeyStore() throws ConfigurationException {
		File keyStoreFile = new File(UserConfiguration.keystoreFileName());
		if (!keyStoreFile.exists()) {
			Library.logger().info("Creating new CCN key store...");
			_keystore = createKeyStore();	
		}
		if (null == _keystore) {
		    FileInputStream in = null;
			try {
				Library.logger().info("Loading CCN key store...");
				_password = UserConfiguration.keystorePassword().toCharArray();
				_keystore = KeyStore.getInstance(KeyStore.getDefaultType());
				in = new FileInputStream(UserConfiguration.keystoreFileName());
				_keystore.load(in, _password);
			} catch (NoSuchAlgorithmException e) {
				Library.logger().warning("Cannot load default keystore.");
				throw new ConfigurationException("Cannot load default keystore.");
			} catch (CertificateException e) {
				Library.logger().warning("Cannot load default keystore with no certificates.");
				throw new ConfigurationException("Cannot load default keystore with no certificates.");
			} catch (FileNotFoundException e) {
				Library.logger().warning("Cannot open existing key store file: " + UserConfiguration.keystoreFileName());
				throw new ConfigurationException("Cannot open existing key store file: " + UserConfiguration.keystoreFileName());
			} catch (IOException e) {
				Library.logger().warning("Cannot open existing key store file: " + UserConfiguration.keystoreFileName() + ": " + e.getMessage());
				throw new ConfigurationException(e);
			} catch (KeyStoreException e) {
				Library.logger().warning("Cannot create instance of default key store type: " + e.getMessage());
				Library.warningStackTrace(e);
				throw new ConfigurationException("Cannot create instance of default key store type: " + e.getMessage());
			} finally {
				if (null != in)
					try {
						in.close();
					} catch (IOException e) {
						Library.logger().warning("IOException closing key store file after load.");
						Library.warningStackTrace(e);
					}
			}
			
		}
	    _defaultAlias = UserConfiguration.defaultKeyAlias();
		KeyStore.PrivateKeyEntry entry = null;
		try {
			entry = (KeyStore.PrivateKeyEntry)_keystore.getEntry(_defaultAlias, new KeyStore.PasswordProtection(_password));
			if (null == entry) {
				Library.logger().warning("Cannot get default key entry: " + _defaultAlias);
			}
		    _privateKey = entry.getPrivateKey();
		    _certificate = (X509Certificate)entry.getCertificate();
		    _defaultKeyID = new PublisherID(_certificate.getPublicKey(), false);

		    // Check to make sure we've published information about
		    // this key. (e.g. in testing, we may frequently
		    // nuke the contents of our repository even though the
		    // key remains, so need to republish). Or the first
		    // time we load this keystore, we need to publish.
		    ContentName keyName = getDefaultKeyName(_defaultKeyID.id());
		    _keyLocator = new KeyLocator(keyName, _defaultKeyID);
			Library.logger().info("Default key locator: " + _keyLocator);
		    if (null == getKey(_defaultKeyID, _keyLocator)) {
		    	publishKey(_certificate.getPublicKey(), _privateKey);
		    }
		
		} catch (Exception e) {
			generateConfigurationException("Cannot retrieve default user keystore entry.", e);
		}    
	}
	
	synchronized protected KeyStore createKeyStore() throws ConfigurationException {

		File ccnDir = new File(UserConfiguration.ccnDirectory());
		if (!ccnDir.exists()) {
			if (!ccnDir.mkdirs()) {
				generateConfigurationException("Cannot create user CCN directory: " + ccnDir.getAbsolutePath(), null);
			}
		}
		File keyStoreFile  = new File(UserConfiguration.keystoreFileName());
		if (keyStoreFile.exists())
			return null;
		
		KeyStore ks = null;
	    try {
	    	_password = UserConfiguration.keystorePassword().toCharArray();
			ks = KeyStore.getInstance(KeyStore.getDefaultType());
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
		String subjectDN = "CN=" + UserConfiguration.userName();
		X509Certificate ssCert = null;
		try {
			 ssCert = 
				BCX509CertificateGenerator.GenerateX509Certificate(userKeyPair, subjectDN, BCX509CertificateGenerator.MSEC_IN_YEAR);
		} catch (Exception e) {
			generateConfigurationException("InvalidKeyException generating user internal certificate.", e);
		} 

		KeyStore.PrivateKeyEntry entry =
	        new KeyStore.PrivateKeyEntry(userKeyPair.getPrivate(), new X509Certificate[]{ssCert});

	    FileOutputStream out = null;
	    try {
		    ks.setEntry(UserConfiguration.defaultKeyAlias(), entry, 
			        new KeyStore.PasswordProtection(_password));
	        out = new FileOutputStream(UserConfiguration.keystoreFileName());
	        ks.store(out, _password);
		} catch (NoSuchAlgorithmException e) {
			generateConfigurationException("Cannot save default keystore.", e);
		} catch (CertificateException e) {
			generateConfigurationException("Cannot save default keystore with no certificates.", e);
	    } catch (KeyStoreException e) {
	    	generateConfigurationException("Cannot set private key entry for user default key", e);
		} catch (FileNotFoundException e) {
			generateConfigurationException("Cannot create keystore file: " + UserConfiguration.keystoreFileName(), e);
		} catch (IOException e) {
			generateConfigurationException("Cannot write keystore file: " + UserConfiguration.keystoreFileName(), e);
		} finally {
	        if (out != null) {
	            try {
					out.close();
				} catch (IOException e) {
					Library.logger().warning("IOException closing key store file after load.");
					Library.warningStackTrace(e);
				}
	        }
	    }
		
		return ks;
	}

	protected CompleteName publishKey(PublicKey key, PrivateKey signingKey) throws ConfigurationException {
		// publish a key locator for our use
		// as long as we can re-generate the name,
		// we know it should exist
		// problem -- we need to be able to put
		// even though we aren't done being created yet
		// go through low-level interface
		byte [] encodedKey = key.getEncoded();
		// Need a key locator to stick in data entry for
		// locator. Could use key itself, but then would have
		// key both in the content for this item and in the
		// key locator, which is redundant. Use naming form
		// that allows for self-referential key names -- the
		// CCN equivalent of a "self-signed cert". Means that
		// we will refer to only the base key name and the publisher ID,
		// not the uniqueified key name...
		PublisherID thisKeyID = new PublisherID(key, false);
		ContentName keyName = getDefaultKeyName(thisKeyID.id());
		KeyLocator locatorLocator = 
			new KeyLocator(keyName, thisKeyID);
		CompleteName uniqueKeyName = null;
		try {
			uniqueKeyName = ContentAuthenticator.generateAuthenticatedName(
									 keyName,
									 thisKeyID,
									 ContentAuthenticator.now(),
									 ContentAuthenticator.ContentType.LEAF,
									 encodedKey,
									 false,
									 locatorLocator,
									 signingKey);
		} catch (Exception e) {
			generateConfigurationException("Exception generating key locator for (and using) default user key.", e);
		}
		try {
			// DKS TODO should we publish this more widely?
			// Just write it locally for now, let others pick
			// it up. Use low-level interface as we may not
			// have a library.
			CompleteName publishedLocation = 
				CCNRepositoryManager.getRepositoryManager().put(
						uniqueKeyName.name(), 
						uniqueKeyName.authenticator(), 
						encodedKey);
			Library.logger().info("Generated user default key. Published key locator as: " + publishedLocation.name());
			return publishedLocation;
		} catch (IOException e) {
			generateConfigurationException("Cannot put key locator for default key.", e);
		}
		return null;
	}
	
	protected void generateConfigurationException(String message, Exception e) throws ConfigurationException {
		Library.logger().warning(message + " " + e.getClass().getName() + ": " + e.getMessage());
		Library.warningStackTrace(e);
		throw new ConfigurationException(message, e);
	}

	public PublisherID getDefaultKeyID() {
		return _defaultKeyID;
	}

	public PublicKey getDefaultPublicKey() {
		return _certificate.getPublicKey();
	}
	
	public KeyLocator getDefaultKeyLocator() {
		return _keyLocator;
	}
	
	public PrivateKey getDefaultSigningKey() {
		return _privateKey;
	}
	
	/**
	 * The default key name is the publisher ID itself,
	 * under the user's key collection. 
	 * @param keyID
	 * @return
	 */
	public ContentName getDefaultKeyName(byte [] keyID) {
		ContentName keyDir =
			new ContentName(UserConfiguration.defaultUserNamespace(), 
				   			UserConfiguration.defaultKeyName());
		return new ContentName(keyDir, keyID);
	}

	public PublicKey getPublicKey(String alias) {
		Certificate cert = null;;
		try {
			cert = _keystore.getCertificate(alias);
		} catch (KeyStoreException e) {
			Library.logger().info("No certificate for alias " + alias + " in BasicKeymManager keystore.");
			return null;
		}
		return cert.getPublicKey();
	}

	public PrivateKey getSigningKey(String alias) {
		PrivateKey key = null;;
		try {
			key = (PrivateKey)_keystore.getKey(alias, _password);
		} catch (Exception e) {
			Library.logger().info("No key for alias " + alias + " in BasicKeymManager keystore. " + 
						e.getClass().getName() + ": " + e.getMessage());
			return null;
		}
		return key;
	}
	
	/**
	 * Find the key for the given publisher, using the 
	 * available location information. Or, more generally,
	 * find a key at the given location that matches the
	 * given publisher information. If the publisher is an
	 * issuer, this gets tricky -- basically the information
	 * at the given location must be sufficient to get the
	 * right key.
	 * TODO DKS need to figure out how to decide what to do
	 * 	with a piece of content. In some sense, mime-types
	 * 	might make sense...
	 * @param publisher
	 * @param locator
	 * @return
	 * @throws IOException
	 */
	public PublicKey getKey(PublisherID desiredKeyID,
							KeyLocator locator) throws IOException {
		
		if (null != locator.certificate())
			return locator.certificate().getPublicKey();
		else if (null != locator.key())
			return locator.key();
		
		// Otherwise, this is a name.
		ArrayList<ContentObject> keys =
			CCNRepositoryManager.getRepositoryManager().get(locator.name().name(), 
															new ContentAuthenticator(locator.name().publisher()),
															true);
			
		// OK, we have a bunch of stuff. 
		Library.logger().info("BasicKeyManager: getKey: retrieved " + keys.size() + " items for one key locator.");
		// We know that it needs to be a key, and it needs to
		// be signed by the publisher we care about. And most
		// importantly, it needs to be for the publisher we are
		// looking for (or an issuer of their key...).
		// When publishers might be an issuer, and this could be a
		// complex process (which should be handled by other code).
		// Start filling in the outline by being simple.
		// We need to find all the objects that match our criteria,
		// and of them, take the latest one.
		// In the end, we don't want anything as complex as
		// the path validation languages, but we do want something
		// that allows referring to signers.
		
		// TODO DKS figure out overloading, KeyLocators, LinkAuthenticators
		// and so on. I think it's pretty close to right, but 
		// we need some basic use cases written up.
		
		// So, lets go through the thing. The PublisherID tells us
		// what to expect at this key location -- a certificate or a key.
		ContentObject keyObject = null;
		PublicKey theKey = null;
		
		Iterator<ContentObject> keyIt = keys.iterator();
		while (keyIt.hasNext()) {
			ContentObject potentialKey = keyIt.next();
			// Want to find key *for* the ID desiredKeyID.
			// First, see if this ContentObject matches this locator.
			// It presumably matches the name, or the get would not have returned
			// it. It also presumably matches the publisher,
			// given the query. But that part isn't really implemented,
			// so check.
			try {
				if (potentialKey.authenticator().publisherID().equals(locator.name().publisher())) {
					// DKS TODO remove the above check

					// First pull the key data. 
					if ((null == potentialKey.content()) || (0 == potentialKey.content().length)) {
						continue;
					}
					
					PublicKey key = CryptoUtil.getPublicKey(potentialKey.content());

					// Tricky -- can't necessarily just call verify,
					// or we could end up with a circular dependency.
					// This will (eventually) check that the publisherID
					// matches the key as well.
					if (potentialKey.verify(key)) {
						if (null == keyObject) {
							keyObject = potentialKey;
							theKey = key;
						} else {
							if (potentialKey.authenticator().timestamp().after(keyObject.authenticator().timestamp())) {
								keyObject = potentialKey;
								theKey = key;
							}
						}
					} else {
						Library.logger().info("Potential key object failed to verify: " + potentialKey.name());
						// TODO DKS fix
						Library.logger().info("Taking key anyway for the moment...");
						if (null == keyObject) {
							keyObject = potentialKey;
							theKey = key;
						} else {
							if (potentialKey.authenticator().timestamp().after(keyObject.authenticator().timestamp())) {
								keyObject = potentialKey;
								theKey = key;
							}
						}
					}
				} else {
					Library.logger().info("Get retrieved name that doesn't match desired publisher: " + potentialKey.name());
				}
			} catch (InvalidKeySpecException ikse) {
				Library.logger().info("Name: " + potentialKey.name() + " is not a key: " + ikse.getMessage());
				// go around again
			} catch (CertificateEncodingException e) {
				Library.logger().info("Name: " + potentialKey.name() + " cannot be decoded: " + e.getMessage());
				// go around again
			} catch (NoSuchAlgorithmException e) {
				Library.logger().info("Name: " + potentialKey.name() + " uses unknown algorithm: " + e.getMessage());
			} catch (InvalidKeyException ikse) {
				Library.logger().info("Name: " + potentialKey.name() + " is not a valid key: " + ikse.getMessage());
				// go around again
			} catch (SignatureException e) {
				Library.logger().warning("Name: " + potentialKey.name() + " exception verifying signature: " + e.getMessage());
				Library.warningStackTrace(e);
				// go around again
			} catch (XMLStreamException e) {
				Library.logger().warning("Name: " + potentialKey.name() + " cannot format data for signature verification: " + e.getMessage());
				Library.warningStackTrace(e);
				// go around again
			}
		}
		if (null != keyObject) {
			Library.logger().info("Retrieved key: " + keyObject.name());
			remember(locator.name().publisher(), theKey, keyObject);
			return theKey;
		} 
		return null;
	}

	protected void remember(PublisherID publisher, PublicKey theKey,
			ContentObject keyObject) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public PublicKey getPublicKey(PublisherID publisher) {
		// TODO Auto-generated method stub
		Library.logger().info("getPublicKey: retrieving key: " + publisher);
		if (_defaultKeyID.equals(publisher))
			return _certificate.getPublicKey();
		return null;
	}

	@Override
	public PrivateKey getSigningKey(PublisherID publisher) {
		// TODO Auto-generated method stub
		Library.logger().info("getSigningKey: retrieving key: " + publisher);
		if (_defaultKeyID.equals(publisher))
			return _privateKey;
		return null;
	}

	@Override
	public PublicKey getPublicKey(PublisherID publisherID, KeyLocator keyLocator) throws IOException {
		// TODO Auto-generated method stub
		Library.logger().info("getPublicKey: retrieving key: " + publisherID + " located at: " + keyLocator);
		// Do we have it locally.
		PublicKey key = getPublicKey(publisherID);
		if (null != key)
			return key;
		return getKey(publisherID, keyLocator);
	}

	@Override
	public PublisherID getPublisherID(PrivateKey signingKey) {
		if (_privateKey.equals(signingKey))
			return _defaultKeyID;
		return null;
	}
	
	@Override
	public KeyLocator getKeyLocator(PrivateKey signingKey) {
		if (signingKey.equals(_privateKey))
			return getDefaultKeyLocator();
		
		// DKS TODO
		return null;
	}

}
