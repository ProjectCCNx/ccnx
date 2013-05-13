package org.ccnx.ccn.impl.sync;

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

import java.util.HashMap;

/**
 * This cache hashes sync hashes to their "SyncTreeEntry" used to walk through trees of hashes.
 * Since SyncTreeEntries can not be shared across comparators since they contain information about where
 * in the walk the comparator currently is, these hashes can not be shared on a slice either.
 */
public class SyncHashCache {
	protected HashMap<SyncHashEntry, SyncTreeEntry> _hashes = new HashMap<SyncHashEntry, SyncTreeEntry>();

	/**
	 * Add a new hash to the list of ones we've seen
	 * @param hash
	 * @return new SyncTreeEntry for the hash
	 */
	public SyncTreeEntry addHash(byte[] hash, SyncNodeCache snc) {
		synchronized (this) {
			SyncTreeEntry entry = _hashes.get(new SyncHashEntry(hash));
			if (null == entry) {
				entry = new SyncTreeEntry(hash, snc);
				_hashes.put(new SyncHashEntry(hash), entry);
			}
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
	 * Remove a specific entry from the cache - needed to avoid unnecessary
	 * memory leaks
	 */
	public void removeHashEntry(SyncTreeEntry entry) {
		SyncHashEntry she = new SyncHashEntry(entry.getHash());
		synchronized (this) {
			_hashes.remove(she);
		}
	}
}
