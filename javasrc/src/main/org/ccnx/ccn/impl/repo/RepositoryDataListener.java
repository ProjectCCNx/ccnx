/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008, 2009, 2010, 2011, 2012 Palo Alto Research Center, Inc.
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

import org.ccnx.ccn.CCNContentHandler;
import org.ccnx.ccn.CCNHandle;
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

public class RepositoryDataListener implements CCNContentHandler {
	private long _timer;				// Used to timeout inactive listeners
	private final Interest _origInterest;		// The interest which originally triggered the creation of
										// this listener. Used to filter out duplicate or overlapping
										// requests for listeners
	private final InterestTable<Object> _interests = new InterestTable<Object>();	// Used to hold outstanding interests
										// expressed but not yet satisfied.  Also used to decide how many interests
										// may be expressed to satisfy the current pipelining window
	protected RepositoryServer _server;
	private final CCNHandle _handle;
	private long _largestSegmentNumberReceived = -1;
	private long _finalSegmentNumber = -1; 	// expected last block of the stream

	private final GetLargestSegmentNumberAction _glsna = new GetLargestSegmentNumberAction();

	protected boolean _throttled = false;
	protected Interest _restartInterest = null;

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

		_server._stats.increment(RepositoryServer.StatsEnum.HandleContent);

		_timer = System.currentTimeMillis();


		boolean isFinalSegment = false;

		if (SegmentationProfile.isSegment(co.name())) {
			long thisSegmentNumber = SegmentationProfile.getSegmentNumber(co.name());
			if (thisSegmentNumber >= _largestSegmentNumberReceived)
				_largestSegmentNumberReceived = thisSegmentNumber;

			// For now, only set _finalSegmentNumber when we *know* we have the correct final
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
				_finalSegmentNumber = SegmentationProfile.getSegmentNumber(co.signedInfo().getFinalBlockID());
				if (_finalSegmentNumber == thisSegmentNumber) {
					isFinalSegment = true; // we only know for sure what the final block is when this is true
				}
				if (Log.isLoggable(Log.FAC_REPO, Level.FINEST)) {
					Log.finest(Log.FAC_REPO, "Found final segment number: {0}", _finalSegmentNumber);
				}
			}
		}
    calculateInterests: synchronized (_interests) {
			long largestSegmentNumberRequested = getLargestSegmentNumber();
			_interests.remove(interest, null);

			// Compute next interests to ask for and ask for them
			// Note that this should only ask for 1 interest except for the first time through this code when it
			// should ask for "windowSize" interests.
			if (Log.isLoggable(Log.FAC_REPO, Level.FINEST)) {
				Log.finest(Log.FAC_REPO, "Largest segment number requested is {0}", largestSegmentNumberRequested);
			}

			int remainingWindow = _server.getWindowSize() - _interests.size();

			// Make sure we don't go past prospective last block.
			if (_finalSegmentNumber >= 0 && _finalSegmentNumber < (largestSegmentNumberRequested + remainingWindow)) {
				// want max to be _finalSegmentNumber or largestSegmentNumberRequested, whichever is larger,
				// unless isFinalSegment is true, in which case max is _finalSegmentNumber (i.e. no more interests)
				remainingWindow = (int)(_finalSegmentNumber - largestSegmentNumberRequested + 1);
				// If we're confident about the final block ID, cancel previous extra interests
				if (isFinalSegment) {
					cancelHigherInterests(_finalSegmentNumber);
                    break calculateInterests; // exit the synchronized block and process the data
				}
			}
			if (remainingWindow < 0)
				remainingWindow = 0;

			if (Log.isLoggable(Log.FAC_REPO, Level.FINEST)) {
				Log.finest(Log.FAC_REPO, "REPO: Got block: {0} expressing {1} more interests, largest block {2} final block {3} last block? {4}", co.name(), remainingWindow, _largestSegmentNumberReceived, _finalSegmentNumber, isFinalSegment);
			}

			if (! _throttled) {

				for (int i = 1; i <= remainingWindow; i++) {
					ContentName name = SegmentationProfile.segmentName(co.name(), largestSegmentNumberRequested + i);
					// DKS - should use better interest generation to only get segments (TBD, in SegmentationProfile)
					Interest newInterest = new Interest(name);
					if (_server.getThrottle()) {
						_throttled = true;
						_restartInterest = newInterest;
						break;
					}
					outputInterest(newInterest);
				}
			}
		}
		handleData(co);
		return null;
	}

	public void outputInterest(Interest interest) {
		try {
			_handle.expressInterest(interest, this);
			_interests.add(interest, null);
			_server._stats.increment(RepositoryServer.StatsEnum.HandleContentExpressInterest);

		} catch (IOException e) {
			_server._stats.increment(RepositoryServer.StatsEnum.HandleContentExpressInterestErrors);
			Log.logStackTrace(Level.WARNING, e);
			e.printStackTrace();
		}
	}

	public void restart() {
		synchronized (_interests) {
			if (_throttled) {
				if (null != _restartInterest) {
					Log.warning("Restarting - interest is {0}", _restartInterest);
					outputInterest(_restartInterest);
					_restartInterest = null;
				} else
					Log.warning("Warning - restart with no interest");
				_throttled = false;
			}
		}
	}

	/**
	 * Allow subclasses to override data handling behavior
	 * @param co
	 */
	public void handleData(ContentObject co) {
		_server._stats.increment(RepositoryServer.StatsEnum.HandleContentHandleData);
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
	private class GetLargestSegmentNumberAction extends InterestActionClass {
		@Override
		protected void action(long value, Entry<?> entry, Iterator<Entry<Object>> it) {
			if (value >= _value)
				_value = value;
		}

		private long getValue() {
			return _value;
		}

	}
	private long getLargestSegmentNumber() {
		interestsAction(_glsna);
		return _glsna.getValue();
	}

	/**
	 * Cancel all interests for segments higher than "value"
	 * @param value
	 */
	private class CancelInterestsAction extends InterestActionClass {
		CCNContentHandler _handler;

		private CancelInterestsAction(long startValue, CCNContentHandler handler) {
			_value = startValue;
			_handler = handler;
		}

		@Override
		protected void action(long value, Entry<?> entry, Iterator<Entry<Object>> it) {
			if (value > _value) {
				_server._stats.increment(RepositoryServer.StatsEnum.HandleContentCancelInterest);
				_handle.cancelInterest(entry.interest(), _handler);
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
		for (Entry<Object> entry : _interests.values()) {
			_server._stats.increment(RepositoryServer.StatsEnum.HandleContentCancelInterest);
			_handle.cancelInterest(entry.interest(), this);
		}
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
