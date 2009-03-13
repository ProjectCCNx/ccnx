package com.parc.ccn.library.io.repo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.TreeMap;

import com.parc.ccn.CCNBase;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.query.CCNInterestListener;
import com.parc.ccn.data.query.Interest;
import com.parc.ccn.library.CCNIOBackEnd;
import com.parc.ccn.library.CCNLibrary;

/**
 * Implement repository ack protocol
 * 
 * @author rasmusse
 *
 */
public class RepositoryBackEnd implements CCNIOBackEnd {
	
	protected static final int ACK_BLOCK_SIZE = 20;
	
	protected CCNLibrary _library;
	protected CCNInterestListener _listener;
	protected boolean _useAck = true;
	protected TreeMap<ContentName, ContentObject> _unacked = new TreeMap<ContentName, ContentObject>();
	protected int _blocksSinceAck = 0;
	protected ContentName _lastAcked = null;
	
	public RepositoryBackEnd(ContentName name, CCNLibrary library, CCNInterestListener listener) {
		_library = library;
		_listener = listener;
	}

	public void putBackEnd(ContentObject co) throws IOException {
		_library.put(co);
		if (_useAck) {
			_unacked.put(co.name(), co);
			if (++_blocksSinceAck > ACK_BLOCK_SIZE) {
				sendAckRequest();
				_blocksSinceAck = 0;
			}
		}
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
		}
	}
	
	public void setAck(boolean flag) {
		_useAck = flag;
	}
	
	public void close() throws IOException {
		sendAckRequest();
	}
	
	public boolean flushComplete() {
		return _useAck ? _unacked.size() == 0 : true;
	}
	
	public void sendAckRequest() throws IOException {
		if (_unacked.size() > 0) {
			Interest interest;
			ArrayList<byte[]> components = new ArrayList<byte[]>();
			if (_lastAcked == null) {
				components.addAll(_unacked.firstKey().components());
				components.remove(components.size() - 1);
			} else {
				components = _lastAcked.components();
			}
			components.add(CCNBase.REPO_REQUEST_ACK);
			byte[][] bc = new byte[components.size()][];
			components.toArray(bc);
			ContentName repoAckName = new ContentName(bc);
			if (_lastAcked == null) {
				interest = new Interest(repoAckName);
			} else {
				interest = Interest.next(repoAckName, repoAckName.count() - 3);
			}
			_library.expressInterest(interest, _listener);
		}
	}
}
