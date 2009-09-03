/**
 * 
 */
package org.ccnx.ccn.impl.support;

import java.math.BigInteger;
import java.sql.Timestamp;
import java.util.Date;

/**
 * CCN has a time representation for versions and signing times that
 * is quantized at a granularity of 2^-12 seconds (approximately 1/4 msec).
 * Because it does not quantize time evenly on millisecond boundaries,
 * this can lead to confusion -- t = System.currentTimeMillis(), when
 * used to set a version v, for example, then ends up with v != t as
 * v is quantized and t isn't. Simply providing quantization utility
 * routines to help developers turned out to be error-prone. So we
 * are moving all uses of time in CCN to pre-quantized CCNTime objects,
 * which encapsulates CCN time quantization (which could change),
 * and gives developers ways to deal with it without having to think
 * about it.
 * 
 * CCNTime, all though it implements methods like getNanos, only
 * represents time with a granularity equal to the underlying
 * CCN wire format -- i.e. ~.25 msec.
 * 
 * @author smetters
 *
 */
public class CCNTime extends Timestamp {

	/**
	 * @param time
	 */
	public CCNTime(long time) {
		super(time);
		// TODO Auto-generated constructor stub
	}

	public CCNTime(Timestamp time) {
		this(time.getTime());
	}
	
	public CCNTime(Date time) {
		this(time.getTime());
	}
	
	/**
	 * Creates a CCNTime initialized to the current time.
	 */
	public CCNTime() {
		this(System.currentTimeMillis());
	}
	
	@Override
	public void setTime(long time) {
		// TODO Auto-generated method stub
		super.setTime(time);
	}


	@Override
	public void setNanos(int n) {
		// TODO Auto-generated method stub
		super.setNanos(n);
	}
	/**
	 * We handle all comparison functions by quantizing the thing
	 * we are being compared to, then comparing. The only thing
	 * this won't catch is if a normal Timestamp or Date's comparison
	 * method is called with a CCNTime as an argument. This is a 
	 * small risk, and worth it given the convenience of subclassing
	 * Timestamp and using it for most functionality.
	 */
	@Override
	public boolean equals(Timestamp ts) {
		return super.equals(new CCNTime(ts));
	}

	@Override
	public int compareTo(Date o) {
		return super.compareTo(new CCNTime(o));
	}

	@Override
	public int compareTo(Timestamp ts) {
		return super.compareTo(new CCNTime(ts));
	}

	@Override
	public boolean before(Timestamp ts) {
		return super.before(new CCNTime(ts));
	}

	@Override
	public boolean after(Timestamp ts) {
		return super.after(new CCNTime(ts));
	}

	@Override
	public boolean before(Date when) {
		return super.before(new CCNTime(when));
	}
	
	@Override
	public boolean after(Date when) {
		return super.after(new CCNTime(when));
	}

	/**
	 * Rounding function for timestamps.
	 */
	public static Timestamp roundTimestamp(Timestamp origTimestamp) {
		Timestamp newTimestamp = (Timestamp)origTimestamp.clone();
	   	newTimestamp.setNanos((int)(((newTimestamp.getNanos() % 4096L) * 1000000000L) / 4096L));
	   	return newTimestamp;
	}

	/**
	 * Compare timestamps taking into account the resolution lost in the conversion above.
	 */
	public static boolean timestampEquals(Timestamp t1, Timestamp t2) {
		long timeVal1 = (t1.getTime() / 1000) * 4096L + (t1.getNanos() * 4096L + 500000000L) / 1000000000L;
		long timeVal2 = (t2.getTime() / 1000) * 4096L + (t2.getNanos() * 4096L + 500000000L) / 1000000000L;
		return (timeVal1 == timeVal2);
	}

	public static Timestamp binaryTime12ToTimestamp(long binaryTime12AsLong) {
		Timestamp ts = new Timestamp((binaryTime12AsLong / 4096L) * 1000L);
		ts.setNanos((int)(((binaryTime12AsLong % 4096L) * 1000000000L) / 4096L));
		return ts;
	}

	public static Timestamp binaryTime12ToTimestamp(byte [] binaryTime12) {
		if ((null == binaryTime12) || (binaryTime12.length == 0)) {
			throw new IllegalArgumentException("Invalid binary time!");
		} else if (binaryTime12.length > 6) {
			throw new IllegalArgumentException("Time unacceptably far in the future, can't decode: " + DataUtils.printHexBytes(binaryTime12));
		}
		long time = new BigInteger(binaryTime12).longValue();
		Timestamp ts = CCNTime.binaryTime12ToTimestamp(time);
		return ts;
	}

	public static long timestampToBinaryTime12AsLong(Timestamp timestamp) {
		long timeVal = (timestamp.getTime() / 1000) * 4096L + (timestamp.getNanos() * 4096L + 500000000L) / 1000000000L;
		return timeVal;
	}

	/**
	 * Converts a timestamp into a fixed point representation, with 12 bits in the fractional
	 * component, and adds this to the ContentName as a version field. The timestamp is rounded
	 * to the nearest value in the fixed point representation.
	 * <p>
	 * This allows versions to be recorded as a timestamp with a 1/4096 second accuracy.
	 */
	public static byte [] timestampToBinaryTime12(Timestamp timestamp) {
		long timeVal = CCNTime.timestampToBinaryTime12AsLong(timestamp);
		return BigInteger.valueOf(timeVal).toByteArray();
	}
}
