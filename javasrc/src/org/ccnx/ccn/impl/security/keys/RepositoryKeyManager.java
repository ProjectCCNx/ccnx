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

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.impl.support.Tuple;
import org.ccnx.ccn.io.RepositoryVersionedOutputStream;
import org.ccnx.ccn.io.content.ContentEncodingException;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;


/**
 * This is a repo-based implementation of key manager.
 * In comparison with BasicKeyManager, this class reads (or writes) the user's
 * private key from (or to) a CCN repository.   
 * You actually only need to use a repository key manager the first time
 * you create a keystore. After that, you can use a standard NetworkKeyManager,
 * as long as the data is still in the repo.
 */
public class RepositoryKeyManager extends NetworkKeyManager {

	/** Constructor
	 * 
	 * @param userName
	 * @param keystoreName
	 * @param publisher
	 * @param password
	 * @throws ConfigurationException
	 * @throws IOException
	 */
	public RepositoryKeyManager(String userName, ContentName keystoreName,
			PublisherPublicKeyDigest publisher, char[] password) throws ConfigurationException, IOException {
		super(userName, keystoreName, publisher, password);
	}

	/**
	 * Override to give different storage behavior.
	 * Output stream is repo
	 * @return
	 * @throws ContentEncodingException
	 * @throws IOException
	 */
	@Override
	protected Tuple<KeyStoreInfo,OutputStream> createKeyStoreWriteStream() throws IOException {
		// Have to get the version after we write, unless we force it.
		return new Tuple<KeyStoreInfo,OutputStream>(new KeyStoreInfo(_keystoreName.toURIString(), null, null), 
											  new RepositoryVersionedOutputStream(_keystoreName, CCNHandle.getHandle()));
	}
}
