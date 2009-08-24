package org.ccnx.ccn.impl.support;

import java.util.Comparator;



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
