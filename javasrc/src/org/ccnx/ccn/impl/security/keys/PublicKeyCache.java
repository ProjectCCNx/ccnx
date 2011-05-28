/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008, 2009, 2010, 2011 Palo Alto Research Center, Inc.
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
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.logging.Level;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.TrustManager;
import org.ccnx.ccn.config.UserConfiguration;
import org.ccnx.ccn.impl.support.Log;
import static org.ccnx.ccn.impl.support.Log.FAC_KEYS;
import org.ccnx.ccn.io.ErrorStateException;
import org.ccnx.ccn.io.content.ContentGoneException;
import org.ccnx.ccn.io.content.ContentNotReadyException;
import org.ccnx.ccn.io.content.PublicKeyObject;
import org.ccnx.ccn.profiles.security.KeyProfile;
import org.ccnx.ccn.protocol.CCNTime;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Exclude;
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
public class PublicKeyCache {

	// Stop logging to key cache by default.
	protected static final boolean _DEBUG = false;

	// Reference count in case we are shared. 
	protected int _refCount = 0;

	protected HashMap<ContentName, PublicKeyObject> _keyMap = new HashMap<ContentName, PublicKeyObject>();
	protected HashMap<PublisherPublicKeyDigest, ArrayList<ContentName>> _idMap = new HashMap<PublisherPublicKeyDigest, ArrayList<ContentName>>();
	protected HashMap<PublisherPublicKeyDigest, PublicKey> _rawKeyMap = new HashMap<PublisherPublicKeyDigest, PublicKey>();
	protected HashMap<PublisherPublicKeyDigest, ArrayList<Certificate>> _rawCertificateMap = new HashMap<PublisherPublicKeyDigest, ArrayList<Certificate>>();
	protected HashMap<PublisherPublicKeyDigest, CCNTime> _rawVersionMap = new HashMap<PublisherPublicKeyDigest, CCNTime>();

	public PublicKeyCache() {
	}

	/**
	 * Remember a public key and the corresponding key object.
	 * @param theKey public key to remember
	 * @param keyObject key Object to remember
	 * @throws ContentGoneException 
	 * @throws ContentNotReadyException 
	 * @throws ErrorStateException 
	 */
	public void remember(PublicKeyObject theKey) throws ContentNotReadyException, ContentGoneException, ErrorStateException, IOException {

		_keyMap.put(theKey.getVersionedName(), theKey);
		PublisherPublicKeyDigest id = theKey.publicKeyDigest();
		rememberContentName(id, theKey.getVersionedName());
		_rawKeyMap.put(id, theKey.publicKey());
		_rawVersionMap.put(id, theKey.getVersion());
		if (_DEBUG) {
			recordKeyToFile(theKey);
		}
	}
	
	protected void rememberContentName(PublisherPublicKeyDigest id, ContentName name) {
		synchronized(_idMap) {

			ArrayList<ContentName> nameList = _idMap.get(id);
			if (null == nameList) {
				nameList = new ArrayList<ContentName>();
				_idMap.put(id, nameList);
			}
			nameList.add(name);
		}
	}

	/**
	 * Remember a public key 
	 * @param theKey public key to remember
	 */
	public void remember(PublicKey theKey, CCNTime version) {
		PublisherPublicKeyDigest keyDigest = new PublisherPublicKeyDigest(theKey);
		_rawKeyMap.put(keyDigest, theKey);
		if (null != version) {
			_rawVersionMap.put(keyDigest, version);
		}
	}

	/**
	 * Remember a certificate.
	 * @param theCertificate the certificate to remember
	 */
	public void remember(Certificate theCertificate, CCNTime version) {
		PublisherPublicKeyDigest keyDigest = new PublisherPublicKeyDigest(theCertificate.getPublicKey());
		rememberCertificate(keyDigest, theCertificate);
		_rawKeyMap.put(keyDigest, theCertificate.getPublicKey());
		if (null != version) {
			_rawVersionMap.put(keyDigest, version);
		}
	}

	protected void rememberCertificate(PublisherPublicKeyDigest id, Certificate certificate) {
		synchronized(_rawCertificateMap) {

			ArrayList<Certificate> certificateList = _rawCertificateMap.get(id);
			if (null == certificateList) {
				certificateList = new ArrayList<Certificate>();
				_rawCertificateMap.put(id, certificateList);
			}
			certificateList.add(certificate);
		}
	}

