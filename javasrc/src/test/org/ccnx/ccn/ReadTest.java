/*
 * A CCNx library test.
 *
 * Copyright (C) 2008, 2009, 2011, 2013 Palo Alto Research Center, Inc.
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

package org.ccnx.ccn.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;

import org.ccnx.ccn.CCNContentHandler;
import org.ccnx.ccn.impl.support.DataUtils;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.CCNReader;
import org.ccnx.ccn.io.CCNWriter;
import org.ccnx.ccn.profiles.SegmentationProfile;
import org.ccnx.ccn.protocol.BloomFilter;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Exclude;
import org.ccnx.ccn.protocol.ExcludeComponent;
import org.ccnx.ccn.protocol.Interest;
import org.junit.Assert;
import org.junit.Test;

/**
 * 
 * A test of basic Interest mechanisms, using older test infrastructure.
 */
@SuppressWarnings("deprecation")
public class ReadTest extends LibraryTestBase implements CCNContentHandler {
	
	private static ArrayList<Integer> currentSet;
	
	private byte [] bloomSeed = "burp".getBytes();
	private Exclude ef = null;
	
	private String [] bloomTestValues = {
            "one", "two", "three", "four",
            "five", "six", "seven", "eight",
            "nine", "ten", "eleven", "twelve",
            "thirteen"
      	};
	
	private void excludeSetup() {
		BloomFilter bf1 = new BloomFilter(13, bloomSeed);
		ExcludeComponent e1 = new ExcludeComponent("aaa".getBytes());
		ExcludeComponent e2 = new ExcludeComponent("zzzzzzzz".getBytes());
		
		for (String value : bloomTestValues) {
			bf1.insert(value.getBytes());
		}
		ArrayList<Exclude.Element>excludes = new ArrayList<Exclude.Element>(3);
		excludes.add(e1);
		excludes.add(bf1);
		excludes.add(e2);
		ef = new Exclude(excludes);
	}

	public ReadTest() throws Throwable {
		super();
	}
	
	@Test
	public void getNextTest() throws Throwable {
		Log.info(Log.FAC_TEST, "Starting getNextTest");
		CCNWriter writer = new CCNWriter("/getNext", putHandle);
		CCNReader reader = new CCNReader(getHandle);
		for (int i = 0; i < count; i++) {
			writer.put("/getNext/" + Integer.toString(i), Integer.toString(count - i));
			Thread.sleep(rand.nextInt(50));
			
			// Pull it into ccnd so we have everything there to check nexts from
			ContentObject testCo = getHandle.get(ContentName.fromNative("/getNext/" + Integer.toString(i)), 3000);
			Assert.assertTrue(testCo != null);
		}
		Log.info(Log.FAC_TEST, "Put sequence finished");
		for (int i = 0; i < count; i++) {
			Thread.sleep(rand.nextInt(50));
			int tValue = rand.nextInt(count - 1);
			ContentName cn = ContentName.fromNative("/getNext/" + new Integer(tValue).toString());
			ContentObject result = reader.get(Interest.next(cn, 1, null), 3000);
			checkResult(result, tValue + 1);
		}
		Log.info(Log.FAC_TEST, "Completed getNextTest");
	}
	
	@Test
	public void getLatestTest() throws Throwable {
		Log.info(Log.FAC_TEST, "Starting getLatestTest");
		int highest = 0;
		CCNWriter writer = new CCNWriter("/getLatest", putHandle);
		CCNReader reader = new CCNReader(getHandle);
		for (int i = 0; i < count; i++) {
			int tValue = getRandomFromSet(count, false);
			if (tValue > highest)
				highest = tValue;
			String name = "/getLatest/" + Integer.toString(tValue);
			Log.info(Log.FAC_TEST, "Putting " + name);
			writer.put(name, Integer.toString(tValue));
			
			// Make sure ccnd has what we're looking for
			Thread.sleep(500);
			ContentObject testCo = getHandle.get(ContentName.fromNative(name), 3000);
			Assert.assertTrue(testCo != null);
			
			if (i > 1) {
				if (tValue == highest)
					tValue--;
				ContentName cn = SegmentationProfile.segmentName(
						ContentName.fromNative("/getLatest/" + new Integer(tValue).toString()), SegmentationProfile.baseSegment());
				ContentObject result = reader.get(Interest.last(cn, 1, null), 5000);
				checkResult(result, highest);
			}
		}
		Log.info(Log.FAC_TEST, "Completed getLatestTest");
	}
	
