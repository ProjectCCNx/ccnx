package com.parc.ccn.data.query;

import java.util.Comparator;

import com.parc.ccn.data.util.DataUtils;

/**
 * 
 * @author rasmusse
 * Needed to sort byte arrays to build exclude filters
 *
 */

public class ByteArrayCompare implements Comparator<byte[] >{

	public int compare(byte[] o1, byte[] o2) {
		return DataUtils.compare(o1, o2);
	}
}
