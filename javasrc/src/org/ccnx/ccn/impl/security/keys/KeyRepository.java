package org.ccnx.ccn.impl.security.keys;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.spec.InvalidKeySpecException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;

import org.ccnx.ccn.CCNFilterListener;
import org.ccnx.ccn.CCNInterestListener;
import org.ccnx.ccn.TrustManager;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.config.UserConfiguration;
import org.ccnx.ccn.impl.CCNNetworkManager;
import org.ccnx.ccn.impl.security.crypto.util.CryptoUtil;
import org.ccnx.ccn.impl.support.CCNTime;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Interest;
import org.ccnx.ccn.protocol.KeyLocator;
import org.ccnx.ccn.protocol.PublisherID;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;
import org.ccnx.ccn.protocol.SignedInfo;
import org.ccnx.ccn.protocol.SignedInfo.ContentType;


public class KeyRepository implements CCNFilterListener, CCNInterestListener {
	
	protected static final boolean _DEBUG = true;
	public static final long SHORT_KEY_TIMEOUT = 300;
	public static final long DEFAULT_KEY_TIMEOUT = 2000;
	
	protected  CCNNetworkManager _networkManager = null;
	protected HashMap<ContentName,ContentObject> _keyMap = new HashMap<ContentName,ContentObject>();
	protected HashMap<PublisherPublicKeyDigest, ContentName> _idMap = new HashMap<PublisherPublicKeyDigest,ContentName>();
	protected HashMap<PublisherPublicKeyDigest, PublicKey> _rawKeyMap = new HashMap<PublisherPublicKeyDigest,PublicKey>();
	protected HashMap<PublisherPublicKeyDigest, Certificate> _rawCertificateMap = new HashMap<PublisherPublicKeyDigest,Certificate>();
	
	public KeyRepository() throws IOException {
		_networkManager = new CCNNetworkManager(); // maintain our own connection to the agent, so
			// everyone can ask us for keys
	}
	
	/**
	 * Track an existing key object and make it available.
	 * @param keyObject
	 * @throws NoSuchAlgorithmException 
	 * @throws InvalidKeySpecException 
	 * @throws CertificateEncodingException 
	 */
	public void publishKey(ContentObject keyObject) throws CertificateEncodingException, InvalidKeySpecException, NoSuchAlgorithmException {
		PublicKey key = CryptoUtil.getPublicKey(keyObject.content());
		remember(key, keyObject);
	}
	
	/**
	 * Published a signed record for this key.
	 * @param name
	 * @param key
	 * @param signingKey
	 * @return
	 * @throws ConfigurationException
	 */
	public void publishKey(ContentName keyName, PublicKey key, PublisherPublicKeyDigest keyID, PrivateKey signingKey) throws ConfigurationException {
		byte [] encodedKey = key.getEncoded();
		// Need a key locator to stick in data entry for
		// locator. Could use key itself, but then would have
		// key both in the content for this item and in the
		// key locator, which is redundant. Use naming form
		// that allows for self-referential key names -- the
		// CCN equivalent of a "self-signed cert". Means that
		// we will refer to only the base key name and the publisher ID,
		// not the uniqueified key name...

		KeyLocator locatorLocator = 
			new KeyLocator(keyName, new PublisherID(keyID));

		ContentObject keyObject = null;
		try {
			keyObject = new ContentObject(
					keyName,
					new SignedInfo(keyID,
							CCNTime.now(),
							SignedInfo.ContentType.KEY,
							locatorLocator),
							encodedKey,
							signingKey);
		} catch (Exception e) {
			BasicKeyManager.generateConfigurationException("Exception generating key locator and publishing key.", e);
		}
		remember(key, keyObject);
	}
	
	public void remember(PublicKey theKey, ContentObject keyObject) {
		PublisherPublicKeyDigest id = new PublisherPublicKeyDigest(theKey);
		_idMap.put(id, keyObject.name());
		_keyMap.put(keyObject.name(), keyObject);
		_rawKeyMap.put(id, theKey);
		if (_DEBUG) {
			recordKeyToFile(id, keyObject);
		}
		
		// DKS TODO -- do we want to put in a prefix filter...?
		_networkManager.setInterestFilter(this, keyObject.name(), this);
	}
	
