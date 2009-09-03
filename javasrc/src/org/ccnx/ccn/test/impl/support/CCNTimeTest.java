/**
 * 
 */
package org.ccnx.ccn.test.impl.support;

import static org.junit.Assert.*;

import java.math.BigInteger;
import java.sql.Timestamp;

import org.ccnx.ccn.impl.support.DataUtils;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * @author smetters
 *
 */
public class CCNTimeTest {

	/**
	 * @throws java.lang.Exception
	 */
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
	}

	/**
	 * Test method for {@link org.ccnx.ccn.impl.support.CCNTime#setTime(long)}.
	 */
	@Test
	public void testSetTime() {
		fail("Not yet implemented");
	}

	/**
	 * Test method for {@link org.ccnx.ccn.impl.support.CCNTime#setNanos(int)}.
	 */
	@Test
	public void testSetNanos() {
		fail("Not yet implemented");
	}

	/**
	 * Test method for {@link org.ccnx.ccn.impl.support.CCNTime#CCNTime(long)}.
	 */
	@Test
	public void testCCNTimeLong() {
		fail("Not yet implemented");
	}

	/**
	 * Test method for {@link org.ccnx.ccn.impl.support.CCNTime#CCNTime(java.sql.Timestamp)}.
	 */
	@Test
	public void testCCNTimeTimestamp() {
		fail("Not yet implemented");
	}

	/**
	 * Test method for {@link org.ccnx.ccn.impl.support.CCNTime#CCNTime(java.util.Date)}.
	 */
	@Test
	public void testCCNTimeDate() {
		fail("Not yet implemented");
	}

	/**
	 * Test method for {@link org.ccnx.ccn.impl.support.CCNTime#CCNTime(byte[])}.
	 */
	@Test
	public void testCCNTimeByteArray() {
		fail("Not yet implemented");
	}

	/**
	 * Test method for {@link org.ccnx.ccn.impl.support.CCNTime#CCNTime()}.
	 */
	@Test
	public void testCCNTime() {
		fail("Not yet implemented");
	}

	/**
	 * Test method for {@link org.ccnx.ccn.impl.support.CCNTime#fromBinaryTimeAsLong(long)}.
	 */
	@Test
	public void testFromBinaryTimeAsLong() {
		fail("Not yet implemented");
	}

	/**
	 * Test method for {@link org.ccnx.ccn.impl.support.CCNTime#toBinaryTime()}.
	 */
	@Test
	public void testToBinaryTime() {
		fail("Not yet implemented");
	}

	/**
	 * Test method for {@link org.ccnx.ccn.impl.support.CCNTime#toBinaryTimeAsLong()}.
	 */
	@Test
	public void testToBinaryTimeAsLong() {
		fail("Not yet implemented");
	}

	/**
	 * Test method for {@link org.ccnx.ccn.impl.support.CCNTime#equals(java.sql.Timestamp)}.
	 */
	@Test
	public void testEqualsTimestamp() {
		fail("Not yet implemented");
	}

	/**
	 * Test method for {@link org.ccnx.ccn.impl.support.CCNTime#compareTo(java.util.Date)}.
	 */
	@Test
	public void testCompareToDate() {
		fail("Not yet implemented");
	}

	/**
	 * Test method for {@link org.ccnx.ccn.impl.support.CCNTime#compareTo(java.sql.Timestamp)}.
	 */
	@Test
	public void testCompareToTimestamp() {
		fail("Not yet implemented");
	}

	/**
	 * Test method for {@link org.ccnx.ccn.impl.support.CCNTime#before(java.sql.Timestamp)}.
	 */
	@Test
	public void testBeforeTimestamp() {
		fail("Not yet implemented");
	}

	/**
	 * Test method for {@link org.ccnx.ccn.impl.support.CCNTime#after(java.sql.Timestamp)}.
	 */
	@Test
	public void testAfterTimestamp() {
		fail("Not yet implemented");
	}

	/**
	 * Test method for {@link org.ccnx.ccn.impl.support.CCNTime#before(java.util.Date)}.
	 */
	@Test
	public void testBeforeDate() {
		fail("Not yet implemented");
	}

	/**
	 * Test method for {@link org.ccnx.ccn.impl.support.CCNTime#after(java.util.Date)}.
	 */
	@Test
	public void testAfterDate() {
		fail("Not yet implemented");
	}

	/**
	 * Test method for {@link org.ccnx.ccn.impl.support.CCNTime#now()}.
	 */
	@Test
	public void testNow() {
		fail("Not yet implemented");
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
		long timeVal = (timestamp.getTime() / 1000) * 4096L + (timestamp.getNanos() * 4096L + 500000000L) / 1000000000L;
		return timeVal;
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
		Timestamp newTimestamp = (Timestamp)origTimestamp.clone();
	   	newTimestamp.setNanos((int)(((origTimestamp.getNanos() % 4096L) * 1000000000L) / 4096L));
	   	return newTimestamp;
	}

}
