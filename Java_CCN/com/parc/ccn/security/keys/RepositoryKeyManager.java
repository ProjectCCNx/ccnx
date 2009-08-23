package com.parc.ccn.security.keys;

import java.io.IOException;
import java.io.OutputStream;

import javax.xml.stream.XMLStreamException;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;

import com.parc.ccn.config.ConfigurationException;
import com.parc.ccn.library.io.repo.RepositoryVersionedOutputStream;

/**
 * You actually only need to use a repository key manager the first time
 * you create a keystore. After that, you can use a standard NetworkKeyManager,
 * as long as the data is still in the repo.
 * @author smetters
 *
 */
public class RepositoryKeyManager extends NetworkKeyManager {

	public RepositoryKeyManager(String userName, ContentName keystoreName,
			PublisherPublicKeyDigest publisher, char[] password,
			CCNHandle library) throws ConfigurationException, IOException {
		super(userName, keystoreName, publisher, password, library);
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
