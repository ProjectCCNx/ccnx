/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008-2012 Palo Alto Research Center, Inc.
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
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Set;

import org.ccnx.ccn.CCNContentHandler;
import org.ccnx.ccn.CCNHandle;
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
public class Pathfinder implements CCNContentHandler {
	
	public static class SearchResults {
		
		private ContentObject result;
		private ContentName interestName;
		private Set<ContentName> excluded;
		
		public SearchResults(ContentObject result, ContentName interestName) {
			this(result, interestName, null);
		}
		
		public SearchResults(ContentObject result, ContentName interestName, Set<ContentName> excluded) {
			this.result = result;
			this.interestName = interestName;
			this.excluded = excluded;
		}
		
		public ContentObject getResult() {
			return result;
		}
		
		public ContentName getInterestName() {
			return interestName;
		}
		
		public Set<ContentName> getExcluded() {
			return excluded;
		}
		
		public void setResult(ContentObject result) {
			this.result = result;
		}
		
		public void setExcluded(Set<ContentName> excluded) {
			this.excluded = excluded;
		}
		
	}
	
	protected ContentName _startingPoint;
	protected ContentName _stoppingPoint; // where to stop, inclusive; ROOT if non-null
	protected ContentName _postfix;
	protected boolean _closestOnPath;
	protected boolean _goneOK;
	protected long _timeout;
	protected CCNHandle _handle;
	protected long _startingTime;
	protected boolean _timedOut = false;
	protected Set<ContentName> _searchedPathCache;
	
	protected SearchResults _searchResult;
	
	
	// In order from startingPoint to root.
	protected LinkedList<Interest> _outstandingInterests = new LinkedList<Interest>();
	
	/**
	 * Search from startingPoint to stoppingPoint *inclusive* (i.e. stoppingPoint
	 * will be searched).
	 * @param startingPoint
	 * @param stoppingPoint
	 * @param desiredPostfix
	 * @param closestOnPath
	 * @param goneOK
	 * @param timeout
	 * @param searchedPathCache
	 * @param handle
	 * @throws IOException
	 */
	public Pathfinder(ContentName startingPoint, ContentName stoppingPoint, ContentName desiredPostfix, 
					  boolean closestOnPath, boolean goneOK,
					  int timeout, 
					  Set<ContentName> searchedPathCache,
					  CCNHandle handle) throws IOException {
		_startingPoint = startingPoint;
		_stoppingPoint = (null == stoppingPoint) ? ContentName.ROOT : stoppingPoint;
		_postfix = desiredPostfix;
		_closestOnPath = closestOnPath;
		_goneOK = goneOK;
		_timeout = timeout;
		_searchedPathCache = searchedPathCache;
		_handle = handle;
		startSearch();
	}
	
