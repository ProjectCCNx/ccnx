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

package org.ccnx.ccn.impl;

import java.util.concurrent.atomic.AtomicLong;

import org.ccnx.ccn.impl.CCNStats.CCNEnumStats.IStatsEnum;

/**
 * This is the base class for performance counters.  It is the public API that a user
 * would access to check or display performance counters.  A class that counts statistics
 * should implement CCNStatistics.
 *
 * A typical usage is illustrated in ExampleClassWithStatistics at the end of this
 * module.  By using the helper class CCNEnumStats, a class with statistics only
 * needs to define an enum of the stats it will use, and create a CCNEnumStats
 * member with reference to that Enum.
 *
 * Usage example is also shown in org.ccnx.ccn.test.impl.CCNStatsTest.java
 * 
 * The high-level abstractions are CCNStats and CCNStatistics.  A typical user would
 * only deal with those two things.
 * 
 * The mid-level abstractions are CCNEnumStats and IStatsEnum.  These are for the
 * developer of a class that uses CCNStats.  The developer could, of course, do any
 * implementation that implements CCNStatistics.  The CCNEnumStats abstraction is
 * to simplify the process so the developer only needs to define an Enum of the
 * counters they want to use, and pass that to CCNEnumStats.  The Enum must
 * implement IStatsEnum.  After that, the usage is very simple and automagically
 * gives the CCNStats object needed by the CCNStatistics interface.
 * 
 * The interface CCNCategoriezedStatistics is for modules that keep statistics
 * in several cateogries (e.g. for each ContentName you express an interest for).
 * 
 * Added support for an "averaging" counter.  This will track the sum, sum^2, and
 * count so one can get an average and standard deviation.  Basically, there's 
 * a simple "long" counter and an "averaging counter" for each Enum, so you could
 * use the normal "increment(item)" or the new "addSample(item, value)" calls on any
 * of the Enums.  If you call addSample(item, value), then the item "item" will be
 * tagged as an averaging stat and the toString() method will format it as such.
 * 
 * Might want to add an EWMA type counter too.  I think we'll want to expand the
 * IStatsEnum to make it take a counter type argument.
 */
public abstract class CCNStats {

	public interface CCNStatistics {
		public CCNStats getStats();
	}
	
	public interface CCNCategorizedStatistics {
		/**
		 * Get a list of the category keys.  The toString() method
		 * of the key should be meaningful.
		 */
		public Object [] getCategoryNames();
		
		/**
		 * Get the statistics associated with the key
		 * @param name
		 * @return May be null if #category is not found
		 * @throws ClassCastException if #cateogry is not appropriate
		 */
		public CCNStats getStatsByName(Object category) throws ClassCastException;
	}

	// ===========================================================
	// This is the public API for a user
	
	/**
	 * If enabled, gather stats, otherwise do not
	 * @param enabled
	 */
	public abstract void setEnabled(boolean enabled);

	/**
	 * Return a list of statistic counter names, in the preferred display order
	 * @return
	 */
	public abstract String [] getCounterNames();

	/**
	 * Is the counter an averaging counter?  This will only function
	 * correctly after the system is run for a while and we see if
	 * it is called with increment or addsample.
	 */
	public abstract boolean isAveragingCounter(String name) throws IllegalArgumentException;
	
	/**
	 * Return the value of a counter
	 * @param name
	 * @return
	 * @throws IllegalArgumentException if name unrecognized
	 */
	public abstract long getCounter(String name) throws IllegalArgumentException;

	/**
	 * Return the average and standard deviation of a counter.  You
	 * need to have been accumulating samples with the addSample(item, value)
	 * method.
	 * 
	 * @param name
	 * @return [avg, stdev] array.  May be NaN.
	 * @throws IllegalArgumentException if name unrecognized
	 */
	public abstract double[] getAverageAndStdev(String name) throws IllegalArgumentException;

	/**
	 * Return a text description of the units of the counter (e.g. packets, packets per second)
	 * @param name
	 * @return
	 * @throws IllegalArgumentException if name unrecognized
	 */
	public abstract String getCounterUnits(String name) throws IllegalArgumentException;

	/**
	 * Reset all counters to zero
	 */
	public abstract void clearCounters();

	/**
	 * Dump the counters in the preferred format to a String for display
	 */
	public abstract String toString();

	// =======================================================================================
	// Everything below here is helpers for a developer creating a class that
	// implements CCNStatistics
	
