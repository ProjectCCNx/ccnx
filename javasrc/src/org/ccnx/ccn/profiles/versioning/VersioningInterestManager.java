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
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.CCNInterestListener;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.profiles.VersionMissingException;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.protocol.CCNTime;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Interest;

/**
 * Manage splitting interests and generating interests for a base name.
 * 
 * This class is used by VersioningInterest.
 * 
 * Current interest filling algorithm:
 *    Interests are filled to MIN_FULL exclusions initially and allowed
 *    to grow to MAX_FULL exclusions.    When MAX_FULL exclusions is reached,
 *    the starting times are re-arranged.
 * 
 * XXX We should do a quantized filling method so interests are
 *     more likely to be aggregated.
 */
public class VersioningInterestManager implements CCNInterestListener {
	public final static int MIN_FILL = 100;
	public final static int MAX_FILL = 200;

	/**
	 * Create a VersioningInterestManager for a specific content name.  Send
	 * results to the listener.
	 * 
	 * @param handle
	 * @param name
	 * @param exclusions may be null or empty
	 * @param startingVersion non-negative, use 0 for all versions
	 * @param listener
	 */
	public VersioningInterestManager(CCNHandle handle, ContentName name, int retrySeconds, Set<CCNTime> exclusions, long startingVersion, CCNInterestListener listener) {
		_handle = handle;
		_name = name;
		_retrySeconds = retrySeconds;
		_startingVersion = startingVersion;
		_listener = listener;
		if( null != exclusions )
			_exclusions.addAll(exclusions);
	}

	public void setRetrySeconds(int retrySeconds) {
		_retrySeconds = retrySeconds;
		updateRetry();
	}

	/**
	 * Generate interests and return data to the listener
	 * @throws IOException 
	 */
	public synchronized void start() throws IOException {
		_running = true;
		generateInterests();
	}

	/**
	 * cancel all interests and stop operation
	 */
	public synchronized void stop() {
		_running = false;
		cancelInterests();
	}

	@Override
	public synchronized Interest handleContent(ContentObject data, Interest interest) {
		return receive(data, interest);
	}

	// ==============================================
	// Internal implementation
	private boolean _running = false;
	private final CCNHandle _handle;
	private final ContentName _name;
	private final TreeSet<CCNTime> _exclusions = new TreeSet<CCNTime>();
	private final long _startingVersion;
	private int _retrySeconds;
	private final CCNInterestListener _listener; // our callback

	// this will be sorted by the starttime of each InterestData
	private final TreeSet<InterestData> _interestData = new TreeSet<InterestData>();

	// We need to store a map from an interest given to the network to the
	// interestData that generated it so we can re-express interest
	private final Map<Interest, InterestData> _interestMap = new HashMap<Interest, InterestData>();


	// when we got the last CCN timeout and went in to a retry period
	// we will retry when now <= _retryStartingTime + _retrySeconds
	private int _retryStartingTime;


	// the retry period was updated, so do the right thing
	private void updateRetry() {
		// XXX finish
	}

	/**
	 * Called on start()
	 */
	private void generateInterests() {
		synchronized(_exclusions) {
			// we ask for content from right to left, so fill from right to left
			Iterator<CCNTime> iter = _exclusions.descendingIterator();

			// The first interest (being right most) goes from 0 to infinity.  If it gets
			// filled up, we will set the startTime and create a new one to the left.
			InterestData id = new InterestData(_name, _startingVersion, InterestData.NO_STOP_TIME);

			// fill the current InterestData with exclusions until it is at MIN_FILL,
			// then make a new InterestData with a range below the first one.
			while( iter.hasNext() ) {
				CCNTime version = iter.next();

				// don't add stuff before the start time
				if( version.getTime() < _startingVersion )
					break;

				if( id.size() >= MIN_FILL ) {
					long t = version.getTime();
					id.setStartTime(t+1);
					// now that the start time is fixed, add to TreeSet
					_interestData.add(id);

					// now make a new one, that ends at the current version
					id = new InterestData(_name, _startingVersion, t);
				}

				id.addExclude(version);
			}
			// now save the last one
			_interestData.add(id);
		}

		// we now have all the interests done, actually send them
		synchronized(_interestMap) {
			for(InterestData datum : _interestData) {
				sendInterest(datum);
			}
		}
	}

