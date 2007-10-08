package com.parc.ccn.network.daemons;

import com.parc.ccn.Library;
import com.parc.ccn.network.impl.JackrabbitCCNRepository;

/**
 * Top-level wrapper for standalone repositories that
 * want to run independent of any particular application.
 * @author smetters
 *
 */
public class JackrabbitRepositoryDaemon extends Daemon {
	
	protected static class JackrabbitWorkerThread extends Daemon.WorkerThread {

		private static final long serialVersionUID = -6093561895394961537L;
		JackrabbitCCNRepository _repository = null;
		
		protected JackrabbitWorkerThread(String daemonName) {
			super(daemonName);
		}
		
		public void work() {
			// we don't need to do anything on each work loop
			// other than keep alive
		}
		
		public void initialize() {
			// we start up a jackrabbit and let it run
			_repository = new JackrabbitCCNRepository();
		}
	}

	public JackrabbitRepositoryDaemon(String args[]) {
		super(args);
		_daemonName = "jackrabbitRepositoryDaemon";
	}
	
	/**
	 * Overridden by subclasses.
	 *
	 */
	protected void usage() {
		super.usage(); // add our own if we have args
	}

	protected WorkerThread createWorkerThread() {
		return new JackrabbitWorkerThread(daemonName());
	}

	/**
	 * @param args
	 */
	public static void main(String[] args) {
		// Need to override in each subclass to make proper class.
		Daemon daemon = null;
		try {
			daemon = new JackrabbitRepositoryDaemon(args);
			runDaemon(daemon, args);
			
		} catch (Exception e) {
			System.err.println("Error attempting to start daemon.");
			Library.logger().warning("Error attempting to start daemon.");
			Library.warningStackTrace(e);
		}
	}

}
