/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2011-2013 Palo Alto Research Center, Inc.
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License version 2.1
 * as published by the Free Software Foundation. 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details. You should have received
 * a copy of the GNU Lesser General Public License along with this library;
 * if not, write to the Free Software Foundation, Inc., 51 Franklin Street,
 * Fifth Floor, Boston, MA 02110-1301 USA.
 */

package org.ccnx.ccn.profiles.versioning;

import java.util.Arrays;
import java.util.Iterator;
import java.util.Random;
import java.util.TreeSet;

import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.protocol.CCNTime;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;
import org.junit.Assert;
import org.junit.Test;

public class InterestDataTest {

	protected final Random _rnd = new Random();
	protected final static long TIMEOUT=30000;
	protected final ContentName prefix;
	
	protected final VersionNumber vn_411000000000L = new VersionNumber(411000000000L);
	protected final VersionNumber vn_411111000000L = new VersionNumber(411111000000L);
	protected final VersionNumber vn_411222000000L = new VersionNumber(411222000000L);
	protected final VersionNumber vn_411333000000L = new VersionNumber(411333000000L);

	public InterestDataTest() throws MalformedContentNameStringException {
		prefix  = ContentName.fromNative(String.format("/repotest/test_%016X", _rnd.nextLong()));
	}
	
	@Test
	public void testVersionNumberInTree() throws Exception {
		Log.info(Log.FAC_TEST, "Started testVersionNumberInTree");

		// make sure the sortable work
		long [] values = new long [] {1111110000000L, 1110000000000L, 1113330000000L, 1112220000000L};
		VersionNumber [] vns = new VersionNumber[values.length];
		TreeSet<VersionNumber> tree = new TreeSet<VersionNumber>();

		for(int i = 0; i < values.length; i++) {
			vns[i] = new VersionNumber(values[i]);
			tree.add(vns[i]);
		}

		// they should be in the same order as the sorted values
		Arrays.sort(values);
		Iterator<VersionNumber> iter = tree.iterator();
		int index = 0;
		while( iter.hasNext() ) {
			VersionNumber v = iter.next();
			Assert.assertEquals(values[index], v.getAsMillis());
			index++;
		}
		
		Log.info(Log.FAC_TEST, "Completed testVersionNumberInTree");
	}

	@Test
	public void testVersionNumberCompare() throws Exception {
		Log.info(Log.FAC_TEST, "Started testVersionNumberCompare");

		VersionNumber a = new VersionNumber(new CCNTime(111111000000L));
		VersionNumber aa = new VersionNumber(new CCNTime(111111000000L));
		VersionNumber b = new VersionNumber(new CCNTime(111222000000L));
		VersionNumber c = new VersionNumber(new CCNTime(111333000000L));

		Assert.assertTrue(a.compareTo(b) < 0);
		Assert.assertTrue(b.compareTo(a) > 0);
		Assert.assertTrue(a.compareTo(aa) == 0);
		Assert.assertTrue(a.compareTo(c) < 0);
		Assert.assertTrue(b.compareTo(c) < 0);
		Assert.assertTrue(c.compareTo(a) > 0);
		Assert.assertTrue(c.compareTo(b) > 0);
		
		Log.info(Log.FAC_TEST, "Started testVersionNumberCompare");
	}

	@Test
	public void testInterestDataStartTimeCompare() throws Exception {
		Log.info(Log.FAC_TEST, "Started testInterestDataStartTimeCompare");

		ContentName basename = new ContentName(prefix, String.format("content_%016X", _rnd.nextLong()));

		InterestData id1 =  new InterestData(basename, vn_411000000000L, new VersionNumber(411110000010L));
		InterestData id1a = new InterestData(basename, vn_411000000000L, new VersionNumber(411110000020L));
		InterestData id2 =  new InterestData(basename, vn_411222000000L, new VersionNumber(411330000000L));

		InterestData.StartTimeComparator stc = new InterestData.StartTimeComparator();
		
		Assert.assertTrue(stc.compare(id1, id1a) == 0);
		Assert.assertTrue(stc.compare(id1a, id1) == 0);
		Assert.assertTrue(stc.compare(id1, id2) < 0);
		Assert.assertTrue(stc.compare(id2, id1) > 0);
		
		Log.info(Log.FAC_TEST, "Completed testInterestDataStartTimeCompare");
	}
	
	@Test
	public void testSplitLeft() throws Exception {
		Log.info(Log.FAC_TEST, "Started testSplitLeft");

		// put a bunch of exclusions in an INterestData, then split it and check results.
		ContentName basename = new ContentName(prefix, String.format("content_%016X", _rnd.nextLong()));

		VersionNumber starttime = new VersionNumber();
		VersionNumber stoptime = null;
		
		int count = VersioningInterestManager.MAX_FILL;
		
		InterestData data = new InterestData(basename, starttime, VersionNumber.getMaximumVersion());
		
		VersionNumber t = starttime;
		for(int i = 0; i < count; i++) {
			// walk up to 100 seconds, converted to nanos, with minimum 1 msec
			long walk = _rnd.nextInt(100000) * 1000000L + 1000000L;
			t = t.addAndReturn(walk);
			
			data.addExclude(t);
			stoptime = t.addAndReturn(100);
			
			data.setStopTime(stoptime);
		}
		
		// now split it, so MIN_FILL will stay in data, and the rest will go to left
		InterestData left = data.splitLeft(data.size() - VersioningInterestManager.MIN_FILL);
	
		Assert.assertTrue(left.getStartVersion().equals(starttime));
		Assert.assertTrue(data.getStopVersion().equals(stoptime));
		
		Assert.assertEquals(VersioningInterestManager.MIN_FILL, data.size());
		Assert.assertEquals(count - VersioningInterestManager.MIN_FILL, left.size());	
		
		Assert.assertTrue(left.getStopVersion().addAndReturn(1).equals(data.getStartVersion()));
		
		// Ensure data consistency
		Assert.assertTrue(left.validate());
		Assert.assertTrue(data.validate());
		
		Log.info(Log.FAC_TEST, "Completed testSplitLeft");		
	}
	
}
