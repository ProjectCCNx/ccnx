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

import java.util.Random;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.impl.CCNFlowControl.SaveType;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.CCNStringObject;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.profiles.versioning.VersioningHelper.TestListener;
import org.ccnx.ccn.protocol.CCNTime;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Interest;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;
import org.junit.Assert;
import org.junit.Test;

public class InterestDataTestRepo {

	protected final Random _rnd = new Random();
	protected final static long TIMEOUT=30000;
	protected final ContentName prefix;
	
	public InterestDataTestRepo() throws MalformedContentNameStringException {
		prefix  = ContentName.fromNative(String.format("/repotest/test_%016X", _rnd.nextLong()));
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

	/**
	 * Create one object then create an InterestData for it and make sure we get the object.
	 * @throws Exception
	 */
	@Test
	public void testInterestDataInterest() throws Exception {
		Log.info(Log.FAC_TEST, "Started testInterestDataInterest");

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

		Log.info(Log.FAC_TEST, "Expressing interest " + interest);
		
		handle.expressInterest(interest, listener);

		Assert.assertTrue( listener.cl.waitForValue(1L, TIMEOUT) );

		// now make sure what we got is what we expected
		ContentObject co = listener.received.get(0).object;

		CCNTime received_version = VersioningProfile.getLastVersionAsTimestamp(co.name());
		Assert.assertTrue(version.equals(received_version));

		CCNStringObject received_so = new CCNStringObject(co, handle);
		Assert.assertTrue(so.string().equals(received_so.string()));
		
		Log.info(Log.FAC_TEST, "Completed testInterestDataInterest");
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
