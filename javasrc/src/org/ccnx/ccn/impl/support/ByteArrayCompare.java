/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008, 2009 Palo Alto Research Center, Inc.
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

package org.ccnx.ccn.impl.support;

import java.util.Comparator;
import java.io.Serializable;

/**
 * Needed to sort byte arrays to build exclude filters
 */
public class ByteArrayCompare implements Serializable, Comparator<byte[] >{

	/**
	 * 
	 */
	private static final long serialVersionUID = -5953709076351116596L;

	/**
	 * 
	 */

	public int compare(byte[] o1, byte[] o2) {
		return DataUtils.compare(o1, o2);
	}
}
