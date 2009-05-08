package com.parc.ccn.library.io.repo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
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

public class RepositorySegmenter extends CCNFlowControl {
	
	protected static final int ACK_BLOCK_SIZE = 20;
	
	protected boolean _useAck = true;
	protected TreeMap<ContentName, ContentObject> _unacked = new TreeMap<ContentName, ContentObject>();
	protected int _blocksSinceAck = 0;
	//protected ContentName _lastAcked = null;
	protected Interest _ackInterest = null;
	protected String _repoName;
	protected String _repoPrefix;
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

	public RepositorySegmenter(ContentName name, CCNLibrary library) {
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
			if (null != _ackInterest) {
				_library.cancelInterest(_ackInterest, _listener);
			}
			ContentName repoAckName = new ContentName(_baseName, CCNBase.REPO_REQUEST_ACK, CCNLibrary.nonce());
			_ackInterest = new Interest(repoAckName);
			Library.logger().info("Sending ACK request with " + _unacked.size() + " unacknowledged content objects");
			_library.expressInterest(_ackInterest, _listener);
		}
	}
	
	public void close() throws IOException {
		sendAckRequest();
		int remaining = getTimeout();
		synchronized(this) {
			while (remaining > 0 && !flushComplete()) {
				boolean interrupted;
				do {
					interrupted = false;
					try {
						long start_wait = new Date().getTime();
						wait(remaining);
						long elapsed = new Date().getTime() - start_wait;
						remaining -= elapsed;
					} catch (InterruptedException e) {
						interrupted = true;
					}
				} while (interrupted);
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
		if (_ackInterest != null)
			_library.cancelInterest(_ackInterest, _listener);
	}

}
