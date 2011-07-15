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
import java.util.ArrayList;

import org.ccnx.ccn.CCNFilterListener;
import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.CCNInterestListener;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Interest;

/**
 * This class is designed to handle actually erroring in assertions that fail within CCN handlers. Normally
 * since the handler is called by a different thread than the test, an assertion failure within the handler
 * would not actually cause the test to fail.
 * 
 * To use this test, replace CCNHandle with an AssertionCCNHandle. Then after each expressInterest or registerFilter
 * call within your test you must call checkError to insure that the handler ran without error.
 */
public class AssertionCCNHandle extends CCNHandle {
	protected Error _error = null;
	protected ArrayList<RelatedInterestListener> _interestListeners = new ArrayList<RelatedInterestListener>();
	protected ArrayList<RelatedFilterListener> _filterListeners = new ArrayList<RelatedFilterListener>();
	
	protected class RelatedInterestListener {
		AssertionInterestListener _aListener;
		CCNInterestListener _listener;
		
		protected RelatedInterestListener(AssertionInterestListener aListener, CCNInterestListener listener) {
			_aListener = aListener;
			_listener = listener;
		}
	}
	
	protected class RelatedFilterListener {
		AssertionFilterListener _aListener;
		CCNFilterListener _listener;
		
		protected RelatedFilterListener(AssertionFilterListener aListener, CCNFilterListener listener) {
			_aListener = aListener;
			_listener = listener;
		}
	}

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
	
	/**
	 * Overrides of CCNHandle calls referencing the listener
	 */
	public void expressInterest(
			Interest interest,
			CCNInterestListener listener) throws IOException {
		AssertionInterestListener ail = null;
		synchronized (_interestListeners) {
			ail = getInterestListener(listener);
			if (null == ail) {
				ail = new AssertionInterestListener(listener);
				_interestListeners.add(new RelatedInterestListener(ail, listener));
			}
			ail._references++;
		}
		super.expressInterest(interest, ail);
	}
	
	public void cancelInterest(Interest interest, CCNInterestListener listener) {
		AssertionInterestListener toCancel = null;
		synchronized (_interestListeners) {
			toCancel = getInterestListener(listener);
			if (null == toCancel) {
				Log.warning("Questionable cancel of never expressed interest: %0", interest);
				toCancel = new AssertionInterestListener(listener);
				_interestListeners.add(new RelatedInterestListener(toCancel, listener));
			}
		}
		super.cancelInterest(interest, toCancel);

		synchronized (_interestListeners) {
			if (--toCancel._references <= 0)
				_interestListeners.remove(toCancel);
		}
	}
	
	public void registerFilter(ContentName filter,
			CCNFilterListener callbackListener) throws IOException {
		AssertionFilterListener listener = null;
		synchronized (_filterListeners) {
			listener = getFilterListener(callbackListener);
			if (null == listener) {
				listener = new AssertionFilterListener(callbackListener);
				_filterListeners.add(new RelatedFilterListener(listener, callbackListener));
			}
			listener._references++;
		}
		super.registerFilter(filter, listener);
	}
	
	public void unregisterFilter(ContentName filter, CCNFilterListener callbackListener) {
		AssertionFilterListener toUnregister = null;
		synchronized (_filterListeners) {
			toUnregister = getFilterListener(callbackListener);
			if (null == toUnregister) {
				Log.warning("Questionable unregister of never registered filter: %0", filter);
				toUnregister = new AssertionFilterListener(callbackListener);
				_filterListeners.add(new RelatedFilterListener(toUnregister, callbackListener));
			}
		}
		super.unregisterFilter(filter, toUnregister);
		synchronized (_filterListeners) {
			if (--toUnregister._references <= 0)
				_filterListeners.remove(toUnregister);
		}
	}
	
	/**
	 * Should be called after any callback has been triggered on the handle that would have
	 * received the callback
	 * @param timeout millis to wait for callback to occur
	 * @throws Error
	 * @throws InterruptedException
	 */
	public void checkError(long timeout) throws Error, InterruptedException {
		synchronized (this) {
			wait(timeout);
		}
		if (null != _error)
			throw _error;
	}
	
	private AssertionInterestListener getInterestListener(CCNInterestListener listener) {
		for (RelatedInterestListener ril : _interestListeners) {
			if (ril._listener == listener) {
				return ril._aListener;
			}
		}
		return null;
	}
	
	private AssertionFilterListener getFilterListener(CCNFilterListener listener) {
		for (RelatedFilterListener rfl : _filterListeners) {
			if (rfl._listener == listener) {
				return rfl._aListener;
			}
		}
		return null;
	}
	
	protected class AssertionFilterListener implements CCNFilterListener {
		
		protected CCNFilterListener _listener;
		protected int _references = 0;
		
		public AssertionFilterListener(CCNFilterListener listener) {
			_listener = listener;
		}

		public boolean handleInterest(Interest interest) {
			boolean result = false;
			try {
				result = _listener.handleInterest(interest);
			} catch (Error e) {
				_error = e;
			}
			synchronized (this) {
				notifyAll();
			}
			if (null != _error)
				throw _error;
			return result;
		}	
	}
	
	protected class AssertionInterestListener implements CCNInterestListener {
		
		protected CCNInterestListener _listener;
		protected int _references = 0;
		
		public AssertionInterestListener(CCNInterestListener listener) {
			_listener = listener;
		}

		public Interest handleContent(ContentObject data, Interest interest) {
			Interest result = null;
			try {
				result = _listener.handleContent(data, interest);
			} catch (Error e) {
				_error = e;
			}
			synchronized (this) {
				notifyAll();
			}
			if (null != _error)
				throw _error;
			return result;
		}
	}
}
