package com.parc.ccn.library.io.repo;

import java.io.IOException;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.security.KeyLocator;
import com.parc.ccn.data.security.PublisherPublicKeyDigest;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.library.io.CCNOutputStream;

/**
 * Set up a CCNOutputStream that can talk to a repository
 * @author rasmusse
 *
 */

public class RepositoryOutputStream extends CCNOutputStream {
	
	protected RepositoryProtocol _repoFlowControl;

	public RepositoryOutputStream(ContentName name, CCNLibrary library) throws XMLStreamException, IOException {
		this(name, null, null, library);
	}
	
	public RepositoryOutputStream(ContentName name, 
			KeyLocator locator, PublisherPublicKeyDigest publisher, CCNLibrary library)
			throws XMLStreamException, IOException {
		super(name, locator, publisher, new RepositoryProtocol(name, library));
		_repoFlowControl = (RepositoryProtocol)_segmenter.getFlowControl();
		_repoFlowControl.init(getBaseName());
	}
	
	public void close() throws IOException {
		_repoFlowControl.beforeClose();
		super.close();
		_repoFlowControl.afterClose();
	}
}
