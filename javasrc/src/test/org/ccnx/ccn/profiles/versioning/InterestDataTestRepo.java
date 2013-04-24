/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2011-2012 Palo Alto Research Center, Inc.
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

package org.ccnx.ccn.test.profiles.versioning;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Random;
import java.util.TreeSet;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.impl.CCNFlowControl.SaveType;
import org.ccnx.ccn.io.content.CCNStringObject;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.profiles.versioning.InterestData;
import org.ccnx.ccn.profiles.versioning.VersionNumber;
import org.ccnx.ccn.profiles.versioning.VersioningInterestManager;
import org.ccnx.ccn.protocol.CCNTime;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Interest;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;
import org.ccnx.ccn.test.profiles.versioning.VersioningHelper.TestListener;
import org.junit.Assert;
import org.junit.Test;

public class InterestDataTestRepo {

	protected final Random _rnd = new Random();
	protected final static long TIMEOUT=30000;
	protected final ContentName prefix;
	
	protected final VersionNumber vn_411000000000L = new VersionNumber(411000000000L);
	protected final VersionNumber vn_411111000000L = new VersionNumber(411111000000L);
	protected final VersionNumber vn_411222000000L = new VersionNumber(411222000000L);
	protected final VersionNumber vn_411333000000L = new VersionNumber(411333000000L);

	public InterestDataTestRepo() throws MalformedContentNameStringException {
		prefix  = ContentName.fromNative(String.format("/repotest/test_%016X", _rnd.nextLong()));
	}
	
	@Test
	public void testVersionNumberInTree() throws Exception {
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
	}

	@Test
	public void testVersionNumberCompare() throws Exception {
		// make sure the sortable work

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
	}

	@Test
	public void testInterestDataStartTimeCompare() throws Exception {
		ContentName basename = new ContentName(prefix, String.format("content_%016X", _rnd.nextLong()));

		InterestData id1 =  new InterestData(basename, vn_411000000000L, new VersionNumber(411110000010L));
		InterestData id1a = new InterestData(basename, vn_411000000000L, new VersionNumber(411110000020L));
		InterestData id2 =  new InterestData(basename, vn_411222000000L, new VersionNumber(411330000000L));

		InterestData.StartTimeComparator stc = new InterestData.StartTimeComparator();
		
		Assert.assertTrue(stc.compare(id1, id1a) == 0);
		Assert.assertTrue(stc.compare(id1a, id1) == 0);
		Assert.assertTrue(stc.compare(id1, id2) < 0);
		Assert.assertTrue(stc.compare(id2, id1) > 0);		
	}

	/**
	 * Create one object then create an InterestData for it and make sure we get the object.
	 * @throws Exception
	 */
	@Test
	public void testInterestDataInterest() throws Exception {
		CCNHandle handle = CCNHandle.getHandle();
		ContentName basename = new ContentName(prefix, String.format("content_%016X", _rnd.nextLong()));
		TestListener listener = new TestListener();

		InterestData id = new InterestData(basename);

		// Save content
		CCNStringObject so = new CCNStringObject(basename, "hello, world!", SaveType.LOCALREPOSITORY, handle);
		so.save();
		CCNTime version = so.getVersion();
		so.close();

		// Now use the interest to retrive it
		Interest interest = id.buildInterest();

		System.out.println("Expressing interest " + interest);
		
		handle.expressInterest(interest, listener);

		Assert.assertTrue( listener.cl.waitForValue(1L, TIMEOUT) );

		// now make sure what we got is what we expected
		ContentObject co = listener.received.get(0).object;

		CCNTime received_version = VersioningProfile.getLastVersionAsTimestamp(co.name());
		Assert.assertTrue(version.equals(received_version));

		CCNStringObject received_so = new CCNStringObject(co, handle);
		Assert.assertTrue(so.string().equals(received_so.string()));
	}


