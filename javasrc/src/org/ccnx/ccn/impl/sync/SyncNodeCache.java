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

import java.util.HashMap;

import org.ccnx.ccn.io.content.SyncNodeComposite;

public class SyncNodeCache {
	
	protected HashMap<SyncHashEntry, SyncNodeComposite> _nodes = new HashMap<SyncHashEntry, SyncNodeComposite>();

	public void putNode(SyncNodeComposite node) {
		synchronized (this) {
			_nodes.put((new SyncHashEntry(node.getHash())), node);
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
			return _nodes.get(new SyncHashEntry(hash));
		}
	}
}
