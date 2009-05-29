package com.parc.ccn.library.io.repo;

import java.io.IOException;
import java.util.ArrayList;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.CCNBase;
import com.parc.ccn.Library;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.query.BasicNameEnumeratorListener;
import com.parc.ccn.data.query.CCNInterestListener;
import com.parc.ccn.data.query.Interest;
import com.parc.ccn.data.security.SignedInfo.ContentType;
import com.parc.ccn.library.CCNFlowControl;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.library.CCNNameEnumerator;
import com.parc.ccn.network.daemons.repo.RepositoryInfo;

/**
 * Implements the client side of the repository protocol
 * Mostly this has to do with the "repository ack protocol"
 * which trys to verify that data has been written to the
 * repository.
 * 
 * Currently due to problems with the implementation this
 * has been turned off by default.
 * 
 * @author rasmusse
 *
 */

public class RepositoryProtocol extends CCNFlowControl {
	
	protected static final int ACK_BLOCK_SIZE = 20;
	protected static final int ACK_INTERVAL = 128;
	
	protected boolean _bestEffort = true;
	protected int _blocksSinceAck = 0;
	protected int _ackInterval = ACK_INTERVAL;
	protected String _repoName = null;
	protected ContentName _baseName; // the name prefix under which we are writing content
	protected RepoListener _listener = null;
	protected Interest _writeInterest = null;
	protected CCNNameEnumerator _ackne;
	protected RepoAckHandler _ackHandler;

	private class RepoListener implements CCNInterestListener {

		public Interest handleContent(ArrayList<ContentObject> results,
				Interest interest) {
			Interest interestToReturn = null;
			for (ContentObject co : results) {
				if (co.signedInfo().getType() != ContentType.DATA)
					continue;
				RepositoryInfo repoInfo = new RepositoryInfo();
				try {
					repoInfo.decode(co.content());
					switch (repoInfo.getType()) {
					case INFO:
						_repoName = repoInfo.getLocalName();
						_writeInterest = null;
						synchronized (this) {
							notify();
						}
						break;
					default:
						break;
					}
				} catch (XMLStreamException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
			return interestToReturn;
		}
	}
	
	/**
	 * The names returned by NameEnumerator are only the 1 level names
	 * without prefix, but the names we are holding contain the basename
	 * so we reconstruct a full name here.
	 *
	 * @author rasmusse
	 *
	 */
	private class RepoAckHandler implements BasicNameEnumeratorListener {

		public int handleNameEnumerator(ContentName prefix,
				ArrayList<ContentName> names) {
			for (ContentName name : names)
				ack(new ContentName(_baseName, name.component(0)));
			return names.size();
		}
	}

	public RepositoryProtocol(ContentName name, CCNLibrary library) {
		super(name, library);
		// TODO Auto-generated constructor stub
	}
	
	public void init(ContentName name) throws IOException {
		_baseName = name;
		clearUnmatchedInterests();	// Remove possible leftover interests from "getLatestVersion"
		ContentName repoWriteName = new ContentName(name, CCNBase.REPO_START_WRITE, CCNLibrary.nonce());
		_listener = new RepoListener();
		_writeInterest = new Interest(repoWriteName);
		_library.expressInterest(_writeInterest, _listener);
		if (! _bestEffort) {
			_ackHandler = new RepoAckHandler();
			_ackne = new CCNNameEnumerator(_library, _ackHandler);
		}
		
		/*
		 * Wait for information to be returned from a repo
		 */
		synchronized (this) {
			boolean interrupted;
			do
				try {
					interrupted = false;
					wait(getTimeout());
				} catch (InterruptedException e) {
					interrupted = true;
				}
			while (interrupted);
		}
		if (_repoName == null)
			throw new IOException("No response from a repository");
	}
	
	/**
	 * Handle acknowledgement packet from the repo
	 * @param co
	 */
	public void ack(ContentName name) {
		synchronized (_holdingArea) {
			Library.logger().fine("Handling ACK " + name);
			if (_holdingArea.get(name) != null) {
				ContentObject co = _holdingArea.get(name);
				Library.logger().fine("CO " + co.name() + " acked");
				_holdingArea.remove(co.name());
				if (_holdingArea.size() < _highwater)
					_holdingArea.notify();
			}
		}
	}
	
	public void setBestEffort(boolean flag) {
		_bestEffort = flag;
	}
	
	public boolean flushComplete() {
		return _bestEffort ? true : _holdingArea.size() == 0;
	}
	
	public void afterPutAction(ContentObject co) throws IOException {
		if (! _bestEffort) {
			if (_holdingArea.size() > _ackInterval) {
				_ackne.cancelPrefix(_baseName);
				_ackne.registerPrefix(_baseName);
			}
		} else {
			super.afterPutAction(co);
		}
	}
	
	public void beforeClose() throws IOException {
		_ackInterval = 0;
	}
		
	public void afterClose() throws IOException {
		if (! _bestEffort)
			cancelInterests();
		if (!flushComplete()) {
			throw new IOException("Unable to confirm writes are stable: timed out waiting ack for " + _holdingArea.firstKey());
		}
	}
	
	private void cancelInterests() {
		_ackne.cancelPrefix(_baseName);
		if (_writeInterest != null)
			_library.cancelInterest(_writeInterest, _listener);
	}
}
