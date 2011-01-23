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
	 * Return the value of a counter
	 * @param name
	 * @return
	 * @throws IllegalArgumentException if name unrecognized
	 */
	public abstract long getCounter(String name) throws IllegalArgumentException;

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
			for(int i = 0; i < size; i++) {
				_counters[i] = new AtomicLong(0);
			}
		}

		@Override
		public void clearCounters() {
			for(AtomicLong al : _counters)
				al.set(0);
		}

		@Override
		public long getCounter(String name) throws IllegalArgumentException {
			int index = _resolver.getIndex(name);
			return _counters[index].get();
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
				long value = _counters[i].get();
				String units = _resolver.getUnits(i);
				String description = _resolver.getDescription(i);
				
				sb.append(String.format(format, key));

				sb.append(": ");
				sb.append(value);
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

		// =======================
		protected final AtomicLong [] _counters;
		protected final IStatsEnum _resolver;
		protected boolean _enabled = true;

	}

	public static class ExampleClassWithStatistics implements CCNStatistics {
		public enum MyStats implements IStatsEnum {
			// =============================================
			// The properties are of the format:
			// EnumProperty ("units", "short description")
			// To use in a different class, just copy-and-paste, then change this section
			
			SendRequests ("packets", "The number of packets sent"),
			RecvMessages ("packets", "The number of packets received"),
			SendRate ("packets per second", "The 5 minute moving average of packet/sec transmits");

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
		public void send(Object o) {
			_stats.increment(MyStats.SendRequests);
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
