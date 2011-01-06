package org.ccnx.ccn.profiles.versioning;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.TreeSet;
import java.util.logging.Level;

import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.protocol.CCNTime;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.Exclude;
import org.ccnx.ccn.protocol.ExcludeAny;
import org.ccnx.ccn.protocol.ExcludeComponent;
import org.ccnx.ccn.protocol.Interest;


/**
 * Stores state about a specific Interest on the wire.
 *
 */
public class InterestData implements Comparable<InterestData> {
	public final static long NO_STOP_TIME = -1;
	
	/**
	 * @param startTime minimum version to include (milliseconds)
	 * @param stopTime maximum version to include (use NO_STOP_TIME for infinity) (milliseconds)
	 */
	public InterestData(ContentName basename, long startTime, long stopTime) {
		_name = basename;
		setStartTime(startTime);
		setStopTime(stopTime);
	}

	public synchronized int size() {
		return _excludedVersions.size();
	}
	
	// sorted based on startTime
//	@Override
//	public synchronized int compareTo(Object arg0) throws ClassCastException {
//		if( ! (arg0 instanceof InterestData) )
//			throw new ClassCastException("Not instance of InterestData");
//		InterestData other = (InterestData) arg0;
//		if( _startTime < other._startTime ) return -1;
//		if( _startTime > other._startTime ) return +1;
//		return 0;
//	}
	

	@Override
	public int compareTo(InterestData other) {
		if( _startTime < other._startTime ) return -1;
		if( _startTime > other._startTime ) return +1;
		return 0;
	}

	/**
	 * Dont do this while in a sorted set, as the sort order will break.
	 * Start time is the minimum version to include.
	 * in milliseconds (not binarytime)
	 */
	public synchronized void setStartTime(long time) {
		_startTime = time;
		_dirty = true;
	}

	/**
	 * stopTime is the maximum version to include.
	 * use NO_STOP_TIME for infinity
	 * in milliseconds (not binarytime)
	 */
	public synchronized void setStopTime(long time) {
		_stopTime = time;
		_dirty = true;
	}

	/**
	 * Returns false if too many excludes in this Interest
	 * @param version
	 * @return
	 */
	public synchronized boolean addExclude(CCNTime version) {
		if( _excludedVersions.size() >= VersioningInterestManager.MAX_FILL )
			return false;
		
		TimeElement te = new TimeElement(version);
		_excludedVersions.add(te);
		_dirty = true;
		return true;
	}

	public synchronized Interest buildInterest() {
		if( !_dirty )
			return _interest;

		ArrayList<Exclude.Element> components = new ArrayList<Exclude.Element>();

		if( _startTime > 0 ) {
			CCNTime tmpTime = new CCNTime(_startTime - 1);
			byte [] startTimeMinusOneComponent = VersioningProfile.timeToVersionComponent(tmpTime);

			components.add(new ExcludeAny());
			components.add(new ExcludeComponent(startTimeMinusOneComponent));
			
			if( Log.isLoggable(Log.FAC_ENCODING, Level.FINEST) )
				Log.finest(Log.FAC_ENCODING, "Exclusion: start version {0}", VersioningProfile.printAsVersionComponent(tmpTime));
		}

		// Now add the specific exclusions
		
		if( !_excludedVersions.isEmpty() ) {
			// TreeSet is sorted, so this is in right order for the exclusion filter
			Iterator<TimeElement> i = _excludedVersions.iterator();
			while( i.hasNext() ) {
				TimeElement elem = i.next();
				components.add(new ExcludeComponent(elem.versionComponent));
			}
		}

		// Now exclude everything after stop time
		if( NO_STOP_TIME != _stopTime ) {
			CCNTime tmpTime =  new CCNTime(_stopTime + 1);
			byte [] stopTimePlusOneComponent = VersioningProfile.timeToVersionComponent(tmpTime);
			components.add(new ExcludeComponent(stopTimePlusOneComponent));
			components.add(new ExcludeAny());
			
			if( Log.isLoggable(Log.FAC_ENCODING, Level.FINEST) )
				Log.finest(Log.FAC_ENCODING, "Exclusion: stop  version {0}", VersioningProfile.printAsVersionComponent(tmpTime));

		}
		
		Exclude exclude = new Exclude(components);
		
		if( Log.isLoggable(Log.FAC_ENCODING, Level.FINEST) )
			Log.finest(Log.FAC_ENCODING, "Exclusion: {0}", exclude.toString());
		
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
	
	/**
	 * Is #version contained in [startTime, stopTime]?
	 * @param version
	 * @return
	 */
	public synchronized boolean contains(CCNTime version) {
		long t = version.getTime();
		if( _startTime <= t && t <= _stopTime )
			return true;
		return false;
	}
	
	public String toString() {
		return String.format("InterestData(%s, %d, %d)", _name, _startTime, _stopTime);
	}
	
	/**
	 * Split this object to the left, keeping MIN_FILL elements here
	 * and shifting what's remaining to the new object.
	 */
	public InterestData splitLeft() {
		// create a pristine object
		InterestData left = new InterestData(_name, _startTime, _stopTime);
		
		// how many items do we want to move?
		int count = _excludedVersions.size() - VersioningInterestManager.MIN_FILL;
		
		// walk from the left
		Iterator<TimeElement> iter = _excludedVersions.iterator();
		
		// this is a redundant condition
		CCNTime lastversion = null;
		while( count-- > 0 && iter.hasNext() ) {
			TimeElement te = iter.next();
			lastversion = te.version;
			left.addExclude(lastversion);
			iter.remove();
		}
		
		// now fixup the start and stop times
		left.setStopTime(lastversion.getTime());
		
		// add 1 millisecond
		this.setStartTime(lastversion.getTime() + 1);
		
		if( Log.isLoggable(Log.FAC_ENCODING, Level.INFO) ) 
			Log.info(Log.FAC_ENCODING, String.format("SplitLeft %s and %s", left.toString(), this.toString()));
		
		return left;
	}
	
	public synchronized long getStartTime() {
		return _startTime;
	}
	
	public synchronized long getStopTime() {
		return _stopTime;
	}
	
	/**
	 * Sanity check that all the excluded versions fall between
	 * [start, stop] inclusive
	 * @return
	 */
	public synchronized boolean validate() {
		for(TimeElement te : _excludedVersions) {
			long t = te.version.getTime();
			if( t < _startTime || _stopTime < t)
				return false;
		}
		return true;
	}
	
	// ===========================
	private final TreeSet<TimeElement> _excludedVersions = new TreeSet<TimeElement>();
	private final ContentName _name;
	private long _startTime;
	private long _stopTime;
	private boolean _dirty = true;
	private Interest _interest = null;
	
	// ===================================
	// Inner classes
	
	// public for testing
	public static class TimeElement implements Comparable<TimeElement> {
		public final CCNTime version;
		public final byte [] versionComponent;

		public TimeElement(CCNTime version) {
			this.version = version;
			this.versionComponent = VersioningProfile.timeToVersionComponent(version);
		}

		// So it is sortable
		@Override
		public int compareTo(TimeElement other) {
			return version.compareTo(other.version);
		}	
	}
}
