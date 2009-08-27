package org.ccnx.ccn.impl.repo;

import java.io.IOException;
import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import javax.xml.stream.XMLStreamException;

import org.ccnx.ccn.CCNFilterListener;
import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.impl.repo.Repository.NameEnumerationResponse;
import org.ccnx.ccn.impl.support.Daemon;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.CCNWriter;
import org.ccnx.ccn.io.content.Collection;
import org.ccnx.ccn.io.content.Collection.CollectionObject;
import org.ccnx.ccn.profiles.CommandMarkers;
import org.ccnx.ccn.profiles.nameenum.CCNNameEnumerator;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.Exclude;
import org.ccnx.ccn.protocol.Interest;
import org.ccnx.ccn.test.BitBucketRepository;


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
	private CCNHandle _library = null;
	private ArrayList<NameAndListener> _repoFilters = new ArrayList<NameAndListener>();
	private ArrayList<RepositoryDataListener> _currentListeners = new ArrayList<RepositoryDataListener>();
	private Exclude _markerFilter;
	private CCNWriter _writer;
	private boolean _pendingNameSpaceChange = false;
	private int _windowSize = WINDOW_SIZE;
	private int _ephemeralFreshness = FRESHNESS;
	protected ThreadPoolExecutor _threadpool = null; // pool service
	
	public static final int PERIOD = 2000; // period for interest timeout check in ms.
	public static final int THREAD_LIFE = 8;	// in seconds
	public static final int WINDOW_SIZE = 4;
	public static final int FRESHNESS = 4;	// in seconds
		
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
			synchronized (_currentListeners) {
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
					Log.finer("InterestTimer - resetting nameSpace");
					try {
						resetNameSpace();
					} catch (IOException e) {
						Log.logStackTrace(Level.WARNING, e);
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
						interrupted = false;
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
				Log.logStackTrace(Level.WARNING, e);
				e.printStackTrace();
			}
			
			byte[][]markerOmissions = new byte[2][];
			markerOmissions[0] = CommandMarkers.REPO_START_WRITE;
			markerOmissions[1] = CCNNameEnumerator.NEMARKER;
			_markerFilter = new Exclude(markerOmissions);
			
			Timer periodicTimer = new Timer(true);
			periodicTimer.scheduleAtFixedRate(new InterestTimer(), PERIOD, PERIOD);
		}
		
		public void finish() {
			synchronized (this) {
				notifyAll(); // notifyAll ensures shutdown in interactive case when main thread is join()'ing
			}
		}
		
		public boolean signal(String name) {
			return _repo.diagnostic(name);
		}
	}
	
	public RepositoryDaemon() {
		super();
		// This is a daemon: it should not do anything in the
		// constructor but everything in the initialize() method
		// which will be run in the process that will finally 
		// execute as the daemon, rather than in the launching
		// and stopping processes also.
		_daemonName = "repository";
	}
	
	public void initialize(String[] args, Daemon daemon) {
		Log.info("Starting " + _daemonName + "...");				
		Log.setLevel(Level.INFO);
		boolean useLogging = false;
		try {
			_library = CCNHandle.open();
			_writer = new CCNWriter(_library);

			 // At some point we may want to refactor the code to
			 // write repository info back in a stream.  But for now
			 // we're just doing a simple put and the writer could be
			 // writing anywhere so the simplest thing to do is to just
			 // disable flow control
			_writer.disableFlowControl();

			SystemConfiguration.setLogging("repo", false);
			for (int i = 0; i < args.length; i++) {
				if (args[i].equals("-log")) {
					if (args.length < i + 2) {
						usage();
						return;
					}
					try {
						SystemConfiguration.setLogging("repo", true);
						Level level = Level.parse(args[i + 1]);
						Log.setLevel(level);
						useLogging = level.intValue() < Level.INFO.intValue();
					} catch (IllegalArgumentException iae) {
						usage();
						return;
					}
				}

				if (args[i].equals("-bb"))  // Following is for upper half performance testing for writes
					_repo = new BitBucketRepository();
				
				if(args[i].equals("-singlefile"))
					_repo = new RFSLogImpl();

			}

			if (!useLogging)
				SystemConfiguration.setLogging("repo", false);
			
			if (_repo == null)	// default lower half
				_repo = new RFSLogImpl();
			
			_repo.initialize(args, _library);
			
			// Create callback threadpool
			_threadpool = (ThreadPoolExecutor)Executors.newCachedThreadPool();
			_threadpool.setKeepAliveTime(THREAD_LIFE, TimeUnit.SECONDS);
		} catch (InvalidParameterException ipe) {
			usage();
		} catch (Exception e) {
			e.printStackTrace();
			Log.logStackTrace(Level.SEVERE, e);
			System.exit(1);
		}
	}
	
	protected void usage() {
		try {
			// Without parsing args, we don't know which repo impl we will get, so show the default 
			// impl usage and allow for differences 
			String msg = "usage: " + this.getClass().getName() + " -start | -stop <pid> | -interactive | -signal <signal> <pid>" +
			" [-log <level>] [-singlefile | -bb] " + RFSLogImpl.getUsage() + " | <repoimpl-args>";
			System.out.println(msg);
			Log.severe(msg);
		} catch (Exception e) {
			e.printStackTrace();
			Log.logStackTrace(Level.SEVERE, e);
		}
		System.exit(1);
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
			Log.finer("ResetNameSpaceFromHandler: pendingNameSpaceChange is " + _pendingNameSpaceChange);
		}	
	}
	
	private void resetNameSpace() throws IOException {
		synchronized (_repoFilters) {
			ArrayList<NameAndListener> newIL = new ArrayList<NameAndListener>();
			synchronized (_repo.getPolicy()) {
				ArrayList<ContentName> newNameSpace = _repo.getNamespace();
				if (newNameSpace == null)
					newNameSpace = new ArrayList<ContentName>();
				ArrayList<NameAndListener> unMatchedOld = new ArrayList<NameAndListener>();
				ArrayList<ContentName> unMatchedNew = new ArrayList<ContentName>();
				getUnMatched(_repoFilters, newNameSpace, unMatchedOld, unMatchedNew);
				for (NameAndListener oldName : unMatchedOld) {
					_library.unregisterFilter(oldName.name, oldName.listener);
					Log.info("Dropping namespace " + oldName.name);
				}
				for (ContentName newName : unMatchedNew) {
					RepositoryInterestHandler iHandler = new RepositoryInterestHandler(this);
					_library.registerFilter(newName, iHandler);
					Log.info("Adding namespace " + newName);
					newIL.add(new NameAndListener(newName, iHandler));
				}
				_repoFilters = newIL;
			}
		}
	}
	
	/**
	 * Determine changes in the namespace so we can decide what needs to be newly registered
	 * and what should be unregistered. This also checks to see that no names duplicate each other
	 * i.e. if we have /foo and /foo/bar only /foo should be registered for the repo to listen
	 * to.
	 * 
	 * @param oldIn - previously registered names
	 * @param newIn - new names to consider
	 * @param oldOut - previously registered names to deregister
	 * @param newOut - new names which need to be registered
	 */
	@SuppressWarnings("unchecked")
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
		
		// Make sure no name can be expressed a prefix of another name in the list
		ArrayList<ContentName> tmpOut = (ArrayList<ContentName>)newOut.clone();
		for (ContentName cn : tmpOut) {
			for (ContentName tcn : tmpOut) {
				if (tcn.equals(cn))
					continue;
				if (tcn.isPrefixOf(cn))
					newOut.remove(cn);
				if (cn.isPrefixOf(tcn))
					newOut.remove(tcn);
			}
		}
	}
	
	public CCNHandle getLibrary() {
		return _library;
	}
	
	public Repository getRepository() {
		return _repo;
	}
	
	public Exclude getExcludes() {
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
	
	public RepositoryDataListener addListener(Interest interest, Interest readInterest) throws XMLStreamException, IOException {
		RepositoryDataListener listener = new RepositoryDataListener(interest, readInterest, this);
		synchronized(_currentListeners) {
			_currentListeners.add(listener);
		}
		return listener;
	}
	
	public boolean getPendingNameSpaceState() {
		return _pendingNameSpaceChange;
	}
	
	public ThreadPoolExecutor getThreadPool() {
		return _threadpool;
	}
	
	public int getWindowSize() {
		return _windowSize;
	}
	
	public int getFreshness() {
		return _ephemeralFreshness;
	}
	
	public void sendEnumerationResponse(NameEnumerationResponse ner){
		if(ner!=null && ner.getPrefix()!=null && ner.hasNames()){
			CollectionObject co = null;
			try{
				Log.finer("returning names for prefix: "+ner.getPrefix());

				for (int x = 0; x < ner.getNames().size(); x++) {
					Log.finer("name: "+ner.getNames().get(x));
				}
				if (ner.getTimestamp()==null)
					Log.info("node.timestamp was null!!!");
				Collection cd = ner.getNamesInCollectionData();
				co = new CollectionObject(ner.getPrefix(), cd, _library);
				co.disableFlowControl();
				co.save(ner.getTimestamp());
				Log.finer("saved collection object: "+co.getCurrentVersionName());
				return;

			} catch(IOException e){
				Log.logException("error saving name enumeration response for write out (prefix = "+ner.getPrefix()+" collection name: "+co.getCurrentVersion()+")", e);
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
			Log.warning("Error attempting to start daemon.");
			Log.warningStackTrace(e);
		}
	}
}