	public void remember(PublicKey theKey) {
		_rawKeyMap.put(new PublisherPublicKeyDigest(theKey), theKey);
	}
	
	public void remember(Certificate theCertificate) {
		_rawCertificateMap.put(new PublisherPublicKeyDigest(theCertificate.getPublicKey()), theCertificate);
	}

	protected void recordKeyToFile(PublisherPublicKeyDigest id, ContentObject keyObject) {
		File keyDir = new File(UserConfiguration.keyRepositoryDirectory());
		if (!keyDir.exists()) {
			if (!keyDir.mkdirs()) {
				Log.warning("recordKeyToFile: Cannot create user CCN key repository directory: " + keyDir.getAbsolutePath());
				return;
			}
		}
		
		// Alas, until 1.6, we can't set permissions on the file or directory...
		// TODO DKS when switch to 1.6, add permission settings.
		File keyFile  = new File(keyDir, id.toString() + ".ccnb");
		if (keyFile.exists()) {
			Log.info("Already stored key " + id.toString() + " to file.");
			// return; // temporarily store it anyway, to overwrite old-format data.
		}
		
		try {
			FileOutputStream fos = new FileOutputStream(keyFile);
			keyObject.encode(fos);
			fos.close();
		} catch (Exception e) {
			Log.info("recordKeyToFile: cannot record key: " + id.toString() + " to file " + keyFile.getAbsolutePath() + " error: " + e.getClass().getName() + ": " + e.getMessage());
			return;
		}
		Log.info("Logged key " + id.toString() + " to file: " + keyFile.getAbsolutePath());
	}
	
	public PublicKey getPublicKey(PublisherPublicKeyDigest desiredKeyID) throws IOException {
		ContentObject keyObject = null;
		PublicKey theKey = _rawKeyMap.get(desiredKeyID);
		if (null == theKey) {
			Certificate theCertificate = _rawCertificateMap.get(desiredKeyID);
			if (null != theCertificate) {
				theKey = theCertificate.getPublicKey();
			}
		}
		ContentName name = _idMap.get(desiredKeyID);
		if (null != name) {
			keyObject = _keyMap.get(name);
			if (null != keyObject) {
				try {
					theKey = CryptoUtil.getPublicKey(keyObject.content());
				} catch (CertificateEncodingException e) {
					Log.warning("Unexpected exception " + e.getClass().getName() + ": " + e.getMessage() + ", should not have to decode public key, should have it in cache.");
					throw new IOException("Unexpected exception " + e.getClass().getName() + ": " + e.getMessage() + ", should not have to decode public key, should have it in cache.");
				} catch (InvalidKeySpecException e) {
					Log.warning("Unexpected exception " + e.getClass().getName() + ": " + e.getMessage() + ", should not have to decode public key, should have it in cache.");
					throw new IOException("Unexpected exception " + e.getClass().getName() + ": " + e.getMessage() + ", should not have to decode public key, should have it in cache.");
				} catch (NoSuchAlgorithmException e) {
					Log.warning("Unexpected exception " + e.getClass().getName() + ": " + e.getMessage() + ", should not have to decode public key, should have it in cache.");
					throw new IOException("Unexpected exception " + e.getClass().getName() + ": " + e.getMessage() + ", should not have to decode public key, should have it in cache.");
				}
			}
		}	
		return theKey;
	}

