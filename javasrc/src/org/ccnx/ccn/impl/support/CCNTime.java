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

	private static final long serialVersionUID = -1537142893653443100L;

	/**
	 * @param time in msec
	 */
	public CCNTime(long msec) {
		super((msec / 1000) * 4096L);
		setNanos(0);
	}

	public CCNTime(Timestamp time) {
		super(time.getTime());
	   	this.setNanos((int)(((time.getNanos() % 4096L) * 1000000000L) / 4096L));
	}
	
	public CCNTime(Date time) {
		this(time.getTime());
	}
	
	public CCNTime(byte [] binaryTime12) {
		super(0);
		if ((null == binaryTime12) || (binaryTime12.length == 0)) {
			throw new IllegalArgumentException("Invalid binary time!");
		} else if (binaryTime12.length > 6) {
			throw new IllegalArgumentException("Time unacceptably far in the future, can't decode: " + DataUtils.printHexBytes(binaryTime12));
		}
		long binaryTime12AsLong = new BigInteger(1, binaryTime12).longValue();
		setTime((binaryTime12AsLong / 4096L) * 1000L);
		setNanos((int)(((binaryTime12AsLong % 4096L) * 1000000000L) / 4096L));
	}
	
	/**
	 * Creates a CCNTime initialized to the current time.
	 */
	public CCNTime() {
		this(System.currentTimeMillis());
	}
	
	/**
	 * Make this a static method to avoid confusion; should be little used
	 * @return
	 */
	public static CCNTime fromBinaryTimeAsLong(long binaryTimeAsLong) {
		CCNTime ts = new CCNTime((binaryTimeAsLong / 4096L) * 1000L);
		ts.setNanos((int)(((binaryTimeAsLong % 4096L) * 1000000000L) / 4096L));
		return ts;
	}
	
	public byte [] toBinaryTime() {
		return BigInteger.valueOf(toBinaryTimeAsLong()).toByteArray();
	}
	
	public long toBinaryTimeAsLong() {
		long timeVal = (getTime() / 1000) * 4096L + (getNanos() * 4096L + 500000000L) / 1000000000L;
		return timeVal;
	}
	
	@Override
	public void setTime(long time) {
		super.setTime((time / 1000) * 4096L);
	}


	@Override
	public void setNanos(int nanos) {
	   	super.setNanos((int)(((nanos % 4096L) * 1000000000L) / 4096L));
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
