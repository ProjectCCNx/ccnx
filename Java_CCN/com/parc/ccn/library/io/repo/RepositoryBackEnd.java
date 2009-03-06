package com.parc.ccn.library.io.repo;

import java.io.IOException;
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
	
	protected CCNLibrary _library;
	protected CCNInterestListener _listener;
	protected boolean _useAck = false;
	protected TreeMap<ContentName, ContentObject> _unacked = new TreeMap<ContentName, ContentObject>();
	
	public RepositoryBackEnd(ContentName name, CCNLibrary library, CCNInterestListener listener) {
		_library = library;
		_listener = listener;
	}

	public void putBackEnd(ContentObject co) throws IOException {
		_library.put(co);
		if (_useAck) {
			_unacked.put(co.name(), co);
			ContentName repoAckName = new ContentName(co.name(), CCNBase.REPO_REQUEST_ACK);
			_library.expressInterest(new Interest(repoAckName), _listener);
		}
	}

	/**
	 * Handle acknowledgement packet from the repo
	 * @param co
	 */
	public void ack(ContentName name) {
		if (_unacked.get(name) != null)
			_unacked.remove(name);
	}
	
	public void setAck(boolean flag) {
		_useAck = flag;
	}
	
	public boolean flushComplete() {
		return _useAck ? _unacked.size() == 0 : true;
	}
}
