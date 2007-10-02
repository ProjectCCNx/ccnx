package com.parc.ccn.network.daemons;

import com.parc.ccn.Library;

/**
 * Top-level wrapper for standalone repositories that
 * want to run independent of any particular application.
 * @author smetters
 *
 */
public class RepositoryDaemon extends Daemon {

	public RepositoryDaemon(String args[]) {
		super(args);
		_daemonName = "repositoryDaemon";
	}
	
	/**
	 * @param args
	 */
	public static void main(String[] args) {
		// Need to override in each subclass to make proper class.
		Daemon daemon = null;
		try {
			daemon = new Daemon(args);
			runDaemon(daemon, args);
		} catch (Exception e) {
			Library.logger().warning("Error attempting to start daemon.");
			Library.warningStackTrace(e);
			System.err.println("Error attempting to start daemon.");
		}
	}

}