	public PublicKey getPublicKey(PublisherPublicKeyDigest desiredKeyID, KeyLocator locator, long timeout) throws IOException {
	
		// Look for it in our cache first.
		PublicKey publicKey = getPublicKey(desiredKeyID);
		if (null != publicKey) {
			return publicKey;
		}
		
		ContentObject keyObject = null;
		if (locator.type() != KeyLocator.KeyLocatorType.NAME) {
			Log.info("This is silly: asking the repository to retrieve for me a key I already have...");
			if (locator.type() == KeyLocator.KeyLocatorType.KEY) {
				PublicKey key = locator.key();
				remember(key);
				return key;
			} else if (locator.type() == KeyLocator.KeyLocatorType.CERTIFICATE) {
				Certificate certificate = locator.certificate();
				PublicKey key = certificate.getPublicKey();
				remember(certificate);
				return key;
			}
		} else {
			// DKS TODO -- better key retrieval
			// take code from #BasicKeyManager.getKey, to validate more complex publisher constraints
			Interest keyInterest = new Interest(locator.name().name());
			if (null != locator.name().publisher()) {
				keyInterest.publisherID(locator.name().publisher());
			}			
			//  it would be really good to know how many additional name components to expect...
			try {
				Log.info("Trying network retrieval of key: " + keyInterest.name());
				keyObject = _networkManager.get(keyInterest, timeout);
			} catch (IOException e) {
				Log.warning("IOException attempting to retrieve key: " + keyInterest.name() + ": " + e.getMessage());
				Log.warningStackTrace(e);
			} catch (InterruptedException e) {
				Log.warning("Interrupted attempting to retrieve key: " + keyInterest.name() + ": " + e.getMessage());
			}
			if (null != keyObject) {
				if (keyObject.signedInfo().getType().equals(ContentType.KEY)) {
					try {
						Log.info("Retrieved public key using name: " + locator.name().name());
						PublicKey theKey = CryptoUtil.getPublicKey(keyObject.content());
						remember(theKey, keyObject);
						return theKey;
					} catch (CertificateEncodingException e) {
						Log.warning("Unexpected exception " + e.getClass().getName() + ": " + e.getMessage() + ", should not have to decode public key, should have it in cache.");
						throw new IOException("Unexpected exception " + e.getClass().getName() + ": " + e.getMessage() + ", should not have to decode public key, should have it in cache.");
					} catch (InvalidKeySpecException e) {
						Log.warning("Unexpected exception " + e.getClass().getName() + ": " + e.getMessage() + ", should not have to decode public key, should have it in cache.");
						throw new IOException("Unexpected exception " + e.getClass().getName() + ": " + e.getMessage() + ", should not have to decode public key, should have it in cache.");
					} catch (NoSuchAlgorithmException e) {
						Log.warning("Unexpected exception " + e.getClass().getName() + ": " + e.getMessage() + ", should not have to decode public key, should have it in cache.");
						throw new IOException("Unexpected exception " + e.getClass().getName() + ": " + e.getMessage() + ", should not have to decode public key, should have it in cache.");
					}
				} else {
					Log.warning("Retrieved an object when looking for key " + locator.name().name() + " at " + keyObject.name() + ", but type is " + keyObject.signedInfo().getTypeName());
				}
			}
		}
		return null;
	}
	
	public ContentObject retrieve(PublisherPublicKeyDigest keyName) {
		ContentName name = _idMap.get(keyName);
		if (null != name) {
			return _keyMap.get(name);
		}		
		return null;
	}
	
	public ContentObject retrieve(ContentName name, PublisherID publisherID) {
		ContentObject result = _keyMap.get(name);
		if (null != result) {
			if (null != publisherID) {
				if (TrustManager.getTrustManager().matchesRole(
						publisherID,
						result.signedInfo().getPublisherKeyID())) {
					return result;
				}
			}
		}
		return null;
	}
	
	public ContentObject retrieve(Interest interest) {
		ContentObject result = retrieve(interest.name(), interest.publisherID());
		if (null != result)
			return result;
		
		// OK, the straight match didn't cut it; maybe we're just close.
		Iterator<ContentObject> it = _keyMap.values().iterator();
		while (it.hasNext()) {
			ContentObject co = it.next();
			if (interest.matches(co)) {
				// doesn't handle preventing returning same thing over and over
				return co;	
			}
		}
		return null;
	}

	public int handleInterests(ArrayList<Interest> interests) {
		Iterator<Interest> it = interests.iterator();
		
		while (it.hasNext()) {
			ContentObject keyObject = retrieve(it.next());
			if (null != keyObject) {
				try {
					ContentObject co = new ContentObject(keyObject.name(), keyObject.signedInfo(), keyObject.content(), keyObject.signature()); 
					_networkManager.put(co);
				} catch (Exception e) {
					Log.info("KeyRepository::handleInterests, exception in put: " + e.getClass().getName() + " message: " + e.getMessage());
					Log.infoStackTrace(e);
				}
			}
		}
		return 0;
	}

	public Interest handleContent(ArrayList<ContentObject> results, Interest interest) {
		// TODO Auto-generated method stub
		return null;
	}
}
