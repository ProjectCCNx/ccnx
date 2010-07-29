/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008, 2009, 2010 Palo Alto Research Center, Inc.
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

package org.ccnx.ccn.impl.repo;

import java.io.IOException;
import java.util.Collection;
import java.util.Iterator;
import java.util.logging.Level;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.CCNInterestListener;
import org.ccnx.ccn.impl.InterestTable;
import org.ccnx.ccn.impl.InterestTable.Entry;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.profiles.SegmentationProfile;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Interest;


/**
 * Handles incoming data for the repository. Its jobs are to store data in the repository
 * by interfacing with the RepositoryStore and to generate interests for data following the
 * received data in an input stream. RepositoryDataListeners are destroyed after the stream
 * which triggered their creation has been fully read.
 */

public class RepositoryDataListener implements CCNInterestListener {
	private long _timer;				// Used to timeout inactive listeners
	private Interest _origInterest;		// The interest which originally triggered the creation of
										// this listener. Used to filter out duplicate or overlapping
										// requests for listeners
	private InterestTable<Object> _interests = new InterestTable<Object>();	// Used to hold outstanding interests
										// expressed but not yet satisfied.  Also used to decide how many interests
										// may be expressed to satisfy the current pipelining window
	protected RepositoryServer _server;
	private CCNHandle _handle;
	private long _currentBlock = 0; 	// latest block we're looking for
	private long _finalBlockID = -1; 	// expected last block of the stream
	
	/**
	 * @param origInterest	interest to be used to identify this listener to filter out subsequent duplicate or overlapping
	 * 		requests
	 * @param interest	used only to log the actual interest that created this listener
	 * @param server		associated RepositoryServer
	 */
	public RepositoryDataListener(Interest origInterest, Interest interest, RepositoryServer server) {
		_origInterest = origInterest;
		_server = server;
		_handle = server.getHandle();
		_timer = System.currentTimeMillis();
		if (Log.isLoggable(Log.FAC_REPO, Level.INFO)) {
			Log.info(Log.FAC_REPO, "Starting up repository listener on original interest: {0} interest {1}", origInterest, interest);
		}
	}
	
	/**
	 * The actual incoming data handler. Kicks off a thread to store the data and expresses interest in data following
	 * the incoming data.
	 */
	public Interest handleContent(ContentObject co,
			Interest interest) {
		
		_timer = System.currentTimeMillis();

		handleData(co);

		boolean isFinalBlock = false;

		if (SegmentationProfile.isSegment(co.name())) {
			long thisBlock = SegmentationProfile.getSegmentNumber(co.name());
			if (thisBlock >= _currentBlock)
				_currentBlock = thisBlock;

			// For now, only set _finalBlockID when we *know* we have the correct final
			// block number -- i.e. we get a block whose segment number matches the encoded
			// final block. A pipelining stream may help us by setting the finalBlockID in several
			// blocks prior to the last one, to let us know when to slow down -- but it's allowed
			// to be wrong, and keep going if it hasn't yet hit a block which is itself marked
			// as the last one (whose own segment number matches its own finalBlockID value).
			// Taking into account incorrect ramp-down finalBlockIDs, recovering, and knowing we
			// have more to get requires a bit more sophisticated tweaking of the pipelining code.
			// Basically if we think we know the finalBlockID, we get that block, and it isn't
			// marked as the final block, we open the window back up.
			if (null != co.signedInfo().getFinalBlockID()) {
				// Alright, either we didn't know a final block id before, in which case
				// we just believe this one, or we did, in which case this one is later than
				// the one we knew, or earlier. If it's later, we just store it and open up
				// the window somewhat. If it's earlier, we shorten the window, but don't bother
				// canceling already expressed interests for blocks past the window till we finish
				// the stream. So just update our notion of finalBlockID.
				// So in other words, the only time we use this value to actually cancel outstanding
				// interests is when we have hit the end of the stream.
				_finalBlockID = SegmentationProfile.getSegmentNumber(co.signedInfo().getFinalBlockID());
				if (_finalBlockID == thisBlock) {
					isFinalBlock = true; // we only know for sure what the final block is when this is true
				}
			}
		}
		synchronized (_interests) {
			_interests.remove(interest, null);

			// Compute next interests to ask for and ask for them
			// Note that this should only ask for 1 interest except for the first time through this code when it
			// should ask for "windowSize" interests.
			long firstInterestToRequest = getNextBlockID();
			if (Log.isLoggable(Log.FAC_REPO, Level.FINEST)) {
				Log.finest(Log.FAC_REPO, "First interest to request is {0}", firstInterestToRequest);
			}
			if (_currentBlock > firstInterestToRequest) // Can happen if last requested interest precedes all others
				// out of order
				firstInterestToRequest = _currentBlock;

			int nOutput = _interests.size() >= _server.getWindowSize() ? 0 : _server.getWindowSize() - _interests.size();

			// Make sure we don't go past prospective last block.
			if (_finalBlockID >= 0 && _finalBlockID < (firstInterestToRequest + nOutput)) {
				// want max to be _finalBlockID or firstInterestToRequest, whichever is larger,
				// unless isFinalBlock is true, in which case max is _finalBlockID (i.e. no more interests)
				nOutput = (int)(_finalBlockID - firstInterestToRequest + 1);
				if (nOutput < 0)
					nOutput = 0;
				// If we're confident about the final block ID, cancel previous extra interests
				if (isFinalBlock) {
					cancelHigherInterests(_finalBlockID);
					return null;
				}
			}

			if (Log.isLoggable(Log.FAC_REPO, Level.FINEST)) {
				Log.finest(Log.FAC_REPO, "REPO: Got block: {0} expressing {1} more interests, current block {2} final block {3} last block? {4}", co.name(), nOutput, _currentBlock, _finalBlockID, isFinalBlock);
			}
			for (int i = 0; i < nOutput; i++) {
				ContentName name = SegmentationProfile.segmentName(co.name(), firstInterestToRequest + 1 + i);
				// DKS - should use better interest generation to only get segments (TBD, in SegmentationProfile)
				Interest newInterest = new Interest(name);
				try {
					_handle.expressInterest(newInterest, this);
					_interests.add(newInterest, null);
				} catch (IOException e) {
					Log.logStackTrace(Level.WARNING, e);
					e.printStackTrace();
				}
			}
		}
		return null;
	}
	
