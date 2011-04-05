/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2009, 2010, 2011 Palo Alto Research Center, Inc.
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

package org.ccnx.ccn.profiles.ccnd;

import java.util.HashMap;
import java.util.Map;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.KeyManager;
import org.ccnx.ccn.impl.CCNNetworkManager.NetworkProtocol;
import org.ccnx.ccn.impl.support.Log;


/**
 * Simple API to manage faces and prefix registrations all in one place.
 * 
 * A given keystore only has one of these, and it remembers what faces
 * you've created.
 * 
 * If you do not provide a handle, the default handle is used.  You will
 * need to close that handle when your program exits.
 */
public class SimpleFaceControl {
	public final static String CCN_MULTICAST_IP = "224.0.23.170";
	public final static int CCN_PORT = 9695;
	public final static int CCN_MULTICAST_PORT = 59695;

	public static SimpleFaceControl getInstance() throws CCNDaemonException {
		return getInstance(CCNHandle.getHandle());
	}

	public static SimpleFaceControl getInstance(CCNHandle handle) throws CCNDaemonException {
		SimpleFaceControl sfc;

		synchronized(_sfcMap) {
			sfc = _sfcMap.get(handle.keyManager());
			if( null == sfc ) {
				sfc = new SimpleFaceControl(handle);
				_sfcMap.put(handle.keyManager(), sfc);
			}
		}

		return sfc;
	}

	/**
	 * Open the default multicast interface on 9695 and register /
	 * @return the faceId
	 * @throws CCNDaemonException 
	 */
	public int openMulicastInterface() throws CCNDaemonException {
		return createFaceAndRegistration(NetworkProtocol.UDP, CCN_MULTICAST_IP, CCN_MULTICAST_PORT, "ccnx:/");
	}

	/**
	 * Open a unicast connection to the given host.
	 * port = 9695
	 * prefix = /
	 * @return faceId
	 * @throws CCNDaemonException 
	 */
	public int connectUdp(String host) throws CCNDaemonException {
		return createFaceAndRegistration(NetworkProtocol.UDP, host, CCN_PORT, "ccnx:/");		
	}

	/**
	 * Open a unicast connection to the given host.
	 * port = 9695
	 * prefix = /
	 * @return faceId
	 * @throws CCNDaemonException 
	 * @throws CCNDaemonException 
	 */
	public int connectTcp(String host) throws CCNDaemonException {
		return connectTcp(host, CCN_PORT);		
	}
	
	/**
	 * Open a unicast connection to the given host.
	 * prefix = /
	 * @param port the ccnd port on the remote system
	 * @return faceId
	 * @throws CCNDaemonException 
	 * @throws CCNDaemonException 
	 */
	public int connectTcp(String host, int port) throws CCNDaemonException {
		return createFaceAndRegistration(NetworkProtocol.TCP, host, port, "ccnx:/");		
	}

	/**
	 * Destroy a face (and all registrations on it)
	 * @param faceid
	 * @throws CCNDaemonException
	 */
	public void removeFace(int faceid) throws CCNDaemonException {
		_faceManager.deleteFace(faceid);
	}

	// ============================================
	private final CCNHandle _handle;
	private final PrefixRegistrationManager _prefixManager;
	private final FaceManager _faceManager;

	protected final static Map<KeyManager, SimpleFaceControl> _sfcMap = new HashMap<KeyManager,SimpleFaceControl>();

	/**
	 * Open a SimpleFaceControl.
	 * @throws CCNDaemonException 
	 */
	protected SimpleFaceControl(CCNHandle handle) throws CCNDaemonException {
		_handle = handle;
		_prefixManager = new PrefixRegistrationManager(_handle);
		_faceManager = new FaceManager(_handle);
	}

	/**
	 * Do the real work of manipulating faces and registrations.  
	 * @param proto
	 * @param host
	 * @param port
	 * @param uri
	 * @return
	 * @throws CCNDaemonException
	 */
	private int createFaceAndRegistration(NetworkProtocol proto, String host, int port, String uri) throws CCNDaemonException {
		// createFace has no state variables, and it always creates a new FaceInstance, so
		// does not need synchronization.
		
		Log.info(Log.FAC_IO, 
				String.format("Creating face: proto %s host %s port %d",
						proto.toString(),
						host,
						port));
		
		int faceID = _faceManager.createFace(proto, host, port);

		// This appears to only share a reference to CCNNetworkManager, so should be
		// thread-safe too
		Log.info(Log.FAC_IO, 
				String.format("Registering prefix: faceid %d prefix %s",
						faceID,
						uri));
		
		_prefixManager.registerPrefix(uri, faceID, 3);

		return faceID;
	}
}
