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

import org.ccnx.ccn.CCNFilterListener;
import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.Interest;

public class AssertionCCNHandle extends CCNHandle {
	protected Error _error = null;

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
	
	public void registerFilter(ContentName filter,
			CCNFilterListener callbackListener) throws IOException {
		super.registerFilter(filter, new AssertionFilterListener(callbackListener));
	}
	
	public void checkError() throws Error {
		if (null != _error)
			throw _error;
	}
	
	protected class AssertionFilterListener implements CCNFilterListener {
		
		protected CCNFilterListener _listener;
		
		public AssertionFilterListener(CCNFilterListener listener) {
			_listener = listener;
		}

		public boolean handleInterest(Interest interest) {
			try {
				return _listener.handleInterest(interest);
			} catch (Error t) {
				_error = t;
				throw t;
			}
		}
		
	}
	
}
