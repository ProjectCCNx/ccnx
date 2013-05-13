/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008, 2009, 2011 Palo Alto Research Center, Inc.
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
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Level;

import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.protocol.Interest;



/**
 * A base class handling standard query listener
 * functionality.
 *
 */
public abstract class BasicInterestListener implements CCNContentHandler {

	/**
	 * Interest must implement Comparable.
	 */
	protected Set<Interest> _interests = new TreeSet<Interest>();
	
	/**
	 * This allows the same basic class to handle interests
	 * expressed at the CCNHandle level or directly to
	 * a CCNRepository.
	 */
	protected CCNBase _interestProvider = null;
	
	public BasicInterestListener(CCNBase interestProvider) {
		_interestProvider = interestProvider;
	}
	
	public void cancelInterest(Interest interest) throws IOException {
		if( Log.isLoggable(Level.INFO) )
			Log.info("Interest cancelled: " + interest.name());
		// What happens if we do this in the middle of cancel interests?
		_interestProvider.cancelInterest(interest, this);
		_interests.remove(interest);
	}
}
