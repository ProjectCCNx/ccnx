/*
 * A CCNx library test.
 *
 * Copyright (C) 2011 Palo Alto Research Center, Inc.
 *
 * This work is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License version 2 as published by the
 * Free Software Foundation. 
 * This work is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License
 * for more details. You should have received a copy of the GNU General Public
 * License along with this program; if not, write to the
 * Free Software Foundation, Inc., 51 Franklin Street, Fifth Floor,
 * Boston, MA 02110-1301, USA.
 */
package org.ccnx.ccn.test;

import java.io.IOException;
import java.util.TreeMap;

import org.ccnx.ccn.CCNFilterListener;
import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.CCNInterestListener;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Interest;

public class AssertionCCNHandle extends CCNHandle {
	protected Error _error = null;
	protected TreeMap<CCNInterestListener, CCNInterestListener> _interestListeners = new TreeMap<CCNInterestListener, CCNInterestListener>();
	protected TreeMap<CCNFilterListener, CCNFilterListener> _filterListeners = new TreeMap<CCNFilterListener, CCNFilterListener>();

	protected AssertionCCNHandle() throws ConfigurationException, IOException {
		super();
	}
	
	public static AssertionCCNHandle open() throws ConfigurationException, IOException { 
		try {
			return new AssertionCCNHandle();
		} catch (ConfigurationException e) {
			Log.severe(Log.FAC_NETMANAGER, "Configuration exception initializing CCN library: " + e.getMessage());
			throw e;
		} catch (IOException e) {
			Log.severe(Log.FAC_NETMANAGER, "IO exception initializing CCN library: " + e.getMessage());
			throw e;
		}
	}
	
	public void expressInterest(
			Interest interest,
			CCNInterestListener listener) throws IOException {
		CCNInterestListener ail = new AssertionInterestListener(listener);
		_interestListeners.put(ail, listener);
		super.expressInterest(interest, listener);
	}
	
	public void cancelInterest(Interest interest, CCNInterestListener listener) {
		CCNInterestListener toCancel = null;
		for (CCNInterestListener l : _interestListeners.keySet()) {
			if (l == listener) {
				toCancel = _interestListeners.get(l);
				break;
			}
		}
		super.cancelInterest(interest, toCancel);
	}
	
	public void registerFilter(ContentName filter,
			CCNFilterListener callbackListener) throws IOException {
		CCNFilterListener listener = new AssertionFilterListener(callbackListener);
		_filterListeners.put(listener, callbackListener);
		super.registerFilter(filter, listener);
	}
	
	public void unregisterFilter(ContentName filter,CCNFilterListener callbackListener) {
		CCNFilterListener toCancel = null;
		for (CCNFilterListener l : _filterListeners.keySet()) {
			if (l == callbackListener) {
				toCancel = _filterListeners.get(l);
				break;
			}
		}
		super.unregisterFilter(filter, toCancel);
	}
	
	public void checkError(long timeout) throws Error, InterruptedException {
		synchronized (this) {
			wait(timeout);
		}
		if (null != _error)
			throw _error;
	}
	
	protected class AssertionFilterListener implements CCNFilterListener {
		
		protected CCNFilterListener _listener;
		
		public AssertionFilterListener(CCNFilterListener listener) {
			_listener = listener;
		}

		public boolean handleInterest(Interest interest) {
			boolean result = false;
			try {
				result = _listener.handleInterest(interest);
			} catch (Throwable t) {
				_error = (Error)t;
			}
			synchronized (this) {
				notifyAll();
			}
			return result;
		}	
	}
	
	protected class AssertionInterestListener implements CCNInterestListener {
		
		protected CCNInterestListener _listener;
		
		public AssertionInterestListener(CCNInterestListener listener) {
			_listener = listener;
		}

		public Interest handleContent(ContentObject data, Interest interest) {
			Interest result = null;
			try {
				result = _listener.handleContent(data, interest);
			} catch (Error t) {
				_error = t;
			}
			synchronized (this) {
				notifyAll();
			}
			return result;
		}
	}
}
