package com.parc.ccn.network;

import com.parc.ccn.network.discovery.CCNDiscoveryListener;

public class InterestManager extends DiscoveryManager implements CCNDiscoveryListener {

	/**
	 * Static singleton.
	 */
	protected static InterestManager _interestManager = null;

	protected InterestManager() {
		super(true, false);
	}

	public static InterestManager getCCNInterestManager() { 
		if (null != _interestManager) 
			return _interestManager;
		
		return createInterestManager();
	}
	
	protected static synchronized InterestManager createInterestManager() {
		if (null == _interestManager) {
			_interestManager = new InterestManager();
			// Might need to add a thread to handle discovery responses...
			_interestManager.start();
		}
		return _interestManager;
	}
}
