/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2011 Palo Alto Research Center, Inc.
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

package org.ccnx.ccn.profiles.versioning;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.CCNInterestListener;
import org.ccnx.ccn.protocol.CCNTime;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Interest;

/**
 * Given a base name, retrieve all versions.  We have maintained a similar method
 * naming to CCNHandle (expressInterest, cancelInterest, close), except we take
 * a ContentName input instead of an Interest input.  A future extension might be to
 * take an Interest to make it more drop-in replacement for existing CCNHandle methods.
 * 
 * This object is meant to be private for one application, which provides the
 * CCNHandle to use.  It may be shared between multiple threads.  The way the
 * retry expression works is intended for one app to use that has an understanding
 * of its needs.
 * 
 * The class will:
 * - return all available versions of a base name, without duplicates
 * - allow the user to supply a list of versions to exclude (e.g. they
 *   have already been seen by the application)
 * - allow the user to supply a hard-cutoff starting time
 * - allow the user to supply the interest re-expression rate,
 *   which may be very slow and use our own timer not the one built in
 *   to ccnx.  
 *   
 * Because the list of excluded version can be very long, this
 * class manages expressing multiple interests.
 * 
 * To make the interests aggregate at ccnd, the split boundaries
 * are quantized so two different nodes have a chance of getting
 * their interests identical when asking for identical things.
 *   
 * The re-expression time works like this:
 * 1) issue an Interest with normal timeout
 * 2) As long as we get data, keep re-expressing as normal
 * 3) When we get a timeout, use the custom timer to re-express (e.g. try
 *    again in an hour).  Repeat from step #1.
 *    
 * All the work is done down in the inner class BasenameState, which is the state
 * stored per basename and tracks the interests issued for that basename.
 */
public class VersioningInterest {
	
	// ==============================================================================
	// Public API

	/**
	 * @param handle
	 * @param listener
	 */
	public VersioningInterest(CCNHandle handle) {
		_handle = handle;
	}
	
	/**
	 * Express an interest for #name.  We will assume that #name does not
	 * include a version, and we construct an interest that will only match
	 * 3 additional components to #name (version/segment/digest).
	 * 
	 * When the default CCN timeout is exceeded, we stop responding.
	 * 
	 * If there is already an interest for the same (name, listener), no action is taken.
	 * 
	 * @param name
	 * @param listener
	 */
	public void expressInterest(ContentName name, CCNInterestListener listener) {
		expressInterest(name, listener, 0);
	}
	
	/**
	 * As above, but after we get a CCN timeout for an interest, we will sleep and
	 * re-try the interest after #retrySeconds.
	 * 
	 * If there is already an interest for the same (name, listener), the retrySeconds is updated.
	 * 
	 * If retrySeconds is non-positive, no retry is done.
	 * 
	 * retrySeconds is global for #name, not specific per listener.  The last set will win.
	 * 
	 * @param name
	 * @param listener
	 * @param retrySeconds Must be positive, and should be longer than the CCN timeout.
	 */
	public void expressInterest(ContentName name, CCNInterestListener listener, int retrySeconds) {
		addInterest(name, listener, retrySeconds, null, 0);
	}

	/**
	 * As above, and provide a set of versions to exclude
	 * @param name
	 * @param listener
	 * @param retrySeconds
	 * @param exclusions
	 */
	public void expressInterest(ContentName name, CCNInterestListener listener, int retrySeconds, Set<Long> exclusions) {
		addInterest(name, listener, retrySeconds, exclusions, 0);
	}
	
	/**
	 * As above, and provide a set of versions to exclude and a hard floor startingVersion, any version
	 * before that will be ignored.
	 * 
	 * @param name
	 * @param listener
	 * @param retrySeconds
	 * @param exclusions
	 * @param startingVersion the minimum version to include
	 */
	public void expressInterest(ContentName name, CCNInterestListener listener, int retrySeconds, Set<Long> exclusions, long startingVeersion) {
		addInterest(name, listener, retrySeconds, exclusions, startingVeersion);
	}
	
	/**
	 * Kill off all interests.
	 */
	public void close() {
		removeAll();
	}

	/**
	 * Cancel a specific interest
	 * @param name
	 * @param listener
	 */
	public void cancelInterest(ContentName name, CCNInterestListener listener) {
		removeInterest(name, listener);
	}

	/**
	 * in case we're GC'd without a close().  Don't rely on this.
	 */
	public void finalize() {
		removeAll();
	}
	// ==============================================================================
	// Internal implementation
	private final CCNHandle _handle;
	private final Map<ContentName, BasenameState> _map = new HashMap<ContentName, BasenameState>();

	private void addInterest(ContentName name, CCNInterestListener listener, int retrySeconds, Set<Long> exclusions, long startingVersion) {
		BasenameState data;
		
		synchronized(_map) {
			data = _map.get(name);
			if( null == data ) {
				data = new BasenameState();
				_map.put(name, data);
			}
			data.addListener(listener, retrySeconds);
		}
	}
	
	/**
	 * Remove a listener.  If it is the last listener, remove from map and
	 * kill all interests.
	 * @param name
	 * @param listener
	 */
	private void removeInterest(ContentName name, CCNInterestListener listener) {
		BasenameState data;
		
		synchronized(_map) {
			data = _map.get(name);
			if( null != data ) {
				data.removeListener(listener);
				if( data.size() == 0 ) {
					data.stop();
					_map.remove(name);
				}
			}
		}
	}
	
	private void removeAll() {
		// TODO Auto-generated method stub
	}
	
	// ======================================================================
	// This is the state stored per base name
	
	private static class BasenameState {
		
		/**
		 * @param listener
		 * @param retrySeconds
		 * @return true if added, false if existed or only retrySeconds updated
		 */
		public synchronized boolean addListener(CCNInterestListener listener, int retrySeconds) {
			_retrySeconds = retrySeconds;
			return _listeners.add(listener);
		}
		
		/**
		 * @return true if removed, false if not found
		 */
		public synchronized boolean removeListener(CCNInterestListener listener) {
			return _listeners.remove(listener);
		}
		
		/**
		 * User is responsible for concurrent update exclusion using the iterator
		 */
		public Iterator<CCNInterestListener> listenerIterator() {
			return _listeners.iterator();
		}
		
		public synchronized int size() {
			return _listeners.size();
		}

		/**
		 * start issuing interests
		 */
		public void start() {
			
		}
		
		/**
		 * Cancel all interests for the name
		 */
		public void stop() {
			
		}
		
		// =======
		int _retrySeconds = 0;
		long startingVersion = 0;
		
		private Set<CCNInterestListener> _listeners = new HashSet<CCNInterestListener>();


	}

}
