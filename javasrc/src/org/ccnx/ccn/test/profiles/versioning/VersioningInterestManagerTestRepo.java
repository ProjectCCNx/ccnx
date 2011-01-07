/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2011 Palo Alto Research Center, Inc.
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

import java.util.Random;
import java.util.TreeSet;
import java.util.logging.Level;

import junit.framework.Assert;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.impl.CCNFlowControl.SaveType;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.CCNStringObject;
import org.ccnx.ccn.profiles.versioning.InterestData;
import org.ccnx.ccn.profiles.versioning.VersioningInterestManager;
import org.ccnx.ccn.protocol.CCNTime;
import org.ccnx.ccn.protocol.ContentName;
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
	protected final ContentName prefix;

	protected CCNHandle realhandle = null;
	protected SinkHandle sinkhandle = null;

	public VersioningInterestManagerTestRepo() throws MalformedContentNameStringException {
		prefix  = ContentName.fromNative(String.format("/test_%016X", _rnd.nextLong()));
	}

	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		Log.setLevel(Log.FAC_ALL, Level.WARNING);
		Log.setLevel(Log.FAC_ENCODING, Level.FINE);
	}

	@Before
	public void setUp() throws Exception {
		realhandle = CCNHandle.getHandle();
		sinkhandle = SinkHandle.open(realhandle);		
	}

	@After
	public void tearDown() throws Exception {
		sinkhandle.close();
	}

	/**
	 * Send a stream of versions from the right to the left in order
	 * @throws Exception
	 */
	@Test
	public void testStreamFromRight() throws Exception {
		ContentName basename = ContentName.fromNative(prefix, String.format("content_%016X", _rnd.nextLong()));

		
		TestListener listener = new TestListener();

		TestVIM vim = new TestVIM(sinkhandle, basename, 0, null, 0, listener);
		vim.setSendInterest(true);
		vim.start();

		// Verify that we have 1 interest pending
		Assert.assertTrue( sinkhandle.count.waitForValue(1, TIMEOUT) );


		// send MAX_FILL items, should only be one interest
		CCNTime now = CCNTime.now();
		long t = now.getTime();

		int tosend = VersioningInterestManager.MAX_FILL;
		
		TreeSet<CCNTime> sent1 = sendStream(sinkhandle, vim, basename, t, tosend);

		// we should see only the desired number of interests
		Assert.assertEquals(tosend + 1, sinkhandle.total_count.getValue());
		Assert.assertEquals(1, vim.getInterestDataTree().size());

		Assert.assertEquals( sent1.size(), vim.getExclusions().size());

		// now send one more and we sould see the right sort of split
		TreeSet<CCNTime> sent2 = sendStream(sinkhandle, vim, basename, t, 1);

		sent1.addAll(sent2);
		
		// there should now be 2 extrea re-expressions because of extra interest
		Assert.assertEquals(VersioningInterestManager.MAX_FILL + 3, sinkhandle.total_count.getValue());
		Assert.assertEquals(2, vim.getInterestDataTree().size());
		
		// make sure we have all the right versions
		System.out.println("total sent    : " + sent1.size());
		System.out.println("exclusion size: " + vim.getExclusions().size());
		
		Assert.assertEquals( sent1.size(), vim.getExclusions().size());
		
		
		// the new left one should have MIN_FILL elements and the old (right) one should have the rest
		InterestData left = vim.getInterestDataTree().first();
		InterestData right = vim.getInterestDataTree().last();
		
		int left_size = VersioningInterestManager.MIN_FILL;
		int right_size = sent1.size() - left_size;
		
		Assert.assertEquals(sent1.size(), left_size + right_size);

		if( left.size() != left_size || right.size() != right_size ) {
			System.out.println("truth : " + vim.dumpExcluded());
			System.out.println("left  : " + left.dumpContents());
			System.out.println("right : " + right.dumpContents());
		}
		
		Assert.assertEquals(left_size, left.size());
		Assert.assertEquals(right_size, right.size());

	}


	// ==============================

	private TreeSet<CCNTime> sendStream(CCNHandle handle, TestVIM vim, ContentName name, long t, int count) throws Exception {
		TreeSet<CCNTime> sent = new TreeSet<CCNTime>();
		
		for(int i = 0; i < count; i++) {
			// walk backwares from 10 msec to 100 sec
			t -= _rnd.nextInt(100000) + 10;
			CCNTime version = new CCNTime(t);
			sent.add(version);
			
			CCNStringObject so = new CCNStringObject(name, "Hello, World!", SaveType.LOCALREPOSITORY, handle);
			so.save(version);
			so.close();
			Thread.sleep(10);

			// We are satisfying the interest, so it is no longer pending
			Interest interest = sinkhandle.interests.get(0);
			sinkhandle.cancelInterest(interest, vim);

			Assert.assertNotNull(interest);

			Interest newInterest = vim.exposeReceive(so.getFirstSegment(), interest);

			// this is normally done by handleContent
			if( newInterest != null )
				sinkhandle.expressInterest(newInterest, vim);

		}
		return sent;
	}
}
