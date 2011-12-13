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
import java.util.logging.Level;

import org.ccnx.ccn.CCNContentHandler;
import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.impl.CCNStats;
import org.ccnx.ccn.impl.CCNStats.CCNEnumStats;
import org.ccnx.ccn.impl.CCNStats.CCNStatistics;
import org.ccnx.ccn.impl.CCNStats.CCNEnumStats.IStatsEnum;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.impl.support.TreeSet6;
import org.ccnx.ccn.profiles.VersionMissingException;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Interest;

/**
 * Manage splitting interests and generating interests for a single base name.
 * This class is used by VersioningInterest, and should not be used stand-alone.
 * 
 * Current interest filling algorithm:
 *    Interests are filled to MIN_FULL exclusions initially and allowed
 *    to grow to MAX_FULL exclusions.    When MAX_FULL exclusions is reached,
 *    the starting times are re-arranged.
 * 
 * This operates by maintaining a set of interests from [startingVersion, infinity).
 * Initially, we issue one interest I_0[startingVersion, infinity).  When that
 * interest fills, it is split in to two interests, and it is split to the left:
 * I_-1[startingVersion, k], I_0[k+1, infinity), where "k" is picked to keep
 * MIN_FILL in the right member (I_0).
 * 
 * "infinity" is really the maximum version component, so it is well-defined.
 * 
 * At some point, we will have a series of interests:
 *   I0[startingVersion, k0], I1[k0+1, k1], I2[k1+1, k2], I3[k2+1, k3], I4[k3+1, infinity)
 *   
 * Algorithm:
 *    - This algorithm is biased towards shifting to the left, because we fill from the right.
 *      Based on usage experience, we may want to change this filling algorithm.
 *    - The density of an InterestData is defined as (# exclusions) / (stop - start).  Seemed
 *      like a good idea at the time, but might want to drop this concept and just use the
 *      # of exclusions.
 *    - We track the average density of all intervals and use that to decide if we should
 *      rebalance by shifing or add a new interest.
 *      
 *    - When we receive an interest, find the corresponding InterestData that contains
 *      the version number.
 *    A) If that InterestData is not full (under MAX_FILL), then add the exclusion to that InterestData
 *      and re-express an Interest from that InterestData via the handleContent return value. Done.
 *    - The InterestData is full.
 *    B) Pick the left or right neighbor that is least full, if such a neighbor exists, and is
 *      under MID_FILL.  If equally filled, pick the left.
 *         - If picked left, transfer exclusions from the head to the left neighbor and then
 *           adjust the start & stop values.  Fill it up to MID_FILL.
 *         - If picked right, transfer exclusions from the tail to the right neighbor and then
 *           adjust the start & stop values.  Fill it up to MID_FILL.
 *         - Issue a new interest for the picked neighbor, then the current InterestData returns
 *           a new interest to handleContent.  These both replace the entries in _interestMap, so
 *           if we receive content for an old outstanding interest, no match is found and no
 *           "extra" interest will be generated from that.
 *         - done
 *   C) If the left or right neighbor does not exist, create it.  If both are missing, pick the left.
 *        - Let the current InterestData be M[a, b].
 *        - If left, create L[a, k] M[k, b] by transferring MIN_FILL elements
 *        - If right, create M[a, k] R[k, b] by transferring MID_FILL elements
 *        - Send a new interest for the new InterestData
 *        - Return a new interest to handleContent for M.
 *   D) If both the L and R neighbors are at MID_FILL or more, then we want to re-balance
 *     the intervals.  
 *        1) If the density of each of (L, M, R) is above average, insert a new interest.  Pick
 *          L or R based on the higher density of the neighbor.  If equal, pick left.
 *          Transfer MIN_FILL from M and the chosen side to the new interest.  Send a new
 *          interest message from the new interest and from the chosen neighbor, then
 *          return a new interest from M to handleContent
 *        2) Otherwise, rebalance.  Pick the denser side, and recursively shift that
 *          direction until the last node is under MID_FILL, or we are at the terminus
 *          and create a new interest.  Then balance current node and neighbor. 
 *          Issue a new interest message for each modified or created InterestData, 
 *          then return interest to handleContent for current interestdata. 
 *   
 */
public class VersioningInterestManager implements CCNContentHandler, CCNStatistics {

