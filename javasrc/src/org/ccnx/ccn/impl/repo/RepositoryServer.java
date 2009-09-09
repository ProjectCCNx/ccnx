package org.ccnx.ccn.impl.repo;

import java.io.IOException;
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
import org.ccnx.ccn.impl.repo.RepositoryStore.NameEnumerationResponse;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.CCNWriter;
import org.ccnx.ccn.io.content.Collection;
import org.ccnx.ccn.io.content.Collection.CollectionObject;
import org.ccnx.ccn.profiles.CommandMarkers;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.Exclude;
import org.ccnx.ccn.protocol.Interest;

/**
 * High level implementation of repository protocol that
 * can be used by any application to provide repository service. 
 * The application must supply a RepositoryStore instance to take care of actual storage
 * and retrieval, which might use persistent storage or application data structures.
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

public class RepositoryServer {
	private RepositoryStore _repo = null;
	private CCNHandle _handle = null;
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

	/**
	 * Constructor.  Note that merely creating an instance does not begin service 
	 * of requests from the network.  For that you must call start().
	 * @param handle the CCNHandle to use for communication
	 * @param repo the RepositoryStore instance to use for backing storage
	 * @throws IOException
	 */
	public RepositoryServer(CCNHandle handle, RepositoryStore repo) throws IOException {
			_handle = handle;
			_repo = repo;
			_writer = new CCNWriter(_handle);

			 // At some point we may want to refactor the code to
			 // write repository info back in a stream.  But for now
			 // we're just doing a simple put and the writer could be
			 // writing anywhere so the simplest thing to do is to just
			 // disable flow control
			_writer.disableFlowControl();

			// Create callback threadpool
			_threadpool = (ThreadPoolExecutor)Executors.newCachedThreadPool();
			_threadpool.setKeepAliveTime(THREAD_LIFE, TimeUnit.SECONDS);
	}

	/**
	 * Start serving requests from the network
	 */
	public void start() {
		Log.info("Starting service of repository requests");
		try {
			resetNameSpace();
		} catch (Exception e) {
			Log.logStackTrace(Level.WARNING, e);
			e.printStackTrace();
		}
		
		byte[][]markerOmissions = new byte[2][];
		markerOmissions[0] = CommandMarkers.COMMAND_MARKER_REPO_START_WRITE;
		markerOmissions[1] = CommandMarkers.COMMAND_MARKER_BASIC_ENUMERATION;
		_markerFilter = new Exclude(markerOmissions);
		
		Timer periodicTimer = new Timer(true);
		periodicTimer.scheduleAtFixedRate(new InterestTimer(), PERIOD, PERIOD);
	}
	
	/**
	 * Stop serving requests
	 */
	public void shutDown() {
		// TODO: implement
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
					_handle.unregisterFilter(oldName.name, oldName.listener);
					Log.info("Dropping namespace " + oldName.name);
				}
				for (ContentName newName : unMatchedNew) {
					RepositoryInterestHandler iHandler = new RepositoryInterestHandler(this);
					_handle.registerFilter(newName, iHandler);
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
	
	public CCNHandle getHandle() {
		return _handle;
	}
	
	public RepositoryStore getRepository() {
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
				co = new CollectionObject(ner.getPrefix(), cd, _handle);
				co.disableFlowControl();
				co.save(ner.getTimestamp());
				Log.finer("saved collection object: "+co.getVersionedName());
				return;

			} catch(IOException e){
				Log.logException("error saving name enumeration response for write out (prefix = "+ner.getPrefix()+" collection name: "+co.getVersionedName()+")", e);
			}

		}
	}
	

}
