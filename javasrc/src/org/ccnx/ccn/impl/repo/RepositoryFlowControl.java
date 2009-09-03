package org.ccnx.ccn.impl.repo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.NoSuchElementException;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.xml.stream.XMLStreamException;

import org.ccnx.ccn.CCNInterestListener;
import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.impl.CCNFlowControl;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.profiles.CommandMarkers;
import org.ccnx.ccn.profiles.nameenum.BasicNameEnumeratorListener;
import org.ccnx.ccn.profiles.nameenum.CCNNameEnumerator;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Interest;
import org.ccnx.ccn.protocol.SignedInfo.ContentType;


/**
 * Handle repo specialty start/end protocol. Currently this handles only the
 * stream "shape".
 * 
 * Needs to be able to handle multiple clients. Currently due to limitations in close,
 * to do this requires that clients above close their streams in order when multiple
 * streams are using the same FC.
 * 
 * Intended to handle the repo ack protocol. This is currently unused until we find
 * a workable way to do it.
 * 
 * @author smetters, rasmusse
 *
 */
public class RepositoryFlowControl extends CCNFlowControl implements CCNInterestListener {

	protected static final int ACK_BLOCK_SIZE = 20;
	protected static final int ACK_INTERVAL = 128;
	protected boolean _bestEffort = true;
	protected int _blocksSinceAck = 0;
	protected int _ackInterval = ACK_INTERVAL;
	protected HashSet<Interest> _writeInterests = new HashSet<Interest>();
	protected CCNNameEnumerator _ackne;
	protected RepoAckHandler _ackHandler;
	
	protected Queue<Client> _clients = new ConcurrentLinkedQueue<Client>();

	public Interest handleContent(ArrayList<ContentObject> results,
			Interest interest) {
		
		Interest interestToReturn = null;
		for (ContentObject co : results) {
			Log.info("handleContent: got potential repo message: " + co.name());
			if (co.signedInfo().getType() != ContentType.DATA)
				continue;
			RepositoryInfo repoInfo = new RepositoryInfo();
			try {
				repoInfo.decode(co.content());
				switch (repoInfo.getType()) {
				case INFO:
					for (Client client : _clients) {
						if (client._name.isPrefixOf(co.name()))
							client._initialized = true;
					}
					//_writeInterest = null;
					synchronized (this) {
						notify();
					}
					break;
				default:
					break;
				}
			} catch (XMLStreamException e) {
				Log.info("XMLStreamException parsing RepositoryInfo: " + e.getMessage() + " from content object " + co.name() + ", skipping.");
			}
		}
		// So far, we seem never to have anything to return.
		return interestToReturn;
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

		public int handleNameEnumerator(ContentName prefix, ArrayList<ContentName> names) {
			Log.info("Enumeration response for " + names.size() + " children of " + prefix + ".");
			for (ContentName name : names)
				ack(new ContentName(prefix, name.component(0)));
			return names.size();
		}
	}
	
	protected class Client {
		protected ContentName _name;
		protected Shape _shape;
		protected boolean _initialized = false;
		
		public Client(ContentName name, Shape shape) {
			_name = name;
			_shape = shape;
		}
		
		public ContentName name() { return _name; }
		public Shape shape() { return _shape; }
	}

	public RepositoryFlowControl(CCNHandle library) throws IOException {
		super(library);
	}

	public RepositoryFlowControl(ContentName name, CCNHandle library) throws IOException {
		this(library);
		addNameSpace(name);
		startWrite(name, Shape.STREAM);
	}
	
	public RepositoryFlowControl(ContentName name, CCNHandle library, Shape shape) throws IOException {
		this(library);
		addNameSpace(name);
		startWrite(name, shape);
	}

	@Override
	public void startWrite(ContentName name, Shape shape) throws IOException {
		
		Client client = new Client(name, shape);
		_clients.add(client);
		clearUnmatchedInterests();	// Remove possible leftover interests from "getLatestVersion"
		ContentName repoWriteName = new ContentName(name, CommandMarkers.REPO_START_WRITE, Interest.generateNonce());

		Interest writeInterest = new Interest(repoWriteName);
		_library.expressInterest(writeInterest, this);
		_writeInterests.add(writeInterest);
		if (! _bestEffort) {
			_ackHandler = new RepoAckHandler();
			_ackne = new CCNNameEnumerator(_library, _ackHandler);
		}

		/*
		 * Wait for information to be returned from a repo
		 */
		synchronized (this) {
			boolean interrupted;
			long startTime = System.currentTimeMillis();
			do {
				try {
					interrupted = false;
					long timeout = getTimeout() - (System.currentTimeMillis() - startTime);
					if (timeout <= 0)
						break;
					wait(timeout);
				} catch (InterruptedException e) {
					interrupted = true;
				}
			} while (interrupted || (!client._initialized && ((getTimeout() + startTime) > System.currentTimeMillis())));
		}
		if (!client._initialized) {
			Log.finest("No response from a repository, cannot add name space : " + name);
			throw new IOException("No response from a repository for " + name);
		}
	}

	/**
	 * Handle acknowledgement packet from the repo
	 * @param co
	 */
	public void ack(ContentName name) {
		synchronized (_holdingArea) {
			Log.fine("Handling ACK " + name);
			if (_holdingArea.get(name) != null) {
				ContentObject co = _holdingArea.get(name);
				Log.fine("CO " + co.name() + " acked");
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
				ContentName prefix = getNameSpace(co.name());
				_ackne.cancelPrefix(prefix);
				_ackne.registerPrefix(prefix);
			}
		} else {
			super.afterPutAction(co);
		}
	}

	@Override
	public void beforeClose() throws IOException {
		_ackInterval = 0;
	}

	@Override
	public void afterClose() throws IOException {
		try {
			_clients.remove();
		} catch (NoSuchElementException nse) {}
		
		// super.afterClose() calls waitForPutDrain.
		super.afterClose();
		// DKS don't actually want to cancel all the interests, only the
		// ones relevant to the data we've finished writing.
		cancelInterests();
		if (!flushComplete()) {
			throw new IOException("Unable to confirm writes are stable: timed out waiting ack for " + _holdingArea.firstKey());
		}
	}

	public void cancelInterests() {
		if (! _bestEffort) {
			for (ContentName prefix : _filteredNames) {
				_ackne.cancelPrefix(prefix);
			}
		}
		for (Interest writeInterest : _writeInterests){
			_library.cancelInterest(writeInterest, this);
		}
	}
}