	// MIN_FILL should be less than MAX_FILL/2 due to how step D.1 works.
		public final static int MIN_FILL = 50;
		public final static int MID_FILL = 125;
		public final static int MAX_FILL = 200;

	// for testing
//	public final static int MIN_FILL = 5;
//	public final static int MID_FILL = 12;
//	public final static int MAX_FILL = 20;


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
	public VersioningInterestManager(CCNHandle handle, ContentName name, Set<VersionNumber> exclusions, VersionNumber startingVersion, CCNContentHandler handler) {
		_handle = handle;
		_name = name;
		_handler = handler;
		
		if( null == startingVersion )
			_startingVersion = VersionNumber.getMinimumVersion();
		else
			_startingVersion = startingVersion;
		
		if( null != exclusions )
			_exclusions.addAll(exclusions);
	}

	/**
	 * Generate interests and return data to the listener
	 * @throws IOException 
	 */
	public synchronized void start() throws IOException {
		//		_running = true;
		generateInterests();
	}

	/**
	 * cancel all interests and stop operation
	 */
	public synchronized void stop() {
		//		_running = false;
		cancelInterests();
	}

	public Interest handleContent(ContentObject data, Interest interest) {
		return receive(data, interest);
	}

	/**
	 * This is purely a debugging aid
	 */
	public String dumpExcluded() {
		StringBuilder sb = new StringBuilder();
		for( VersionNumber version : _exclusions ) {
			sb.append(version.printAsVersionComponent());
			sb.append(", ");
		}
		return sb.toString();
	}

	// ==============================================
	// Internal implementation
	//	private boolean _running = false;
	protected final CCNHandle _handle;
	private final ContentName _name;
	private final VersionNumber _startingVersion;
	private final CCNContentHandler _handler; // our callback

	// make sure receive() is single-threaded
	private final Object _receiveLock = new Object();
	
	// These are to track the average density
	private double sum_density = 0.0;
	private double average_density = 0.0;

	// used to protect _exclusions, _interestData, and _interestMap
	protected final Object _dataLock = new Object();
	protected final TreeSet6<VersionNumber> _exclusions = new TreeSet6<VersionNumber>();

	// this will be sorted by the starttime of each InterestData
	protected final TreeSet6<InterestData> _interestData = new TreeSet6<InterestData>(new InterestData.StartTimeComparator());

	// We need to store a map from an interest given to the network to the
	// interestData that generated it so we can re-express interest
	// If InterestData is null, the interest is obsolete and should not be re-expressed.
	protected final Map<Interest, InterestMapData> _interestMap = new HashMap<Interest, InterestMapData>();

	/**
	 * Called on start()
	 */
	private void generateInterests() {
		synchronized(_dataLock) {
			// we ask for content from right to left, so fill from right to left
			Iterator<VersionNumber> iter = _exclusions.descendingIteratorCompatible();

			// The first interest (being right most) goes from 0 to infinity.  If it gets
			// filled up, we will set the startTime and create a new one to the left.
			InterestData id = new InterestData(_name, _startingVersion, VersionNumber.getMaximumVersion());

			// fill the current InterestData with exclusions until it is at MIN_FILL,
			// then make a new InterestData with a range below the first one.
			while( iter.hasNext() ) {
				VersionNumber version = iter.next();

				// don't add stuff before the start time
				if( version.before(_startingVersion) )
					break;

				if( id.size() >= MIN_FILL ) {
					VersionNumber t = version.addAndReturn(1);
					id.setStartTime(t);
					// now that the start time is fixed, add to TreeSet
						_interestData.add(id);

					// now make a new one, that ends at the current version
					id = new InterestData(_name, _startingVersion, t);
				}

				id.addExclude(version);
			}
			// now save the last one
			_interestData.add(id);
			
			computeAverageDensity();

			// we now have all the interests done, actually send them
			for(InterestData datum : _interestData) {
				sendInterest(datum);
				Log.info(Log.FAC_ENCODING, "Sent initial interest {0}", datum.getLastInterest());
			}
		}
	}

	private void computeAverageDensity() {
		// this should never happen, we always have at least 1
		if( _interestData.size() == 0 )
			average_density = 0.0;

		for(InterestData datum : _interestData )
			sum_density += datum.getDensity();

		average_density = sum_density / _interestData.size();
	}

