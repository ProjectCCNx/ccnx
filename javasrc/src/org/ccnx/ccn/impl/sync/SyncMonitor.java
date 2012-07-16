package org.ccnx.ccn.impl.sync;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;

import org.ccnx.ccn.CCNSyncHandler;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.ConfigSlice;

public abstract class SyncMonitor {
	
	protected static HashMap<ConfigSlice, ArrayList<CCNSyncHandler>> callbacks = new HashMap<ConfigSlice, ArrayList<CCNSyncHandler>>();
	
	/**
	 * Do the callback registration. Must be called with callbacks locked
	 * 
	 * @param syncHandler
	 * @param slice
	 */
	protected void registerCallbackInternal(CCNSyncHandler syncHandler, ConfigSlice slice) {
		ArrayList<CCNSyncHandler> cb = callbacks.get(slice);
		if (cb != null) {
			//the slice is already registered...  add handler if not there
			if (cb.contains(syncHandler)) {
				Log.fine(Log.FAC_SYNC, "the handler is already registered!");
			} else {
				cb.add(syncHandler);
				Log.fine(Log.FAC_SYNC, "the handler has been added!");
			}
		} else {
			//the slice is not there...  adding now!
			cb = new ArrayList<CCNSyncHandler>();
			cb.add(syncHandler);
			callbacks.put(slice, cb);
		}
	}
	
	/**
	 * Remove the callback. Must be called with callbacks locked
	 * @param syncHandler
	 * @param slice
	 */
	protected void removeCallbackInternal(CCNSyncHandler syncHandler, ConfigSlice slice) {
		ArrayList<CCNSyncHandler> cb = callbacks.get(slice);
		if (cb.contains(syncHandler)) {
			Log.fine(Log.FAC_SYNC, "found the callback to remove");
			cb.remove(syncHandler);
			if (cb.isEmpty()) {
				//no callbacks left for the slice, go ahead and remove it.
				callbacks.remove(slice);
			}
		}
	}
	
	public abstract void registerCallback(CCNSyncHandler syncHandler, ConfigSlice slice) throws IOException;
	
	public abstract void removeCallback(CCNSyncHandler syncHandler, ConfigSlice slice) throws IOException;
	
}
