package com.parc.ccn.library.io.repo;

import java.io.IOException;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.security.KeyLocator;
import com.parc.ccn.data.security.PublisherPublicKeyDigest;
import com.parc.ccn.data.security.SignedInfo.ContentType;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.library.io.CCNVersionedOutputStream;

public class RepositoryVersionedOutputStream extends CCNVersionedOutputStream {

	public RepositoryVersionedOutputStream(ContentName name, CCNLibrary library) throws XMLStreamException, IOException {
		this(name, null, null, null, library);
	}
	
	public RepositoryVersionedOutputStream(ContentName name, 
			KeyLocator locator, PublisherPublicKeyDigest publisher, ContentType type, CCNLibrary library)
			throws XMLStreamException, IOException {

		super(name, locator, publisher, null, new RepositoryFlowControl(name, library));
	}
}
