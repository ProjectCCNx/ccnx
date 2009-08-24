package org.ccnx.ccn.io;

import java.io.IOException;

import javax.xml.stream.XMLStreamException;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.impl.repo.RepositoryFlowControl;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.KeyLocator;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;
import org.ccnx.ccn.protocol.SignedInfo.ContentType;



public class RepositoryVersionedOutputStream extends CCNVersionedOutputStream {

	public RepositoryVersionedOutputStream(ContentName name, CCNHandle library) throws XMLStreamException, IOException {
		this(name, null, null, null, library);
	}
	
	public RepositoryVersionedOutputStream(ContentName name, 
			KeyLocator locator, PublisherPublicKeyDigest publisher, ContentType type, CCNHandle library)
			throws XMLStreamException, IOException {

		super(name, locator, publisher, null, new RepositoryFlowControl(name, library));
	}
}
