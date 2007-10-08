package com.parc.ccn.network;

import com.parc.ccn.network.discovery.CCNDiscoveryListener;

/**
 * Collects up pointers to remote repositories it
 * can see via mDNS, and forwards queries to them.
 * Right now it does this on the level of whole content
 * items, but will move to fragments as we have them,
 * then a per-packet protocol. Also allow the option
 * to specify what things we already have in the query
 * packet.
 * 
 * @author smetters
 *
 */
public class CCNInterestManager extends DiscoveryManager implements CCNDiscoveryListener {

	/**
	 * Static singleton.
	 */
	protected static CCNInterestManager _interestManager = null;

	protected CCNInterestManager() {
		super(true, false);
	}

	public static CCNInterestManager getCCNInterestManager() { 
		if (null != _interestManager) 
			return _interestManager;
		
		return createInterestManager();
	}
	
	protected static synchronized CCNInterestManager createInterestManager() {
		if (null == _interestManager) {
			_interestManager = new CCNInterestManager();
			// Might need to add a thread to handle discovery responses...
			_interestManager.start();
		}
		return _interestManager;
	}
}
