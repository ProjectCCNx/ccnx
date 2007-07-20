package com.parc.ccn.network.discovery;

import javax.jmdns.ServiceInfo;

public interface CCNDiscoveryListener {

	void serviceRemoved(ServiceInfo info, boolean isLocal);

	/**
	 * Note: CCNDiscovery.addDiscoveryListener will call this
	 * method *before* returning from addDiscoveryListener.
	 * The CCNDiscoveryListener should be prepared to cope
	 * with that, e.g. by spawning a subthread.
	 * @param info
	 * @param isLocal
	 */
	void serviceAdded(ServiceInfo info, boolean isLocal);

}
