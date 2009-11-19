package org.ccnx.ccn.utils;

import org.ccnx.ccn.config.SystemConfiguration;

public class ccngetpid {

	/**
	 * Print out the PID determined for the VM as a test.
	 * @param args
	 */
	public static void main(String[] args) {
		String pid = SystemConfiguration.getPID();
		System.out.println("PID obtained is " + ((null == pid) ? "null" : pid));
	}

}