	/**
	 * Called on stop()
	 */
	private void cancelInterests() {
		synchronized(_dataLock){
			
			for(Interest interest : _interestMap.keySet() ) {
				_handle.cancelInterest(interest, this);
			}

			_interestMap.clear();
		}
	}

	/**
	 * Called from handleInterest
	 * @param data
	 * @param interest
	 * @return
	 */
	protected Interest receive(ContentObject data, Interest interest) {
		InterestData datum = null;
		Interest newInterest = null;

		// should we reexpress the interest?
		boolean reexpress = false;

		VersionNumber version;

		_stats.increment(StatsEnum.Receive);
		
		// this is to single-thread the whole interest rebuilding process.
		// Used to synchronized handleContent, but that could cause deadlock
		// with the call to _listener.handleContent().
		synchronized(_receiveLock) {
			// Match the interest to our pending interests.  This removes it
			// from the pending interest map.
			synchronized(_dataLock) {
				InterestMapData imd = _interestMap.remove(interest);
				if( null != imd ) {
					datum = imd.getInterestData(); 
					reexpress = imd.getReexpress();
				}
			}
	
			// if we cannot find a version component, just re-express the same interest
			try {
				version = new VersionNumber(data.name());
			} catch (VersionMissingException e) {
				_stats.increment(StatsEnum.ReceiveVersionNumberError);
				e.printStackTrace();
	
				if( null != datum && reexpress )
					newInterest = datum.buildInterest();
	
				if( Log.isLoggable(Log.FAC_ENCODING, Level.FINER))
					Log.finer(Log.FAC_ENCODING, "Returning new interest {0}",
							null == newInterest ? "NULL" : newInterest.toString());
	
				if( null != newInterest )
					_stats.increment(StatsEnum.ReceiveReturnInterest);
	
				return newInterest;
			}
	
			if( null == datum ) {
				_stats.increment(StatsEnum.ReceiveNoPendingInterest);
				if( Log.isLoggable(Log.FAC_ENCODING, Level.WARNING) )
					Log.warning(Log.FAC_ENCODING, "Did not match a pending interest for version {0} interest {1}",
							version.toString(), interest.toString());
			}
	
			// Is this something we should ignore?  This will avoid sending the
			// object up to the user.
			if( version.before(_startingVersion) || VersionNumber.getMaximumVersion().before(version) ) 
			{
				_stats.increment(StatsEnum.ReceiveIgnored);
	
				if( Log.isLoggable(Log.FAC_ENCODING, Level.FINE) )
					Log.fine(Log.FAC_ENCODING, "Ignorning version {0} because outside interval {1} to {2}",
							version.toString(),
							_startingVersion.toString(),
							VersionNumber.getMaximumVersion().toString());		
	
				return null;
			}
	
			// store it in our global list of exclusions
			// If the version is already in the exclusion list, 
			synchronized(_dataLock) {
				if( ! _exclusions.add(version) ) {
					if( Log.isLoggable(Log.FAC_ENCODING, Level.FINE) )
						Log.fine(Log.FAC_ENCODING, "Receive duplicate version {0}",
								version.toString());
					_stats.increment(StatsEnum.ReceiveDuplicates);
	
				} else {
					if( Log.isLoggable(Log.FAC_ENCODING, Level.FINER) )
						Log.finer(Log.FAC_ENCODING, "Receive version {0}",
								version.toString());	
					_stats.increment(StatsEnum.ReceiveUnique);
				}
			}
	
			InterestData excludeDatum = findInterestContainingVersion(version, datum);
	
			if( null != excludeDatum ) {
				if( Log.isLoggable(Log.FAC_ENCODING, Level.FINE))
					Log.fine(Log.FAC_ENCODING, "Excluding version {0} from InterestData {1}",
							version.toString(),
							excludeDatum);		
	
				if( !excludeDatum.addExclude(version)) {
					// we cannot put the new exclusion in there, so we need to rebuild
					rebuild(version, excludeDatum);
				}
	
				if( reexpress ) {
					newInterest = excludeDatum.buildInterest();
					synchronized(_dataLock) {
						_interestMap.put(newInterest, new InterestMapData(excludeDatum));
					}
				} else {
					if( Log.isLoggable(Log.FAC_ENCODING, Level.FINE))
						Log.fine(Log.FAC_ENCODING, "Re-express false, so letting interest die: {0}",
								interest.toString());
				}
			}
		} // synchronized(_receiveLock)

		// pass it off to the user
		_handler.handleContent(data, interest);

		if( Log.isLoggable(Log.FAC_ENCODING, Level.FINER))
			Log.finer(Log.FAC_ENCODING, "Returning new interest {0}",
					null == newInterest ? "NULL" : newInterest.toString());

		if( null != newInterest )
			_stats.increment(StatsEnum.ReceiveReturnInterest);

		return newInterest;
	}


