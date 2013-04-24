/*
 * A CCNx library test.
 *
 * Copyright (C) 2011 Palo Alto Research Center, Inc.
 *
 * This work is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License version 2 as published by the
 * Free Software Foundation. 
 * This work is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License
 * for more details. You should have received a copy of the GNU General Public
 * License along with this program; if not, write to the
 * Free Software Foundation, Inc., 51 Franklin Street, Fifth Floor,
 * Boston, MA 02110-1301, USA.
 */

package org.ccnx.ccn.test.impl.support;

import java.math.BigInteger;
import java.util.Random;

import org.ccnx.ccn.impl.support.DataUtils;
import org.ccnx.ccn.profiles.SegmentationProfile;
import org.junit.Assert;
import org.junit.Test;

public class DataUtilsTest {
	long [] testset = {0, 0x0FL, 0x0000FFFFFFFFL, 0x7FFFFFFFL, 0x0000FFFFFFFFFFFFL, 0x1000000000000L, 
			0x1000FFFFFFFFFFFFL, 0x7000FFFFFFFFFFFFL, 0xF000FFFFFFFFFFFFL};  

	Random _rnd = new Random();
	
	/**
	 * This should perform like BigInteger(1,bytes).longVlaue()
	 * 
	 * @throws Exception
	 */
	@Test
	public void testByteArrayToUnsignedLong() throws Exception {
		for(long v : testset) {
			// Generate the byte array using the BigInteger method
			byte [] b = longToByteArray(v);
		
			long truth = new BigInteger(1, b).longValue();
			long test  = DataUtils.byteArrayToUnsignedLong(b);
			
			Assert.assertEquals(truth, test);
		}
	}

	@Test
	public void testUnsignedLongToByteArray() throws Exception {
		for(long v : testset) {
			byte [] truth = longToByteArray(v);
			
			byte [] test  = DataUtils.unsignedLongToByteArray(v);
			
			Assert.assertArrayEquals(truth, test);
		}		
	}
	

	@Test
	public void testSegmentationProfile() throws Exception {
		long v;
		
		for(v = 0; v < 4096; v++) {
			byte [] truth = segmentToByteArray(v);
			byte [] test  = DataUtils.unsignedLongToByteArray(v, SegmentationProfile.SEGMENT_MARKER);
			Assert.assertArrayEquals(String.format("Mismatch value %d", v), truth, test);
		}
	}
	
	
	// =========================
	
	private byte [] longToByteArray(long value) {
		// We need to get this in a signum representation that's not 2's complement
		byte [] b = BigInteger.valueOf(value).toByteArray();
		if( 0 == b[0] && b.length > 1 ) {
			byte [] bb = new byte[b.length - 1];
			System.arraycopy(b, 1, bb, 0, bb.length);
			b = bb;
		}
		return b;
	}

	/**
	 * This is how the SegmentationProfile wants to do it
	 * @param segment
	 * @return
	 */
	private byte [] segmentToByteArray(long segmentNumber) {
		byte [] iarr = BigInteger.valueOf(segmentNumber).toByteArray();
		byte [] segmentNumberNameComponent = new byte[iarr.length + ((0 == iarr[0]) ? 0 : 1)];
		segmentNumberNameComponent[0] = SegmentationProfile.SEGMENT_MARKER;
		int offset = ((0 == iarr[0]) ? 1 : 0);
		System.arraycopy(iarr, offset, segmentNumberNameComponent, 1, iarr.length-offset);
		return segmentNumberNameComponent;
	}
}
