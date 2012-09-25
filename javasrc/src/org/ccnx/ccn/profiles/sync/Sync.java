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

package org.ccnx.ccn.profiles.sync;

import org.ccnx.ccn.profiles.CommandMarker;
import static org.ccnx.ccn.profiles.context.ServiceDiscoveryProfile.LOCALHOST_SCOPE;
import org.ccnx.ccn.protocol.ContentName;

public class Sync {
	
	public static final int SLICE_VERSION = 20110614;
	public static final int SYNC_VERSION = 20110614;

	public static final CommandMarker SYNC_SLICE_MARKER = CommandMarker.commandMarker(CommandMarker.SYNC_NAMESPACE, "cs");
	public static final CommandMarker SYNC_ROOT_ADVISE_MARKER = CommandMarker.commandMarker(CommandMarker.SYNC_NAMESPACE, "ra");
	public static final CommandMarker SYNC_NODE_FETCH_MARKER = CommandMarker.commandMarker(CommandMarker.SYNC_NAMESPACE, "nf");
	public static final ContentName SYNC_SLICE_PREFIX = new ContentName(new byte[][]
	                                      { LOCALHOST_SCOPE.getBytes(), SYNC_SLICE_MARKER.getBytes() } );

	// This is the wildcard name component used in Sync filter definitions
	public static final byte[] WILDCARD = new byte[] { (byte) 255 };
}