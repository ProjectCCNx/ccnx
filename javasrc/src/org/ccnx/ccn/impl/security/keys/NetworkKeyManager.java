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

import java.io.IOException;
import java.io.OutputStream;
import java.security.KeyStore;

import javax.xml.stream.XMLStreamException;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.config.UserConfiguration;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.CCNVersionedInputStream;
import org.ccnx.ccn.io.CCNVersionedOutputStream;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;


/**
 * A Key Manager designed to make dynamic key stores and back them up to CCN.
 * Used for creating test users to start, might be generally useful.
 * @author smetters
 *
 */
public class NetworkKeyManager extends BasicKeyManager {
	
	static final long DEFAULT_TIMEOUT = 1000;
	
	ContentName _keystoreName;
	PublisherPublicKeyDigest _publisher;
	CCNHandle _handle;

	public NetworkKeyManager(String userName, ContentName keystoreName, PublisherPublicKeyDigest publisher,
							char [] password, CCNHandle handle) throws ConfigurationException, IOException {
		// key repository created by default superclass constructor
		if (null != userName)
			_userName = userName; // otherwise default for actual user
		_keystoreName = keystoreName;
		_publisher = publisher;
		_handle = handle;
		setPassword(password);
		// loading done by initialize()
	}

	/**
	 * The default key name is the publisher ID itself,
	 * under the keystore namespace.
	 * @param keyID
	 * @return
	 */
	@Override
	public ContentName getDefaultKeyName(byte [] keyID) {
		ContentName keyDir =
			ContentName.fromNative(_keystoreName, 
				   			UserConfiguration.defaultKeyName());
		return new ContentName(keyDir, keyID);
	}

	protected void loadKeyStore() throws ConfigurationException {
		// Is there an existing version of this key store? don't assume repo, so don't enumerate.
		// timeouts should be ok.
		// DKS TODO -- once streams pull first block on creation, don't need this much work.
		ContentObject keystoreObject = null;
		try {
			keystoreObject = 
				VersioningProfile.getFirstBlockOfLatestVersion(_keystoreName, null, _publisher, DEFAULT_TIMEOUT, new ContentObject.SimpleVerifier(_publisher),  _handle);
			if (null == keystoreObject) {
				Log.info("Creating new CCN key store..." + _keystoreName);
				_keystore = createKeyStore();	
			}
		} catch (IOException e) {
			Log.warning("Cannot get first block of existing key store: " + _keystoreName);
			throw new ConfigurationException("Cannot get first block of existing key store: " + _keystoreName + ": " + e.getMessage(), e);
		} 
		if ((null == _keystore) && (null != keystoreObject)){
			CCNVersionedInputStream in = null;
			Log.info("Loading CCN key store from " + _keystoreName + "...");
			try {
				in = new CCNVersionedInputStream(keystoreObject, _handle);
				readKeyStore(in);
			} catch (XMLStreamException e) {
				Log.warning("Cannot open existing key store: " + _keystoreName);
				throw new ConfigurationException("Cannot open existing key store: " + _keystoreName + ": " + e.getMessage(), e);
			} catch (IOException e) {
				Log.warning("Cannot open existing key store: " + _keystoreName);
				throw new ConfigurationException("Cannot open existing key store: " + _keystoreName + ": " + e.getMessage(), e);
			} 
		}
		
		if (!loadValuesFromKeystore(_keystore)) {
			Log.warning("Cannot process keystore!");
		}
	}

	synchronized protected KeyStore createKeyStore() throws ConfigurationException {
		
		OutputStream out = null;
		try {
			out = createKeyStoreWriteStream();
		} catch (XMLStreamException e) {
			Log.warning("Cannot create key store: " + _keystoreName);
			throw new ConfigurationException("Cannot create key store: " + _keystoreName + ": " + e.getMessage(), e);
		} catch (IOException e) {
			Log.warning("Cannot create key store: " + _keystoreName);
			throw new ConfigurationException("Cannot create key store: " + _keystoreName + ": " + e.getMessage(), e);
		} 
	    return createKeyStore(out);	    
	}
	
	/**
	 * Override to give different storage behavior.
	 * @return
	 * @throws XMLStreamException
	 * @throws IOException
	 */
	protected OutputStream createKeyStoreWriteStream() throws XMLStreamException, IOException {
		return new CCNVersionedOutputStream(_keystoreName, _handle);
	}
}
