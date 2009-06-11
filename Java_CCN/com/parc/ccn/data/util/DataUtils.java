package com.parc.ccn.data.util;

import java.io.IOException;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.util.ArrayList;

import org.bouncycastle.util.encoders.Base64;

import com.parc.ccn.config.SystemConfiguration;

public class DataUtils {

	public static <T extends Comparable<T>> int compare(T left, T right) {
		int result = 0;
		if (null != left) {
			if (null == right) 
				return 1; // sort nothing before something
			result = left.compareTo(right);
		} else {
			if (null != right)
				result = -1; // sort nothing before something
			// else fall through and compare publishers
			else
				result = 0; // null == null
		}
		return result;
	}
	
	/**
	 * Perform a lexigraphical comparison
	 * @param left
	 * @param right
	 * @return < 0 if left comes before right, 0 if they are equal, > 0 if left comes after right
	 */
	public static int compare(byte [] left, byte [] right) {
		int result = 0;
		if (null != left) {
			if (null == right) {
				result = 1;
			} else {
				// If a is shorter than b then a comes before b
				if (left.length < right.length) {
					result = -1;
				} else if (left.length > right.length) {
					result = 1;
				} else {
					// They have equal lengths - compare byte by byte
					for (int i=0; i < left.length; ++i) {
						if ((short)(left[i] & 0xff) < (short)(right[i] & 0xff)) {
							result = -1;
							break;
						} else if ((short)(left[i] & 0xff) > (short)(right[i] & 0xff)) {
							result = 1;
							break;
						}
					}
				}
			}
		} else {
			if (null != right)
				result = -1; // sort nothing before something
			// else fall through and compare publishers
			else
				result = 0; // null == null
		}
		return result;
	}
	
	/**
	 * @see #compare(byte[], byte[])
	 */
	public static int compare(ArrayList<byte []> left, ArrayList<byte []> right) {

		int result = 0;
		if (null != left) {
			if (null == right) {
				result = 1;
			} else {
				// here we have the comparison.
				int minlen = (left.size() < right.size()) ? left.size() : right.size();
				for (int i=0; i < minlen; ++i) {
					result = compare(left.get(i), right.get(i));
					if (0 != result) break;
				}
				if (result == 0) {
					// ok, they're equal up to the minimum length
					if (left.size() < right.size()) {
						result = -1;
					} else if (left.size() > right.size()) {
						result = 1;
					}
					// else they're equal, result = 0
				}
			}
		} else {
			if (null != right)
				result = -1; // sort nothing before something
			// else fall through and compare publishers
			else
				result = 0; // null == null
		}
		return result;
	}

	public static String printBytes(byte [] bytes) {
		BigInteger bi = new BigInteger(1, bytes);
		return bi.toString(SystemConfiguration.DEBUG_RADIX);
	}
	
	public static String printHexBytes(byte [] bytes) {
		BigInteger bi = new BigInteger(1, bytes);
		return bi.toString(16);
	}
	
	/*
	 * A place to centralize interfaces to base64 encoding/decoding, as the classes
	 * we use change depending on what ships with Java.
	 */
	public static byte [] base64Decode(byte [] input) throws IOException {
		return Base64.decode(input);
	}
	
	public static byte [] base6Encode(byte [] input) {
		return Base64.encode(input);
	}

	public static boolean arrayEquals(byte[] left, byte[] right) {
		if (left == null) {
			return ((right == null) ? true : false);
		}
		if (right == null) {
			return ((left == null) ? true : false);
		}
		if (left.length != right.length)
			return false;
		for (int i = 0; i < left.length; i++) {
			if (left[i] != right[i])
				return false;
		}
		return true;
	}
	
	public static boolean arrayEquals(byte[] left, byte[] right, int length) {
		if (left == null) {
			return ((right == null) ? true : false);
		}
		if (right == null) {
			return ((left == null) ? true : false);
		}
		if (left.length < length || right.length < length)
			return false;
		for (int i = 0; i < length; i++) {
			if (left[i] != right[i])
				return false;
		}
		return true;
	}
	
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
			throw new IllegalArgumentException("Time unacceptably far in the future, can't decode: " + printHexBytes(binaryTime12));
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
		long timeVal1 = (t1.getTime() / 1000) * 4096L + (t1.getNanos() * 4096L + 500000000L) / 1000000000L;
		long timeVal2 = (t2.getTime() / 1000) * 4096L + (t2.getNanos() * 4096L + 500000000L) / 1000000000L;
		return (timeVal1 == timeVal2);
	}
	
	/**
	 * Rounding function for timestamps.
	 */
	public static Timestamp roundTimestamp(Timestamp origTimestamp) {
		Timestamp newTimestamp = (Timestamp)origTimestamp.clone();
	   	newTimestamp.setNanos((int)(((newTimestamp.getNanos() % 4096L) * 1000000000L) / 4096L));
	   	return newTimestamp;
	}

	public static boolean isBinaryPrefix(byte [] prefix,
										 byte [] data) {
		if ((null == prefix) || (prefix.length == 0))
			return true;
		if ((null == data) || (data.length < prefix.length))
			return false;
		for (int i=0; i < prefix.length; ++i) {
			if (prefix[i] != data[i])
				return false;
		}
		return true;
	}	
}
