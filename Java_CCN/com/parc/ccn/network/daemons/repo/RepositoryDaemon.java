package com.parc.ccn.network.daemons.repo;

import java.io.IOException;
import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.parc.ccn.Library;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.MalformedContentNameStringException;
import com.parc.ccn.data.query.CCNFilterListener;
import com.parc.ccn.data.query.CCNInterestListener;
import com.parc.ccn.data.query.Interest;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.network.daemons.Daemon;

/**
 * 
 * @author rasmusse
 *
 */

public class RepositoryDaemon extends Daemon {
	
	private Repository _repo = null;
	private ConcurrentLinkedQueue<ContentObject> _dataQueue = new ConcurrentLinkedQueue<ContentObject>();
	private CCNLibrary _library = null;
	private boolean _started = false;
	
	private class ContentListener implements CCNInterestListener {
		public Interest handleContent(ArrayList<ContentObject> results,
				Interest interest) {
			_dataQueue.addAll(results);
			return interest;
		}
	}
	
	private class FilterListener implements CCNFilterListener {

		public int handleInterests(ArrayList<Interest> interests) {
			return 0;
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
					if (data != null)
						try {
							_repo.saveContent(data);
						} catch (RepositoryException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}
				} while (data != null) ;
			}
		}
		
		/**
		 * Express our interest in "everything" and get all interests forwarded
		 * from the ccnd
		 */
		public void initialize() {
			
			FilterListener filterListener = new FilterListener();
			ContentListener contentListener = new ContentListener();
			try {
				Interest repoInterest = new Interest(ContentName.fromNative("/"));
				repoInterest.answerOriginKind(0);
				_library.expressInterest(repoInterest, contentListener);
				_library.registerFilter(ContentName.fromNative("/"), filterListener);
			} catch (MalformedContentNameStringException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		
		public void finish() {
			//_repository.shutdown();
		}
	}
	
	public RepositoryDaemon() {
		super();
		_daemonName = "repository";
		Library.logger().info("Starting interest server...");				
		_started = true;
		
		try {
			_library = CCNLibrary.open();
			_repo = new RFSImpl();
		} catch (Exception e1) {
			e1.printStackTrace();
			System.exit(0);
		} 
	}
	
	public void initialize(String[] args, Daemon daemon) {
		try {
			_repo.initialize(args);
		} catch (InvalidParameterException ipe) {
			usage();
		} catch (RepositoryException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	
	protected void usage() {
		try {
			System.out.println("usage: " + this.getClass().getName() + " [ [-root <directory>] -start | -stop | -interactive]");
		} catch (Exception e) {
			e.printStackTrace();
		}
		System.exit(0);
	}

	protected WorkerThread createWorkerThread() {
		return new RepositoryWorkerThread(daemonName());
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
