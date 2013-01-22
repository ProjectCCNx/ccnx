/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2012 Palo Alto Research Center, Inc.
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
import java.util.logging.Level;

import org.ccnx.ccn.CCNContentHandler;
import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.CCNInterestHandler;
import org.ccnx.ccn.CCNSyncHandler;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.ConfigSlice;
import org.ccnx.ccn.profiles.sync.Sync;
import org.ccnx.ccn.protocol.Component;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Interest;

/**
 * Snoops on the sync protocol to report new files seen by sync on a slice to a registered handler
 * To do this we use "comparators" which compare a sync tree of "what we already have" to a sync tree
 * from sync (@see {@link SliceComparator}). We can have multiple monitors on the same slice and initially
 * these will each need their own comparator since the starting point of what we will monitor depends on
 * user parameters. However once we have completed an initial round of comparison on a slice, we will have
 * created a tree of "what we have" which will be the same for every comparator, and since all input from
 * sync will also be identical, we could end up with multiple comparators all doing exactly the same thing.
 * To avoid this, we introduce the "ComparatorGroup" which is per slice and contains a "lead comparator" and
 * other "active comparators". The first comparator created on a slice becomes the "lead comparator" and
 * subsequent ones are added to the active comparators. After a comparator which is not a lead comparator has
 * completed its first round, its callback is added to the lead comparators callbacks and that comparator
 * becomes deactivated. Except in the case noted below, this takes place within the comparator since it
 * alone knows when it has completed a round.
 * 
 * Note also that all comparators can share the same node data but must keep their own version of where they
 * are in the treewalk through the data. Node data is shared in _snc below.
 */
public class ProtocolBasedSyncMonitor extends SyncMonitor implements CCNContentHandler, CCNInterestHandler {
	private class SliceData {
		protected SyncNodeCache _snc = new SyncNodeCache();
		SliceComparator _leadComparator;
		ArrayList<SliceComparator> _activeComparators;
		
		public SliceData() {
			_activeComparators = new ArrayList<SliceComparator>();
		}
		
		public void setLeadComparator(SliceComparator sc) {
			_leadComparator = sc;
		}
	}
	protected CCNHandle _handle;
	protected HashMap<SyncHashEntry, SliceData> _sliceData = new HashMap<SyncHashEntry, SliceData>();
	
	public ProtocolBasedSyncMonitor(CCNHandle handle) {
		_handle = handle;
	}

	/**
	 * Register callback and output a root advise interest to start the process of looking at sync
	 * hashes to find new files. We also start looking at interests for root advises on this slice
	 * which are used to notice new hashes coming through which may contain unseen files.
	 */
	public void registerCallback(CCNSyncHandler syncHandler, ConfigSlice slice, byte[] startHash, ContentName startName) throws IOException {
		boolean sendRootAdviseRequest = true;
		synchronized (this) {
			SyncHashEntry she = new SyncHashEntry(slice.getHash());
			SliceData sd = _sliceData.get(she);
			if (null != sd && null != startHash && startHash.length == 0) {
				// For 0 length hash (== start with current hash) we can just add the handler to the leadComparator if there is one since it should
				// already know the latest hash
				sd._leadComparator.addCallback(syncHandler, startHash);
				sendRootAdviseRequest = false;
			} else {
				boolean newData = false;
				if (null == sd) {
					newData = true;
					sd = new SliceData();
				}
				SliceComparator sc = new SliceComparator(newData ? null : sd._leadComparator, sd._snc, syncHandler, slice, startHash, startName, _handle);
				if (newData)
					sd.setLeadComparator(sc);
				sd._activeComparators.add(sc);
				_sliceData.put(she, sd);
			}
		}
		if (sendRootAdviseRequest) {
			ContentName rootAdvise = new ContentName(slice.topo, Sync.SYNC_ROOT_ADVISE_MARKER, slice.getHash());
			Interest interest = new Interest(rootAdvise);
			interest.scope(1);
			_handle.registerFilter(rootAdvise, this);
			_handle.expressInterest(interest, this);
		}
	}

	/**
	 * We don't want to shutdown just because there are no more callbacks because if someone asks
	 * for a new sync starting at the current hash, having something running is the best way to
	 * find the current hash. If someone wants to shutdown, they should explicitly ask for it.
	 */
	public void removeCallback(CCNSyncHandler syncHandler, ConfigSlice slice) {
		ArrayList<SliceComparator> removes = new ArrayList<SliceComparator>();
		SyncHashEntry she = new SyncHashEntry(slice.getHash());
		synchronized (this) {
			SliceData sd = _sliceData.get(she);
			if (null != sd) {
				for (SliceComparator sc : sd._activeComparators) {
					sc.removeCallback(syncHandler);
					if (sc != sd._leadComparator) {
						if (sc.shutdownIfUseless())
							removes.add(sc);
					}
				}
				sd._activeComparators.removeAll(removes);
			}
		}
	}
	
