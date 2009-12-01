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
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.util.HashMap;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.KeyManager;
import org.ccnx.ccn.TrustManager;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.config.UserConfiguration;
import org.ccnx.ccn.impl.CCNFlowServer;
import org.ccnx.ccn.impl.CCNFlowControl.Shape;
import org.ccnx.ccn.impl.repo.RepositoryFlowControl;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.ContentGoneException;
import org.ccnx.ccn.io.content.ContentNotReadyException;
import org.ccnx.ccn.io.content.PublicKeyObject;
import org.ccnx.ccn.profiles.nameenum.EnumeratedNameList;
import org.ccnx.ccn.profiles.security.KeyProfile;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Interest;
import org.ccnx.ccn.protocol.KeyLocator;
import org.ccnx.ccn.protocol.PublisherID;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;
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
 * the library. So we allow the KeyRepository to have a CCNHandle, we can use
 * all of the library functionality to write keys once that handle is sufficiently
 * initialized.
 */

public class KeyRepository {
	
	// Stop logging to key cache by default.
	protected static final boolean _DEBUG = false;
	
	protected CCNHandle _handle = null;
	protected CCNFlowServer _keyServer = null;
	
	protected HashMap<ContentName, PublicKeyObject> _keyMap = new HashMap<ContentName, PublicKeyObject>();
	protected HashMap<PublisherPublicKeyDigest, ContentName> _idMap = new HashMap<PublisherPublicKeyDigest, ContentName>();
	protected HashMap<PublisherPublicKeyDigest, PublicKey> _rawKeyMap = new HashMap<PublisherPublicKeyDigest, PublicKey>();
	protected HashMap<PublisherPublicKeyDigest, Certificate> _rawCertificateMap = new HashMap<PublisherPublicKeyDigest, Certificate>();
	
	/** 
	 * Constructor. Must be called carefully; either with a fully constructed and
	 * initialized KeyManager, or as is used by the implementation, a sufficiently
	 * initialized KeyManager to allow us to make a CCNHandle that we can use to
	 * publish keys.
	 * 
	 * @throws IOException
	 */
	public KeyRepository(KeyManager keyManager) throws IOException {
		_handle = CCNHandle.open(keyManager); // maintain our own connection to the agent, so
			// everyone can ask us for keys even if we have no repository
		// make a buffered server to return key data
		_keyServer = new CCNFlowServer(null, true, _handle);
	}
	
	public CCNHandle handle() { return _handle; }
		
	/**
	 * Published a signed record for this key if one doesn't exist.
	 * (if it does exist, pulls it at least to our ccnd, and optionally
	 * makes it available).
	 * @param keyName the key's content name
	 * @param key the public key
	 * @param keyID the publisher id
	 * @param signingKeyID the key id of the key pair to sign with; uses the default
	 * 	key locator
	 * @return void
	 * @throws IOException
	 */
	public void publishKey(ContentName keyName, PublicKey key, PublisherPublicKeyDigest signingKeyID) 
						throws IOException {
		publishKey(keyName, key, signingKeyID, null);
	}

