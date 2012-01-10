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

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import org.ccnx.ccn.CCNContentHandler;
import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.impl.CCNStats;
import org.ccnx.ccn.impl.CCNStats.CCNCategorizedStatistics;
import org.ccnx.ccn.impl.CCNStats.CCNStatistics;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Interest;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;

/**
 * Given a base name, retrieve all versions.  We have maintained a similar method
 * naming to CCNHandle (expressInterest, cancelInterest, close), except we take
 * a ContentName input instead of an Interest input.  A future extension might be to
 * take an Interest to make it more drop-in replacement for existing CCNHandle methods.
 * 
 * IMPORTANT: The CCNInterestListener that gets the data should not block for long
 * and should not hijack the incoming thread.
 * 
 * NOTE: The retry is not implemented.  Interests are re-expressed every 4 seconds
 * as per normal, and they continue to be expressed until canceled.
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
 * All the work is done down in the inner class BasenameState, which is the state
 * stored per basename and tracks the interests issued for that basename.  It
 * is really just a holder for VersioningInterestManager plus state about the
 * set of listeners.
 */
public class VersioningInterest implements CCNCategorizedStatistics {
	
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
	 * The return value from #listener is ignored, the listener does not need to re-express
	 * an interest.  Interests are re-expressed automatically until canceled.
	 * 
	 * @param name
	 * @param listener
	 * @throws IOException 
	 */
	public void expressInterest(ContentName name, CCNContentHandler handler) throws IOException {
		expressInterest(name, handler, null, null);
	}

	/**
	 * As above, and provide a set of versions to exclude
	 * The return value from #listener is ignored, the listener does not need to re-express
	 * an interest.  Interests are re-expressed automatically until canceled.
	 * 
	 * @param name
	 * @param handler
	 * @param retrySeconds
	 * @param exclusions may be null
	 * @throws IOException 
	 */
	public void expressInterest(ContentName name, CCNContentHandler handler, Set<VersionNumber> exclusions) throws IOException {
		expressInterest(name, handler, exclusions, null);
	}
	
	/**
	 * As above, and provide a set of versions to exclude and a hard floor startingVersion, any version
	 * before that will be ignored.
	 * 
	 * The return value from #listener is ignored, the listener does not need to re-express
	 * an interest.  Interests are re-expressed automatically until canceled.
	 * 
	 * @param name
	 * @param handler
	 * @param retrySeconds
	 * @param exclusions may be null
	 * @param startingVersion the minimum version to include (may be null)
	 * @throws IOException 
	 */
	public void expressInterest(ContentName name, CCNContentHandler handler, Set<VersionNumber> exclusions, VersionNumber startingVeersion) throws IOException {
		addInterest(name, handler, exclusions, startingVeersion);
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
	 * @param handler
	 */
	public void cancelInterest(ContentName name, CCNContentHandler handler) {
		removeInterest(name, handler);
	}

	/**
	 * in case we're GC'd without a close().  Don't rely on this.
	 */
	protected void finalize() throws Throwable {
		try {
			removeAll();
		} finally {
			super.finalize();
		}
	}
	
	/**
	 * return the statistics for the interests corresponding to name
	 * @param name A ContentName or a URI-encoded string
	 * @return May be null if no interest expressed for name
	 */
	public CCNStats getStatsByName(Object name) throws ClassCastException {
		ContentName cn = null;
		if( name instanceof ContentName )
			cn = (ContentName) name;
		else if( name instanceof String )
			try {
				cn = ContentName.fromURI((String) name);
			} catch (MalformedContentNameStringException e) {
			}
	
		if( null == cn )
			throw new ClassCastException("Name must be a ContentName or a URI string");
		
		synchronized(_map) {
			BasenameState data = _map.get(cn);
			if( null != data )
				return data.getStats();
			return null;
		}
	}
	
	public Object[] getCategoryNames() {
		synchronized(_map) {
			return _map.keySet().toArray();
		}
	}
	
	// ==============================================================================
	// Internal implementation
	private final CCNHandle _handle;
	private final Map<ContentName, BasenameState> _map = new HashMap<ContentName, BasenameState>();

	private void addInterest(ContentName name, CCNContentHandler handler, Set<VersionNumber> exclusions, VersionNumber startingVersion) throws IOException {
		BasenameState data;
		
		synchronized(_map) {
			data = _map.get(name);
			if( null == data ) {
				data = new BasenameState(_handle, name, exclusions, startingVersion);
				_map.put(name, data);
				data.addListener(handler);
				data.start();
			} else {
				data.addListener(handler);
			}
		}
	}
	
	/**
	 * Remove a listener.  If it is the last listener, remove from map and
	 * kill all interests.
	 * @param name
	 * @param listener
	 */
	private void removeInterest(ContentName name, CCNContentHandler handler) {
		BasenameState data;
		
		synchronized(_map) {
			data = _map.get(name);
			if( null != data ) {
				data.removeListener(handler);
				if( data.size() == 0 ) {
					data.stop();
					_map.remove(name);
				}
			}
		}
	}
	
	private void removeAll() {
		synchronized(_map) {
			Iterator<BasenameState> iter = _map.values().iterator();
			while( iter.hasNext() ) {
				BasenameState bns = iter.next();
				bns.stop();
				iter.remove();
			}
		}
	}
	
	// ======================================================================
	// This is the state stored per base name
	
	private static class BasenameState implements CCNContentHandler, CCNStatistics {
		
		public BasenameState(CCNHandle handle, ContentName basename, Set<VersionNumber> exclusions, VersionNumber startingVersion) {
			_vim = new VersioningInterestManager(handle, basename, exclusions, startingVersion, this);
		}
		
		/**
		 * @param listener
		 * @param retrySeconds IGNORED, not implemented
		 * @return true if added, false if existed or only retrySeconds updated
		 */
		public boolean addListener(CCNContentHandler handler) {
			if( handler == null) return false;
			synchronized(_handlers) {
				return _handlers.add(handler);
			}
		}
		
		/**
		 * @return true if removed, false if not found
		 */
		public boolean removeListener(CCNContentHandler handler) {
			if( handler == null) return false;
			synchronized(_handlers) {
				return _handlers.remove(handler);
			}
		}
				
		public int size() {
			synchronized(_handlers) {
				return _handlers.size();
			}
		}

		/**
		 * start issuing interests.  No data is passed to
		 * any listener in the stopped state
		 * @throws IOException 
		 */
		public void start() throws IOException {
			_running = true;
			_vim.start();
		}
		
		/**
		 * Cancel all interests for the name
		 */
		public void stop() {
			_running = false;
			_vim.stop();
		}
		
		/**
		 * Pass any received data up to the user.
		 * @param data
		 * @param interest
		 * @return null
		 */
		public Interest handleContent(ContentObject data, Interest interest) {
			// when we're stopped, we do not pass any data
			if( ! _running )
				return null;
			
			synchronized(_handlers) {
				for(CCNContentHandler handler : _handlers) {
					try {
						handler.handleContent(data, interest);
					} catch(Exception e){
						e.printStackTrace();
					}
				}
			}
			return null;
		}
		
		public CCNStats getStats() {
			return _vim.getStats();
		}
		
		// =======
		
		private final Set<CCNContentHandler> _handlers = new HashSet<CCNContentHandler>();
		private final VersioningInterestManager _vim;
		private boolean _running = false;

	}

}
