package com.parc.ccn.network.daemons.repo;

import java.io.IOException;
import java.security.InvalidParameterException;
import java.security.SignatureException;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.Timer;
import java.util.TimerTask;
import java.util.logging.Level;

import com.parc.ccn.CCNBase;
import com.parc.ccn.Library;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.query.CCNFilterListener;
import com.parc.ccn.data.query.ExcludeFilter;
import com.parc.ccn.data.query.Interest;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.library.io.CCNWriter;
import com.parc.ccn.network.daemons.Daemon;

/**
 * High level repository implementation. Handles communication with
 * ccnd. Low level takes care of actual storage.
 * 
 * @author rasmusse
 *
 * Some notes:
 * We use a policy file to decide which namespaces to save. The policy file
 * is currently parsed within the lower level.
 * 
 * We can't just express an interest in anything that's within the namespaces
 * that we want to save within, because we will keep getting back the same
 * content over and over again if we do that. Instead the clients trigger a
 * write to the repository by expressing an interest in contentName +
 * "write_marker" (CCNBase.REPO_START_WRITE). When we see this we write back
 * some information then express an interest in the contentName without the
 * "write_marker" to get the initial block, then express interest in blocks
 * after the initial block for this particular write.
 */

public class RepositoryDaemon extends Daemon {
	
	private Repository _repo = null;
	private CCNLibrary _library = null;
	private ArrayList<NameAndListener> _repoFilters = new ArrayList<NameAndListener>();
	private ArrayList<RepositoryDataListener> _currentListeners = new ArrayList<RepositoryDataListener>();
	private ExcludeFilter _markerFilter;
	private CCNWriter _writer;
	private boolean _pendingNameSpaceChange = false;
	
	public static final int PERIOD = 2000; // period for interest timeout check in ms.
	
	private class NameAndListener {
		private ContentName name;
		private CCNFilterListener listener;
		private NameAndListener(ContentName name, CCNFilterListener listener) {
			this.name = name;
			this.listener = listener;
		}
	}
	
	private class InterestTimer extends TimerTask {

		public void run() {
			long currentTime = new Date().getTime();
			synchronized(_currentListeners) {
				if (_currentListeners.size() > 0) {
					Iterator<RepositoryDataListener> iterator = _currentListeners.iterator();
					while (iterator.hasNext()) {
						RepositoryDataListener listener = iterator.next();
						if ((currentTime - listener.getTimer()) > (PERIOD * 2)) {
							synchronized(_repoFilters) {
								listener.cancelInterests();
								iterator.remove();
							}
						}
					}
				}
				if (_currentListeners.size() == 0 && _pendingNameSpaceChange) {
					try {
						resetNameSpace();
					} catch (IOException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
					_pendingNameSpaceChange = false;
				}
			}	
		}	
	}
	
	protected class RepositoryWorkerThread extends Daemon.WorkerThread {

		private static final long serialVersionUID = -6093561895394961537L;
		
		protected RepositoryWorkerThread(String daemonName) {
			super(daemonName);
		}
		
		public void work() {
			synchronized(this) {
				boolean interrupted = false;
				do {
					try {
						wait();
					} catch (InterruptedException e) {
						interrupted = true;
					}		
				} while (interrupted);
			}
		}
		
		public void initialize() {
			try {
				resetNameSpace();
			} catch (Exception e) {
				e.printStackTrace();
			}
			
			byte[][]markerOmissions = new byte[2][];
			markerOmissions[0] = CCNBase.REPO_START_WRITE;
			markerOmissions[1] = CCNBase.REPO_REQUEST_ACK;
			_markerFilter = new ExcludeFilter(markerOmissions);
			
			Timer periodicTimer = new Timer(true);
			periodicTimer.scheduleAtFixedRate(new InterestTimer(), PERIOD, PERIOD);
		}
		
		public void finish() {
			synchronized (this) {
				notify();
			}
		}
	}
	
	public RepositoryDaemon() {
		super();
		_daemonName = "repository";
		Library.logger().info("Starting " + _daemonName + "...");				
		
		try {
			_library = CCNLibrary.open();
			_repo = new RFSImpl();
			_writer = new CCNWriter(_library);
			
			/*
			 * At some point we may want to refactor the code to
			 * write repository info back in a stream.  But for now
			 * we're just doing a simple put and the writer could be
			 * writing anywhere so the simplest thing to do is to just
			 * disable flow control
			 */
			_writer.disableFlowControl();
			
		} catch (Exception e1) {
			e1.printStackTrace();
			System.exit(0);
		} 
	}
	
	public void initialize(String[] args, Daemon daemon) {
		for (int i = 0; i < args.length; i++) {
			if (args[i].equals("-log")) {
				if (args.length < i + 2) {
					usage();
					return;
				}
				try {
					Level level = Level.parse(args[i + 1]);
					Library.logger().setLevel(level);
				} catch (IllegalArgumentException iae) {
					usage();
					return;
				}
			}
		}
		try {
			_repo.initialize(args);
		} catch (InvalidParameterException ipe) {
			usage();
		} catch (RepositoryException e) {
			e.printStackTrace();
		}
	}
	
	protected void usage() {
		try {
			System.out.println("usage: " + this.getClass().getName() + 
						_repo.getUsage() + "[-start | -stop | -interactive] [-log <level>]");
		} catch (Exception e) {
			e.printStackTrace();
		}
		System.exit(0);
	}

