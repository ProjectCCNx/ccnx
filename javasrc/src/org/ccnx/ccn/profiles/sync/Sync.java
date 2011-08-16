package org.ccnx.ccn.profiles.sync;

import org.ccnx.ccn.profiles.CommandMarker;
import static org.ccnx.ccn.profiles.context.ServiceDiscoveryProfile.LOCALHOST_SCOPE;
import org.ccnx.ccn.protocol.ContentName;

public class Sync {

	public static final CommandMarker SYNC_MARKER = CommandMarker.commandMarker("S", "cs");
	public static final ContentName SYNC_PREFIX = new ContentName(new byte[][]
	                                      { LOCALHOST_SCOPE.getBytes(), SYNC_MARKER.getBytes() } );

	// This is the wildcard name component used in Sync filter definitions
	public static final byte[] WILDCARD = new byte[] { (byte) 255 };
}