package com.parc.ccn.network.daemons;

import com.parc.ccn.Library;
import com.parc.ccn.network.impl.JackrabbitCCNRepository;

/**
 * Top-level wrapper for standalone repositories that
 * want to run independent of any particular application.
 * 
 * Starts a local jackrabbit repository. Clients on the same machine
 * use RMI to find the repository. 
 * 
 * Run only one of these per machine.
 * @author smetters
 *
 */
public class RepositoryDaemon extends Daemon {
	
	protected static class RepositoryWorkerThread extends Daemon.WorkerThread {

		private static final long serialVersionUID = -6093561895394961537L;
		JackrabbitCCNRepository _repository = null;

		boolean _noNetwork = false;
		boolean _started = false;
		
		protected RepositoryWorkerThread(String daemonName) {
			super(daemonName);
		}
		
		public void work() {
			// Put call to start the server in the work method, as it blocks.
			if (!_started) {
				Library.logger().info("Starting interest server...");				
				_started = true;
			}
		}
		
		public void initialize() {
			// we start up a jackrabbit and let it run
			Library.logger().info("Starting Jackrabbit repository...");
			_repository = new JackrabbitCCNRepository();
			Library.logger().info("...started.");
		}
		
		public void finish() {
			_repository.shutdown();
		}
	}

	public RepositoryDaemon(String args[]) {
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
		return new RepositoryWorkerThread(daemonName());
	}

	/**
	 * @param args
	 */
	public static void main(String[] args) {
		// Need to override in each subclass to make proper class.
		Daemon daemon = null;
		try {
			daemon = new RepositoryDaemon(args);
			runDaemon(daemon, args);
			
		} catch (Exception e) {
			System.err.println("Error attempting to start daemon.");
			Library.logger().warning("Error attempting to start daemon.");
			Library.warningStackTrace(e);
		}
	}

}
