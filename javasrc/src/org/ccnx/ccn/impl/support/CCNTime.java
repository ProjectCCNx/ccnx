/**
 * 
 */
package org.ccnx.ccn.impl.support;

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
}
