package com.parc.ccn.network.daemons;

import org.acplt.oncrpc.OncRpcException;

import com.parc.ccn.Library;
import com.parc.ccn.network.CCNInterestServer;
import com.parc.ccn.network.impl.JackrabbitCCNRepository;

/**
 * Top-level wrapper for standalone repositories that
 * want to run independent of any particular application.
 * 
 * Starts a local jackrabbit repository, and a RPC server
 * to handle requests from a transport agent.
 * @author smetters
 *
 */
public class RepositoryDaemon extends Daemon {
	
	protected static class RepositoryWorkerThread extends Daemon.WorkerThread {

		private static final long serialVersionUID = -6093561895394961537L;
		JackrabbitCCNRepository _repository = null;
		CCNInterestServer _interestServer = null;
		
		protected RepositoryWorkerThread(String daemonName) {
			super(daemonName);
		}
		
		public void work() {
			// we don't need to do anything on each work loop
			// other than keep alive
		}
		
		public void initialize() {
			// we start up a jackrabbit and let it run
			Library.logger().info("Starting Jackrabbit repository...");
			_repository = new JackrabbitCCNRepository();
			try {
				Library.logger().info("Creating interest server..");
				_interestServer = 
					new CCNInterestServer(_repository);

				Library.logger().info("Starting interest server...");				
			//	_interestServer.run(_interestServer.transports); // starts with no portmap, expects direct connects
				try {
					_interestServer.run(); // starts using portmap

				} catch (OncRpcException oe) {
					Library.logger().warning("Cannot register service with portmapper. Continuing without network connectivity.");
					
				}
			} catch (Exception e) {
				Library.logger().warning("Exception starting interest server: " + e.getMessage());
				Library.warningStackTrace(e);
			}
		}
		
		public void finish() {
			_interestServer.stopRpcProcessing();
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