	/**
	 * Allow subclasses to override data handling behavior
	 * @param co
	 */
	public void handleData(ContentObject co) {
		_server.getDataHandler().add(co);
	}
	
	/**
	 * Since the interest table doesn't have a defined order for values with the same length we
	 * must explicitly go through all the values to decide whether we want to take some action
	 * based on the "value" (i.e. segment #) of some particular interest
	 */
	
	/**
	 * Must match implementation of nextSegmentNumber in input streams, segmenters.
	 */
	private class GetNextBlockIDAction extends InterestActionClass {
		@Override
		protected void action(long value, Entry<?> entry, Iterator<Entry<Object>> it) {
			if (value >= _value)
				_value = value;
		}
		
		private long getValue() {
			return _value;
		}
		
	}
	private long getNextBlockID() {
		GetNextBlockIDAction gnbia = new GetNextBlockIDAction();
		interestsAction(gnbia);
		return gnbia.getValue();
	}
	
	/**
	 * Cancel all interests for segments higher than "value"
	 * @param value
	 */
	private class CancelInterestsAction extends InterestActionClass {
		CCNInterestListener _listener;
		
		private CancelInterestsAction(long startValue, CCNInterestListener listener) {
			_value = startValue;
			_listener = listener;
		}

		@Override
		protected void action(long value, Entry<?> entry, Iterator<Entry<Object>> it) {
			if (value > _value) {
				_handle.cancelInterest(entry.interest(), _listener);
				it.remove();
			}
		}
	}
	private void cancelHigherInterests(long value) {
		CancelInterestsAction cia = new CancelInterestsAction(value, this);
		interestsAction(cia);
	}
	
	/**
	 * Perform the specified action for all values in the interest table
	 * @param value
	 * @param action
	 */
	private abstract class InterestActionClass {
		protected long _value = 0;
		protected abstract void action(long value, Entry<?> entry, Iterator<Entry<Object>> it);
	}
	private void interestsAction(InterestActionClass action) {
		Collection<Entry<Object>> values = _interests.values();
		Iterator<Entry<Object>> it = values.iterator();
		while (it.hasNext()) {
			Entry<?> entry = it.next();
			if (SegmentationProfile.isSegment(entry.interest().name())) {
				long value = SegmentationProfile.getSegmentNumber(entry.interest().name());
				action.action(value, entry, it);
			}
		}
	}
	
	/**
	 * Called on listener teardown.
	 */
	public void cancelInterests() {
		for (Entry<Object> entry : _interests.values())
			_handle.cancelInterest(entry.interest(), this);
	}
	
	/**
	 * Gets the time of the last data received
	 * @return
	 */
	public long getTimer() {
		return _timer;
	}
	
	/**
	 * Changes the time used to timeout the listener
	 * @param time 
	 */
	public void setTimer(long time) {
		_timer = time;
	}
	
	/**
	 * Gets the namespace served by this listener as an interest
	 * @return
	 */
	public Interest getOrigInterest() {
		return _origInterest;
	}
	
	/**
	 * Gets the current set of outstanding interests for this listener
	 * @return
	 */
	public InterestTable<Object> getInterests() {
		return _interests;
	}
}