	/**
	 * This is a helper class for implementing statistics
	 */
	public static class CCNEnumStats<K extends Enum<K>> extends CCNStats {

		/**
		 * The statistics Enum used by "K extends Enum<K>" must implement this
		 * interface.  See the example in ExampleClassWithStatistics below for
		 * how to do this.
		 */
		public interface IStatsEnum {
			/**
			 * Given the enum string, return its index value
			 */
			public int getIndex(String name) throws IllegalArgumentException;

			/**
			 * Given an index value, return the name
			 */
			public String getName(int index) throws ArrayIndexOutOfBoundsException;

			/**
			 * Return the units of the count
			 */
			public String getUnits(int index) throws ArrayIndexOutOfBoundsException;

			/**
			 * Return a short description of the counter
			 */
			public String getDescription(int index) throws ArrayIndexOutOfBoundsException;

			/**
			 * Return all counter names
			 */
			public String [] getNames();
		}

		public CCNEnumStats(IStatsEnum stats) {
			_resolver = stats;
			int size = _resolver.getNames().length;
			_counters = new AtomicLong[size];		
			_avgcounters = new AveragingCounter[size];
			
			for(int i = 0; i < size; i++) {
				_counters[i] = new AtomicLong(0);
				_avgcounters[i] = new AveragingCounter();
			}
		}

		@Override
		public void clearCounters() {
			for(AtomicLong al : _counters)
				al.set(0);
			
			for(AveragingCounter ac : _avgcounters)
				ac.clear();
		}

		@Override
		public boolean isAveragingCounter(String name) throws IllegalArgumentException {
			int index = _resolver.getIndex(name);
			return _avgcounters[index].getCount() > 0;
		}
		
		@Override
		public long getCounter(String name) throws IllegalArgumentException {
			int index = _resolver.getIndex(name);
			return _counters[index].get();
		}
		
		@Override
		public double[] getAverageAndStdev(String name) throws IllegalArgumentException {
			int index = _resolver.getIndex(name);
			return _avgcounters[index].getAverageAndDeviation();
		}

		@Override
		public String [] getCounterNames() {
			return _resolver.getNames();
		}

		@Override
		public String getCounterUnits(String name) throws IllegalArgumentException {
			int index = _resolver.getIndex(name);
			return _resolver.getUnits(index);
		}

		@Override
		public void setEnabled(boolean enabled) {
			_enabled = enabled;	
		}

		@Override
		public String toString() {
			// figure out a width
			int width = 1;
			for(int i = 0; i < _counters.length; i++) {
				String key = _resolver.getName(i);
				if( key.length() > width )
					width = key.length();
			}
			
			String format = String.format("%%-%ds", width);
			
			// we should cache this and use a dirty flag
			StringBuilder sb = new StringBuilder();
			for(int i = 0; i < _counters.length; i++) {
				String key = _resolver.getName(i);
				String units = _resolver.getUnits(i);
				String description = _resolver.getDescription(i);
				
				sb.append(String.format(format, key));

				sb.append(": ");
				
				// if we have been accumulating an avg/std, then return it
				// as that, otherwise return it as a counter.
				if( _avgcounters[i].getCount() > 0 ) {
					sb.append(_avgcounters[i].toString());
				} else {
					sb.append(_counters[i].get());
				}

				sb.append(" (");
				sb.append(units);
				sb.append(") ");
				sb.append(description);
				sb.append('\n');
			}
			return sb.toString();
		}

		public void increment(K key) {
			increment(key, 1);
		}

		public void increment(K key, int value) {
			if(_enabled) {
				_counters[key.ordinal()].addAndGet(value);
			}
		}
		
		/**
		 * Add a sample to the averaging counter for the key.  This
		 * make the key an averaging counter as reported by toString().
		 */
		public void addSample(K key, long value) {
			if(_enabled) {
				_avgcounters[key.ordinal()].addSample(value);
			}
		}
		
		// =======================
		protected final AtomicLong [] _counters;
		protected final IStatsEnum _resolver;
		protected boolean _enabled = true;
		protected final AveragingCounter [] _avgcounters;
		
		/**
		 * This is used to track an averaging counter.
		 * This is a thread-safe class, so will work like
		 * the AtomicLong.
		 * 
		 * Returns the mean (sum/count) and sample standard
		 * deviation.  The sample standard deviation is:
		 * 
		 * 1/(N-1) * Sum(x_i - mean)^2 = N/(N-1) * ( 1/N * sum^2 - mean^2)
		 */
		private static class AveragingCounter {
			public AveragingCounter() {
				clear();
			}
			
