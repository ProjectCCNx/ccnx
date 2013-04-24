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

import java.io.IOException;
import java.util.Random;
import java.util.TreeSet;
import java.util.logging.Level;

import junit.framework.Assert;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.profiles.versioning.InterestData;
import org.ccnx.ccn.profiles.versioning.VersionNumber;
import org.ccnx.ccn.profiles.versioning.VersioningInterestManager;
import org.ccnx.ccn.protocol.CCNTime;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Interest;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;
import org.ccnx.ccn.test.profiles.versioning.VersioningHelper.SinkHandle;
import org.ccnx.ccn.test.profiles.versioning.VersioningHelper.TestListener;
import org.ccnx.ccn.test.profiles.versioning.VersioningHelper.TestVIM;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;


/**
 * Test the receive method, and check how interests are rebuilt
 */
public class VersioningInterestManagerTestRepo {

	protected final Random _rnd = new Random();
	protected final static long TIMEOUT=30000;
	protected final static long SEND_PAUSE = 100;
	protected final static int LONG_SEND_MULTIPLE = 30;
	protected final ContentName prefix;

	protected CCNHandle realhandle = null;
	protected SinkHandle sinkhandle = null;

	public VersioningInterestManagerTestRepo() throws MalformedContentNameStringException {
		prefix  = ContentName.fromNative(String.format("/repotest/test_%016X", _rnd.nextLong()));
	}

	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		Log.setLevel(Log.FAC_ALL, Level.WARNING);
		Log.setLevel(Log.FAC_ENCODING, Level.WARNING);
	}

	@Before
	public void setUp() throws Exception {
		realhandle = CCNHandle.open();
		sinkhandle = SinkHandle.open(realhandle);		
	}

	@After
	public void tearDown() throws Exception {
		realhandle.close();
		sinkhandle.close();
	}

	/**
	 * Send a stream of versions from the right to the left in order.  Sends
	 * just enough to fill to FIL_MAX and verify we have exactly 1 interest.
	 * Then sends 1 more exclusion and verifies that the interest was
	 * split the right way.
	 * @throws Exception
	 */
	@Test
	public void testStreamFromRight() throws Exception {
		System.out.println("********** testStreamFromRight");

		ContentName basename = new ContentName(prefix, String.format("content_%016X", _rnd.nextLong()));

		TestListener listener = new TestListener();

		TestVIM vim = new TestVIM(sinkhandle, basename, null, VersionNumber.getMinimumVersion(), listener);
		vim.setSendInterest(true);
		vim.start();

		// Verify that we have 1 interest pending
		Assert.assertTrue( sinkhandle.count.waitForValue(1, TIMEOUT) );


		// send MAX_FILL items, should only be one interest
		CCNTime now = CCNTime.now();
		long t = now.getTime();

		int tosend = VersioningInterestManager.MAX_FILL;

		System.out.println("***** Sending stream 1 *****");
		TreeSet<CCNTime> sent1 = sendStreamRight(sinkhandle, vim, basename, t, tosend);

		// wait for them to be received
		Assert.assertTrue( sinkhandle.total_count.waitForValue(tosend + 1, TIMEOUT));

		// we should see only the desired number of interests
		Assert.assertEquals(1, vim.getInterestDataTree().size());

		Assert.assertEquals( sent1.size(), vim.getExclusions().size());

		//		System.out.println(String.format("data  (%d): %s", vim.getInterestDataTree().first().size(), vim.getInterestDataTree().first().dumpContents()));

		System.out.println("***** Sending stream 2 *****");
		// now send one more and we should see the right sort of split
		TreeSet<CCNTime> sent2 = sendStreamRight(sinkhandle, vim, basename, t, 1);

		sent1.addAll(sent2);

		// wait for them to be received
		// it's 23 because: 20 to start with + 1 extra, then that one is replaced by 2 because
		// the new interest is split.
		boolean b = sinkhandle.total_count.waitForValue(tosend + 3, TIMEOUT);
		Assert.assertTrue("sinkhandle incorrect count: " + sinkhandle.total_count.getValue(), b);

		// make sure we have all the right versions
		System.out.println("total sent    : " + sent1.size());
		System.out.println("exclusion size: " + vim.getExclusions().size());

		// the new left one should have MIN_FILL elements and the old (right) one should have the rest
		InterestData left = vim.getInterestDataTree().first();
		InterestData right = vim.getInterestDataTree().last();

		int left_size = VersioningInterestManager.MIN_FILL;
		int right_size = sent1.size() - left_size;

		if( left.size() != left_size || right.size() != right_size ) {
			System.out.println(String.format("truth (%d): %s", vim.getExclusions().size(), vim.dumpExcluded()));
			System.out.println(String.format("left  (%d): %s", left.size(), left.dumpContents()));
			System.out.println(String.format("right (%d): %s", right.size(), right.dumpContents()));
		}

		vim.stop();
		
		Assert.assertEquals(sent1.size(), left_size + right_size);

		// there should now be 2 extrea re-expressions because of extra interest
		Assert.assertEquals(2, vim.getInterestDataTree().size());

		Assert.assertEquals( sent1.size(), vim.getExclusions().size());


		Assert.assertEquals(left_size, left.size());
		Assert.assertEquals(right_size, right.size());

	}

	/**
	 * Send a very long stream from the right
	 * @throws Exception
	 */
	@Test
	public void testLongStreamFromRight() throws Exception {
		System.out.println("********** testLongStreamFromRight");
		ContentName basename = new ContentName(prefix, String.format("content_%016X", _rnd.nextLong()));

		TestListener listener = new TestListener();

		TestVIM vim = new TestVIM(sinkhandle, basename, null, VersionNumber.getMinimumVersion(), listener);
		vim.setSendInterest(true);
		vim.start();

		// Verify that we have 1 interest pending
		Assert.assertTrue( sinkhandle.count.waitForValue(1, TIMEOUT) );


		// send MAX_FILL items, should only be one interest
		CCNTime now = CCNTime.now();
		long t = now.getTime();

		int tosend = VersioningInterestManager.MAX_FILL * LONG_SEND_MULTIPLE;

		// How many interets will this be?  Every time it fills an interest, it will
		// leave MAX_FILL - MIN_FILL in that interest, then shift MIN_FILL to the left.
		int packets = 1;
		int occupancy = 0;
		for(int i = 0; i < tosend; i++) {
			if( occupancy >= VersioningInterestManager.MAX_FILL ) {
				packets++;
				occupancy = VersioningInterestManager.MIN_FILL;
			}
			occupancy++;
		}

		System.out.println(String.format("Sending %d exclusions should result in %d interest packets", tosend, packets));

		System.out.println("***** Sending stream 1 *****");
		TreeSet<CCNTime> sent1 = sendStreamRight(sinkhandle, vim, basename, t, tosend);

		// There will be 1 interest per exclusion plus the number of outstanding packets
		boolean b = sinkhandle.total_count.waitForValue(tosend + packets, TIMEOUT);

		Assert.assertTrue("sinkhandle incorrect count: " + sinkhandle.total_count.getValue(), b);

		// we should see only the desired number of interests
		Assert.assertEquals(packets, vim.getInterestDataTree().size());

		Assert.assertEquals(sent1.size(), vim.getExclusions().size());
		
		vim.stop();
	}

	/**
	 * Send a very long stream from the right
	 * @throws Exception
	 */
	@Test
	public void testLongStreamFromLeft() throws Exception {
		System.out.println("********** testLongStreamFromLeft");
		ContentName basename = new ContentName(prefix, String.format("content_%016X", _rnd.nextLong()));

		TestListener listener = new TestListener();

		TestVIM vim = new TestVIM(sinkhandle, basename, null, VersionNumber.getMinimumVersion(), listener);
		vim.setSendInterest(true);
		vim.start();

		// Verify that we have 1 interest pending
		Assert.assertTrue( sinkhandle.count.waitForValue(1, TIMEOUT) );


		// send MAX_FILL items, should only be one interest
		CCNTime now = CCNTime.now();
		long t = now.getTime();

		int tosend = VersioningInterestManager.MAX_FILL * LONG_SEND_MULTIPLE;

		// How many interets will this be?  Every time it fills an interest, it will
		// leave MAX_FILL - MIN_FILL in that interest, then shift MIN_FILL to the left.
		int packets = 1;
		int occupancy = 0;
		for(int i = 0; i < tosend; i++) {
			if( occupancy >= VersioningInterestManager.MAX_FILL ) {
				packets++;
				occupancy = VersioningInterestManager.MIN_FILL;
			}
			occupancy++;
		}

		System.out.println(String.format("Sending %d exclusions should result in %d interest packets", tosend, packets));

		System.out.println("***** Sending stream 1 *****");
		TreeSet<CCNTime> sent1 = sendStreamLeft(sinkhandle, vim, basename, t, tosend);

		int expected = tosend + packets + 1;
		boolean b = sinkhandle.total_count.waitForValue(expected, TIMEOUT);

		// we should see only the desired number of interests
		Assert.assertEquals(packets, vim.getInterestDataTree().size());

		Assert.assertEquals(sent1.size(), vim.getExclusions().size());

		Assert.assertTrue(
				String.format("sinkhandle incorrect count %d expected %d", 
						sinkhandle.total_count.getValue(),
						expected), b);
		vim.stop();

	}

	/**
	 * Send a very long stream with arrivals uniformly over some interval
	 * @throws Exception
	 */
	@Test
	public void testLongStreamUniform() throws Exception {
		System.out.println("********** testLongStreamUniform");
		ContentName basename = new ContentName(prefix, String.format("content_%016X", _rnd.nextLong()));

		TestListener listener = new TestListener();

		TestVIM vim = new TestVIM(sinkhandle, basename, null, VersionNumber.getMinimumVersion(), listener);
		vim.setSendInterest(true);
		vim.start();

		int tosend = VersioningInterestManager.MAX_FILL * LONG_SEND_MULTIPLE;

		// send MAX_FILL items, should only be one interest
		CCNTime now = CCNTime.now();
		long max_spacing = 20000;
		long start_time = now.getTime();
		long stop_time = start_time + tosend * max_spacing;

		System.out.println("***** Sending stream 1 *****");
		sendStreamUniform(sinkhandle, vim, basename, start_time, stop_time, tosend);

		// wait a while
		Thread.sleep(10000);

		// we dont know how many interets this will take, but we can bound it
		int min_interests = (int) Math.ceil((double) tosend / VersioningInterestManager.MAX_FILL);
		int max_interests = (int) Math.floor((double) tosend / VersioningInterestManager.MIN_FILL);

		System.out.println("handle interests: " + sinkhandle.total_count.getValue());
		System.out.println("exclusions      : " + vim.getExclusions().size());
		System.out.println("vim interests   : " + vim.getInterestDataTree().size() + " (should be close to min interests)");
		System.out.println("min interests   : " + min_interests);
		System.out.println("max interests   : " + max_interests);

		Assert.assertTrue( min_interests <= vim.getInterestDataTree().size() );
		Assert.assertTrue( vim.getInterestDataTree().size() <= max_interests );


		Assert.assertTrue( min_interests + tosend <= sinkhandle.total_count.getValue() );
		Assert.assertTrue( sinkhandle.total_count.getValue() <= max_interests + tosend );
		vim.stop();
	}

	/**
	 * Send a very long stream with arrivals normally distributed around a spot
	 * @throws Exception
	 */
	@Test
	public void testLongStreamGaussian() throws Exception {
		System.out.println("********** testLongStreamGaussian");
		ContentName basename = new ContentName(prefix, String.format("content_%016X", _rnd.nextLong()));

		TestListener listener = new TestListener();

		TestVIM vim = new TestVIM(sinkhandle, basename, null, VersionNumber.getMinimumVersion(), listener);
		vim.setSendInterest(true);
		vim.start();

		int tosend = VersioningInterestManager.MAX_FILL * LONG_SEND_MULTIPLE;

		// send MAX_FILL items, should only be one interest
		CCNTime now = CCNTime.now();
		int max_spacing = 20000;
		double mean_time = now.getTime();
		double std_time = tosend * max_spacing;

		System.out.println("***** Sending stream 1 *****");
		sendStreamGaussian(sinkhandle, vim, basename, mean_time, std_time, tosend);

		// wait a while
		Thread.sleep(10000);

		// we dont know how many interets this will take, but we can bound it
		int min_interests = (int) Math.ceil((double) tosend / VersioningInterestManager.MAX_FILL);
		int max_interests = (int) Math.floor((double) tosend / VersioningInterestManager.MIN_FILL);

		System.out.println("handle interests: " + sinkhandle.total_count.getValue());
		System.out.println("exclusions      : " + vim.getExclusions().size());
		System.out.println("vim interests   : " + vim.getInterestDataTree().size() + " (should be close to min interests)");
		System.out.println("min interests   : " + min_interests);
		System.out.println("max interests   : " + max_interests);

		Assert.assertTrue( min_interests <= vim.getInterestDataTree().size() );
		Assert.assertTrue( vim.getInterestDataTree().size() <= max_interests );


		Assert.assertTrue( min_interests + tosend <= sinkhandle.total_count.getValue() );
		Assert.assertTrue( sinkhandle.total_count.getValue() <= max_interests + tosend );
		vim.stop();
	}

	// ==============================

	private TreeSet<CCNTime> sendStreamGaussian(CCNHandle handle, TestVIM vim, ContentName name, double mean_time, double std_time, int count) throws Exception {
		TreeSet<CCNTime> sent = new TreeSet<CCNTime>();

		for(int i = 0; i < count; i++) {
			CCNTime version;
			// avoid sending duplicate version numbers
			do {
				double d = std_time * _rnd.nextGaussian() + mean_time;

				version = new CCNTime(Math.round(d));
			} while( !sent.add(version));

			send(handle, vim, name, version);
		}
		return sent;
	}

	private TreeSet<CCNTime> sendStreamUniform(CCNHandle handle, TestVIM vim, ContentName name, long start_time, long stop_time, int count) throws Exception {
		TreeSet<CCNTime> sent = new TreeSet<CCNTime>();

		int width = (int) (stop_time - start_time + 1);
		for(int i = 0; i < count; i++) {
			CCNTime version;

			// avoid sending duplicate version numbers
			do {
				int delta = _rnd.nextInt(width);
				version = new CCNTime(start_time + delta);
			} while( !sent.add(version));

			send(handle, vim, name, version);
		}
		return sent;
	}

	private TreeSet<CCNTime> sendStreamRight(CCNHandle handle, TestVIM vim, ContentName name, long t, int count) throws Exception {
		TreeSet<CCNTime> sent = new TreeSet<CCNTime>();

		for(int i = 0; i < count; i++) {
			// walk backwares from 10 msec to 100 sec
			t -= _rnd.nextInt(100000) + 10;
			CCNTime version = new CCNTime(t);
			sent.add(version);		
			send(handle, vim, name, version);
		}
		return sent;
	}

	private TreeSet<CCNTime> sendStreamLeft(CCNHandle handle, TestVIM vim, ContentName name, long t, int count) throws Exception {
		TreeSet<CCNTime> sent = new TreeSet<CCNTime>();

		for(int i = 0; i < count; i++) {
			// walk backwares from 10 msec to 100 sec
			t += _rnd.nextInt(100000) + 10;
			CCNTime version = new CCNTime(t);
			sent.add(version);		
			send(handle, vim, name, version);
		}
		return sent;
	}

	private void send(CCNHandle handle, TestVIM vim, ContentName name, CCNTime version) throws IOException, InterruptedException {
		
		String mystring = "Hello, World!";

		ContentName versionedNamed = new ContentName(name, version);
		ContentObject data = ContentObject.buildContentObject(versionedNamed, mystring.getBytes());
		


		// We are satisfying the interest, so it is no longer pending
		Interest interest = sinkhandle.interests.get(0);
		sinkhandle.cancelInterest(interest, vim);

		Assert.assertNotNull(interest);

		Interest newInterest = vim.exposeReceive(data, interest);

		// this is normally done by handleContent
		if( newInterest != null )
			sinkhandle.expressInterest(newInterest, vim);
	}

	@SuppressWarnings("unused")
	private void dumpstate(TestVIM vim) {
		System.out.println("=========================================");
		System.out.println("Sinkhandle state");
		for(Interest interest : sinkhandle.interests )
			System.out.println(interest.toString());

		System.out.println("=========================================");
		System.out.println("TestVM InterestData");
		for(InterestData data : vim.getInterestDataTree() )
			System.out.println(data.toString());

		//		System.out.println("=========================================");
		//		System.out.println("TestVM pending interests");
		//		for(InterestData data : vim.getInterestDataTree() )
		//			System.out.println(data.toString());
	}
}
