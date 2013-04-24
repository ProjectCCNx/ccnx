/*
 * A CCNx library test.
 *
 * Copyright (C) 2008-2013 Palo Alto Research Center, Inc.
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

package org.ccnx.ccn.test.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.TreeSet;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.ccnx.ccn.CCNContentHandler;
import org.ccnx.ccn.CCNInterestHandler;
import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.impl.CCNNetworkManager.NetworkProtocol;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.CCNWriter;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Interest;
import org.ccnx.ccn.test.CCNTestBase;
import org.ccnx.ccn.test.CCNTestHelper;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;


/**
 * Test CCNNetworkManager.
 *
 * Note - this test requires ccnd to be running
 *
 */
public class NetworkTest extends CCNTestBase {

	protected static final int WAIT_MILLIS = 8000;
	protected static final int FLOOD_ITERATIONS = 1000;

	protected static final int TEST_TIMEOUT = SystemConfiguration.MEDIUM_TIMEOUT;
	protected static final int CANCEL_TEST_TIMEOUT = 100;

	private final Semaphore sema = new Semaphore(0);
	private final Semaphore filterSema = new Semaphore(0);
	private final Semaphore cancelSema = new Semaphore(1);
	private boolean gotData = false;
	private boolean gotInterest = false;
	private boolean cancelWait = false;
	private final Object cancelLock = new Object();
	Interest testInterest = null;

