package com.parc.ccn.library.io.repo;

import java.io.IOException;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.security.KeyLocator;
import com.parc.ccn.data.security.PublisherKeyID;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.library.io.CCNOutputStream;

/**
 * Set up a CCNOutputStream that can talk to a repository
 * @author rasmusse
 *
 */

public class RepositoryOutputStream extends CCNOutputStream {
	
	protected RepositorySegmenter _repoFlowControl;

	public RepositoryOutputStream(ContentName name, CCNLibrary library) throws XMLStreamException, IOException {
		this(name, null, null, library);
	}
	
	public RepositoryOutputStream(ContentName name, 
			KeyLocator locator, PublisherKeyID publisher, CCNLibrary library)
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
