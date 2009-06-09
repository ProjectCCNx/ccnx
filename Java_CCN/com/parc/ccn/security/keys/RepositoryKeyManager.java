package com.parc.ccn.security.keys;

import java.io.IOException;
import java.io.OutputStream;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.config.ConfigurationException;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.security.PublisherPublicKeyDigest;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.library.io.repo.RepositoryVersionedOutputStream;

/**
 * You actually only need to use a repository key manager the first time
 * you create a keystore. After that, you can use a standard NetworkKeyManager,
 * as long as the data is still in the repo.
 * @author smetters
 *
 */
public class RepositoryKeyManager extends NetworkKeyManager {

	public RepositoryKeyManager(ContentName keystoreName,
			PublisherPublicKeyDigest publisher, char[] password,
			CCNLibrary library) throws ConfigurationException, IOException {
		super(keystoreName, publisher, password, library);
	}

	/**
	 * Override to give different storage behavior.
	 * @return
	 * @throws XMLStreamException
	 * @throws IOException
	 */
	protected OutputStream createKeyStoreWriteStream() throws XMLStreamException, IOException {
		return new RepositoryVersionedOutputStream(_keystoreName, _library);
	}
}