			public synchronized void addSample(long sample) {
				_sum += sample;
				_sum2+= sample * sample;
				_count++;
				_dirty = true;
			}
			
			public synchronized void clear() {
				_sum = 0;
				_sum2= 0;
				_count=0;
				_dirty = true;
			}
			
			/**
			 * returns the [average, stdev] pair.  Both may be NaN if there
			 * are not enough samples (need 1 for avg, 2 for stdev).
			 */
			public synchronized double[] getAverageAndDeviation() {
				if(_dirty) {
					calculate();
				}
				
				// return a copy so values inside the array cannot be modified
				double[] out = new double[2];
				out[0] = _avg;
				out[1] = _std;
				return out;
			}

			public synchronized long getCount() {
				return _count;
			}
			
			public synchronized String toString() {
				if(_dirty) {
					calculate();
				}
				
				return _string;
			}
			
			// ============================
			private boolean _dirty = false;
			private long _sum;
			private long _sum2;
			private long _count;
			private double _avg, _std;
			private String _string;
			
			// must be called in a synchronized method
			private void calculate() {
				_avg = Double.NaN;
				_std = Double.NaN;
				if( _count > 0 ) {
					_avg = (double) _sum / _count;
					
					if( _count > 1 ) {
						double inner = (double) _sum2 / _count - (_avg * _avg);
						double var = _count/(_count-1) * inner ;
						_std = Math.sqrt(var);
					}
				}
				
				_string = String.format("avg %.3g stdev %.3g", _avg, _std);
				_dirty = false;
			}
		}
	}

	public static class ExampleClassWithStatistics implements CCNStatistics {
		public enum MyStats implements IStatsEnum {
			// =============================================
			// The properties are of the format:
			// EnumProperty ("units", "short description")
			// To use in a different class, just copy-and-paste, then change this section
			
			SendRequests ("packets", "The number of packets sent"),
			RecvMessages ("packets", "The number of packets received"),
			SendRate ("packets per second", "The average of packet/sec transmits"),
			BytesPerPacket ("bytes per packet", "The average of bytes per packet transmits");

			// ============================================
			// Everything below here is the internal implementation for the
			// IStatsEnum interface.  You should not need to change anything
			
			protected final String _units;
			protected final String _description;
			protected final static String [] _names;

			static {
				_names = new String[MyStats.values().length];
				for(MyStats stat : MyStats.values() )
					_names[stat.ordinal()] = stat.toString();

			}

			MyStats(String units, String description) {
				_units = units;
				_description = description;
			}

			public String getDescription(int index) {
				return MyStats.values()[index]._description;
			}

			public int getIndex(String name) {
				MyStats x = MyStats.valueOf(name);
				return x.ordinal();
			}

			public String getName(int index) {
				return MyStats.values()[index].toString();
			}

			public String getUnits(int index) {
				return MyStats.values()[index]._units;
			}

			public String [] getNames() {
				return _names;
			}
		}

		/**
		 * Instantiate our stats counter.  Note that we need to pass a Java generic type
		 * for our Enum and then pass an instance of the Enum to the constructor.  It does
		 * not matter which enum value we pass to the constructor, it just needs a concrete
		 * object it can reference.
		 */
		CCNEnumStats<MyStats> _stats = new CCNEnumStats<MyStats>(MyStats.SendRequests);
		
		// These are example methods showing the typical calling conventions
		// track the last time we sent a packet to get the packets/sec counter.
		private long _lastsend = System.currentTimeMillis() / 1000;
		private long _pktcount = 0;
		public void send(Object o, int len) {
			_stats.increment(MyStats.SendRequests);
			// some example proxy for byte size
			_stats.addSample(MyStats.BytesPerPacket, len);
			
			long now = System.currentTimeMillis() / 1000;
			// how many seconds has it been?
			long secs = now - _lastsend;
			_lastsend = now;
			
			_pktcount ++;
			if( secs > 0 ) {
				// integer pps.
				long value = _pktcount / secs;
				_stats.addSample(MyStats.SendRate, value);
				_pktcount = 0;
			}

		}

		public void recv(Object o) {
			_stats.increment(MyStats.RecvMessages);
		}

		/**
		 * Implement the IStatsEnum interface
		 */
		public CCNStats getStats() {
			return _stats;
		}
		
	}
}
