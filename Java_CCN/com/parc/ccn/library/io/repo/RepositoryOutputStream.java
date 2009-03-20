package com.parc.ccn.library.io.repo;

import java.io.IOException;
import java.security.PrivateKey;

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
	
	protected RepositorySegmenter _segmenter;

	public RepositoryOutputStream(ContentName name, PublisherKeyID publisher,
			KeyLocator locator, PrivateKey signingKey, CCNLibrary library)
			throws XMLStreamException, IOException {
		super(name, publisher, locator, signingKey, new RepositorySegmenter(name, library));
		_segmenter = (RepositorySegmenter)_writer;
		_segmenter.init(getBaseName());
	}
	
	public void close() throws IOException {
		super.close();
		_segmenter.close();
	}
}
