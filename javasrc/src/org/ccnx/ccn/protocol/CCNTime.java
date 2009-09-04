/**
 * 
 */
package org.ccnx.ccn.protocol;

import java.math.BigInteger;
import java.sql.Timestamp;
import java.util.Date;

import org.ccnx.ccn.impl.support.DataUtils;



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

	private static final long serialVersionUID = -1537142893653443100L;

	/**
	 * @param time in msec
	 */
	public CCNTime(long msec) {
		this((msec/1000) * 1000, (msec % 1000) * 1000000L);
	}

	public CCNTime(Timestamp timestamp) {
		this(timestamp.getTime(), timestamp.getNanos());
	}
	
	public CCNTime(Date time) {
		this(time.getTime());
	}
	
	public CCNTime(byte [] binaryTime12) {
		this(new BigInteger(1, binaryTime12).longValue(), true);
		if ((null == binaryTime12) || (binaryTime12.length == 0)) {
			throw new IllegalArgumentException("Invalid binary time!");
		} else if (binaryTime12.length > 6) {
			throw new IllegalArgumentException("Time unacceptably far in the future, can't decode: " + DataUtils.printHexBytes(binaryTime12));
		}
	}
	
	/**
	 * Creates a CCNTime initialized to the current time.
	 */
	public CCNTime() {
		this(System.currentTimeMillis());
	}
	
	protected CCNTime(long msec, long nanos) {
		this(toBinaryTimeAsLong(msec, nanos), true);
	}
	
	protected CCNTime(long binaryTimeAsLong, boolean unused) {
		super((binaryTimeAsLong / 4096L) * 1000L);
		super.setNanos((int)(((binaryTimeAsLong % 4096L) * 1000000000L) / 4096L));
	}
	
	/**
	 * Make this a static method to avoid confusion; should be little used
	 * @return
	 */
	public static CCNTime fromBinaryTimeAsLong(long binaryTimeAsLong) {
		return new CCNTime(binaryTimeAsLong, true);
	}
	
	public byte [] toBinaryTime() {
		return BigInteger.valueOf(toBinaryTimeAsLong()).toByteArray();
	}
	
	public long toBinaryTimeAsLong() {
		return toBinaryTimeAsLong(getTime(), getNanos());
	}
	
	/**
	 * Assumes that nanos also contains the integral milliseconds for this
	 * time. Ignores msec component in msec.
	 * @param msec
	 * @param nanos
	 * @return
	 */
	public static long toBinaryTimeAsLong(long msec, long nanos) {
		long timeVal = (msec / 1000) * 4096L + (nanos * 4096L + 500000000L) / 1000000000L;
		return timeVal;		
	}
	
	@Override
	public void setTime(long msec) {
		long binaryTimeAsLong = toBinaryTimeAsLong((msec/1000) * 1000, (msec % 1000) * 1000000L);
		super.setTime((binaryTimeAsLong / 4096L) * 1000L);
		super.setNanos((int)(((binaryTimeAsLong % 4096L) * 1000000000L) / 4096L));
	}

	@Override
	public void setNanos(int nanos) {
	   	super.setNanos((int)(((((nanos * 4096L + 500000000L) / 1000000000L)) * 1000000000L) / 4096L));
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

	public static CCNTime now() {
		return new CCNTime();
	}
}
