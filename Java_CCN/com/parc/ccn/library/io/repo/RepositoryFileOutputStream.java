package com.parc.ccn.library.io.repo;

import java.io.IOException;

import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.security.PublisherPublicKeyDigest;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.library.io.CCNFileOutputStream;

public class RepositoryFileOutputStream extends CCNFileOutputStream {

	public RepositoryFileOutputStream(ContentName name, CCNLibrary library) throws IOException {
		this(name, null, library);
	}
	
	public RepositoryFileOutputStream(ContentName name,
			PublisherPublicKeyDigest publisher, CCNLibrary library)
			throws IOException {

		super(name, null, publisher, null, new RepositoryFlowControl(name, library));
	}
}