	/**
	 * Publish a signed record for this key if one doesn't exist.
	 * (if it does exist, pulls it at least to our ccnd, and optionally
	 * makes it available). (TODO: decide what to do if it's published by someone
	 * else... need another option for that.)
	 * @param keyName the key's content name. Will add a version when saving if it doesn't
	 * 	have one already. If it does have a version, will use that one (see below for effect
	 * 	of version on the key locator).
	 * @param key the public key
	 * @param keyID the publisher id
	 * @param signingKeyID the key id of the key pair to sign with
	 * @param keyLocator the key locator to use if we save this key (if it is not already published).
	 * 	If not specified, we look for the default locator for the signing key. If there is none,
	 * 	and we are signing with the same key we are publishing, we build a
	 * 	self-referential key locator, using the name passed in (version or not).
	 * @return void
	 * @throws IOException
	 */
	public void publishKey(ContentName keyName, PublicKey key, PublisherPublicKeyDigest signingKeyID, KeyLocator keyLocator) 
						throws IOException {

		PublisherPublicKeyDigest keyDigest = new PublisherPublicKeyDigest(key);
		
		// See if we can pull something acceptable for this key at this name.
		// Use same code path for default key retrieval as getPublicKey, so that we can manage
		// version handling in a single place.
		PublicKey theKey = getPublicKey(keyDigest, new KeyLocator(keyName), SystemConfiguration.SHORT_TIMEOUT);
		PublicKeyObject keyObject = null;
		if (null != theKey) {
			keyObject = retrieve(keyDigest);
		} 
		if (null == keyObject) {
			keyObject = new PublicKeyObject(keyName, null, signingKeyID, keyLocator, _keyServer);
		}
		
		if (!keyObject.available() || (!keyObject.equalsKey(key))) {
			// Eventually may want to find something already published and link to it, but be simple here.

			// Need a key locator to stick in data entry for
			// locator. Could use key itself, but then would have
			// key both in the content for this item and in the
			// key locator, which is redundant. Use naming form
			// that allows for self-referential key names -- the
			// CCN equivalent of a "self-signed cert". Means that
			// we will refer to only the base key name and the publisher ID.
			
			if (null == keyLocator) {
				keyLocator = _handle.keyManager().getKeyLocator(signingKeyID);
				if (null == keyLocator) {
					if (signingKeyID.equals(keyDigest)) {
						// Make a self-referential key locator. For now do not include the
						// version.
						keyLocator = buildKeyLocator(keyName, signingKeyID);
					} else {
						Log.info("Cannot find a key locator to use with key {0}, using key itself.", signingKeyID);
						keyLocator = new KeyLocator(_handle.keyManager().getPublicKey(signingKeyID));
					}
				}
			}
			
			keyObject.setOurPublisherInformation(signingKeyID, keyLocator);
			// nobody's written it where we can find it fast enough.
			keyObject.setData(key);

			if (!keyObject.save()) {
				Log.info("Not saving key when we thought we needed to: desired key value {0}, have key value {1}, " +
							keyDigest, new PublisherPublicKeyDigest(keyObject.publicKey()));
			} else {
				Log.info("Published key {0} to name {1}", keyDigest, keyObject.getVersionedName());
			}
		} else {
			Log.info("Retrieved existing key object {0}, whose key locator is {1}.", keyObject.getVersionedName(), keyObject.getPublisherKeyLocator());
		}
		remember(keyObject);
	}
	
