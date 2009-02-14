package com.parc.ccn.library.io.repo;

import java.io.IOException;
import java.security.PrivateKey;
import java.util.ArrayList;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.CCNBase;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.query.CCNInterestListener;
import com.parc.ccn.data.query.Interest;
import com.parc.ccn.data.security.KeyLocator;
import com.parc.ccn.data.security.PublisherKeyID;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.library.io.CCNDescriptor;

/**
 * Set up a CCNOutputStream that can talk to a repository
 * @author rasmusse
 *
 */

public class RepositoryOutputStream extends CCNDescriptor {
	
	CCNLibrary _library;
	
	private class RepoListener implements CCNInterestListener {

		public Interest handleContent(ArrayList<ContentObject> results,
				Interest interest) {
			return null;	// For now we ignore the return
		}
	}

	public RepositoryOutputStream(ContentName name, PublisherKeyID publisher,
			KeyLocator locator, PrivateKey signingKey, CCNLibrary library)
			throws XMLStreamException, IOException {
		super(name, publisher, locator, signingKey, library);
		this._library = library;
	}

	public void close() throws IOException {
		super.close();
		ContentName repoWriteName = new ContentName(_output.getBaseName(), CCNBase.REPO_START_WRITE);
		_library.expressInterest(new Interest(repoWriteName), new RepoListener());
	}
	
	public ContentName getBaseName() {
		return _output.getBaseName();
	}
}
