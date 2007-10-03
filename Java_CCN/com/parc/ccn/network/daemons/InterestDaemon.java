package com.parc.ccn.network.daemons;

import com.parc.ccn.Library;


/**
 * Interface to low-level daemon that communicates
 * interests to other nodes and returns content.
 * Eventually, use Michael's daemon. Midrange,
 * maybe sync repositories. Short term, never send 
 * any interests or get any responses.
 * @author smetters
 *
 */

public class InterestDaemon extends Daemon {

	public InterestDaemon(String args[]) {
		super(args);
		_daemonName = "interestDaemon";
	}

	public void work() {
	}

	public void initialize() {
	}

	/**
	 * Overridden by subclasses.
	 *
	 */
	protected void usage() {
		super.usage(); // add our own if we have args
	}

	/**
	 * @param args
	 */
	public static void main(String[] args) {
		// Need to override in each subclass to make proper class.
		Daemon daemon = null;
		try {
			daemon = new InterestDaemon(args);
			runDaemon(daemon, args);
		} catch (Exception e) {
			Library.logger().warning("Error attempting to start daemon.");
			Library.warningStackTrace(e);
			System.err.println("Error attempting to start daemon.");
		}
	}
}
