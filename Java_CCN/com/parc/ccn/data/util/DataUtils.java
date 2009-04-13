package com.parc.ccn.data.util;

import java.math.BigInteger;
import java.util.ArrayList;

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
	
	public static boolean arrayEquals(byte[] left, byte[] right, int length) {
		if (left.length < length || right.length < length)
			return false;
		for (int i = 0; i < length; i++) {
			if (left[i] != right[i])
				return false;
		}
		return true;
	}

}