	// Fix test so it doesn't use static names.
	static CCNTestHelper testHelper = new CCNTestHelper(NetworkTest.class);
	static ContentName testPrefix = testHelper.getClassChildName("networkTest");

	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		CCNTestBase.setUpBeforeClass();
	}

	@AfterClass
	public static void tearDownAfterClass() throws Exception {
		CCNTestBase.tearDownAfterClass();
	}

	@Before
	public void setUp() throws Exception {
	}

	/**
	 * Partially test prefix registration/deregistration
	 * @throws Exception
	 */
	@Test
	public void testRegisteredPrefix() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testRegisteredPrefix");

		TestInterestHandler tfl = new TestInterestHandler();
		TestContentHandler tl = new TestContentHandler();

		ContentName testName1 = new ContentName(testPrefix, "foo");
		Interest interest1 = new Interest(testName1);
		ContentName testName2 = new ContentName(testName1, "bar"); // /foo/bar
		Interest interest2 = new Interest(testName2);
		ContentName testName3 = new ContentName(testName2, "blaz"); // /foo/bar/blaz
		ContentName testName4 = new ContentName(testName2, "xxx");  // /foo/bar/xxx
		Interest interest4 = new Interest(testName4);
		ContentName testName5 = new ContentName(testPrefix, "zoo"); // /zoo
		ContentName testName6 = new ContentName(testName1, "zoo");  // /foo/zoo
		ContentName testName7 = new ContentName(testName2, "spaz"); // /foo/bar/spaz
		Interest interest6 = new Interest(testName6);

		// Test that we don't receive interests above what we registered
		gotInterest = false;
		putHandle.registerFilter(testName2, tfl);
		getHandle.expressInterest(interest1, tl);
		Assert.assertFalse(gotInterest);
		getHandle.cancelInterest(interest1, tl);
		getHandle.expressInterest(interest2, tl);
		Assert.assertTrue("Couldn't get semaphore", filterSema.tryAcquire(WAIT_MILLIS, TimeUnit.MILLISECONDS));
		getHandle.checkError(TEST_TIMEOUT);
		Assert.assertTrue(gotInterest);
		getHandle.cancelInterest(interest2, tl);

		// Test that an "in-between" prefix gets registered properly
		gotInterest = false;
		putHandle.getNetworkManager().cancelInterestFilter(this, testName2, tfl);
		putHandle.registerFilter(testName3, tfl);
		putHandle.registerFilter(testName4, tfl);
		putHandle.registerFilter(testName5, tfl);
		putHandle.registerFilter(testName2, tfl);
		putHandle.registerFilter(testName1, tfl);
		gotInterest = false;
		filterSema.drainPermits();
		getHandle.expressInterest(interest6, tl);
		Assert.assertTrue("Couldn't acquire semaphore", filterSema.tryAcquire(WAIT_MILLIS, TimeUnit.MILLISECONDS));
		getHandle.checkError(TEST_TIMEOUT);
		Assert.assertTrue(gotInterest);
		getHandle.cancelInterest(interest6, tl);

		// Make sure that a filter that is a prefix of a registered filter
		// doesn't get registered separately.
		gotInterest = false;
		filterSema.drainPermits();
		putHandle.registerFilter(testName7, tfl);
		ArrayList<ContentName> prefixes = putHandle.getNetworkManager().getRegisteredPrefixes();
		Assert.assertFalse(prefixes.contains(testName7));
		getHandle.expressInterest(interest4, tl);
		Assert.assertTrue("Couldn't acquire semaphore", filterSema.tryAcquire(WAIT_MILLIS, TimeUnit.MILLISECONDS));
		getHandle.checkError(TEST_TIMEOUT);
		Assert.assertTrue(gotInterest);
		getHandle.cancelInterest(interest4, tl);
		gotInterest = false;
		filterSema.drainPermits();
		getHandle.expressInterest(interest6, tl);
		Assert.assertTrue("Couldn't acquire semaphore", filterSema.tryAcquire(WAIT_MILLIS, TimeUnit.MILLISECONDS));
		getHandle.checkError(TEST_TIMEOUT);
		Assert.assertTrue(gotInterest);
		getHandle.cancelInterest(interest6, tl);
		putHandle.unregisterFilter(testName1, tfl);
		putHandle.unregisterFilter(testName2, tfl);
		putHandle.unregisterFilter(testName3, tfl);
		putHandle.unregisterFilter(testName5, tfl);
		putHandle.unregisterFilter(testName7, tfl);

		// Make sure nothing is registered after a /
		ContentName slashName = ContentName.fromNative("/");
		putHandle.registerFilter(testName1, tfl);
		putHandle.registerFilter(slashName, tfl);
		putHandle.registerFilter(testName5, tfl);
		prefixes = putHandle.getNetworkManager().getRegisteredPrefixes();
		Assert.assertFalse(prefixes.contains(testName5));

		putHandle.unregisterFilter(testName1, tfl);
		putHandle.unregisterFilter(slashName, tfl);
		putHandle.unregisterFilter(testName5, tfl);

		Log.info(Log.FAC_TEST, "Completed testRegisteredPrefix");
	}

	@Test
	public void testNetworkManager() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testNetworkManager");

		/*
		 * Test re-expression of interest
		 */
		CCNWriter writer = new CCNWriter(testPrefix, putHandle);
		ContentName testName = new ContentName(testPrefix, "aaa");

		testInterest = new Interest(testName);
		TestContentHandler tl = new TestContentHandler();
		getHandle.expressInterest(testInterest, tl);
		writer.put(testName, "aaa");
		Assert.assertTrue("Couldn't acquire semaphore", sema.tryAcquire(WAIT_MILLIS, TimeUnit.MILLISECONDS));
		getHandle.checkError(0);
		Assert.assertTrue(gotData);
		writer.close();

		Log.info(Log.FAC_TEST, "Completed testNetworkManager");
	}

	@Test
	public void testNetworkManagerFixedPrefix() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testNetworkManagerFixedPrefix");

		CCNWriter writer = new CCNWriter(putHandle);
		ContentName testName = new ContentName(testPrefix, "ddd");
		testInterest = new Interest(testName);
		TestContentHandler tl = new TestContentHandler();
		getHandle.expressInterest(testInterest, tl);
		writer.put(testName, "ddd");
		Assert.assertTrue("Couldn't acquire semaphore", sema.tryAcquire(WAIT_MILLIS, TimeUnit.MILLISECONDS));
		Assert.assertTrue(gotData);
		writer.close();

		Log.info(Log.FAC_TEST, "Completed testNetworkManagerFixedPrefix");
	}

	@Test
	public void testNetworkManagerBackwards() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testNetworkManagerBackwards");

		CCNWriter writer = new CCNWriter(testPrefix, putHandle);
		// Shouldn't have to do this -- need to refactor test. Had to add it after
		// fixing CCNWriter to do proper flow control.
		writer.disableFlowControl();

		ContentName testName = new ContentName(testPrefix, "bbb");

		testInterest = new Interest(testName);
		TestContentHandler tl = new TestContentHandler();
		writer.put(testName, "bbb");
		getHandle.expressInterest(testInterest, tl);
		Assert.assertTrue("Couldn't acquire semaphore", sema.tryAcquire(WAIT_MILLIS, TimeUnit.MILLISECONDS));
		getHandle.checkError(0);
		Assert.assertTrue(gotData);
		writer.close();

		Log.info(Log.FAC_TEST, "Completed testNetworkManagerBackwards");
	}

	@Test
	public void testFreshnessSeconds() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testFreshnessSeconds");

		CCNWriter writer = new CCNWriter(testPrefix, putHandle);
		writer.disableFlowControl();

		ContentName testName = new ContentName(testPrefix, "freshnessTest");
		writer.put(testName, "freshnessTest", 3);
		Thread.sleep(80);
		ContentObject co = getHandle.get(testName, 1000);
		Assert.assertFalse(co == null);
		Thread.sleep(WAIT_MILLIS);
		co = getHandle.get(testName, 1000);
		Assert.assertTrue(co == null);
		writer.close();

		Log.info(Log.FAC_TEST, "Completed testFreshnessSeconds");
	}

	@Test
	public void testInterestReexpression() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testInterestReexpression");

		/*
		 * Test re-expression of interest
		 */
		CCNWriter writer = new CCNWriter(testPrefix, putHandle);
		ContentName testName = new ContentName(testPrefix, "ccc");
		testInterest = new Interest(testName);
		TestContentHandler tl = new TestContentHandler();
		getHandle.expressInterest(testInterest, tl);
		// Sleep long enough that the interest must be re-expressed
		Thread.sleep(WAIT_MILLIS);
		writer.put(testName, "ccc");
		Assert.assertTrue("Couldn't acquire semaphore", sema.tryAcquire(WAIT_MILLIS, TimeUnit.MILLISECONDS));
		getHandle.checkError(0);
		Assert.assertTrue(gotData);
		writer.close();

		Log.info(Log.FAC_TEST, "Completed testInterestReexpression");
	}

	/**
	 * Test flooding the system with a bunch of content. Only works for TCP
	 * @throws Exception
	 */
	@Test
	public void testFlood() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testFlood");

		if (getHandle.getNetworkManager().getProtocol() == NetworkProtocol.TCP) {
			TreeSet<ContentObject> cos = new TreeSet<ContentObject>();
			for (int i = 0; i < FLOOD_ITERATIONS; i++) {
				ContentName name = new ContentName(testPrefix, (new Integer(i)).toString());
				cos.add(ContentObject.buildContentObject(name, new byte[]{(byte)i}));
			}
			for (ContentObject co : cos)
				putHandle.put(co);
			for (int i = 0; i < FLOOD_ITERATIONS; i++) {
				ContentObject co = getHandle.get(new ContentName(testPrefix, new Integer(i).toString()), 2000);
				Assert.assertNotNull("Failed in flood after " + i + " iterations", co);
			}
		}
		Log.info(Log.FAC_TEST, "Completed testFlood");
	}

	/**
	 * Test that when we cancel an interest and the interest is satisfied during the cancel, side affects
	 * from handling the interest are not allowed to keep the interest alive.
	 *
	 * @throws Exception
	 */
	@Test
	public void testCancelAtomicity() throws Exception {
		CancelTestInterestHandler ctih = new CancelTestInterestHandler();
		CancelTestContentHandler ctch = new CancelTestContentHandler();

		cancelSema.acquire();
		putHandle.registerFilter(testPrefix, ctih);
		Interest interest =  new Interest(testPrefix);
		getHandle.expressInterest(interest, ctch);
		putHandle.checkError(CANCEL_TEST_TIMEOUT);
		getHandle.checkError(CANCEL_TEST_TIMEOUT);
		Assert.assertTrue(cancelSema.tryAcquire(TEST_TIMEOUT, TimeUnit.MILLISECONDS));
		synchronized (cancelLock) {
			cancelWait = true;
		}
		gotData = false;
		getHandle.cancelInterest(interest, ctch);
		cancelSema.release();
		putHandle.checkError(CANCEL_TEST_TIMEOUT);
		Assert.assertFalse("Interest was totally cancelled", gotData);
	}

	class TestInterestHandler implements CCNInterestHandler {

		public boolean handleInterest(Interest interest) {
			gotInterest = true;
			filterSema.release();
			return true;
		}
	}

	class TestContentHandler implements CCNContentHandler {

		public Interest handleContent(ContentObject co,
				Interest interest) {
			Assert.assertFalse(co == null);
			gotData = true;
			sema.release();

			/*
			 * Test call of cancel in handler doesn't hang
			 */
			getHandle.cancelInterest(interest, this);
			return null;
		}
	}

	class CancelTestInterestHandler implements CCNInterestHandler {
		CCNWriter writer = null;
		ContentName testName = new ContentName(testPrefix, "cancelTest");

		private CancelTestInterestHandler() throws IOException {
			writer = new CCNWriter(testName, putHandle);
			writer.disableFlowControl();
		}

		public boolean handleInterest(Interest interest) {
			gotInterest = true;
			try {
				writer.put(testName, "Cancel Atomicity Test");
			} catch (Exception e) {
				Assert.fail(e.getMessage());
			}
			return false;
		}
	}

	class CancelTestContentHandler implements CCNContentHandler {

		public Interest handleContent(ContentObject data, Interest interest) {
			gotData = true;
			cancelSema.release();
			int timeToWait = TEST_TIMEOUT;
			while (!cancelWait && timeToWait > 0) {
				try {
					Thread.sleep(50);
					timeToWait -=50;
				} catch (InterruptedException e) {
					break;
				}
			}
			try {
				Assert.assertTrue(cancelSema.tryAcquire(TEST_TIMEOUT, TimeUnit.MILLISECONDS));
			} catch (InterruptedException e) {
				Assert.fail(e.getMessage());
			}
			return interest;
		}

	}
}