	/**
	 * Write encoded key to file for debugging purposes.
	 * @throws ContentGoneException 
	 * @throws ContentNotReadyException 
	 * @throws ErrorStateException 
	 */
	protected void recordKeyToFile(PublicKeyObject keyObject) throws ContentNotReadyException, ContentGoneException, ErrorStateException {
		File keyDir = new File(UserConfiguration.keyRepositoryDirectory());
		if (!keyDir.exists()) {
			if (!keyDir.mkdirs()) {
				Log.warning(FAC_KEYS, "recordKeyToFile: Cannot create user CCN key repository directory: {0}", keyDir.getAbsolutePath());
				return;
			}
		}
		PublisherPublicKeyDigest id = keyObject.publicKeyDigest();

		File keyFile  = new File(keyDir, KeyProfile.keyIDToNameComponentAsString(keyObject.publicKeyDigest()));

		if (keyFile.exists()) {
			Log.info(FAC_KEYS, "Already stored key {0} to file.", id);
			// return; // temporarily store it anyway, to overwrite old-format data.
		}

		try {
			FileOutputStream fos = new FileOutputStream(keyFile);
			try {
				fos.write(keyObject.publicKey().getEncoded());
			} finally {
				fos.close();
			}
		} catch (Exception e) {
			Log.info(FAC_KEYS, "recordKeyToFile: cannot record key: {0} to file {1} error: {2}: {3}", id, keyFile.getAbsolutePath(), e.getClass().getName(), e.getMessage());
			return;
		}
		Log.info(FAC_KEYS, "Logged key {0} to file: {1}", id, keyFile.getAbsolutePath());
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
	public PublicKey getPublicKey(
			PublisherPublicKeyDigest desiredKeyID, KeyLocator locator, 
			long timeout, CCNHandle handle) throws IOException {

		// Look for it in our cache first.
		PublicKey publicKey = getPublicKeyFromCache(desiredKeyID);
		if (null != publicKey) {
			return publicKey;
		}
		
		if (null == locator) {
			Log.warning(FAC_KEYS, "Cannot retrieve key -- no key locator for key {0}", desiredKeyID);
			throw new IOException("Cannot retrieve key -- no key locator for key " + desiredKeyID + ".");
		}

		if (locator.type() != KeyLocator.KeyLocatorType.NAME) {
			Log.info(FAC_KEYS, "Repository looking up a key that is contained in the locator...");
			if (locator.type() == KeyLocator.KeyLocatorType.KEY) {
				PublicKey key = locator.key();
				remember(key, null);
				return key;
			} else if (locator.type() == KeyLocator.KeyLocatorType.CERTIFICATE) {
				Certificate certificate = locator.certificate();
				PublicKey key = certificate.getPublicKey();
				remember(certificate, null);
				return key;
			}
		} else {
			PublicKeyObject publicKeyObject = getPublicKeyObject(desiredKeyID, locator, timeout, handle);
			if (null == publicKeyObject) {
				Log.info(FAC_KEYS, "Could not retrieve key {0} from network with locator {1}!", desiredKeyID, locator);
			} else {
				Log.info(FAC_KEYS, "Retrieved key {0} from network with locator {1}!", desiredKeyID, locator);
				return publicKeyObject.publicKey();
			}
		}
		return null;
	}
	
	public PublicKeyObject getPublicKeyObject(
			PublisherPublicKeyDigest desiredKeyID, KeyLocator locator, 
			long timeout, CCNHandle handle) throws IOException {

		// take code from #BasicKeyManager.getKey, to validate more complex publisher constraints
		PublicKeyObject theKey = retrieve(locator.name().name(), locator.name().publisher());
		if ((null != theKey) && (theKey.available())) {
			Log.info(FAC_KEYS, "retrieved key {0} from cache.", locator.name().name());
			return theKey;
		}

		// How many pieces of bad content do we wade through?
		final int ITERATION_LIMIT = 5;
		// how many times do we time out get? Try 2 just in case we drop one.
		final int TIMEOUT_ITERATION_LIMIT = 2;

		PublicKey publicKey = null;
		
		Interest keyInterest = new Interest(locator.name().name(), locator.name().publisher());
		// we could have from 1 (content digest only) to 3 (version, segment, content digest) 
		// additional name components.
		keyInterest.minSuffixComponents(1);
		keyInterest.maxSuffixComponents(3);

		ContentObject retrievedContent = null;
		int iterationCount = 0;
		int timeoutCount = 0; // be super-aggressive about pulling keys for now.
		IOException lastException = null;

		while ((null == publicKey) && (iterationCount < ITERATION_LIMIT)) {
			//  it would be really good to know how many additional name components to expect...
			while ((null == retrievedContent) && (timeoutCount < TIMEOUT_ITERATION_LIMIT)) {
				try {
					Log.fine(FAC_KEYS, "Trying network retrieval of key: {0} ", keyInterest.name());
					// use more aggressive high-level get
					retrievedContent = handle.get(keyInterest, timeout);
				} catch (IOException e) {
					Log.warning(FAC_KEYS, "IOException attempting to retrieve key: {0}: {1}", keyInterest.name(), e.getMessage());
					Log.warningStackTrace(e);
					lastException = e;
					// go around again
				}
				if (null != retrievedContent) {
					if( Log.isLoggable(FAC_KEYS, Level.INFO)) {
						Log.info(FAC_KEYS, "Retrieved key {0} using locator {1}.", desiredKeyID, locator);
						Log.info(FAC_KEYS, "Retrieved key {0} using locator {1} - got {2}", desiredKeyID, locator, retrievedContent.name());
					}
					break;
				}
				timeoutCount++;
			}
			if (null == retrievedContent) {
				Log.info(FAC_KEYS, "No data returned when we attempted to retrieve key using interest {0}, timeout {1} exception : {2}", keyInterest, timeout, ((null == lastException) ? "none" : lastException.getMessage()));
				if (null != lastException) {
					throw lastException;
				}
				break;
			}
			if ((retrievedContent.signedInfo().getType().equals(ContentType.KEY)) ||
				 (retrievedContent.signedInfo().getType().equals(ContentType.LINK))) {
				theKey = new PublicKeyObject(retrievedContent, handle);
				if ((null != theKey) && (theKey.available())) {
					if ((null != desiredKeyID) && (!theKey.publicKeyDigest().equals(desiredKeyID))) {
						Log.fine(FAC_KEYS, "Got key at expected name {0} from locator {1}, but it wasn't the right key, wanted {2}, got {3}", 
								retrievedContent.name(),
								locator,
								desiredKeyID, theKey.publicKeyDigest());
					} else {
						// either we don't have a preferred key ID, or we matched
						Log.info(FAC_KEYS, "Retrieved public key using name: {0}", locator.name().name());
						// TODO make a key object instead of just retrieving
						// content, use it to decode
						remember(theKey);
						return theKey;
					}
				} else {
					Log.severe(FAC_KEYS, "Decoded key at name {0} without error, but result was null!", retrievedContent.name());
					throw new IOException("Decoded key at name " + retrievedContent.name() + " without error, but result was null!");
				}
			} else {
				Log.info(FAC_KEYS, "Retrieved an object when looking for key {0} at {1}, but type is {2}", locator.name().name(), retrievedContent.name(), retrievedContent.signedInfo().getTypeName());
			}
			// TODO -- not sure this is exactly right, but a start...
			
			// It isn't right - this would only work for locators with no version or segment. And since I think most keys are
			// PublicKeyObjects which would have segments and probably versions it would almost always be wrong...
			// But is a little unclear what problem this code is concerned about anyway...
			Exclude currentExclude = keyInterest.exclude();
			if (null == currentExclude) {
				currentExclude = new Exclude();
			}
			currentExclude.add(new byte [][]{retrievedContent.digest()});
			keyInterest.exclude(currentExclude);
			iterationCount++;
		}
		return null;
	}
	
	/**
	 * Retrieve the public key from cache given a key digest 
	 * @param desiredKeyID the digest of the desired public key.
	 */
	public PublicKey getPublicKeyFromCache(PublisherPublicKeyDigest desiredKeyID) {
		PublicKey theKey = _rawKeyMap.get(desiredKeyID);
		if (null == theKey) {
			if (_rawCertificateMap.containsKey(desiredKeyID)) {
				Certificate theCertificate = _rawCertificateMap.get(desiredKeyID).get(0);
				if (null != theCertificate) {
					theKey = theCertificate.getPublicKey();
				}
			}
		}
		return theKey;
	}

	public CCNTime getPublicKeyVersionFromCache(PublisherPublicKeyDigest desiredKeyID) {
		return _rawVersionMap.get(desiredKeyID);
	}

	/**
	 * Retrieve key object from cache given key name 
	 * @param keyName key digest
	 */
	public PublicKeyObject retrieve(PublisherPublicKeyDigest keyID) {
		if (!_idMap.containsKey(keyID)) {
			return null;
		}
		ContentName name = _idMap.get(keyID).get(0);
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
	
	public ArrayList<Certificate> retrieveCertificates(PublisherPublicKeyDigest keyID) {
		return _rawCertificateMap.get(keyID);
	}
}
