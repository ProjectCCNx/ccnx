package org.ccnx.ccn.io;

import java.io.IOException;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.impl.repo.RepositoryFlowControl;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.KeyLocator;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;



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

