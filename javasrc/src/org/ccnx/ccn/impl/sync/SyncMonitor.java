/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2012, 2013 Palo Alto Research Center, Inc.
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License version 2.1
 * as published by the Free Software Foundation.
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details. You should have received
 * a copy of the GNU Lesser General Public License along with this library;
 * if not, write to the Free Software Foundation, Inc., 51 Franklin Street,
 * Fifth Floor, Boston, MA 02110-1301 USA.
 */

package org.ccnx.ccn.impl.sync;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;

import org.ccnx.ccn.CCNSyncHandler;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.ConfigSlice;
import org.ccnx.ccn.protocol.ContentName;

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
	
	/**
	 * Register a callback for new names seen by sync on the argument slice
	 * 
	 * @param syncHandler
	 * @param slice
	 * @throws IOException
	 */
	public abstract void registerCallback(CCNSyncHandler syncHandler, ConfigSlice slice, byte[] startHash, ContentName startName) throws IOException;
	
	/**
	 * Remove the argument handler for this slice
	 * 
	 * @param syncHandler
	 * @param slice
	 * @throws IOException
	 */
	public abstract void removeCallback(CCNSyncHandler syncHandler, ConfigSlice slice) throws IOException;
	
	/**
	 * Shutdown all sync watching for this slice
	 * @param slice
	 * @throws IOException
	 */
	public abstract void shutdown(ConfigSlice slice);
	
	/**
	 * Get the node cache for this slice
	 * @param slice
	 * @throws IOException
	 */
	public abstract SyncNodeCache getNodeCache(ConfigSlice slice);
}
