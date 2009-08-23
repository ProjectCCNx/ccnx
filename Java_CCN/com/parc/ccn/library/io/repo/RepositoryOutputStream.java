package com.parc.ccn.library.io.repo;

import java.io.IOException;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.KeyLocator;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;

import com.parc.ccn.library.io.CCNOutputStream;

/**
 * Set up a CCNOutputStream that can talk to a repository
 * @author rasmusse
 *
 */

public class RepositoryOutputStream extends CCNOutputStream {
	
	public RepositoryOutputStream(ContentName name, CCNHandle library) throws IOException {
		this(name, null, null, library);
	}
	
	public RepositoryOutputStream(ContentName name, 
			KeyLocator locator, PublisherPublicKeyDigest publisher, CCNHandle library)
			throws IOException {
		super(name, locator, publisher, new RepositoryFlowControl(name, library));
	}
}