	/**
	 * Test an InterestData when retrieving many versions
	 * @throws Exception
	 */
	@Test
	public void testInterestDataInterestStream() throws Exception {
		CCNHandle handle = CCNHandle.getHandle();
		ContentName basename = new ContentName(prefix, String.format("content_%016X", _rnd.nextLong()));

		int tosend = 200;

		// Send a stream of string objects
		ArrayList<CCNStringObject> sent = VersioningHelper.sendEventStream(handle, basename, tosend);

		// Now read them back
		TestListener listener = new TestListener();
		InterestData id = new InterestData(basename);

		listener.setInterestData(id);

		Assert.assertTrue( listener.run(handle, tosend, TIMEOUT) );

		// now make sure what we got is what we sent
		VersioningHelper.compareReceived(handle, sent, listener);
	}
	
	/**
	 * Test an InterestData when retrieving many versions, uses a start time between two streams.
	 * @throws Exception
	 */
	@Test
	public void testInterestDataInterestStreamWithStartTime() throws Exception {
		CCNHandle handle = CCNHandle.getHandle();
		ContentName basename = new ContentName(prefix, String.format("content_%016X", _rnd.nextLong()));

		int tosend = 100;

		// Send a stream of string objects
		ArrayList<CCNStringObject> sent1 = VersioningHelper.sendEventStream(handle, basename, tosend);
		VersionNumber cutoff_version = new VersionNumber(sent1.get(sent1.size()-1).getVersion());
		
		// now send another stream
		ArrayList<CCNStringObject> sent2 = VersioningHelper.sendEventStream(handle, basename, tosend);

		// Now read them back
		TestListener listener = new TestListener();
		InterestData id = new InterestData(basename, cutoff_version.addAndReturn(1), VersionNumber.getMaximumVersion());

		listener.setInterestData(id);

		Assert.assertTrue( listener.run(handle, tosend, TIMEOUT) );

		// now make sure what we got is what we sent
		VersioningHelper.compareReceived(handle, sent2, listener);
	}

	/**
	 * Test an InterestData when retrieving many versions, uses a start & stop time
	 * in the middle of three streams.
	 * @throws Exception
	 */
	@Test
	public void testInterestDataInterestStreamWithStartAndStopTime() throws Exception {
		CCNHandle handle = CCNHandle.getHandle();
		ContentName basename = new ContentName(prefix, String.format("content_%016X", _rnd.nextLong()));

		int tosend = 50;

		// Send a stream of string objects
		ArrayList<CCNStringObject> sent1 = VersioningHelper.sendEventStream(handle, basename, tosend);
		VersionNumber start_version = new VersionNumber(sent1.get(sent1.size()-1).getVersion()).addAndReturn(1);
		
		// now send another stream
		ArrayList<CCNStringObject> sent2 = VersioningHelper.sendEventStream(handle, basename, tosend);
		VersionNumber stop_version = new VersionNumber(sent2.get(sent2.size()-1).getVersion()).addAndReturn(1);
		
		// Make sure everything in sent2 is between the start and stop versions
		for(CCNStringObject so : sent2) {
			Assert.assertTrue(start_version.before(so.getVersion()));
			Assert.assertTrue(stop_version.after(so.getVersion()));
		}

		// now final stream
		VersioningHelper.sendEventStream(handle, basename, tosend);

		System.out.println(String.format("Start/stop versions %s to %s",
				start_version.printAsVersionComponent(),
				stop_version.printAsVersionComponent()));
				
		// Now read them back
		TestListener listener = new TestListener();
		InterestData id = new InterestData(basename, start_version, stop_version);

		listener.setInterestData(id);

		Assert.assertTrue( listener.run(handle, tosend, TIMEOUT) );

		// now make sure what we got is what we sent
		VersioningHelper.compareReceived(handle, sent2, listener);
	}
	
	@Test
	public void testSplitLeft() throws Exception {
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
		
	}
	
}
