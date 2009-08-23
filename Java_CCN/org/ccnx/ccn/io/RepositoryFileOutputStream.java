package org.ccnx.ccn.io;

import java.io.IOException;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.KeyLocator;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;
import org.ccnx.ccn.protocol.SignedInfo.ContentType;

import com.parc.ccn.library.io.repo.RepositoryFlowControl;


public class RepositoryFileOutputStream extends CCNFileOutputStream {

	public RepositoryFileOutputStream(ContentName name, CCNHandle library) throws IOException {
		this(name, null, null, null, library);
	}
	
	public RepositoryFileOutputStream(ContentName name,
			PublisherPublicKeyDigest publisher, CCNHandle library)
			throws IOException {
		this(name, null, publisher, null, library);
	}

	public RepositoryFileOutputStream(ContentName name, KeyLocator locator,
			PublisherPublicKeyDigest publisher, ContentType type, CCNHandle library)
			throws IOException {
		super(name, locator, publisher, type, new RepositoryFlowControl(name, library));
	}
}
