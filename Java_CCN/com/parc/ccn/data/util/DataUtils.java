package com.parc.ccn.data.util;

import java.math.BigInteger;

public class DataUtils {

	public static int compareTo(byte [] arr1, byte [] arr2) {
		if (null == arr1) {
			if (null == arr2)
				return 0;
			else
				return -1;
		}
		if (null == arr2) {
			if (null == arr1)
				return 0;
			else
				return 1;
		}
		int len = (arr1.length>arr2.length) ? arr2.length : arr1.length;
		for (int i=0; i < len; ++i) {
			if (arr1[i] < arr2[i])
				return -1;
			else if (arr1[i] > arr2[i])
				return 1;
		}
		// equal up to the minimum length.
		if (arr1.length < arr2.length)
			return -1;
		else if (arr1.length > arr2.length)
			return 1;
		return 0;
	}
	
	public static String printBytes(byte [] bytes) {
		BigInteger bi = new BigInteger(1, bytes);
		return bi.toString(16);
	}

}