	/**
	 * TODO DKS make sure this works if last component of key name is potentially
	 * a version (using objects to write public keys)
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

		
		PublicKey key = getPublicKeyFromCache(keyToPublish);
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
	 * @throws ContentGoneException 
	 * @throws ContentNotReadyException 
	 */
	public void remember(PublicKeyObject theKey) throws ContentNotReadyException, ContentGoneException {
		_keyMap.put(theKey.getVersionedName(), theKey);
		PublisherPublicKeyDigest id = theKey.publicKeyDigest();
		_idMap.put(id, theKey.getVersionedName());
		_rawKeyMap.put(id, theKey.publicKey());
		if (_DEBUG) {
			recordKeyToFile(theKey);
		}
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
		PublisherPublicKeyDigest keyDigest = new PublisherPublicKeyDigest(theCertificate.getPublicKey());
		_rawCertificateMap.put(keyDigest, theCertificate);
		_rawKeyMap.put(keyDigest, theCertificate.getPublicKey());
	}

	
	/**
	 * Write encoded key to file for debugging purposes.
	 * @throws ContentGoneException 
	 * @throws ContentNotReadyException 
	 */
	protected void recordKeyToFile(PublicKeyObject keyObject) throws ContentNotReadyException, ContentGoneException {
		File keyDir = new File(UserConfiguration.keyRepositoryDirectory());
		if (!keyDir.exists()) {
			if (!keyDir.mkdirs()) {
				Log.warning("recordKeyToFile: Cannot create user CCN key repository directory: " + keyDir.getAbsolutePath());
				return;
			}
		}
		
		PublisherPublicKeyDigest id = keyObject.publicKeyDigest();
		
		File keyFile  = new File(keyDir, KeyProfile.keyIDToNameComponentAsString(keyObject.publicKeyDigest()));
		if (keyFile.exists()) {
			Log.info("Already stored key " + id.toString() + " to file.");
			// return; // temporarily store it anyway, to overwrite old-format data.
		}
		
		try {
			FileOutputStream fos = new FileOutputStream(keyFile);
			fos.write(keyObject.publicKey().getEncoded());
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
	public PublicKey getPublicKeyFromCache(PublisherPublicKeyDigest desiredKeyID) throws IOException {
		PublicKey theKey = _rawKeyMap.get(desiredKeyID);
		if (null == theKey) {
			Certificate theCertificate = _rawCertificateMap.get(desiredKeyID);
			if (null != theCertificate) {
				theKey = theCertificate.getPublicKey();
			}
		}
		return theKey;
	}

	/**
	 * Retrieve the public key from CCN given a key digest and a key locator
	 * the function blocks and waits for the public key until a certain timeout.
	 * As a side effect, caches network storage information for this key, which can
	 * be obtained using retrieve();.
	 * @param desiredKeyID the digest of the desired public key.
	 * @param locator locator for the key
	 * @param timeout timeout value
	 * @throws IOException 
	 */
	public PublicKey getPublicKey(PublisherPublicKeyDigest desiredKeyID, KeyLocator locator, long timeout) throws IOException {
	
		// Look for it in our cache first.
		PublicKey publicKey = getPublicKeyFromCache(desiredKeyID);
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
			// take code from #BasicKeyManager.getKey, to validate more complex publisher constraints
			Interest keyInterest = new Interest(locator.name().name(), locator.name().publisher());

			// we could have from 1 (content digest only) to 3 (version, segment, content digest) 
			// additional name components.
			keyInterest.minSuffixComponents(1);
			keyInterest.maxSuffixComponents(3);
			
			// TODO if it is versioned, do we want to get the latest version?
			while ((null == keyObject) || (!keyObject.signedInfo().getType().equals(ContentType.KEY))) {
				// OK, we need to try again to get the object.
				if (null != keyObject) {
					Log.warning("Retrieved an object when looking for key " + locator.name().name() + " at " + keyObject.name() + ", but type is " + keyObject.signedInfo().getTypeName());
					// exclude whatever we got last time.
					Log.info("Have prefix {0}, want to exclude {1}", locator.name().name(), keyObject.name());
				}
				try {
					Log.info("Trying network retrieval of key: " + keyInterest.name());
					keyObject = _handle.get(keyInterest, timeout);
				} catch (IOException e) {
					Log.warning("IOException attempting to retrieve key: " + keyInterest.name() + ": " + e.getMessage());
					Log.warningStackTrace(e);
				}
				if (null == keyObject) {
					break;
				}
			}
			if (null != keyObject) {
				Log.info("Retrieved public key using name: {0}, resulting object name {1}.", locator.name().name(), keyObject.name());
				PublicKeyObject theKeyObject = new PublicKeyObject(keyObject, _keyServer);
				remember(theKeyObject);
				return theKeyObject.publicKey();
			}
		}
		return null;
	}
	
	/**
	 * Retrieve key object from cache given key name 
	 * @param keyName key digest
	 */
	public PublicKeyObject retrieve(PublisherPublicKeyDigest keyID) {
		ContentName name = _idMap.get(keyID);
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
	 * @throws IOException 
	 */
	public PublicKeyObject retrieve(ContentName name, PublisherID publisherID) throws IOException {
		PublicKeyObject result = _keyMap.get(name);
		if (null != result) {
			if (null != publisherID) {
				if (TrustManager.getTrustManager().matchesRole(
						publisherID,
						result.getContentPublisher())) {
					return result;
				}
			}
		}
		return null;
	}
	
	/**
	 * Build self-referential key locator.
	 * @param keyName
	 * @param publicKey
	 * @return
	 */
	public KeyLocator buildKeyLocator(ContentName keyName, PublisherPublicKeyDigest signingKeyID) {
		return new KeyLocator(keyName, new PublisherID(signingKeyID));
	}
}