	@Test
	public void excludeTest() throws Throwable {
		Log.info(Log.FAC_TEST, "Starting excludeTest");

		excludeSetup();
		CCNWriter writer = new CCNWriter("/excludeTest", putHandle);
		for (String value : bloomTestValues) {
			writer.put("/excludeTest/" + value, value);
		}
		writer.put("/excludeTest/aaa", "aaa");
		writer.put("/excludeTest/zzzzzzzz", "zzzzzzzz");
		Interest interest = Interest.constructInterest(ContentName.fromNative("/excludeTest/"), ef, null, null, null, null);
		ContentObject content = getHandle.get(interest, 3000);
		Assert.assertTrue(content == null);
		
		String shouldGetIt = "/excludeTest/weShouldGetThis";
		writer.put(shouldGetIt, shouldGetIt);
		content = getHandle.get(interest, 3000);
		Assert.assertFalse(content == null);
		assertTrue(content.name().toString().startsWith(shouldGetIt));
		Log.info(Log.FAC_TEST, "Completed excludeTest");
	}
	
	@Test
	public void getExcludeTest() throws Throwable {
		Log.info(Log.FAC_TEST, "Starting getExcludeTest");
		// Try with single bloom filter
		excludeTest("/getExcludeTest1", Exclude.OPTIMUM_FILTER_SIZE/2);
		// Try with multi part filter
		excludeTest("/getExcludeTest2", Exclude.OPTIMUM_FILTER_SIZE + 5);
		Log.info(Log.FAC_TEST, "Completed getExcludeTest");
	}
	
	private void excludeTest(String prefix, int nFilters) throws Throwable {
	
		Log.info(Log.FAC_TEST, "Starting exclude test - nFilters is " + nFilters);
		CCNWriter writer = new CCNWriter(prefix, putHandle);
		byte [][] excludes = new byte[nFilters - 1][];
		for (int i = 0; i < nFilters; i++) {
			String value = new Integer(i).toString();
			if (i < (nFilters - 1))
				excludes[i] = value.getBytes();
			String name = prefix + "/" + value;
			writer.put(name, value);
		}
		CCNReader reader = new CCNReader(getHandle);
		ContentObject content = reader.get(Interest.exclude(ContentName.fromNative(prefix + "/"), new Exclude(excludes), null, null, null), 50000);
		if (null == content || !Arrays.equals(content.content(), new Integer((nFilters - 1)).toString().getBytes())) {
			// Try one more time in case we got a false positive
			content = reader.get(Interest.exclude(ContentName.fromNative(prefix + "/"), new Exclude(excludes), null, null, null), 50000);
		}
		Assert.assertFalse(content == null);
		assertEquals(DataUtils.compare(content.content(), new Integer((nFilters - 1)).toString().getBytes()), 0);
	}

	public Interest handleContent(ContentObject data, Interest interest) {
		return null;
	}
	
	private void checkResult(ContentObject result, int value) {
		assertTrue(result != null);
		String resultAsString = SegmentationProfile.segmentRoot(result.name()).toString();
		int sep = resultAsString.lastIndexOf('/');
		assertTrue(sep > 0);
		int resultValue = Integer.parseInt(resultAsString.substring(sep + 1));
		assertEquals(new Integer(value), new Integer(resultValue));
	}
	
	public int getRandomFromSet(int length, boolean reset) {
		int result = -1;
		if (reset || currentSet == null)
			currentSet = new ArrayList<Integer>(length);
		if (currentSet.size() >= length)
			return result;
		while (true) {
			result = rand.nextInt(length);
			boolean found = false;
			for (int used : currentSet) {
				if (used == result) {
					found = true;
					break;
				}
			}
			if (!found)
				break;
		}
		currentSet.add(result);
		return result;
	}

}