	public void shutdown(ConfigSlice slice) {
		if (Log.isLoggable(Log.FAC_SYNC, Level.INFO))
			Log.info(Log.FAC_SYNC, "Shutting down sync on slice: {0}", slice.prefix);
		SyncHashEntry she = new SyncHashEntry(slice.getHash());
		synchronized (this) {
			SliceData sd = _sliceData.get(she);
			if (null != sd) {
				// remove all callbacks - therefore shutting down all current comparators except
				// the lead
				for (SliceComparator sc : sd._activeComparators) {
					ArrayList<CCNSyncHandler> callbacks = sc.getCallbacks();
					ArrayList<CCNSyncHandler> shutdownCallbacks = new ArrayList<CCNSyncHandler>();
					shutdownCallbacks.addAll(callbacks);
					for (CCNSyncHandler syncHandler : shutdownCallbacks)
						sc.removeCallback(syncHandler);
				}
				// Now shutdown the lead
				sd._leadComparator.shutdownIfUseless();
				_sliceData.remove(she);
			}
		}
	}
	
	/**
	 * Output interest to request a node
	 * @param hash
	 * @return
	 * @throws IOException 
	 */
	public static boolean requestNode(ConfigSlice slice, byte[] hash, CCNHandle handle, CCNContentHandler handler) throws SyncException {
		boolean ret = false;
		Interest interest = new Interest(new ContentName(slice.topo, Sync.SYNC_NODE_FETCH_MARKER, slice.getHash(), hash));
		interest.scope(1);	
		if (Log.isLoggable(Log.FAC_SYNC, Level.FINE))
			Log.fine(Log.FAC_TEST, "Requesting node for hash: {0}", interest.name());
		try {
			handle.expressInterest(interest, handler);
			ret = true;
		} catch (IOException e) {
			Log.warning(Log.FAC_SYNC, "Node request failed: {0}", e.getMessage());
			throw new SyncException(e.getMessage());
		}
		return ret;
	}
	
	/**
	 * Start sync hash compare process after receiving content back from the
	 * original root advise interest.
	 */
	public Interest handleContent(ContentObject data, Interest interest) {
		ContentName name = data.name();
		int hashComponent = name.containsWhere(Sync.SYNC_ROOT_ADVISE_MARKER);
		if (hashComponent < 0 || name.count() < hashComponent + 3) {
			if (Log.isLoggable(Log.FAC_SYNC, Level.INFO))
				Log.info(Log.FAC_SYNC, "Received incorrect content in sync: {0}", name);
			return null;
		}
		if (Log.isLoggable(Log.FAC_SYNC, Level.INFO))
			Log.info(Log.FAC_SYNC, "Saw new content from sync: {0}", name);
		SliceData sd = null;
		synchronized (this) {
			sd = _sliceData.get(new SyncHashEntry(name.component(hashComponent + 1)));
		}
		if (null != sd) {
			for (SliceComparator sc : sd._activeComparators) {
				synchronized (sc) {
					sc.addPendingContent(data.content());
					sc.checkNextRound();
					sc.kickCompare();
				}
			}
		}
		return null;
	}
	
	/**
	 * We have seen a new hash. Feed it into the compare process to discover possible new
	 * unseen files.
	 */
	public boolean handleInterest(Interest interest) {
		Log.info("Saw an interest for {0}", interest.name());
		ContentName name = interest.name();
		int hashComponent = name.containsWhere(Sync.SYNC_ROOT_ADVISE_MARKER);
		if (hashComponent < 0 || name.count() < hashComponent + 3) {
			return false;
		}
		byte[] hash = name.component(hashComponent + 2);
		if (hash.length == 0)
			return false;
		SliceData cg = null;
		synchronized (this) {
			cg = _sliceData.get(new SyncHashEntry(name.component(hashComponent + 1)));
			if (Log.isLoggable(Log.FAC_SYNC, Level.INFO))
				Log.info(Log.FAC_SYNC, "Saw data from interest: hash: {0}", Component.printURI(hash));
			if (null != cg) {
				for (SliceComparator sc : cg._activeComparators) {
					SyncTreeEntry ste = sc.getHashCache().addHash(hash, sc.getNodeCache());
					if (sc == cg._leadComparator || !sc.shutdownIfUseless()) {
						if (sc.addPending(ste)) {
							sc.checkNextRound();
							sc.kickCompare();
						}
					}
				}
			}
		}	
		return false;		// We're just snooping so don't say we've handled this
	}

	public SyncNodeCache getNodeCache(ConfigSlice slice) {
		SyncHashEntry she = new SyncHashEntry(slice.getHash());
		synchronized (this) {
			SliceData sd = _sliceData.get(she);
			if (null == sd)
				return null;
			return sd._snc;
		}
	}
}
