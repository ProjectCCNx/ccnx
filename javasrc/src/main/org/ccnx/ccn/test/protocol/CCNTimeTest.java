/*
 * A CCNx library test.
 *
 * Copyright (C) 2008, 2009, 2011 Palo Alto Research Center, Inc.
 *
 * This work is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License version 2 as published by the
 * Free Software Foundation. 
 * This work is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License
 * for more details. You should have received a copy of the GNU General Public
 * License along with this program; if not, write to the
 * Free Software Foundation, Inc., 51 Franklin Street, Fifth Floor,
 * Boston, MA 02110-1301, USA.
 */

package org.ccnx.ccn.test.protocol;

import java.math.BigInteger;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.Date;
import java.util.Random;

import junit.framework.Assert;

import org.ccnx.ccn.impl.support.DataUtils;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.protocol.CCNTime;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Test CCN quantized time wrapper.
 */
public class CCNTimeTest {

	static int NUM_RUNS = 10;
	static Random random = new Random();
	static Timestamp early, middle, late;
	static Date dearly, dmiddle, dlate;

	/**
	 * @throws java.lang.Exception
	 */
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		dearly = new Date();
		early = new Timestamp(System.currentTimeMillis());
		Thread.sleep(10);
		dmiddle = new Date();
		middle = new Timestamp(System.currentTimeMillis());
		Thread.sleep(10);
		dlate = new Date();
		late = new Timestamp(System.currentTimeMillis());
	}

	/**
	 * Test method for CCNTime#setTime(long).
	 */
	@Test
	public void testSetTime() {
		Log.info(Log.FAC_TEST, "Starting testSetTime");

		for (int i=0; i < NUM_RUNS; ++i) {
			Timestamp e2 = new Timestamp(early.getTime());
			e2.setNanos(early.getNanos());
			Assert.assertEquals(early, e2);

			CCNTime c2 = new CCNTime(e2);
			Assert.assertTrue(timestampEquals(e2, c2));
			Assert.assertTrue(c2.equals(e2));
			long newTime = e2.getTime() + random.nextInt(1000 * 60 * 60 * 24 * 7); // up to a week

			e2.setTime(newTime);
			c2.setTime(newTime);

			Assert.assertTrue(timestampEquals(e2, c2));
			Assert.assertTrue(c2.equals(e2));
			testTimestamp(c2, e2);
		}
		
		Log.info(Log.FAC_TEST, "Completed testSetTime");
	}

	/**
	 * Test method for CCNTime#setNanos(int).
	 */
	@Test
	public void testSetNanos() {
		Log.info(Log.FAC_TEST, "Starting testSetNanos");

		for (int i=0; i < NUM_RUNS; ++i) {
			Timestamp e2 = new Timestamp(early.getTime());
			e2.setNanos(early.getNanos());
			Assert.assertEquals(early, e2);

			CCNTime c2 = new CCNTime(e2);
			Assert.assertTrue(timestampEquals(e2, c2));
			Assert.assertTrue(c2.equals(e2));
			// 999877929 is the highest value that doesn't quantize over the Timestamp limit of 999999999
			int newNanos = random.nextInt(CCNTime.NANOS_MAX);
			e2.setNanos(newNanos);
			c2.setNanos(newNanos);

			Assert.assertTrue(timestampEquals(e2, c2));
			Assert.assertTrue(c2.equals(e2));
			testTimestamp(c2, e2);
		}
		
		Log.info(Log.FAC_TEST, "Completed testSetNanos");
	}

	/**
	 * Test method for CCNTime#CCNTime(long).
	 */
	@Test
	public void testCCNTimeLong() {
		Log.info(Log.FAC_TEST, "Starting testCCNTimeLong");

		for (int i=0; i < NUM_RUNS; ++i) {
			long msec = System.currentTimeMillis();
			CCNTime t1 = new CCNTime(msec);
			Timestamp ts1 = new Timestamp(msec);
			testTimestamp(t1, ts1);
		}
		
		Log.info(Log.FAC_TEST, "Completed testCCNTimeLong");
	}

	/**
	 * Test method for CCNTime#CCNTime(java.sql.Timestamp).
	 */
	@Test
	public void testCCNTimeTimestamp() {
		Log.info(Log.FAC_TEST, "Starting testCCNTimeTimestamp");

		for (int i=0; i < NUM_RUNS; ++i) {
			Timestamp ts2 = new Timestamp(System.currentTimeMillis());
			ts2.setNanos(random.nextInt(10000000));
			CCNTime t2 = new CCNTime(ts2);
			testTimestamp(t2, ts2);
		}
		
		Log.info(Log.FAC_TEST, "Completed testCCNTimeTimestamp");
	}

	/**
	 * Test method for CCNTime#CCNTime(java.util.Date).
	 */
	@Test
	public void testCCNTimeDate() {
		Log.info(Log.FAC_TEST, "Starting testCCNTimeDate");

		for (int i=0; i < NUM_RUNS; ++i) {
			Date now = new Date();
			CCNTime timeNow = new CCNTime(now);
			Timestamp tsn = new Timestamp(now.getTime());
			testTimestamp(timeNow, tsn);
		}
		
		Log.info(Log.FAC_TEST, "Completed testCCNTimeDate");
	}

	/**
	 * Test method for CCNTime#CCNTime().
	 */
	@Test
	public void testCCNTime() {
		Log.info(Log.FAC_TEST, "Starting testCCNTime");

		for (int i=0; i < NUM_RUNS; ++i) {
			CCNTime now = new CCNTime();
			Timestamp tn = new Timestamp(now.getTime());
			tn.setNanos(now.getNanos());
			testTimestamp(now, tn);
		}
		// Sample problem case
		long msec = 1252088415990L;
		CCNTime m = new CCNTime(msec);
		Timestamp tm = new Timestamp(msec);
		Timestamp tn = new Timestamp(m.getTime());
		tn.setNanos(m.getNanos());
		Timestamp rm = roundTimestamp(tm);
		testTimestamp(m, tm);		
		testTimestamp(m, rm);		
		testTimestamp(m, tn);
		
		Log.info(Log.FAC_TEST, "Completed testCCNTime");
	}

	/**
	 * Test method for CCNTime#compareTo(java.util.Date).
	 */
	@Test
	public void testCompareToDate() {
		Log.info(Log.FAC_TEST, "Starting testCompareToDate");

		CCNTime ce = new CCNTime(dearly);
		CCNTime cm = new CCNTime(dmiddle);
		CCNTime cl = new CCNTime(dlate);
		Assert.assertTrue(ce.compareTo(dmiddle) < 0);
		Assert.assertTrue(ce.compareTo(dlate) < 0);
		Assert.assertTrue(cm.compareTo(dearly) > 0);
		Assert.assertTrue(cm.compareTo(dlate) < 0);
		Assert.assertTrue(cl.compareTo(dearly) > 0);
		Assert.assertTrue(cl.compareTo(dmiddle) > 0);
		// Can't compare the other way. Date.compareTo returning 1 for all targets, whether CCNTime or Timestamp. Only works on other Dates
		
		Log.info(Log.FAC_TEST, "Completed testCompareToDate");
	}

	/**
	 * Test method for CCNTime#compareTo(java.sql.Timestamp).
	 */
	@Test
	public void testCompareToTimestamp() {
		Log.info(Log.FAC_TEST, "Starting testCompareToTimestamp");

		CCNTime ce = new CCNTime(early);
		CCNTime cm = new CCNTime(middle);
		CCNTime cl = new CCNTime(late);
		Assert.assertTrue(ce.compareTo(middle) < 0);
		Assert.assertTrue(ce.compareTo(late) < 0);
		Assert.assertTrue(cm.compareTo(early) > 0);
		Assert.assertTrue(cm.compareTo(late) < 0);
		Assert.assertTrue(cl.compareTo(early) > 0);
		Assert.assertTrue(cl.compareTo(middle) > 0);
		Assert.assertTrue(early.compareTo(cm) < 0);
		Assert.assertTrue(early.compareTo(cl) < 0);
		Assert.assertTrue(middle.compareTo(ce) > 0);
		Assert.assertTrue(middle.compareTo(cl) < 0);
		Assert.assertTrue(late.compareTo(ce) > 0);
		Assert.assertTrue(late.compareTo(cm) > 0);
		
		Log.info(Log.FAC_TEST, "Completed testCompareToTimestamp");
	}

	/**
	 * Test method for CCNTime#before(java.sql.Timestamp) and CCNTime#after(java.sql.Timestamp).
	 */
	@Test
	public void testBeforeAfterTimestamp() {
		Log.info(Log.FAC_TEST, "Starting testBeforeAfterTimestamp");

		CCNTime ce = new CCNTime(early);
		CCNTime cm = new CCNTime(middle);
		CCNTime cl = new CCNTime(late);
		Assert.assertTrue(ce.before(middle));
		Assert.assertTrue(ce.before(late));
		Assert.assertTrue(cm.after(early));
		Assert.assertTrue(cm.before(late));
		Assert.assertTrue(cl.after(early));
		Assert.assertTrue(cl.after(middle));
		Assert.assertTrue(early.before(cm));
		Assert.assertTrue(early.before(cl));
		Assert.assertTrue(middle.after(ce));
		Assert.assertTrue(middle.before(cl));
		Assert.assertTrue(late.after(ce));
		Assert.assertTrue(late.after(cm));
		
		Log.info(Log.FAC_TEST, "Completed testBeforeAfterTimestamp");
	}


	/**
	 * Test method for CCNTime#before(java.util.Date) and CCNTime#after(java.util.Date).
	 */
	@Test
	public void testBeforeAfterDate() {
		Log.info(Log.FAC_TEST, "Starting testBeforeAfterDate");

		CCNTime ce = new CCNTime(dearly);
		CCNTime cm = new CCNTime(dmiddle);
		CCNTime cl = new CCNTime(dlate);
		Assert.assertTrue(ce.before(dmiddle));
		Assert.assertTrue(ce.before(dlate));
		Assert.assertTrue(cm.after(dearly));
		Assert.assertTrue(cm.before(dlate));
		Assert.assertTrue(cl.after(dearly));
		Assert.assertTrue(cl.after(dmiddle));
		// Date's comparisons not happy with timestamps
		
		Log.info(Log.FAC_TEST, "Completed testBeforeAfterDate");
	}

	/**
	 * Test method for CCNTime#now().
	 */
	@Test
	public void testNow() {
		Log.info(Log.FAC_TEST, "Starting testNow");

		for (int i=0; i < NUM_RUNS; ++i) {
			CCNTime now = CCNTime.now();
			Timestamp tn = new Timestamp(now.getTime());
			tn.setNanos(now.getNanos());
			testTimestamp(now, tn);
		}
		
		Log.info(Log.FAC_TEST, "Completed testNow");
	}
	
	@Test
	public void testBinaryArray() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testBinaryArray");

		long v0 = 0x7FFFFF;
		byte [] b0 = new byte [] {(byte) 0x7F, (byte) 0xFF, (byte) 0xFF};
		CCNTime t0 = CCNTime.fromBinaryTimeAsLong(v0);
		byte [] x0 = t0.toBinaryTime();
		Assert.assertTrue(java.util.Arrays.equals(b0, x0));
		
		long v1 = 0x80FFFF;
		byte [] b1 = new byte [] {(byte) 0x80, (byte) 0xFF, (byte) 0xFF};
		CCNTime t1 = CCNTime.fromBinaryTimeAsLong(v1);
		byte [] x1 = t1.toBinaryTime();
		Assert.assertTrue(java.util.Arrays.equals(b1, x1));
		
		Log.info(Log.FAC_TEST, "Completed testBinaryArray");
	}

	public void testTimestamp(CCNTime ccnTime, Timestamp compareTS) {

		Timestamp rounded = roundTimestamp(compareTS);
		Assert.assertTrue(timestampEquals(compareTS, rounded));
		Assert.assertTrue(timestampEquals(rounded, ccnTime));
		Assert.assertEquals(rounded, ccnTime);
		Assert.assertTrue(timestampEquals(compareTS, ccnTime));

		long ts12 = timestampToBinaryTime12AsLong(compareTS);
		long r12 = timestampToBinaryTime12AsLong(rounded);
		long c12 = timestampToBinaryTime12AsLong(ccnTime);
		long c12t = ccnTime.toBinaryTimeAsLong();
		Assert.assertEquals(ts12, r12);
		Assert.assertEquals(ts12, c12);
		Assert.assertEquals(ts12, c12t);

		byte [] tsb12 = timestampToBinaryTime12(compareTS);
		byte [] rb12 = timestampToBinaryTime12(rounded);
		byte [] cb12 = timestampToBinaryTime12(ccnTime);
		byte [] cb12t = ccnTime.toBinaryTime();

		Assert.assertTrue(Arrays.equals(tsb12, rb12));
		Assert.assertTrue(Arrays.equals(tsb12, cb12));
		Assert.assertTrue(Arrays.equals(tsb12, cb12t));

		CCNTime cfb = new CCNTime(cb12t);
		Assert.assertEquals(cfb, ccnTime);

		CCNTime cfl = CCNTime.fromBinaryTimeAsLong(c12);
		Assert.assertEquals(cfl, ccnTime);
	}

	/** 
	 * Old static quantized time interface. Move here as "ground truth", as we
	 * know it is compatible with the C side; use it to test against.
	 */

	/**
	 * Converts a timestamp into a fixed point representation, with 12 bits in the fractional
	 * component, and adds this to the ContentName as a version field. The timestamp is rounded
	 * to the nearest value in the fixed point representation.
	 * <p>
	 * This allows versions to be recorded as a timestamp with a 1/4096 second accuracy.
	 */
	public static byte [] timestampToBinaryTime12(Timestamp timestamp) {
		long timeVal = timestampToBinaryTime12AsLong(timestamp);
		return BigInteger.valueOf(timeVal).toByteArray();
	}

	public static long timestampToBinaryTime12AsLong(Timestamp timestamp) {
		long binaryTime12AsLong = (timestamp.getTime() / 1000) * 4096L + (timestamp.getNanos() * 4096L + 500000000L) / 1000000000L;
		return binaryTime12AsLong;
	}

	public static Timestamp binaryTime12ToTimestamp(byte [] binaryTime12) {
		if ((null == binaryTime12) || (binaryTime12.length == 0)) {
			throw new IllegalArgumentException("Invalid binary time!");
		} else if (binaryTime12.length > 6) {
			throw new IllegalArgumentException("Time unacceptably far in the future, can't decode: " + DataUtils.printHexBytes(binaryTime12));
		}
		long time = new BigInteger(binaryTime12).longValue();
		Timestamp ts = binaryTime12ToTimestamp(time);
		return ts;
	}

	public static Timestamp binaryTime12ToTimestamp(long binaryTime12AsLong) {
		Timestamp ts = new Timestamp((binaryTime12AsLong / 4096L) * 1000L);
		ts.setNanos((int)(((binaryTime12AsLong % 4096L) * 1000000000L) / 4096L));
		return ts;
	}

	/**
	 * Compare timestamps taking into account the resolution lost in the conversion above.
	 */
	public static boolean timestampEquals(Timestamp t1, Timestamp t2) {
		long timeVal1 = CCNTimeTest.timestampToBinaryTime12AsLong(t1);
		long timeVal2 = CCNTimeTest.timestampToBinaryTime12AsLong(t2);
		return (timeVal1 == timeVal2);
	}

	/**
	 * Rounding function for timestamps.
	 */
	public static Timestamp roundTimestamp(Timestamp origTimestamp) {
		long binaryTime12AsLong = timestampToBinaryTime12AsLong(origTimestamp);
		Timestamp ts = binaryTime12ToTimestamp(binaryTime12AsLong);
		return ts;
	}

}
