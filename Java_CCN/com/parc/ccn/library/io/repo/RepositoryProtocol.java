package com.parc.ccn.library.io.repo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.TreeMap;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.CCNBase;
import com.parc.ccn.Library;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.query.CCNInterestListener;
import com.parc.ccn.data.query.Interest;
import com.parc.ccn.data.security.SignedInfo.ContentType;
import com.parc.ccn.library.CCNFlowControl;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.network.daemons.repo.RepositoryInfo;

/**
 * Implements the client side of the repository protocol
 * 
 * @author rasmusse
 *
 */

public class RepositoryProtocol extends CCNFlowControl {
	
	protected static final int ACK_BLOCK_SIZE = 20;
	
	protected boolean _useAck = true;
	protected TreeMap<ContentName, ContentObject> _unacked = new TreeMap<ContentName, ContentObject>();
	protected int _blocksSinceAck = 0;
	protected ArrayList<Interest> _ackInterests = new ArrayList<Interest>();
	protected String _repoName = null;
	protected String _repoPrefix = null;
	protected ContentName _baseName; // the name prefix under which we are writing content
	protected RepoListener _listener = null;
	protected Interest _writeInterest = null;

	private class RepoListener implements CCNInterestListener {

		public Interest handleContent(ArrayList<ContentObject> results,
				Interest interest) {
			for (ContentObject co : results) {
				if (co.signedInfo().getType() != ContentType.DATA)
					continue;
				RepositoryInfo repoInfo = new RepositoryInfo();
				try {
					repoInfo.decode(co.content());
					switch (repoInfo.getType()) {
					case INFO:
						_repoName = repoInfo.getLocalName();
						_repoPrefix = repoInfo.getGlobalPrefix();
						_writeInterest = null;
						synchronized (this) {
							notify();
						}
						break;
					case DATA:
						if (!repoInfo.getLocalName().equals(_repoName))
							break;		// not our repository
						if (!repoInfo.getGlobalPrefix().equals(_repoPrefix))
							break;		// not our repository
						for (ContentName name : repoInfo.getNames())
							ack(name);
						Library.logger().finer("ACK message leaves " + _unacked.size() + " unacked");
						Library.logger().finer("unacked " + _unacked);
						break;
					default:
						break;
					}
				} catch (XMLStreamException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
			return null;
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
	
	public ContentObject put(ContentObject co) throws IOException {
		super.put(co);
		if (_useAck) {
			_unacked.put(co.name(), co);
			if (++_blocksSinceAck > ACK_BLOCK_SIZE) {
				sendAckRequest();
				_blocksSinceAck = 0;
			}
		}
		return co;
	}

	/**
	 * Handle acknowledgement packet from the repo
	 * @param co
	 */
	public void ack(ContentName name) {
		Library.logger().finer("Handling ACK " + name);
		if (_unacked.get(name) != null) {
			ContentObject co = _unacked.get(name);
			Library.logger().finest("CO " + co.name() + " acked");
			_unacked.remove(name);
			synchronized (this) {
				if (_unacked.size() == 0)
					this.notify();
			}
		}
	}
	
	public void setAck(boolean flag) {
		_useAck = flag;
	}
	
	public boolean flushComplete() {
		return _useAck ? _unacked.size() == 0 : true;
	}
	
	public void sendAckRequest() throws IOException {
		if (_unacked.size() > 0) {
			ContentName repoAckName = new ContentName(_baseName, CCNBase.REPO_REQUEST_ACK, CCNLibrary.nonce());
			Interest ackInterest = new Interest(repoAckName);
			_ackInterests.add(ackInterest);
			Library.logger().info("Sending ACK request with " + _unacked.size() + " unacknowledged content objects");
			_library.expressInterest(ackInterest, _listener);
		}
	}
	
	/**
	 * Even though we should have output all the data by the time we got here
	 * (due to call of waitForPutDrain) there are still timing pitfalls as the repo may still
	 * be in the process of collecting the data. So we loop sending ackRequests until we are
	 * making no more progress.
	 * 
	 * @throws IOException
	 */
	public void close() throws IOException {
		synchronized(this) {
			while (!flushComplete()) {
				sendAckRequest();
				int unacked = _unacked.size();
				boolean interrupted;
				do {
					interrupted = false;
					try {
						wait(getTimeout());
					} catch (InterruptedException e) {
						interrupted = true;
					}
				} while (interrupted);
				if (unacked == _unacked.size())
					break;
			}
		}
		
		cancelInterests();
		if (!flushComplete()) {
			throw new IOException("Unable to confirm writes are stable: timed out waiting ack for " + _unacked.size());
		}
	}
	
	private void cancelInterests() {
		if (_writeInterest != null)
			_library.cancelInterest(_writeInterest, _listener);
		for (Interest interest : _ackInterests) {
			_library.cancelInterest(interest, _listener);
		}
	}

}