	protected WorkerThread createWorkerThread() {
		return new RepositoryWorkerThread(daemonName());
	}
	
	/**
	 * In general we need to wait until all sessions are complete before
	 * making a namespace change because it involves changing the filter which
	 * could cut off current sessions in process
	 * @throws IOException 
	 */
	public void resetNameSpaceFromHandler() throws IOException {
		synchronized (_currentListeners) {
			if (_currentListeners.size() == 0)
				resetNameSpace();
			else
				_pendingNameSpaceChange = true;
		}	
	}
	
	private void resetNameSpace() throws IOException {
		synchronized (_repoFilters) {
			ArrayList<NameAndListener> newIL = new ArrayList<NameAndListener>();
			ArrayList<ContentName> newNameSpace = _repo.getNamespace();
			if (newNameSpace == null)
				newNameSpace = new ArrayList<ContentName>();
			ArrayList<NameAndListener> unMatchedOld = new ArrayList<NameAndListener>();
			ArrayList<ContentName> unMatchedNew = new ArrayList<ContentName>();
			getUnMatched(_repoFilters, newNameSpace, unMatchedOld, unMatchedNew);
			for (NameAndListener oldName : unMatchedOld) {
				_library.unregisterFilter(oldName.name, oldName.listener);
				Library.logger().info("Dropping namespace " + oldName.name);
			}
			for (ContentName newName : unMatchedNew) {
				RepositoryInterestHandler iHandler = new RepositoryInterestHandler(this);
				_library.registerFilter(newName, iHandler);
				Library.logger().info("Adding namespace " + newName);
				newIL.add(new NameAndListener(newName, iHandler));
			}
			_repoFilters = newIL;
		}
	}
	
	private void getUnMatched(ArrayList<NameAndListener> oldIn, ArrayList<ContentName> newIn, 
			ArrayList<NameAndListener> oldOut, ArrayList<ContentName>newOut) {
		newOut.addAll(newIn);
		for (NameAndListener ial : oldIn) {
			boolean matched = false;
			for (ContentName name : newIn) {
				if (ial.name.equals(name)) {
					newOut.remove(name);
					matched = true;
					break;
				}
			}
			if (!matched)
				oldOut.add(ial);
		}
	}
	
	public CCNLibrary getLibrary() {
		return _library;
	}
	
	public Repository getRepository() {
		return _repo;
	}
	
	public ExcludeFilter getExcludes() {
		return _markerFilter;
	}
	
	public CCNWriter getWriter() {
		return _writer;
	}
	
	public ArrayList<RepositoryDataListener> getDataListeners() {
		return _currentListeners;
	}
	
	public ArrayList<NameAndListener> getFilters() {
		return _repoFilters;
	}
	
	public RepositoryDataListener addListener(Interest interest, Interest readInterest) {
		RepositoryDataListener listener = new RepositoryDataListener(interest, readInterest, this);
		synchronized(_currentListeners) {
			_currentListeners.add(listener);
		}
		return listener;
	}
	
	public boolean getPendingNameSpaceState() {
		return _pendingNameSpaceChange;
	}
	
	public void ack(Interest interest, Interest ackMatch) throws SignatureException, IOException {
		ArrayList<ContentName> names = new ArrayList<ContentName>();
		synchronized(_currentListeners) {
			for (RepositoryDataListener listener : _currentListeners) {
				/*
				 * Find the DataListener with values to Ack
				 */
				boolean found = false;
				for (ContentObject co : listener.getUnacked()) {
					if (ackMatch.matches(co)) {
						found = true;
						break;
					}
				}
				if (!found) {
					/*
					 * If the ack request matches our original interest, assume it arrived
					 * before any unacked data and we will use it to ack the data when it arrives
					 * 
					 * XXX should we care about publisherID here?  And if so, how can
					 * we do this?
					 */
					if (ackMatch.matches(listener.getInterest().name(), null)) {
						Library.logger().finer("Adding ACK interest received before data: " + interest.name());
						// We must record the actual requesting interest that came in, not the internal
						// matching one, because what we store here is going to be used later to generate the name for
						// an ACK response content object that must match the original received interest
						listener.getAckRequests().add(interest);
						break;
					}
					continue;
				}
				
				/*
				 * For now just send back all the names we have. 
				 * Possibly later we may want to make sure they match.
				 * Don't put too many or we'll overflow the ContentObject
				 */
				int count = 0;
				for (ContentObject co : listener.getUnacked()) {
					names.add(co.name());
					if (++count > 20) {
						Library.logger().finer("Acking " + co.name());
						_writer.put(interest.name(), _repo.getRepoInfo(names));
						names.clear();
						count = 0;
					}
				}
				_writer.put(interest.name(), _repo.getRepoInfo(names));
				listener.getUnacked().clear();
				break;
			}
		}
	}
	
	public static void main(String[] args) {
		Daemon daemon = null;
		try {
			daemon = new RepositoryDaemon();
			runDaemon(daemon, args);
			
		} catch (Exception e) {
			System.err.println("Error attempting to start daemon.");
			Library.logger().warning("Error attempting to start daemon.");
			Library.warningStackTrace(e);
		}
	}
}
