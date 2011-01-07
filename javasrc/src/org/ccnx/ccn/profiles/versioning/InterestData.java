package org.ccnx.ccn.profiles.versioning;

import java.security.InvalidParameterException;
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
 * Stores state about a specific Interest on the wire.  This class does not
 * do any network transactions, it only stores state about a specific interest
 * and will generate a new Interest message based on its current start, stop,
 * and exclusion list.
 */
public class InterestData implements Comparable<InterestData> {
	
	// this is the inclusive top value to use
	public final static long NO_STOP_TIME = VersioningProfile.getVersionComponentAsLong(VersioningProfile.LAST_VERSION_MARKER);
	
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
	
	/**
	 * Order by startTime using UNSIGNED COMPARISON
	 */
	@Override
	public int compareTo(InterestData other) {
		if( VersioningInterestManager.isLessThanUnsigned(_startTime, other._startTime) ) return -1;
		if( VersioningInterestManager.isLessThanUnsigned(other._startTime, _startTime) ) return +1;
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
		
		ExcludeComponent lastComponentExcluded = null;
		if( !_excludedVersions.isEmpty() ) {
			// TreeSet is sorted, so this is in right order for the exclusion filter
			Iterator<TimeElement> i = _excludedVersions.iterator();
			while( i.hasNext() ) {
				TimeElement elem = i.next();
				lastComponentExcluded = new ExcludeComponent(elem.versionComponent);
				components.add(lastComponentExcluded);
			}
		}

		// Now exclude everything after stop time
		ExcludeComponent exStop = null;
		if( _stopTime != NO_STOP_TIME ) {
			CCNTime tmpTime =  new CCNTime(_stopTime + 1);
			byte [] stopTimePlusOneComponent = VersioningProfile.timeToVersionComponent(tmpTime);
			exStop = new ExcludeComponent(stopTimePlusOneComponent);
			
		} else {
			exStop = new ExcludeComponent(VersioningProfile.TOP_EXCLUDE_VERSION_MARKER);
		}
		
		// It could happen that our stop time is exactly equal to the version of an
		// exclusion we already made.  if that's the case, don't add a duplicate
		if( null != lastComponentExcluded && ! lastComponentExcluded.equals(exStop) )
			components.add(exStop);

		components.add(new ExcludeAny());
		
		if( Log.isLoggable(Log.FAC_ENCODING, Level.FINEST) )
			Log.finest(Log.FAC_ENCODING, "Exclusion: stop  version {0}", exStop.toString());
		
		Exclude exclude;
		
		try {
			exclude = new Exclude(components);
		} catch(InvalidParameterException ipe) {
			ipe.printStackTrace();
			Log.severe(Log.FAC_ENCODING, "Parameters: " + components.toString());
			throw ipe;
		}
		
		if( Log.isLoggable(Log.FAC_ENCODING, Level.FINEST) )
			Log.finest(Log.FAC_ENCODING, "Exclusion: {0}", exclude.toString());
		
		Interest interest = Interest.last(			
				_name, 
				exclude, 
				(Integer) _name.count(), 
				(Integer) 2, // dont want anything beyond version/segment
				(Integer) 2, // version, segment
				null // publisher
		);

		// recompute density too.  This is the only place
		// where we set _dirty to false
		getDensity();
		
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
	 * Uses UNSIGNED COMPARISON
	 * @param version
	 * @return
	 */
	public synchronized boolean contains(CCNTime version) {
		long t = version.getTime();
		
		if( VersioningInterestManager.isLessThanUnsigned(t, _startTime)  ||
			VersioningInterestManager.isLessThanUnsigned(_stopTime, t) )
			return false;
		return true;
	}
	
	public String toString() {
		return String.format("InterestData(%s, %d, %d)", _name, _startTime, _stopTime);
	}
	
	public String dumpContents() {
		StringBuilder sb = new StringBuilder();
		for( TimeElement te : _excludedVersions ) {
			sb.append(VersioningProfile.printAsVersionComponent(te.version));
			sb.append(", ");
		}
		return sb.toString();
	}
	
	/**
	 * Split this object to the left, transfering #count elements
	 */
	public InterestData splitLeft(int count) {
		// create a pristine object
		InterestData left = new InterestData(_name, _startTime, _stopTime);
		if( count > 0 )
			transferLeft(left, count);		
		return left;
	}
	
	/**
	 * Split this object to the right, transfering #count elements
	 */
	public InterestData splitRight(int count) {
		// create a pristine object
		InterestData right = new InterestData(_name, _startTime, _stopTime);
		
		if( count > 0 )
			transferRight(right, count);
		
		return right;
	}
	
	/**
	 * transfer #count items from head of exclusion list to #left.  Caller
	 * has verified that #count items will fit in #left.
	 * @param left
	 * @param count
	 */
	public void transferLeft(InterestData left, int count) {
		if( count <= 0 )
			return;
		
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
			Log.info(Log.FAC_ENCODING, String.format("TransferLeft  %s and %s", left.toString(), this.toString()));
	}
	
	/**
	 * transfer #count items from tail of exclusion list to #right.  Caller
	 * has verified that #count items will fit in #right.
	 * @param right
	 * @param count
	 */
	public void transferRight(InterestData right, int count) {
		if( count <= 0 )
			return;

		// walk from the right
		Iterator<TimeElement> iter = _excludedVersions.descendingIterator();
		
		// this is a redundant condition
		CCNTime lastversion = null;
		while( count-- > 0 && iter.hasNext() ) {
			TimeElement te = iter.next();
			lastversion = te.version;
			right.addExclude(lastversion);
			iter.remove();
		}
		
		// now fixup the start and stop times
		right.setStartTime(lastversion.getTime());
		
		// subtract 1 millisecond
		this.setStopTime(lastversion.getTime() - 1);
		
		if( Log.isLoggable(Log.FAC_ENCODING, Level.INFO) ) 
			Log.info(Log.FAC_ENCODING, String.format("TransferRight %s and %s", right.toString(), this.toString()));
	}
	
	public synchronized long getStartTime() {
		return _startTime;
	}
	
	public synchronized long getStopTime() {
		return _stopTime;
	}
	
	/**
	 * @return stopTime - startTime + 1
	 */
	public synchronized long getWidth() {
		return _stopTime - _startTime + 1;
	}
	
	public synchronized double getDensity() {
		if( ! _dirty )
			return _density;
		
		_density = (double) size() / getWidth();
		return _density;
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
	private double _density;
	
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
