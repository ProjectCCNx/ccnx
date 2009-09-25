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
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.InvalidKeyException;
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
import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.KeyManager;
import org.ccnx.ccn.TrustManager;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.config.UserConfiguration;
import org.ccnx.ccn.impl.CCNFlowControl.Shape;
import org.ccnx.ccn.impl.repo.RepositoryFlowControl;
import org.ccnx.ccn.impl.security.crypto.util.CryptoUtil;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.profiles.nameenum.EnumeratedNameList;
import org.ccnx.ccn.protocol.CCNTime;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Interest;
import org.ccnx.ccn.protocol.KeyLocator;
import org.ccnx.ccn.protocol.PublisherID;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;
import org.ccnx.ccn.protocol.SignedInfo;
import org.ccnx.ccn.protocol.SignedInfo.ContentType;


/**
 * This class performs the following:
 * - manages published public keys.
 * - answers CCN interestes for these public keys.
 * - allow the caller to look for public keys: retrieve the keys from cache or CCN 
 * 
 * This class is used by the initial KeyManager bootstrapping code. As such,
 * it has to be very careful not to introduce a circular dependency -- to rely
 * on parts of the network stack that need that boostrapping to be complete in
 * order to work. At the same time, we'd like to not have to reimplement segmentation,
 * etc, in order to cache keys; we'd like to be able to use those parts of
 * the library. So we allow the KeyRepository to have a CCNHandle, 
 */

public class KeyRepository implements CCNFilterListener {
	
	protected static final boolean _DEBUG = true;
	
	protected CCNHandle _handle = null;
	protected HashMap<ContentName,ContentObject> _keyMap = new HashMap<ContentName,ContentObject>();
	protected HashMap<PublisherPublicKeyDigest, ContentName> _idMap = new HashMap<PublisherPublicKeyDigest,ContentName>();
	protected HashMap<PublisherPublicKeyDigest, PublicKey> _rawKeyMap = new HashMap<PublisherPublicKeyDigest,PublicKey>();
	protected HashMap<PublisherPublicKeyDigest, Certificate> _rawCertificateMap = new HashMap<PublisherPublicKeyDigest,Certificate>();
	
	/** 
	 * Constructor. Must be called carefully; either with a fully constructed and
	 * initialized KeyManager, or as is used by the implementation, a sufficiently
	 * initialized KeyManager to allow us to make a CCNHandle that we can use to
	 * publish keys.
	 * 
	 * @throws IOException
	 */
	public KeyRepository(KeyManager keyManager) {
		_handle = CCNHandle.open(keyManager); // maintain our own connection to the agent, so
			// everyone can ask us for keys even if we have no repository
	}
	
	public CCNHandle handle() { return _handle; }
	
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
	 * @param keyName the key's content name
	 * @param key the public key
	 * @param keyID the publisher id
	 * @param signingKey the private signing key
	 * @return void
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
	
	/**
	 * Publish my public key to repository
	 * @param keyName content name of the public key
	 * @param keyToPublish public key digest
	 * @param handle handle for ccn
	 * @throws IOException
	 * @throws InvalidKeyException
	 * @throws ConfigurationException
	 */
	public void publishKeyToRepository(ContentName keyName, 
									   PublisherPublicKeyDigest keyToPublish) throws InvalidKeyException, IOException, ConfigurationException {

		
		PublicKey key = getPublicKey(keyToPublish);
		if (null == key) {
			throw new InvalidKeyException("Cannot retrieive key " + keyToPublish);
		}

		// HACK - want to use repo confirmation protocol to make sure data makes it to a repo
		// even if it doesn't come from us. Problem is, we may have already written it, and don't
		// want to write a brand new version of identical data. If we try to publish it under
		// the same (unversioned) name, the repository may get some of the data from the ccnd
		// cache, which will cause us to think it hasn't been written. So for the moment, we use the
		// name enumeration protocol to determine whether this key has been written to a repository
		// already.
		// This works because the last explicit name component of the key is its publisherID. 
		// We then use a further trick, just calling startWrite on the key, to get the repo
		// to read it -- not from here, but from the key server embedded in the KeyManager.
		EnumeratedNameList enl = new EnumeratedNameList(keyName.parent(), handle());
		enl.waitForData(500); // have to time out, may be nothing there.
		enl.stopEnumerating();
		if (enl.hasChildren()) {
			Log.info("Looking for children of {0} matching {1}.", keyName.parent(), keyName);
			for (ContentName name: enl.getChildren()) {
				Log.info("Child: {0}", name);
			}
		}
		if (!enl.hasChildren() || !enl.hasChild(keyName.lastComponent())) {
			RepositoryFlowControl rfc = new RepositoryFlowControl(keyName, handle());
			rfc.startWrite(keyName, Shape.STREAM);
			Log.info("Key {0} published to repository.", keyName);
		} else {
			Log.info("Key {0} already published to repository, not re-publishing.", keyName);
		}
	}

