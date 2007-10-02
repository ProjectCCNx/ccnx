package com.parc.ccn.network;

import java.io.IOException;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.logging.Level;

import javax.jmdns.ServiceInfo;

import com.parc.ccn.Library;
import com.parc.ccn.data.query.CCNQueryDescriptor;
import com.parc.ccn.network.discovery.CCNDiscovery;
import com.parc.ccn.network.discovery.CCNDiscoveryListener;

public class InterestManager extends DiscoveryManager implements CCNDiscoveryListener {

	/**
	 * Static singleton.
	 */
	protected static InterestManager _interestManager = null;

	protected InterestManager() {
		super(true, false);
	}

	public static InterestManager getCCNRepositoryManager() { 
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
