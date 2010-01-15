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

package org.ccnx.ccn.profiles.search;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.CCNInterestListener;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Interest;

/**
 * Implementation of some very specific, first pass search functionality.
 * Assume we have a starting point node N, e.g.
 * 
 * N.name() = /a/b/c/d/e
 * 
 * and we want to find a piece of content at some node along that path with the
 * final name components <postfix>, e.g. valid data would be one of:
 *  /a/b/c/d/e/<postfix>
 *  /a/b/c/d/<postfix>
 *  /a/b/c/<postfix>
 *  /a/b/<postfix>
 *  /a/<postfix>
 *  /<postfix>
 *  
 *  Depending on the requested search, we might want to find either the node with
 *  the postfix <postfix> closest to the starting node (along the path to the root);
 *  or the one farthest from the starting node (closest to the root).
 *  
 *  Eventually allow interests to be more sophisticated, at least via subclassing.
 **/
public class Pathfinder implements CCNInterestListener {
	
	protected ContentName _startingPoint;
	protected ContentName _postfix;
	protected boolean _closestOnPath;
	protected boolean _goneOK;
	protected long _timeout;
	protected CCNHandle _handle;
	protected long _startingTime;
	protected boolean _timedOut = false;
	
	protected ContentObject _searchResult;
	
	// In order from startingPoint to root.
	protected LinkedList<Interest> _outstandingInterests = new LinkedList<Interest>();
	
	public Pathfinder(ContentName startingPoint, ContentName desiredPostfix, 
					  boolean closestOnPath, boolean goneOK,
					  int timeout, CCNHandle handle) throws IOException {
		_startingPoint = startingPoint;
		_postfix = desiredPostfix;
		_closestOnPath = closestOnPath;
		_goneOK = goneOK;
		_timeout = timeout;
		_handle = handle;
		startSearch();
	}
	
	protected synchronized void startSearch() throws IOException {
		// Fire off interests, one per search point.
		ContentName searchPoint = _startingPoint;
		
		Interest theInterest = null;
		while (searchPoint != null) {
			
			theInterest = constructInterest(searchPoint);
			
			_handle.expressInterest(theInterest, this);
			_outstandingInterests.add(theInterest);
			
			if (searchPoint.equals(ContentName.ROOT)) {
				searchPoint = null;
			} else {
				searchPoint = searchPoint.parent();
			}
		}
		_startingTime = System.currentTimeMillis();
	}
	
	/**
	 * Separate out so subclasses can override.
	 */
	protected Interest constructInterest(ContentName searchPoint) {
		ContentName targetName = new ContentName(searchPoint, _postfix.components());
		return new Interest(targetName);
	}

	public synchronized void stopSearch() {
		for (Interest interest : _outstandingInterests) {
			if (null != interest) {
				_handle.cancelInterest(interest, this);
			}
		}
		_outstandingInterests.clear();
	}
	
	public boolean goneOK() { return _goneOK; }
	
	public boolean returnClosestMatchOnPath() { return _closestOnPath; }
	
	public synchronized ContentObject waitForResults() {
		// Wait, if woken up see if we're done, we've timed out, or we woke up early.
		long timeRemaining = _timeout - (System.currentTimeMillis() - _startingTime);
		while (timeRemaining > 0) {
			try {
				Log.info("Pathfinder: waiting {0} more milliseconds.", timeRemaining);
				this.wait(timeRemaining);
			} catch (InterruptedException e) {
			}
			timeRemaining = _timeout - (System.currentTimeMillis() - _startingTime);
			if (done()) {
				break;
			}
		}
		if (done()) {
			Log.info("Pathfinder: found answer, {0}", (null == _searchResult) ? "null"  : _searchResult.name());
			return _searchResult;
		} else {
			stopSearch();
			// Do we return null, as we ran out of time, or the best option
			// we found? 
			_timedOut = true;
			Log.info("Pathfinder: timed out, best answer so far: {0}", (null == _searchResult) ? "null"  : _searchResult.name());
			return _searchResult;
		}
	}
	
	public boolean timedOut() {
		return _timedOut;
	}
	
	@Override
	public Interest handleContent(ArrayList<ContentObject> results,
								  Interest interest) {
		// When we get data back, we can cancel all the outstanding interests in the
		// direction other than the one we want.
		// May want to extend this to allow caller to check this content and
		// go around again if it isn't acceptable.
		// For right now, we try a simple tack; caller can specify whether GONE
		// content is OK (only relevant for postfixes that will pull specific
		// information). If it isn't, and we get GONE content, we put out an
		// interest looking for a later (non-GONE) version at that point.
		Log.info("Got {0} results matching interest {1}, first name is {2}", results.size(), interest, results.get(0).name());
		
		Interest returnInterest = null;
		
		synchronized(this) {
			int index = _outstandingInterests.indexOf(interest);
			
			for (ContentObject result : results) {
				if (result.isGone() && !goneOK()) {
					Log.info("Pathfinder found a GONE object when it wasn't looking for one. Replacing interest with one looking for latest version after (0}", result.name());

					if (!VersioningProfile.hasTerminalVersion(result.name())) {
						Log.info("GONE object not versioned. Ignoring it and hoping something better comes along.");
						returnInterest = interest;
					} else {
						returnInterest = VersioningProfile.latestVersionInterest(result.name(), null, null);
					}
				} else {
					// we got some content that counts, gone or not. Cancel and remove all the
					// interests in the wrong direction from this one.
					// Longer interests (closer to start) are earlier in the list than index; shorter
					// interests (closer to the root) are later in the list.
					Interest thisInterest = null;
					
					if (_closestOnPath) {
						// Want the closest match to us on the path. So we want to remove anything
						// farther away (higher index) than this one. As we're removing items,
						// we actually never change our index, we just wait for the end of the array
						// to come down and meet us.
						
						Log.info("Pathfinder: finding {0} closest to {1}, found {2}, removing more distant interests.",
								_postfix, _startingPoint, result.name());
						
						for (int i=index + 1; i < _outstandingInterests.size(); ) {
							thisInterest = _outstandingInterests.get(i);
							_handle.cancelInterest(thisInterest, this);
							_outstandingInterests.remove(i);
						}
						
						// Still need to remove the interest we are responding to. Do that at the end.
						
					} else {
						// Remove any interests closer to us than the match on the path. Want the farthest
						// away match.
						
						Log.info("Pathfinder: finding {0} farthest from {1}, found {2}, removing closer interests.",
								_postfix, _startingPoint, result.name());
						
						for (int i=0; i < index; ++i) {
							thisInterest = _outstandingInterests.removeFirst();
							_handle.cancelInterest(thisInterest, this);
						}
					}
					_searchResult = result; // what if there is more than one
					
				}
			}
			// Order may have changed
			index = _outstandingInterests.indexOf(interest);
			_outstandingInterests.remove(index);
			if (null == returnInterest) {
				_outstandingInterests.add(index, returnInterest);
			}
			
			if (done()) {
				// We're done
				this.notifyAll();
			}

		}
		return returnInterest;
	}
	
	public boolean done() {
		return (0 == _outstandingInterests.size());
	}
}