	/**
	 * Called on stop()
	 */
	private void cancelInterests() {
		synchronized(_interestMap){
			for(InterestData datum : _interestData) {
				Interest interest = datum.getLastInterest();
				if( null != interest ) {
					_interestMap.remove(interest);
					_handle.cancelInterest(interest, this);
				}
			}

			// interestmap should be empty now....
			if( _interestMap.size() > 0 ) {
				Log.warning(Log.FAC_ENCODING, "interestMap not empty after cancelInterests");
				_interestMap.clear();
			}
		}
	}

	/**
	 * Called from handleInterest
	 * @param data
	 * @param interest
	 * @return
	 */
	private Interest receive(ContentObject data, Interest interest) {
		InterestData datum;
		Interest newInterest = null;

		CCNTime version;

		// Match the interest to our pending interests.  This removes it
		// from the pending interest map.
		synchronized(_interestMap) {
			datum = _interestMap.remove(interest);
		}

		// if we cannot find a version component, just re-express the same interest
		try {
			version = VersioningProfile.getLastVersionAsTimestamp(data.name());
		} catch (VersionMissingException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			return datum.buildInterest();
		}

		// store it in our global list of exclusions
		synchronized(_exclusions) {
			_exclusions.add(version);
		}
	
		// if we did not match anything, then we are no longer interested in it.
		// This most likely happens because of a re-build, in which case we do
		// not need to re-express an interest as the re-build did that.
		if( null != datum ) {
			
			// Figure out where to put the exclusion.  Because of re-building,
			// an exclusion will not always go in the original datum.  But,
			// that is usually a great first choice, so try it.  If that does
			// not work, search for where to put it
			InterestData excludeDatum = datum;
			if( !excludeDatum.contains(version) ) {
				// search for where to add the exclusion
				InterestData x = new InterestData(null, version.getTime(), InterestData.NO_STOP_TIME);
				
				// This is the InterestData that must contain version because
				// it is the largest startTime that is less than version
				InterestData floor = _interestData.floor(x);
				
				if( floor.contains(version) ) {
					excludeDatum = floor;
				} else {
					excludeDatum = null;
					Log.severe(Log.FAC_ENCODING, "Error: floor element {0} did not contain version {1}",
							floor.toString(), version.getTime());
				}
			}
			
			// Because of re-builds, we cannot 
			if( (null != excludeDatum) && !excludeDatum.addExclude(version)) {
				// we cannot put the new exclusion in there, so we need to rebuild
				rebuild(version, excludeDatum);
			}

			newInterest = datum.buildInterest();
			synchronized(_interestMap) {
				_interestMap.put(newInterest, datum);
			}
		}

		// pass it off to the user
		_listener.handleContent(data, interest);

		return newInterest;
	}

	/**
	 * We received a new version and tried to add it to the given #datum,
	 * but that datum is too full.  we need to split the interest.
	 * 
	 * The strategy we use is to keep shifting left.  If there is no
	 * interest to the left of #datum, make one.  If there is and it's full,
	 * rebuild it, then add our exclusion to the left.
	 * 
	 * handleContent is synchronized, and the only path to rebuild is from
	 * there, so we don't worry about locking too much.
	 * 
	 * @param datum
	 * @param version may be null for a rebuild w/o insert
	 */
	private void rebuild(CCNTime version, InterestData datum) {
		
		// Option 1:  If we do not have another interest to the left,
		// create it and split this one on the MIN_FILL boundary.
		
		InterestData left = _interestData.lower(datum);
		if( null == left ) {
			left = datum.splitLeft();
			
			if( null != version ) {
				if( left.contains(version) )
					left.addExclude(version);
				else
					datum.addExclude(version);
			}
			
			// send new interest for left.  datum will resend as the return to handleContent
			sendInterest(left);
		
			return;
		}
		
		
		// Option 2: We have an interest to the left, and it is at MAX_FILL,
		// so rebuild it.
		
		if( left.size() >= MAX_FILL ) {
			// rebuild left recursively without an insert
			rebuild(null, left);
		}
		
		// Option 3: We have an interest to the left and it is under
		// MAX_FILL, so just shift the boundaries between them (this
		// is guaranteed because of Option 2)
		
		
	}
	
	private void sendInterest(InterestData id) {
		Interest interest = id.buildInterest();
		synchronized(_interestMap) {
			try {
				_handle.expressInterest(interest, this);
				_interestMap.put(interest, id);
			} catch(IOException e) {
				e.printStackTrace();
			}
		}
	}
	
	// ====================================================
	// Inner Classes



}
