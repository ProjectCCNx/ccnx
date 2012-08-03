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
import org.ccnx.ccn.io.content.SyncNodeComposite;
import org.ccnx.ccn.profiles.sync.Sync;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Interest;

public class ProtocolBasedSyncMonitor extends SyncMonitor implements CCNContentHandler, CCNInterestHandler {
	protected CCNHandle _handle;
	protected HashMap<SyncHashEntry, ArrayList<SliceComparator>> _comparators = new HashMap<SyncHashEntry, ArrayList<SliceComparator>>();
	
	// received hashes can be kept in common
	protected HashMap<SyncHashEntry, SyncTreeEntry> _hashes = new HashMap<SyncHashEntry, SyncTreeEntry>();
	
	public ProtocolBasedSyncMonitor(CCNHandle handle) {
		_handle = handle;
	}

	public void registerCallback(CCNSyncHandler syncHandler, ConfigSlice slice) throws IOException {
		synchronized (callbacks) {
			registerCallbackInternal(syncHandler, slice);
		}
		SliceComparator sc = new SliceComparator(this, syncHandler, slice, _handle);
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
	
	public SyncTreeEntry addHash(byte[] hash, SyncNodeComposite snc) {
		if (null == hash && null == snc)
			return null;	// Shouldn't happen
		if (null == hash)
			hash = snc.getHash();
		synchronized (this) {
			SyncTreeEntry entry = _hashes.get(new SyncHashEntry(hash));
			if (null == entry) {
				if (null == snc)
					entry = new SyncTreeEntry(hash);
				else
					entry = new SyncTreeEntry(snc);
				_hashes.put(new SyncHashEntry(hash), entry);
			}
			return entry;
		}
	}
	
	public SyncTreeEntry getHash(byte[] hash) {
		if (null == hash)
			return null;
		synchronized (this) {
			return _hashes.get(new SyncHashEntry(hash));
		}
	}

	public Interest handleContent(ContentObject data, Interest interest) {
		ContentName name = data.name();
		int hashComponent = name.containsWhere(Sync.SYNC_ROOT_ADVISE_MARKER);
		if (hashComponent < 0 || name.count() < hashComponent + 3) {
			if (Log.isLoggable(Log.FAC_SYNC, Level.INFO))
				Log.info(Log.FAC_SYNC, "Received incorrect content in sync: {0}", name);
			return null;
		}
		Log.info(Log.FAC_SYNC, "Saw new content from sync: {0}", name);
		ArrayList<SliceComparator> al = null;
		synchronized (this) {
			al = _comparators.get(new SyncHashEntry(name.component(hashComponent + 1)));
		}
		if (null != al) {
			for (SliceComparator sc : al) {
				sc.addPending(data.content());
			}
		}
		return null;
	}
	
	public boolean handleInterest(Interest interest) {
Log.info("Saw an interest for {0}", interest.name());
		Interest newInterest = new Interest(interest.name());
		newInterest.scope(1);
		try {
			_handle.expressInterest(newInterest, this);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return true;
	}
}
