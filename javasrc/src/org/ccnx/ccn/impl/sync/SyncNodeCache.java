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

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.concurrent.Semaphore;

import org.ccnx.ccn.io.content.SyncNodeComposite;

/**
 * Nodes can be cached by hash across different comparators. We use WeakReferences to avoid accidentally caching nodes that
 * no longer have any real referents.
 * 
 * Since we only need to request nodes once per slice, the pending mechanism should be global
 */
public class SyncNodeCache {
	
	private HashMap<SyncHashEntry, Semaphore> _hashesPending = new HashMap<SyncHashEntry, Semaphore>();
	
	protected HashMap<SyncHashEntry, WeakReference<SyncNodeComposite>> _nodes = new HashMap<SyncHashEntry, WeakReference<SyncNodeComposite>>();

	public void putNode(SyncNodeComposite node) {
		synchronized (this) {
			WeakReference<SyncNodeComposite> wr = new WeakReference<SyncNodeComposite>(node);
			_nodes.put((new SyncHashEntry(node.getHash())), wr);
		}
	}
	
	/**
	 * Get a SyncTreeEntry for a hash if there is one
	 * @param hash
	 * @return
	 */
	public SyncNodeComposite getNode(byte[] hash) {
		if (null == hash)
			return null;
		synchronized (this) {
			WeakReference<SyncNodeComposite> wr = _nodes.get(new SyncHashEntry(hash));
			if (null == wr)
				return null;
			return wr.get();
		}
	}
	
	public Semaphore pending(byte[] hash) {
		Semaphore sem = null;
		synchronized (this) {
			SyncHashEntry she = new SyncHashEntry(hash);
			sem = _hashesPending.get(she);
			if (null == sem) {
				sem = new Semaphore(1);
				_hashesPending.put(she, sem);
			}
			return sem;
		}
	}
	
	public void clearPending(byte[] hash) {
		synchronized (this) {
			SyncHashEntry she = new SyncHashEntry(hash);
			Semaphore sem = _hashesPending.get(she);
			if (null != sem) {
				sem.release();
				_hashesPending.remove(she);
			}
		}
	}
}
