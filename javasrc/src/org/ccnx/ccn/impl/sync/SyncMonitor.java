package org.ccnx.ccn.impl.sync;

import org.ccnx.ccn.CCNSyncHandler;
import org.ccnx.ccn.io.content.ConfigSlice;

public interface SyncMonitor {
	
	public void registerCallback(CCNSyncHandler syncHandler, ConfigSlice slice);
	
	public void removeCallback(CCNSyncHandler syncHandler, ConfigSlice slice);
	
}
