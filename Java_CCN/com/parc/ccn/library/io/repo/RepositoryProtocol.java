package com.parc.ccn.library.io.repo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.TreeMap;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.CCNBase;
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
	protected ContentName _lastAcked = null;
	protected Interest _ackInterest = null;
	protected String _repoName = null;
	protected String _repoPrefix = null;
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
						if (flushComplete())
							sendAckRequest();
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

	public RepositoryProtocol(ContentName name, CCNLibrary library) {
		super(name, library);
		// TODO Auto-generated constructor stub
	}
	
	public void init(ContentName name) throws IOException {
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
		if (_unacked.get(name) != null) {
			ContentObject co = _unacked.get(name);
			if (_lastAcked == null) {
				_lastAcked = co.name();
			} else {
				if (co.name().compareTo(_lastAcked) > 0) {
					_lastAcked = co.name();
				}
			}
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
			ArrayList<byte[]> components = new ArrayList<byte[]>();
			if (_lastAcked == null) {
				components.addAll(_unacked.firstKey().components());
				components.remove(components.size() - 1);
			} else {
				components = _lastAcked.components();
			}
			components.add(CCNBase.REPO_REQUEST_ACK);
			components.add(CCNLibrary.nonce());
			byte[][] bc = new byte[components.size()][];
			components.toArray(bc);
			ContentName repoAckName = new ContentName(bc);
			if (_lastAcked == null) {
				_ackInterest = new Interest(repoAckName);
			} else {
				_ackInterest = Interest.next(repoAckName, repoAckName.count() - 3);
			}
			_library.expressInterest(_ackInterest, _listener);
		}
	}
	
	public void close() throws IOException {
		sendAckRequest();
		synchronized(this) {
			int nUnacked = _unacked.size();
			while (!flushComplete()) {
				boolean interrupted = true;
				while (interrupted) {
				interrupted = false;
					try {
						wait(getTimeout());
					} catch (InterruptedException e) {
						interrupted = true;
					}
				}
				if (_unacked.size() == nUnacked)
					return;
			}
		}
		
		cancelInterests();
		if (!flushComplete()) {
			throw new IOException("No ack from repository");
		}
	}
	
	private void cancelInterests() {
		if (_writeInterest != null)
			_library.cancelInterest(_writeInterest, _listener);
		if (_ackInterest != null)
			_library.cancelInterest(_ackInterest, _listener);
	}

}
