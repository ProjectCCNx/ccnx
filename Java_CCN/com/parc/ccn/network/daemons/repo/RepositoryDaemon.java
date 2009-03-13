package com.parc.ccn.network.daemons.repo;

import java.io.IOException;
import java.security.InvalidParameterException;
import java.security.SignatureException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Timer;
import java.util.TimerTask;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;

import com.parc.ccn.CCNBase;
import com.parc.ccn.Library;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.query.CCNFilterListener;
import com.parc.ccn.data.query.CCNInterestListener;
import com.parc.ccn.data.query.ExcludeFilter;
import com.parc.ccn.data.query.Interest;
import com.parc.ccn.library.CCNLibrary;
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
	private ConcurrentLinkedQueue<ContentObject> _dataQueue = new ConcurrentLinkedQueue<ContentObject>();
	private ConcurrentLinkedQueue<Interest> _interestQueue = new ConcurrentLinkedQueue<Interest>();
	private CCNLibrary _library = null;
	private boolean _started = false;
	private ArrayList<NameAndListener> _repoFilters = new ArrayList<NameAndListener>();
	private ArrayList<DataListener> _currentListeners = new ArrayList<DataListener>();
	private ExcludeFilter markerFilter;
	private TreeMap<ContentName, ContentName> _unacked = new TreeMap<ContentName, ContentName>();
	
	public static final int PERIOD = 1000; // period for interest timeout check in ms.
	
	private class NameAndListener {
		private ContentName name;
		private CCNFilterListener listener;
		private NameAndListener(ContentName name, CCNFilterListener listener) {
			this.name = name;
			this.listener = listener;
		}
	}
	
	private class FilterListener implements CCNFilterListener {

		public int handleInterests(ArrayList<Interest> interests) {
			_interestQueue.addAll(interests);
			return interests.size();
		}
	}
	
	private class DataListener implements CCNInterestListener {
		private long _timer;
		private Interest _interest;
		
		private DataListener(Interest interest) {
			_interest = interest;
			_timer = new Date().getTime();
		}
		
		public Interest handleContent(ArrayList<ContentObject> results,
				Interest interest) {
			_dataQueue.addAll(results);
			_timer = new Date().getTime();
			if (results.size() > 0) {
				
				/*
				 * Compute new interest. Its basically a next, but since we want to register it, we
				 * don't do a getNext here. Also we need to set the prefix 1 before the last component
				 * so we get all the blocks
				 */
				ContentObject co = results.get(0);
				ContentName nextName = new ContentName(co.name(), co.contentDigest(), co.name().count() - 1);
				return Interest.constructInterest(nextName,  markerFilter, 
							new Integer(Interest.ORDER_PREFERENCE_LEFT  | Interest.ORDER_PREFERENCE_ORDER_NAME));
			}
			return null;
		}
	}
	
	private class InterestTimer extends TimerTask {

		public void run() {
			long currentTime = new Date().getTime();
			synchronized(_currentListeners) {
				for (int i = 0; i < _currentListeners.size(); i++) {
					DataListener listener = _currentListeners.get(i);
					if ((currentTime - listener._timer) > PERIOD) {
						_library.cancelInterest(listener._interest, listener);
						_currentListeners.remove(i--);
					}
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
			while (_started) {
				
				ContentObject data = null;
				do {
					data = _dataQueue.poll();
					if (data != null) {
						try {
							if (_repo.checkPolicyUpdate(data)) {
								resetNameSpace();
							} else {
								Library.logger().finer("Saving content in: " + data.name().toString());
								_repo.saveContent(data);		
							}
							if (_unacked.get(data.name()) != null) {
								ArrayList<ContentName> names = new ArrayList<ContentName>();
								names.add(data.name());
								_library.put(data.name(), _repo.getRepoInfo(names));
								_unacked.remove(data.name());
							}	
						} catch (Exception e) {
							e.printStackTrace();
						}
					}
				} while (data != null) ;
				
				Interest interest = null;
				do {
					interest = _interestQueue.poll();
					if (interest != null)
						processIncomingInterest(interest);
				} while (interest != null);
				
				Thread.yield();  // Should we sleep?
			}
		}
		
		private void processIncomingInterest(Interest interest) {
			try {
				byte[] marker = interest.name().component(interest.name().count() - 1);
				if (Arrays.equals(marker, CCNBase.REPO_START_WRITE)) {
					startReadProcess(interest);
				} else if (Arrays.equals(marker, CCNBase.REPO_REQUEST_ACK)) {
					
					/*
					 * Collect as many names that we have as we can and send them all back in one "repoInfo"
					 */
					ContentName syncResult = new ContentName(interest.name().count() - 1, interest.name().components());
					ContentObject testContent = _repo.getContent(new Interest(syncResult));
					if (testContent != null) {
						ArrayList<ContentName> names = new ArrayList<ContentName>();
						int i = 0;
						while (testContent != null && ++i < 20) {
							names.add(testContent.name());
							testContent = _repo.getContent(Interest.next(testContent.name()));
						}
						_library.put(interest.name(), _repo.getRepoInfo(names));
					} else
						_unacked.put(syncResult, syncResult);
				} else {
					ContentObject content = _repo.getContent(interest);
					if (content != null) {
						_library.put(content);
					}
				}
			} catch (Exception e) {
				e.printStackTrace();
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
			markerFilter = Interest.constructFilter(markerOmissions);
			
			Timer periodicTimer = new Timer(true);
			periodicTimer.scheduleAtFixedRate(new InterestTimer(), PERIOD, PERIOD);
		}
		
		public void finish() {
			_started = false;
		}
	}
	
	public RepositoryDaemon() {
		super();
		_daemonName = "repository";
		Library.logger().info("Starting " + _daemonName + "...");				
		_started = true;
		
		try {
			_library = CCNLibrary.open();
			_library.disableFlowControl();
			_repo = new RFSImpl();
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
	
	private void resetNameSpace() throws IOException {
		ArrayList<NameAndListener> newIL = new ArrayList<NameAndListener>();
		ArrayList<ContentName> newNameSpace = _repo.getNamespace();
		if (newNameSpace == null)
			newNameSpace = new ArrayList<ContentName>();
		ArrayList<NameAndListener> unMatchedOld = new ArrayList<NameAndListener>();
		ArrayList<ContentName> unMatchedNew = new ArrayList<ContentName>();
		getUnMatched(_repoFilters, newNameSpace, unMatchedOld, unMatchedNew);
		for (NameAndListener oldName : unMatchedOld) {
			_library.unregisterFilter(oldName.name, oldName.listener);
		}
		for (ContentName newName : unMatchedNew) {
			FilterListener listener = new FilterListener();
			_library.registerFilter(newName, listener);
			newIL.add(new NameAndListener(newName, listener));
		}
		_repoFilters = newIL;
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
	
	private void startReadProcess(Interest interest) {
		ContentName listeningName = new ContentName(interest.name().count() - 1, 
				interest.name().components(), interest.name().prefixCount());
		try {
			DataListener listener = new DataListener(interest);
			synchronized(_currentListeners) {
				_currentListeners.add(listener);
			}
			Interest readInterest = Interest.constructInterest(listeningName, markerFilter, null);
			_library.expressInterest(readInterest, listener);
			_library.put(interest.name(), _repo.getRepoInfo(null));
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (SignatureException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
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