	protected synchronized void startSearch() throws IOException {
		// Fire off interests, one per search point.
		ContentName searchPoint = _startingPoint;
		
		Interest theInterest = null;
		while (searchPoint != null) {
			
			if ((null != _searchedPathCache) && (_searchedPathCache.contains(searchPoint))) {
				Log.finer("Skipping search of point {0}, cached negative result.", searchPoint);
			} else {
				Log.finer("Pathfinder searching node {0}", searchPoint);
				theInterest = constructInterest(searchPoint);

				_handle.expressInterest(theInterest, this);
				_outstandingInterests.add(theInterest);
			}
			
			if (searchPoint.equals(_stoppingPoint)) {
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
		ContentName targetName = searchPoint.append(_postfix);
		return new Interest(targetName);
	}

	/**
	 * We want to hand back a list of paths we have checked and ruled out to our
	 * caller, who can opt to keep them and not ask about them again (or to cache
	 * them for some time before asking). These would basically be all the prefixes
	 * we timed out on, not the prefixes we removed because we found something at a
	 * closer point and were looking for the closest entry, or at a farther point and
	 * were looking for the farthest entry.
	 */
	public synchronized Set<ContentName> stopSearch() {
		HashSet<ContentName> outstandingPrefixes = new HashSet<ContentName>();
		int cutCount = _postfix.count();
		ContentName prefixName;
		
		for (Interest interest : _outstandingInterests) {
			if (null != interest) {
				_handle.cancelInterest(interest, this);
				prefixName = interest.name().cut(interest.name().count() - cutCount);
				if (prefixName.isPrefixOf(_startingPoint)) {
					outstandingPrefixes.add(prefixName);
					Log.finer("Pathfinder: caching negative result for {0}", prefixName);
				} else {
					// we found a gone object, and were trying to find a non-gone child of it
					// TODO fix this when we change the GONE handling
					// TODO should this be prefixName or prefixName.parent()
					ContentName thisName = prefixName;
					while (!thisName.isPrefixOf(_startingPoint)) {
						if (thisName.equals(ContentName.ROOT)) {
							thisName = null;
							break;
						}
						thisName = thisName.parent();
					}
					if (null != thisName) {
						outstandingPrefixes.add(thisName);
						Log.finer("Pathfinder: caching negative result for {0}", thisName);
					}
				}
			}
		}
		_outstandingInterests.clear();
		return outstandingPrefixes;
	}
	
	public boolean goneOK() { return _goneOK; }
	
	public boolean seekingClosestMatchOnPath() { return _closestOnPath; }
	
	public synchronized SearchResults waitForResults() {
		// Wait, if woken up see if we're done, we've timed out, or we woke up early.
		long timeRemaining = _timeout - (System.currentTimeMillis() - _startingTime);
		while (timeRemaining > 0) {
			try {
				Log.finest("Pathfinder: waiting {0} more milliseconds.", timeRemaining);
				this.wait(timeRemaining);
			} catch (InterruptedException e) {
			}
			timeRemaining = _timeout - (System.currentTimeMillis() - _startingTime);
			if (done()) {
				break;
			}
		}
		if (done()) {
			Log.finer("Pathfinder: found answer, {0}", (null == _searchResult) ? "null"  : _searchResult.getResult().name());
			if (null == _searchResult) _searchResult = new SearchResults(null, null);
			return _searchResult;
		} else {
			Set<ContentName> excluded = stopSearch();
			// Do we return null, as we ran out of time, or the best option
			// we found? 
			_timedOut = true;
			Log.finer("Pathfinder: timed out, best answer so far: {0}", (null == _searchResult) ? "null"  : _searchResult.getResult().name());
			if (null == _searchResult) _searchResult = new SearchResults(null, null);
			_searchResult.setExcluded(excluded);
			return _searchResult;
		}
	}
	
	public boolean timedOut() {
		return _timedOut;
	}
	
	public Interest handleContent(ContentObject result,
								  Interest interest) {
		// When we get data back, we can cancel all the outstanding interests in the
		// direction other than the one we want.
		// May want to extend this to allow caller to check this content and
		// go around again if it isn't acceptable.
		// For right now, we try a simple tack; caller can specify whether GONE
		// content is OK (only relevant for postfixes that will pull specific
		// information). If it isn't, and we get GONE content, we put out an
		// interest looking for a later (non-GONE) version at that point.
		Log.finer("Pathfinder: Got result matching interest {0}, first name is {1}", interest, result.name());
		
		Interest returnInterest = null;
		
		synchronized(this) {
			int index = _outstandingInterests.indexOf(interest);
			
			// if the interest has already been removed from _outstandingInterests, do nothing.
			if (index == -1) return null;
			
				if (result.isGone() && !goneOK()) {
					Log.finer("Pathfinder found a GONE object when it wasn't looking for one. Replacing interest with one looking for latest version after (0}", result.name());

					// TODO this isn't entirely correct -- will look for later versions of the GONE object, but
					// won't look for other objects that aren't in this one's version chain that aren't GONE;
					// need to maybe exclude the gone one and ask for that as well, but the interest-splitting
					// code won't handle more than one outstanding interest per prefix correctly.
					if (!VersioningProfile.hasTerminalVersion(result.name())) {
						Log.finer("Pathfinder: GONE object not versioned. Ignoring it and hoping something better comes along.");
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
						
						Log.finer("Pathfinder: finding {0} closest to {1}, found {2}, removing more distant interests.",
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
						
						Log.finer("Pathfinder: finding {0} farthest from {1}, found {2}, removing closer interests.",
								_postfix, _startingPoint, result.name());
						
						for (int i=0; i < index; ++i) {
							thisInterest = _outstandingInterests.removeFirst();
							_handle.cancelInterest(thisInterest, this);
							_outstandingInterests.remove(i);
						}
					}
					_searchResult = new SearchResults(result, interest.name()); // what if there is more than one
					
				}

			// Order may have changed
			index = _outstandingInterests.indexOf(interest);
			_outstandingInterests.remove(index);
			if (null != returnInterest) {
				_outstandingInterests.add(index, returnInterest);
			}
			
			if (done()) {
				// We're done -- this means that the answer was either at this point on the path,
				// or at the root, depending on whether we're searching for closest or farthest.
				// Anything else we need to time out.
				this.notifyAll();
			}

		}
		return returnInterest;
	}
	
	public boolean done() {
		return (0 == _outstandingInterests.size());
	}
}