	/**
	 * Find the InterestData that contains #version, using #hint as the
	 * place to look first. 
	 * @param version
	 * @param hint may be null
	 * @return the InterestData that contains version, may be null if none found.
	 */
	private InterestData findInterestContainingVersion(VersionNumber version,
			InterestData hint) {
		// if we did not match anything, then we are no longer interested in it.
		// This most likely happens because of a re-build, in which case we do
		// not need to re-express an interest as the re-build did that.
		if( null != hint && hint.contains(version) )
			return hint;

		InterestData excludeDatum = null;

		// Figure out where to put the exclusion.  Because of re-building,
		// an exclusion will not always go in the original datum.  But,
		// that is usually a great first choice, so try it.  If that does
		// not work, search for where to put it

		// search for where to add the exclusion.  "x" is just to search the tree.
		InterestData x = new InterestData(null, version);

		// This is the InterestData that must contain version because
		// it is the largest startTime that is less than version
		InterestData floor = _interestData.floorCompatible(x);

		// floor shouldn't every be null
		if( null == floor ) {
			Log.warning(Log.FAC_ENCODING, "Warning: floor element is null for version {0}",
					version.toString());	
			return null;
		}

		if( floor.contains(version) ) {
			excludeDatum = floor;
		} else {
			Log.severe(Log.FAC_ENCODING, "Error: floor element {0} did not contain version {1}",
					floor.toString(), version.toString());
			return null;
		}

		return excludeDatum;
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
	protected void rebuild(VersionNumber version, InterestData datum) {
		InterestData left, right;

		_stats.increment(StatsEnum.Rebuild);

		if( Log.isLoggable(Log.FAC_ENCODING, Level.INFO))
			Log.info(Log.FAC_ENCODING, "Rebuilding version {0} data {1}",
					version.toString(), datum.toString());

		synchronized(_dataLock) {
			left = _interestData.lowerCompatible(datum);
			right = _interestData.higherCompatible(datum);
			int left_size = MAX_FILL, right_size = MAX_FILL;
			if( null != left ) 
				left_size = left.size();
	
			if( null != right )
				right_size = right.size();
	
			// Insert "version" into "datum", which will be an overflow condition in datum.
			// this will get balanced out below
			datum.addExcludeUnbounded(version);
	
			// There are the three stages of the algorithm.
			// First, try shifting exclusions to the left or right
			if( !tryLeftOrRightShift(datum, left, left_size, right, right_size) )
				// if that does not work see if we are missing a neighbor, and if so
				// create it.
				if( !tryCreatingMissingNeighbor(datum, left, left_size, right, right_size) )
					// that didn't work, so try rebalancing
					rebalance(datum, left, left_size, right, right_size);	
		}
	}

	/**
	 *    - Pick the left or right neighbor that is least full, if such a neighbor exists, and is
	 *      under MID_FILL.  If equally filled, pick the left.
	 *         - If picked left, transfer exclusions from the head to the left neighbor and then
	 *           adjust the start & stop values.  Fill it up to MID_FILL.
	 *         - If picked right, transfer exclusions from the tail to the right neighbor and then
	 *           adjust the start & stop values.  Fill it up to MID_FILL.
	 *         - Issue a new interest for the picked neighbor, then the current InterestData returns
	 *           a new interest to handleContent.  These both replace the entries in _interestMap, so
	 *           if we receive content for an old outstanding interest, no match is found and no
	 *           "extra" interest will be generated from that.
	 *         - done
	 *         
	 * 
	 */
	private boolean tryLeftOrRightShift(InterestData middle, InterestData left, int left_size, InterestData right, int right_size) {
		// doing this makes the conditions work below for nulls
		if(null == left)
			left_size = MAX_FILL;
		if(null == right)
			right_size = MAX_FILL;

		if( null != left || null != right ) {

			if( (left_size < right_size || left_size == right_size) && left_size < MID_FILL ) {
				// so we can incrementally track density
				sum_density -= middle.getDensity();
				sum_density -= left.getDensity();

				// use the left 

				// this operation will change the start time of middle, so must
				// remove from tree, then re-add it.
				_interestData.remove(middle);
				middle.transferLeft(left, MID_FILL - left_size);
				_interestData.add(middle);
				sendInterest(left);

				sum_density += middle.getDensity();
				sum_density += left.getDensity();
				average_density = sum_density / _interestData.size();

				_stats.increment(StatsEnum.RebuileShiftLeft);

				return true;				
			} else
				if( right_size < left_size && right_size < MID_FILL ) {
					// so we can incrementally track density
					sum_density -= middle.getDensity();
					sum_density -= right.getDensity();

					// use the left 

					// this will change the starttime of right
					_interestData.remove(right);
					middle.transferRight(right, MID_FILL - right_size);
					_interestData.add(right);
					sendInterest(right);

					sum_density += middle.getDensity();
					sum_density += right.getDensity();
					average_density = sum_density / _interestData.size();
					
					_stats.increment(StatsEnum.RebuildShiftRight);
					return true;
				}
		}
		return false;
	}

	/**
	 *   - If the left or right neighbor does not exist, create it.  If both are missing, pick the left.
	 *        - Let the current InterestData be M[a, b].
	 *        - If left, create L[a, k] M[k, b] by transferring MIN_FILL elements
	 *        - If right, create M[a, k] R[k, b] by transferring MID_FILL elements
	 *        - Send a new interest for the new InterestData
	 *        - Return a new interest to handleContent for M.
	 */
	private boolean tryCreatingMissingNeighbor(InterestData middle, InterestData left, int left_size, InterestData right, int right_size) {
		if( null == left ) {
			sum_density -= middle.getDensity();

			// this operation will change the startTime of middle, so it
			// must be removed from the tree then re-added
			_interestData.remove(middle);
			left = middle.splitLeft(MIN_FILL);
			_interestData.add(middle);
			_interestData.add(left);
			sendInterest(left);

			sum_density += middle.getDensity();
			sum_density += left.getDensity();
			average_density = sum_density / _interestData.size();	
			
			_stats.increment(StatsEnum.RebuildCreateLeft);
			return true;
		}

		if( null == right ) {
			sum_density -= middle.getDensity();

			// this operation will change the startTime of middle, so it
			// must be removed from the tree then re-added
			_interestData.remove(middle);
			right = middle.splitRight(MIN_FILL);
			_interestData.add(middle);
			_interestData.add(right);
			sendInterest(right);

			sum_density += middle.getDensity();
			sum_density += right.getDensity();
			average_density = sum_density / _interestData.size();			
			_stats.increment(StatsEnum.RebuildCreateRight);
			return true;
		}

		return false;
	}

	/**         
	 *   - If both the L and R neighbors are at MID_FILL or more, then we want to re-balance
	 *     the intervals.  
	 *        - If the density of each of (L, M, R) is above average, insert a new interest.  Pick
	 *          L or R based on the higher density of the neighbor.  If equal, pick left.
	 *          Transfer MIN_FILL from M and the chosen side to the new interest.  Send a new
	 *          interest message from the new interest and from the chosen neighbor, then
	 *          return a new interest from M to handleContent
	 *        - Otherwise, rebalance.  Pick the denser side, and recursively shift that
	 *          direction until the last node is under MID_FILL, or we are at the terminus
	 *          and create a new interest.  Then balance current node and neighbor. 
	 *          Issue a new interest message for each modified or created InterestData, 
	 *          then return interest to handleContent for current interestdata.
	 *          
	 *  left, middle, and right must not be null
	 */
	private void rebalance(InterestData middle, InterestData left, int left_size, InterestData right, int right_size) {
		if( average_density < left.getDensity() && average_density < middle.getDensity() && average_density < right.getDensity() ) {
			// insert a new element here
			splitInterests(middle, left, left_size, right, right_size);
		} else {
			// rebalance to the left or right, spreading interests evenly
			rollLeftOrRight(middle, left, left_size, right, right_size);
		}

		return;
	}

	private void splitInterests(InterestData middle, InterestData left, int left_size, InterestData right, int right_size) {
		if( left.getDensity() >= right.getDensity() ) {
			sum_density -= middle.getDensity();
			sum_density -= left.getDensity();

			// insert to the left
			_interestData.remove(middle);
			InterestData split = middle.splitLeft(MIN_FILL);
			// does not change startTime of left
			left.transferRight(split, MIN_FILL);
			_interestData.add(middle);
			_interestData.add(split);
			sendInterest(split);
			sendInterest(left);

			sum_density += middle.getDensity();
			sum_density += left.getDensity();
			sum_density += split.getDensity();
			average_density = sum_density / _interestData.size();	
			
			_stats.increment(StatsEnum.RebuildInsertLeft);

		} else {
			sum_density -= middle.getDensity();
			sum_density -= right.getDensity();

			// insert to the left
			_interestData.remove(middle);
			_interestData.remove(right);

			InterestData split = middle.splitRight(MIN_FILL);
			right.transferLeft(split, MIN_FILL);
			_interestData.add(middle);
			_interestData.add(right);
			_interestData.add(split);

			sendInterest(split);
			sendInterest(left);

			sum_density += middle.getDensity();
			sum_density += right.getDensity();
			sum_density += split.getDensity();
			average_density = sum_density / _interestData.size();
			
			_stats.increment(StatsEnum.RebuildInsertRight);
		}
		return;
	}

	private void rollLeftOrRight(InterestData middle, InterestData left, int left_size, InterestData right, int right_size) {

		if( left.getDensity() >= right.getDensity() ) {
			// go left
			InterestData node = middle;
			InterestData next = null;

			while( node.size() >= MID_FILL ) {
				int count = node.size() - MID_FILL;
				next = _interestData.lowerCompatible(node);

				if( null == next ) {
					_interestData.remove(node);
					next = node.splitLeft(count);
					_interestData.add(node);
					_interestData.add(next);
				} else {
					// this might overflow next
					// does not change starttime of next
					_interestData.remove(node);
					node.transferLeft(next, count);
					_interestData.add(node);
				}

				if( node != middle )
					sendInterest(node);

				node = next;
			}

			// At the every end, next will not have had an interest sent
			// because it's size is < MID_FILL
			if( null != next )
				sendInterest(next);
			
			_stats.increment(StatsEnum.RebuildRollLeft);

		} else {
			// go right
			InterestData node = middle;
			InterestData next = null;

			while( node.size() >= MID_FILL ) {
				int count = node.size() - MID_FILL;
				next = _interestData.higherCompatible(node);

				if( null == next ) {
					// right split does not change starttime of node
					next = node.splitRight(count);
					_interestData.add(next);
				} else {
					// this might overflow next
					// changes start time of next
					_interestData.remove(next);
					node.transferRight(next, count);
					_interestData.add(next);
				}

				if( node != middle )
					sendInterest(node);

				node = next;
			}

			// At the every end, next will not have had an interest sent
			// because it's size is < MID_FILL
			if( null != next )
				sendInterest(next);
			
			_stats.increment(StatsEnum.RebuildRollRight);

		}
		computeAverageDensity();
	}


	/**
	 * Send a new interest and manage the _interestMap.  If the
	 * InterestData has an old interest, we set the reexpress flag to false it in the map, so
	 * it will no longer cause a new interest to be sent and then add
	 * the new interest to the map, so when we receive an object for
	 * it, we'll issue a new interest.
	 */
	protected void sendInterest(InterestData id) {
		
		Interest old = id.getLastInterest();
		Interest interest = id.buildInterest();
		synchronized(_interestMap) {
			// Remove the old interest so we never match more than one
			// thing to an INterestData
			if( null != old ) {
				_handle.cancelInterest(old, this);
				_stats.increment(StatsEnum.CancelInterest);
				InterestMapData imd = _interestMap.get(old);
				if( null != imd )
					imd.setReexpress(false);

				if( Log.isLoggable(Log.FAC_ENCODING, Level.FINER) )
					Log.finer(Log.FAC_ENCODING, "sendInterest nulling _interestMap for {0}",old);
			}

			try {
				_handle.expressInterest(interest, this);
				_interestMap.put(interest, new InterestMapData(id));
				_stats.increment(StatsEnum.SendInterest);
				if( Log.isLoggable(Log.FAC_ENCODING, Level.FINER) )
					Log.finer(Log.FAC_ENCODING, "sendInterest setting  _interestMap for {0} to {1}", interest, id);
			} catch(IOException e) {
				_stats.increment(StatsEnum.SendInterestErrors);
				e.printStackTrace();
				if( Log.isLoggable(Log.FAC_ENCODING, Level.SEVERE) )
					Log.severe(Log.FAC_ENCODING, "Error expressing interest: {0}",e.getMessage());
			}
		}
	}


	// ====================================================
	// Inner Classes

	// Each interest we send to the network is mapped back to the originating
	// InterestData.  There is also a flag if this interest should be
	// reexpressed.  On a rebuild(), a new interest is sent, so an
	// old interest should be ignored.
	protected static class InterestMapData {
		public InterestMapData(InterestData data) {
			_data = data;
			_reexpress = true;
		}

		public InterestData getInterestData() { return _data; }
		public boolean getReexpress() { return _reexpress; }
		public void setReexpress(boolean reexpress) { _reexpress = reexpress; }

		@Override
		public int hashCode() { return _data.hashCode(); }

		@Override
		public boolean equals(Object obj) {
			if( !(obj instanceof InterestMapData) )
				return false;
			InterestMapData other = (InterestMapData) obj;
			return _data.equals(other._data);
		}

		protected InterestData _data;
		protected boolean _reexpress;
	}

	// ==============================================================
	// Statistics
	
	protected CCNEnumStats<StatsEnum> _stats = new CCNEnumStats<StatsEnum>(StatsEnum.Receive);

	public CCNStats getStats() {
		return _stats;
	}
	
	public enum StatsEnum implements IStatsEnum {
		// ====================================
		// Just edit this list, dont need to change anything else
		
		Receive ("ContentObjects", "The number of objects received in handleContent"),
		ReceiveVersionNumberError ("Errors", "Errors parsing VersionNumber from content name"),
		ReceiveReturnInterest ("interests", "Number of non-null interests returned from receive()"),
		ReceiveIgnored ("count", "Count of objects ignored because version was out-of-bounds"),
		ReceiveDuplicates ("count", "Count of duplicate version numbers received"),
		ReceiveUnique ("count", "Count of objects with unique version numbers received"),
		ReceiveNoPendingInterest ("Errors", "Received object did not match a pending interest in _interestMap"),
		
		Rebuild ("count", "Calls to rebuild()"),
		RebuileShiftLeft ("count", "Number of left-shift rebuilds"),
		RebuildShiftRight ("count", "Number of right-shift rebuilds"),
		RebuildCreateLeft ("count", "Number of create left neighbor rebuilds"),
		RebuildCreateRight ("count", "Number of create right neighbor rebuilds"),
		RebuildInsertLeft ("count", "Number of inserts to left rebuilds"),
		RebuildInsertRight ("count", "Number of inserts to right rebuilds"),
		RebuildRollLeft ("count", "Number of roll left rebuilds"),
		RebuildRollRight ("count", "Number of roll right rebuilds"),
		
		SendInterest ("count", "Interests sent (not counting ReceiveReturnInterest)"),
		CancelInterest ("count", "Interests cancelled"),
		SendInterestErrors ("errors", "Errors calling expressInterest()"),
		;

		// ====================================
		// This is the same for every user of IStatsEnum
		
		protected final String _units;
		protected final String _description;
		protected final static String [] _names;

		static {
			_names = new String[StatsEnum.values().length];
			for(StatsEnum stat : StatsEnum.values() )
				_names[stat.ordinal()] = stat.toString();

		}

		StatsEnum(String units, String description) {
			_units = units;
			_description = description;
		}

		public String getDescription(int index) {
			return StatsEnum.values()[index]._description;
		}

		public int getIndex(String name) {
			StatsEnum x = StatsEnum.valueOf(name);
			return x.ordinal();
		}

		public String getName(int index) {
			return StatsEnum.values()[index].toString();
		}

		public String getUnits(int index) {
			return StatsEnum.values()[index]._units;
		}

		public String [] getNames() {
			return _names;
		}
	}
}
