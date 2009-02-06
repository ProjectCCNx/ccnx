package com.parc.ccn.network.daemons.repo;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;

import com.parc.ccn.Library;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.query.CCNFilterListener;
import com.parc.ccn.data.query.CCNInterestListener;
import com.parc.ccn.data.query.Interest;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.network.daemons.Daemon;

/**
 * High level repository implementation. Handles communication with
 * ccnd. Low level takes care of actual storage.
 * 
 * @author rasmusse
 *
 */

public class RepositoryDaemon extends Daemon {
	
	private Repository _repo = null;
	private ConcurrentLinkedQueue<ContentObject> _dataQueue = new ConcurrentLinkedQueue<ContentObject>();
	private ConcurrentLinkedQueue<ContentObject> _policyQueue = new ConcurrentLinkedQueue<ContentObject>();
	private CCNLibrary _library = null;
	private boolean _started = false;
	private Policy _policy = null;
	private ArrayList<InterestAndListener> _repoInterests = new ArrayList<InterestAndListener>();
	
	private class InterestAndListener {
		private Interest interest;
		private CCNInterestListener listener;
		private InterestAndListener(Interest interest, CCNInterestListener listener) {
			this.interest = interest;
			this.listener = listener;
		}
	}
	
	private class DataListener implements CCNInterestListener {
		private ConcurrentLinkedQueue<ContentObject> queue;
		private String name = null;		// For debugging
		
		private DataListener(ConcurrentLinkedQueue<ContentObject> queue, String name) {
			this.queue = queue;
			this.name = name;
		}
		public Interest handleContent(ArrayList<ContentObject> results,
				Interest interest) {
			Library.logger().finer("Interest callback on queue: " + name + " (" + results.size() + " data) for: " + interest.name());
			queue.addAll(results);
			return interest;
		}
	}
	
	private class FilterListener implements CCNFilterListener {

		/**
		 * For now we assume that we are only interested :-) in interests
		 * that we can satisfy now. If the interest was satisfiable "later"
		 * then the same ccnd that gave us the interest should also have
		 * satisfied the interest that someone else had given it.
		 */
		public int handleInterests(ArrayList<Interest> interests) {
			int result = 0;
			for (Interest interest : interests) {
				try {
					ContentObject content = _repo.getContent(interest);
					if (content != null) {
						_library.put(content);
						result++;
					}
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
			return result;
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
System.out.println("Saving content: " + data.name().toString());
							Library.logger().finer("Saving content in: " + data.name().toString());
							_repo.saveContent(data);
						} catch (RepositoryException e) {
							e.printStackTrace();
						}
					}
				} while (data != null) ;
				
				/*
				 * TODO - for now we only accept policy in one
				 * content object chunk
				 */
				ContentObject policy = null;
				do {
					policy = _policyQueue.poll();
					if (policy != null) {
						try {
							_policy.update(new ByteArrayInputStream(policy.content()));
							_repo.setPolicy(_policy);
							resetNameSpaceInterests();
						} catch (Exception e) {
							e.printStackTrace();
						}
					}
				} while (data != null) ;
				
				Thread.yield();  // Should we sleep?
			}
		}
		
		/**
		 * Express our interest in "everything" and get all interests forwarded
		 * from the ccnd
		 */
		public void initialize() {
			
			FilterListener filterListener = new FilterListener();
			DataListener policyListener = new DataListener(_policyQueue, "Policy");
			try {
				Interest policyInterest = _repo.getPolicyInterest();
				if (policyInterest != null)
					_library.expressInterest(policyInterest, policyListener);
				resetNameSpaceInterests();
				_library.registerFilter(ContentName.fromNative("/"), filterListener);
			} catch (Exception e) {
				e.printStackTrace();
			}
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
			_repo = new RFSImpl();
			_policy = new BasicPolicy();
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
	
	private void resetNameSpaceInterests() throws IOException {
		ArrayList<InterestAndListener> newIL = new ArrayList<InterestAndListener>();
		ArrayList<Interest> newInterests = _repo.getNamespaceInterests();
		if (newInterests == null)
			newInterests = new ArrayList<Interest>();
		ArrayList<InterestAndListener> unMatchedOld = new ArrayList<InterestAndListener>();
		ArrayList<Interest> unMatchedNew = new ArrayList<Interest>();
		getUnMatched(_repoInterests, newInterests, unMatchedOld, unMatchedNew);
		for (InterestAndListener oldInterest : unMatchedOld) {
			_library.cancelInterest(oldInterest.interest, oldInterest.listener);
		}
		for (Interest newInterest : unMatchedNew) {
			DataListener listener = new DataListener(_dataQueue, "Data");
			_library.expressInterest(newInterest, listener);
			newIL.add(new InterestAndListener(newInterest, listener));
		}
		_repoInterests = newIL;
	}
	
	private void getUnMatched(ArrayList<InterestAndListener> oldIn, ArrayList<Interest> newIn, 
			ArrayList<InterestAndListener> oldOut, ArrayList<Interest>newOut) {
		newOut.addAll(newIn);
		for (InterestAndListener ial : oldIn) {
			boolean matched = false;
			for (Interest interest : newIn) {
				if (ial.interest.equals(interest)) {
					newOut.remove(interest);
					matched = true;
					break;
				}
			}
			if (!matched)
				oldOut.add(ial);
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
