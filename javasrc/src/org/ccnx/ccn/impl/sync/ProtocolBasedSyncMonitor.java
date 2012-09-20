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
 */
public class ProtocolBasedSyncMonitor extends SyncMonitor implements CCNContentHandler, CCNInterestHandler {
	protected CCNHandle _handle;
	protected HashMap<SyncHashEntry, ArrayList<SliceComparator>> _comparators = new HashMap<SyncHashEntry, ArrayList<SliceComparator>>();
	
	// This contains all received hashes. These can be shared via different slice comparators (of course they
	// would have to be on the same slice to have the same hashes).
	protected HashMap<SyncHashEntry, SyncTreeEntry> _hashes = new HashMap<SyncHashEntry, SyncTreeEntry>();
	
	public ProtocolBasedSyncMonitor(CCNHandle handle) {
		_handle = handle;
	}

	/**
	 * Register callback and output a root advise interest to start the process of looking at sync
	 * hashes to find new files. We also start looking at interests for root advises on this slice
	 * which are used to notice new hashes coming through which may contain unseen files.
	 */
	public void registerCallback(CCNSyncHandler syncHandler, ConfigSlice slice, byte[] startHash, ContentName startName) throws IOException {
		synchronized (callbacks) {
			registerCallbackInternal(syncHandler, slice);
		}
		SyncTreeEntry ste = null;
		if (null != startHash)
			ste = addHash(startHash);
		SliceComparator sc = new SliceComparator(this, syncHandler, slice, ste, startName, _handle);
		synchronized (this) {
			SyncHashEntry she = new SyncHashEntry(slice.getHash());
			ArrayList<SliceComparator> al = _comparators.get(she);
			if (null == al)
				al = new ArrayList<SliceComparator>();
			al.add(sc);
			_comparators.put(new SyncHashEntry(slice.getHash()), al);
		}
		ContentName rootAdvise = new ContentName(slice.topo, Sync.SYNC_ROOT_ADVISE_MARKER, slice.getHash());
		Interest interest = new Interest(rootAdvise);
		interest.scope(1);
		_handle.registerFilter(rootAdvise, this);
		_handle.expressInterest(interest, this);
	}

	public void removeCallback(CCNSyncHandler syncHandler, ConfigSlice slice) {
		synchronized (callbacks) {
			removeCallbackInternal(syncHandler, slice);
		}
		SyncHashEntry she = new SyncHashEntry(slice.getHash());
		synchronized (this) {
			ArrayList<SliceComparator> al = _comparators.get(she);
			SliceComparator thisOne = null;
			for (SliceComparator sc : al) {
				if (syncHandler == sc.getCallback()) {
					thisOne = sc;
					break;
				}
			}
			if (null != thisOne) {
				al.remove(thisOne);
				if (al.size() == 0)
					_comparators.remove(she);
			}
		}
	}
	
	/**
	 * Add a new hash to the list of ones we've seen
	 * @param hash
	 * @return new SyncTreeEntry for the hash
	 */
	public SyncTreeEntry addHash(byte[] hash) {
		synchronized (this) {
			SyncTreeEntry entry = _hashes.get(new SyncHashEntry(hash));
			if (null == entry) {
				entry = new SyncTreeEntry(hash);
				_hashes.put(new SyncHashEntry(hash), entry);
			} else
				entry.setPos(0);
			return entry;
		}
	}
	
	/**
	 * Get a SyncTreeEntry for a hash if there is one
	 * @param hash
	 * @return
	 */
	public SyncTreeEntry getHash(byte[] hash) {
		if (null == hash)
			return null;
		synchronized (this) {
			return _hashes.get(new SyncHashEntry(hash));
		}
	}
	
	/**
	 * Put a specific entry in for a hash
	 */
	public void putHashEntry(SyncTreeEntry entry) {
		SyncHashEntry she = new SyncHashEntry(entry.getHash());
		synchronized (this) {
			SyncTreeEntry tste = _hashes.get(she);
			if (null != tste)
				_hashes.remove(she);
			_hashes.put(she, entry);
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
		ArrayList<SliceComparator> al = null;
		synchronized (this) {
			al = _comparators.get(new SyncHashEntry(name.component(hashComponent + 1)));
		}
		if (null != al) {
			for (SliceComparator sc : al) {
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
		ArrayList<SliceComparator> al = null;
		synchronized (this) {
			al = _comparators.get(new SyncHashEntry(name.component(hashComponent + 1)));
		}
		if (Log.isLoggable(Log.FAC_SYNC, Level.INFO))
			Log.info(Log.FAC_SYNC, "Saw data from interest: hash: {0}", Component.printURI(hash));
		SyncTreeEntry ste = addHash(hash);
		if (null != al) {
			for (SliceComparator sc : al) {
				if (sc.addPending(ste)) {
					sc.checkNextRound();
					sc.kickCompare();
				}
			}
		}	
		return false;		// We're just snooping so don't say we've handled this
	}
}
