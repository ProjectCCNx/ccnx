package com.parc.ccn.library.io.repo;

import com.parc.ccn.library.io.CCNFileOutputStream;
import java.io.IOException;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.security.PublisherPublicKeyDigest;
import com.parc.ccn.library.CCNLibrary;


public class RepositoryFileOutputStream extends CCNFileOutputStream {

	public RepositoryFileOutputStream(ContentName name, CCNLibrary library) throws XMLStreamException, IOException {
		this(name, null, library);
	}
	
	public RepositoryFileOutputStream(ContentName name, 
			PublisherPublicKeyDigest publisher, CCNLibrary library)
			throws XMLStreamException, IOException {
		super(name, publisher, new RepositoryFlowControl(name, library));
	}
}
