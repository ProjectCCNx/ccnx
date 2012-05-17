package org.ccnx.ccn;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;

import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.impl.sync.FileBasedSyncMonitor;
import org.ccnx.ccn.impl.sync.SyncMonitor;
import org.ccnx.ccn.io.content.ConfigSlice;
import org.ccnx.ccn.io.content.ConfigSlice.Filter;
import org.ccnx.ccn.protocol.ContentName;

public class CCNSync {
	
	protected SyncMonitor syncMon = null;
	
	public void startSync(CCNHandle handle, ConfigSlice syncSlice, CCNSyncHandler syncCallback) throws IOException, ConfigurationException{
		try {
			syncSlice.checkAndCreate(handle);
			if (syncMon == null)
				syncMon = new FileBasedSyncMonitor();
			syncMon.registerCallback(syncCallback, syncSlice);
		} catch (ConfigurationException e){
			throw e;
		} catch (Exception e) {
			Log.warning(Log.FAC_REPO, "Error when starting sync for slice: {0}", syncSlice);
			throw new IOException("Unable to create sync slice: "+e.getMessage());
		}
	}
		
	public ConfigSlice startSync(CCNHandle handle, ContentName topo, ContentName prefix, Collection<ContentName> filters, CCNSyncHandler syncCallback) throws IOException, ConfigurationException{
		if (handle == null)
			handle = CCNHandle.getHandle();
		Collection<Filter> f = new ArrayList<Filter>();
		if (filters!=null) {
			for (ContentName cn: filters)
				f.add(new Filter(cn));
		}
		try {
			ConfigSlice slice = ConfigSlice.checkAndCreate(topo, prefix, f, handle);
			if (syncMon == null)
				syncMon = new FileBasedSyncMonitor();
			syncMon.registerCallback(syncCallback, slice);
			return slice;
		} catch (ConfigurationException e) {
			throw e;
		} catch (Exception e) {
			Log.warning(Log.FAC_REPO, "Error when starting sync for slice with prefix: {0}", prefix);
			throw new IOException("Unable to create sync slice: "+e.getMessage());
		}
	}
	
	public ConfigSlice startSync(ContentName topo, ContentName prefix, Collection<ContentName> filters, CCNSyncHandler syncCallback) throws IOException, ConfigurationException{
		return startSync(null, topo, prefix, filters, syncCallback);
	}
	
	public void stopSync(CCNSyncHandler syncHandler, ConfigSlice syncSlice){
		//will unregister the callback here
		syncMon.removeCallback(syncHandler, syncSlice);
	}

}
