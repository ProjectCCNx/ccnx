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
	
	JackrabbitCCNRepository _repository = null;

	public JackrabbitRepositoryDaemon(String args[]) {
		super(args);
		_daemonName = "jackrabbitRepositoryDaemon";
	}
	
	public void work() {
		// we don't need to do anything on each work loop
		// other than keep alive
	}
	
	public void initialize() {
		// we start up a jackrabbit and let it run
		_repository = new JackrabbitCCNRepository();
	}

	/**
	 * Overridden by subclasses.
	 *
	 */
	protected static void usage() {
		try {
			System.out.println("usage: " + JackrabbitRepositoryDaemon.class.getName() + " [-start | -stop | <interactive>]");
		} catch (Exception e) {
			e.printStackTrace();
		}
		System.exit(0);
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
			Library.logger().warning("Error attempting to start daemon.");
			Library.warningStackTrace(e);
			System.err.println("Error attempting to start daemon.");
		}
	}

}
