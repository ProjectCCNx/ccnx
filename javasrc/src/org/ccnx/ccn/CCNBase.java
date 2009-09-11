/**
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

package org.ccnx.ccn;

import java.io.IOException;

import org.ccnx.ccn.impl.CCNNetworkManager;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Interest;


/**
 * @author smetters, rasmussen
 *
 */
public class CCNBase {
	
	public final static int NO_TIMEOUT = -1;
	
	/**
	 * Allow separate per-instance to control reading/writing within
	 * same app. Get default one if use static VM instance of StandardCCNLibrary,
	 * but if you make a new instance, get a new connection to ccnd.
	 */
	protected CCNNetworkManager _networkManager = null;
	
	public CCNNetworkManager getNetworkManager() { 
		if (null == _networkManager) {
			synchronized(this) {
				if (null == _networkManager) {
					try {
						_networkManager = new CCNNetworkManager();
					} catch (IOException ex){
						Log.warning("IOException instantiating network manager: " + ex.getMessage());
						ex.printStackTrace();
						_networkManager = null;
					}
				}
			}
		}
		return _networkManager;
	}
	
	/**
	 * Implementation of CCNBase.put.
	 * @param co
	 * @return
	 * @throws IOException
	 * @throws InterruptedException
	 */
	public ContentObject put(ContentObject co) throws IOException {
		boolean interrupted = false;
		do {
			try {
				Log.finest("Putting content on wire: " + co.name());
				return getNetworkManager().put(co);
			} catch (InterruptedException e) {
				interrupted = true;
			}
		} while (interrupted);
		return null;
	}
	
	/**
	 * Implementation of CCNBase get
	 * @param interest
	 * @param timeout
	 * @return
	 * @throws IOException
	 * @throws InterruptedException
	 */
	public ContentObject get(Interest interest, long timeout) throws IOException {
		while (true) {
			try {
				return getNetworkManager().get(interest, timeout);
			} catch (InterruptedException e) {}
		}
	}
	
	/**
	 * Register a standing interest filter with callback to receive any 
	 * matching interests seen
	 */
	public void registerFilter(ContentName filter,
			CCNFilterListener callbackListener) {
		getNetworkManager().setInterestFilter(this, filter, callbackListener);
	}
	
	/**
	 * Unregister a standing interest filter
	 */
	public void unregisterFilter(ContentName filter,
			CCNFilterListener callbackListener) {
		getNetworkManager().cancelInterestFilter(this, filter, callbackListener);		
	}
	
	/**
	 * Query, or express an interest in particular
	 * content. This request is sent out over the
	 * CCN to other nodes. On any results, the
	 * callbackListener if given, is notified.
	 * Results may also be cached in a local repository
	 * for later retrieval by get().
	 * Get and expressInterest could be implemented
	 * as a single function that might return some
	 * content immediately and others by callback;
	 * we separate the two for now to simplify the
	 * interface.
	 * 
	 * Pass it on to the CCNInterestManager to
	 * forward to the network. Also express it to the
	 * repositories we manage, particularly the primary.
	 * Each might generate their own CCNQueryDescriptor,
	 * so we need to group them together.
	 */
	public void expressInterest(
			Interest interest,
			CCNInterestListener listener) throws IOException {
		// Will add the interest to the listener.
		getNetworkManager().expressInterest(this, interest, listener);
	}

	/**
	 * Cancel this interest. 
	 * @param interest
	 * @param listener Used to distinguish the same interest
	 * 	requested by more than one listener.
	 * @throws IOException
	 */
	public void cancelInterest(Interest interest, CCNInterestListener listener) {
		getNetworkManager().cancelInterest(this, interest, listener);
	}
}
