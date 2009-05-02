package com.parc.ccn.library.io.repo;

import com.parc.ccn.library.io.CCNFileOutputStream;
import java.io.IOException;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.security.KeyLocator;
import com.parc.ccn.data.security.PublisherPublicKeyDigest;
import com.parc.ccn.library.CCNLibrary;


public class RepositoryFileOutputStream extends CCNFileOutputStream {

	protected RepositorySegmenter _repoFlowControl;

	public RepositoryFileOutputStream(ContentName name, CCNLibrary library) throws XMLStreamException, IOException {
		this(name, null, null , library);
	}
	
	public RepositoryFileOutputStream(ContentName name, 
			KeyLocator locator, PublisherPublicKeyDigest publisher, CCNLibrary library)
			throws XMLStreamException, IOException {
		super(name, locator, publisher, new RepositorySegmenter(name, library));
		_repoFlowControl = (RepositorySegmenter)_segmenter.getFlowControl();
		_repoFlowControl.init(getBaseName());
	}
	
	public void close() throws IOException {
		super.close();
		_repoFlowControl.close();
	}
	
}
