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
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Set;
import java.util.TreeSet;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.CCNInterestListener;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.protocol.CCNTime;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Exclude;
import org.ccnx.ccn.protocol.ExcludeAny;
import org.ccnx.ccn.protocol.ExcludeComponent;
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
	public Interest handleContent(ContentObject data, Interest interest) {
		// TODO Auto-generated method stub
		return null;
	}

	// ==============================================
	// Internal implementation
	private boolean _running = false;
	private final CCNHandle _handle;
	private final ContentName _name;
	private final TreeSet<CCNTime> _exclusions = new TreeSet<CCNTime>();
	private final long _startingVersion;
	private int _retrySeconds;
	
	// this will be sorted by the starttime of each InterestData
	private final TreeSet<InterestData> _interests = new TreeSet<InterestData>();

	// when we got the last CCN timeout and went in to a retry period
	// we will retry when now <= _retryStartingTime + _retrySeconds
	private int _retryStartingTime;

	private final static int MIN_FILL = 100;
	private final static int MAX_FILL = 200;
	

	// the retry period was updated, so do the right thing
	private void updateRetry() {
		// XXX finish
	}

	/**
	 * Called on start()
	 * @throws IOException 
	 */
	private void generateInterests() throws IOException {
		// we ask for content from right to left, so fill from right to left
		Iterator<CCNTime> iter = _exclusions.descendingIterator();
		
		// The first interest (being right most) goes from 0 to infinity.  If it gets
		// filled up, we will set the startTime and create a new one to the left.
		InterestData id = new InterestData(_startingVersion, InterestData.NO_STOP_TIME);
		
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
				_interests.add(id);
				
				// now make a new one, that ends at the current version
				id = new InterestData(_startingVersion, t);
			}
			
			id.addExclude(version);
		}
		
		// now save the last one
		_interests.add(id);
		
		// we now have all the interests done, actually send them
		for(InterestData datum : _interests) {
			Interest interest = datum.buildInterest();
			_handle.expressInterest(interest, this);
		}
	}
	
	/**
	 * Called on stop()
	 */
	private void cancelInterests() {
		
	}
	
	// ====================================================
	// Inner Classes
	
	private static class TimeElement implements Comparable {
		public final CCNTime version;
		public final byte [] versionComponent;

		public TimeElement(CCNTime version) {
			this.version = version;
			this.versionComponent = VersioningProfile.timeToVersionComponent(version);
		}

		// So it is sortable
		@Override
		public int compareTo(Object arg0) throws ClassCastException {
			if( ! (arg0 instanceof TimeElement) )
				throw new ClassCastException("Not instance of TimeElement");
			TimeElement other = (TimeElement) arg0;
			return version.compareTo(other.version);
		}	
	}

	/**
	 * Stores state about a specific Interest on the wire.
	 *
	 */
	private class InterestData implements Comparable<InterestData> {
		private final static String TAG="InterestData";

		public final static long NO_STOP_TIME = -1;
		
		/**
		 * @param startTime minimum version to include
		 * @param stopTime maximum version to include (use NO_STOP_TIME for infinity)
		 */
		public InterestData(long startTime, long stopTime) {
			setStartTime(startTime);
			setStopTime(stopTime);
		}

		public synchronized int size() {
			return _excludedVersions.size();
		}
		
		// sorted based on startTime
//		@Override
//		public synchronized int compareTo(Object arg0) throws ClassCastException {
//			if( ! (arg0 instanceof InterestData) )
//				throw new ClassCastException("Not instance of InterestData");
//			InterestData other = (InterestData) arg0;
//			if( _startTime < other._startTime ) return -1;
//			if( _startTime > other._startTime ) return +1;
//			return 0;
//		}
		

		@Override
		public int compareTo(InterestData other) {
			if( _startTime < other._startTime ) return -1;
			if( _startTime > other._startTime ) return +1;
			return 0;
		}

		/**
		 * Dont do this while in a sorted set, as the sort order will break.
		 * Start time is the minimum version to include.
		 */
		public synchronized void setStartTime(long time) {
			_startTime = time;
			_dirty = true;
		}

		/**
		 * stopTime is the maximum version to include.
		 * use NO_STOP_TIME for infinity
		 */
		public synchronized void setStopTime(long time) {
			_stopTime = time;
			_dirty = true;
		}

		public synchronized void addExclude(CCNTime version) {
			TimeElement te = new TimeElement(version);
			_excludedVersions.add(te);
			_dirty = true;
		}

		public synchronized Interest buildInterest() {
			if( !_dirty )
				return _interest;

			ArrayList<Exclude.Element> components = new ArrayList<Exclude.Element>();

			if( _startTime > 0 ) {
				CCNTime tmpTime = CCNTime.fromBinaryTimeAsLong(_startTime - 1);
				byte [] startTimeMinusOneComponent = VersioningProfile.timeToVersionComponent(tmpTime);

				components.add(new ExcludeAny());
				components.add(new ExcludeComponent(startTimeMinusOneComponent));
			}

			// Now add the specific exclusions
			
			if( !_excludedVersions.isEmpty() ) {
				// If there's anything in the queue, add it
				byte [][] elems = new byte [_excludedVersions.size()][];
				int cnt = 0;

				// TreeSet is sorted, so this is in right order for the exclusion filter
				Iterator<TimeElement> i = _excludedVersions.iterator();
				while( i.hasNext() ) {
					TimeElement elem = i.next();
					components.add(new ExcludeComponent(elem.versionComponent));
				}
			}

			// Now exclude everything after stop time
			if( NO_STOP_TIME != _stopTime ) {
				CCNTime tmpTime = CCNTime.fromBinaryTimeAsLong(_stopTime + 1);
				byte [] stopTimePlusOneComponent = VersioningProfile.timeToVersionComponent(tmpTime);
				components.add(new ExcludeComponent(stopTimePlusOneComponent));
				components.add(new ExcludeAny());
			}
			
			Exclude exclude = new Exclude(components);
			
			Interest interest = Interest.last(			
					_name, 
					exclude, 
					(Integer) _name.count(), 
					(Integer) 3, // dont want anything beyond version/segment/digest
					(Integer) 3, // version, segment, digest
					null // publisher
			);

			_dirty = false;
			_interest = interest;
			return _interest;
		}

		/**
		 * return the last interest built.
		 */
		public Interest getLastInterest() {
			return _interest;
		}
		
		// ===========================
		private final TreeSet<TimeElement> _excludedVersions = new TreeSet<TimeElement>();
		private long _startTime;
		private long _stopTime;
		private boolean _dirty = true;
		private Interest _interest = null;

	}


}