	/**
	 * Remember a public key and the corresponding key object.
	 * @param theKey public key to remember
	 * @param keyObject key Object to remember
	 */
	public void remember(PublicKey theKey, ContentObject keyObject) {
		PublisherPublicKeyDigest id = new PublisherPublicKeyDigest(theKey);
		_idMap.put(id, keyObject.name());
		_keyMap.put(keyObject.name(), keyObject);
		_rawKeyMap.put(id, theKey);
		if (_DEBUG) {
			recordKeyToFile(id, keyObject);
		}
		
		// DKS TODO -- do we want to put in a prefix filter...?
		_handle.registerFilter(keyObject.name(), this);
	}
	
	/**
	 * Remember a public key 
	 * @param theKey public key to remember
	 */
	public void remember(PublicKey theKey) {
		_rawKeyMap.put(new PublisherPublicKeyDigest(theKey), theKey);
	}
	
	/**
	 * Remember a certificate.
	 * @param theCertificate the certificate to remember
	 */
	public void remember(Certificate theCertificate) {
		_rawCertificateMap.put(new PublisherPublicKeyDigest(theCertificate.getPublicKey()), theCertificate);
	}

	
	/**
	 * Write key to file for debugging purposes.
	 */
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
	
	/**
	 * Retrieve the public key from cache given a key digest 
	 * @param desiredKeyID the digest of the desired public key.
	 */
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
				}
			}
		}	
		return theKey;
	}

	/**
	 * Retrieve the public key from CCN given a key digest and a key locator
	 * the function blocks and waits for the public key until a certain timeout
	 * @param desiredKeyID the digest of the desired public key.
	 * @param locator locator for the key
	 * @param timeout timeout value
	 * @throws IOException 
	 */
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
				keyObject = _handle.get(keyInterest, timeout);
			} catch (IOException e) {
				Log.warning("IOException attempting to retrieve key: " + keyInterest.name() + ": " + e.getMessage());
				Log.warningStackTrace(e);
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
					}
				} else {
					Log.warning("Retrieved an object when looking for key " + locator.name().name() + " at " + keyObject.name() + ", but type is " + keyObject.signedInfo().getTypeName());
				}
			}
		}
		return null;
	}
	
	/**
	 * retrieve key object from cache given key name 
	 * @param keyName key digest
	 */
	public ContentObject retrieve(PublisherPublicKeyDigest keyName) {
		ContentName name = _idMap.get(keyName);
		if (null != name) {
			return _keyMap.get(name);
		}		
		return null;
	}
	
	/**
	 * Retrieve key object from cache given content name and publisher id
	 * check if the retrieved content has the expected publisher id 
	 * @param name contentname of the key
	 * @param publisherID publisher id
	 */
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
	
	/**
	 * Retrieve content object given an interest 
	 * @param interest interest
	 */
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

	/**
	 * Answers interests for published public keys.
	 * @param interests interests expressed by other parties.
	 */
	
	public int handleInterests(ArrayList<Interest> interests) {
		Iterator<Interest> it = interests.iterator();
		
		while (it.hasNext()) {
			ContentObject keyObject = retrieve(it.next());
			if (null != keyObject) {
				try {
					ContentObject co = new ContentObject(keyObject.name(), keyObject.signedInfo(), keyObject.content(), keyObject.signature()); 
					_handle.put(co);
				} catch (Exception e) {
					Log.info("KeyRepository::handleInterests, exception in put: " + e.getClass().getName() + " message: " + e.getMessage());
					Log.infoStackTrace(e);
				}
			}
		}
		return 0;
	}
}
