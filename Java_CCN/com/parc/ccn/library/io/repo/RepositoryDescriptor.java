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
import com.parc.ccn.network.daemons.repo.RepositoryInfo;

/**
 * Set up a CCNOutputStream that can talk to a repository
 * @author rasmusse
 *
 */

public class RepositoryDescriptor extends CCNDescriptor {
	
	protected CCNLibrary _library;
	protected RepositoryBackEnd _backend;
	protected String _repoName;
	protected String _repoPrefix;
	
	private class RepoListener implements CCNInterestListener {

		public Interest handleContent(ArrayList<ContentObject> results,
				Interest interest) {
			for (ContentObject co : results) {
				RepositoryInfo repoInfo = new RepositoryInfo();
				try {
					repoInfo.decode(co.content());
					switch (repoInfo.getType()) {
					case INFO:
						_repoName = repoInfo.getLocalName();
						_repoPrefix = repoInfo.getGlobalPrefix();
						break;
					case DATA:
						if (!repoInfo.getLocalName().equals(_repoName))
							break;		// not our repository
						if (!repoInfo.getGlobalPrefix().equals(_repoPrefix))
							break;		// not our repository
						for (ContentName name : repoInfo.getNames())
							_backend.ack(name);
						if (! _backend.flushComplete())
							_backend.sendAckRequest();
						break;
					default:
						break;
					}
				} catch (XMLStreamException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
			return null;
		}
	}

	public RepositoryDescriptor(ContentName name, PublisherKeyID publisher,
			KeyLocator locator, PrivateKey signingKey, CCNLibrary library)
			throws XMLStreamException, IOException {
		super(name, publisher, locator, signingKey, library);
		this._library = library;
		_backend = new RepositoryBackEnd(_output.getBaseName(), _library, new RepoListener());
		_library.pushBackEnd(_backend);
		ContentName repoWriteName = new ContentName(_output.getBaseName(), CCNBase.REPO_START_WRITE);
		_library.expressInterest(new Interest(repoWriteName), new RepoListener());
	}
	
	public ContentName getBaseName() {
		return _output.getBaseName();
	}
	
	public void close() throws IOException {
		super.close();
		_backend.close();
		while (!_backend.flushComplete())
			Thread.yield();
		_library.popBackEnd();
	}
	
	public void setAck(boolean flag) {
		_backend.setAck(flag);
	}
}
