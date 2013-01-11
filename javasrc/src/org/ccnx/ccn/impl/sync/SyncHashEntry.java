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

import java.util.Arrays;

public class SyncHashEntry {
	protected byte[] _hash;
	
	public SyncHashEntry(byte[] hash) {
		_hash = hash;
	}
	public boolean equals(Object hash) {
		if (null == hash)
			return false;
		return Arrays.equals(((SyncHashEntry)hash)._hash, _hash);
	}
	public int hashCode() {
		return Arrays.hashCode(_hash);
	}
}
