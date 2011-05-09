/*
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
import java.net.URI;
import java.net.URISyntaxException;
import java.security.KeyStore;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.impl.support.Tuple;
import org.ccnx.ccn.io.CCNVersionedInputStream;
import org.ccnx.ccn.io.CCNVersionedOutputStream;
import org.ccnx.ccn.io.content.ContentEncodingException;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.protocol.CCNTime;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;


/**
 * This is a network-based implementation of key manager.
 * In comparison with BasicKeyManager, this class reads (or writes) the user's
 * private key (as a java keystore) from (or to) CCN.   
 * @see BasicKeyManager, KeyManager
 */
public class NetworkKeyManager extends BasicKeyManager {
		
	ContentName _keystoreName;
	PublisherPublicKeyDigest _publisher;

	/** Constructor
	 * @param userName
	 * @param keystoreName
	 * @param publisher
	 * @param password
	 * @throws ConfigurationException
	 * @throws IOException
	 */
	public NetworkKeyManager(String userName, 
							ContentName keystoreName, 
							PublisherPublicKeyDigest publisher,
							char [] password) throws ConfigurationException, IOException {
		// key repository created by superclass constructor
		super(userName, null, null, password);
		_keystoreName = keystoreName;
		_publisher = publisher;
		// loading done by initialize()
	}
	
	/**
	 * Reads the user's keys from CCN
	 * @throws ConfigurationException
	 */
	@Override
	protected KeyStoreInfo loadKeyStore() throws ConfigurationException, IOException {
		// Is there an existing version of this key store? don't assume repo, so don't enumerate.
		// timeouts should be ok.
		// DKS TODO -- once streams pull first block on creation, don't need this much work.
		ContentObject keystoreObject = null;
		KeyStoreInfo keyStoreInfo = null;
		try {
			keystoreObject = 
				VersioningProfile.getFirstBlockOfLatestVersion(_keystoreName, null, _publisher, 
																SystemConfiguration.getDefaultTimeout(), 
																new ContentObject.SimpleVerifier(_publisher, this),  handle());
			if (null == keystoreObject) {
				Log.info("Creating new CCN key store..." + _keystoreName);
				keyStoreInfo = createKeyStore();	
			}
		} catch (IOException e) {
			Log.warning("Cannot get first block of existing key store: " + _keystoreName);
			throw e;
		} 
		if ((null == keyStoreInfo) && (null != keystoreObject)){
			CCNVersionedInputStream in = null;
			Log.info("Loading CCN key store from " + _keystoreName + "...");
			try {
				in = new CCNVersionedInputStream(keystoreObject, null, handle());
				KeyStore keyStore = readKeyStore(in);
				keyStoreInfo = new KeyStoreInfo(_keystoreName.toURIString(), keyStore, in.getVersion());
			} catch (IOException e) {
				Log.warning("Cannot open existing key store: " + _keystoreName);
				throw e;
			} 
		}
		return keyStoreInfo;
	}
	
	protected CCNTime getKeyStoreVersion(OutputStream out) throws IOException {
		// in our case, our output stream should be a file output stream...
		if (!(out instanceof CCNVersionedOutputStream)) {
			throw new IOException("Unexpected output stream type in getKeyStoreVersion: " + out.getClass().getName());
		}
		
		return ((CCNVersionedOutputStream)out).getVersion();
	}
	
	/**
	 * Override to give different storage behavior.
	 * Output stream is CCN
	 * @return
	 * @throws ContentEncodingException
	 * @throws IOException
	 */
	@Override
	protected Tuple<KeyStoreInfo, OutputStream> createKeyStoreWriteStream() throws IOException {
		// Pull the version after we write
		return new Tuple<KeyStoreInfo, OutputStream>(new KeyStoreInfo(_keystoreName.toURIString(), null, null),
											  new CCNVersionedOutputStream(_keystoreName, CCNHandle.getHandle()));
	}
	
	@Override
	public URI getConfigurationDataURI() {
		try {
			return new URI(_keystoreName.toURIString());
		} catch (URISyntaxException e) {
			Log.warning(Log.FAC_ENCODING, "Cannot parse CCN URI {0} as Java URI!", _keystoreName.toURIString());
			return null;
		}
	}
}
