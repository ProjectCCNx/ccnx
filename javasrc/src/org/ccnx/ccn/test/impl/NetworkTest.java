/*
 * A CCNx library test.
 *
 * Copyright (C) 2008, 2009, 2010, 2011 Palo Alto Research Center, Inc.
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

import java.util.ArrayList;
import java.util.TreeSet;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.ccnx.ccn.CCNFilterListener;
import org.ccnx.ccn.CCNInterestListener;
import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.impl.CCNNetworkManager.NetworkProtocol;
import org.ccnx.ccn.io.CCNWriter;
import org.ccnx.ccn.profiles.SegmentationProfile;
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
	
	private Semaphore sema = new Semaphore(0);
	private Semaphore filterSema = new Semaphore(0);
	private boolean gotData = false;
	private boolean gotInterest = false;
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
		TestFilterListener tfl = new TestFilterListener();
		TestListener tl = new TestListener();
		ContentName testName1 = ContentName.fromNative(testPrefix, "foo");
		Interest interest1 = new Interest(testName1);
		ContentName testName2 = ContentName.fromNative(testName1, "bar"); // /foo/bar
		Interest interest2 = new Interest(testName2);
		ContentName testName3 = ContentName.fromNative(testName2, "blaz"); // /foo/bar/blaz
		ContentName testName4 = ContentName.fromNative(testName2, "xxx");  // /foo/bar/xxx
		Interest interest4 = new Interest(testName4);
		ContentName testName5 = ContentName.fromNative(testPrefix, "zoo"); // /zoo
		ContentName testName6 = ContentName.fromNative(testName1, "zoo");  // /foo/zoo
		ContentName testName7 = ContentName.fromNative(testName2, "spaz"); // /foo/bar/spaz
		Interest interest6 = new Interest(testName6);
		
		// Test that we don't receive interests above what we registered
		gotInterest = false;
		putHandle.getNetworkManager().setInterestFilter(this, testName2, tfl);
		getHandle.expressInterest(interest1, tl);
		getHandle.checkError(TEST_TIMEOUT);
		Assert.assertFalse(gotInterest);
		getHandle.cancelInterest(interest1, tl);
		getHandle.expressInterest(interest2, tl);
		filterSema.tryAcquire(WAIT_MILLIS, TimeUnit.MILLISECONDS);
		getHandle.checkError(TEST_TIMEOUT);
		Assert.assertTrue(gotInterest);
		getHandle.cancelInterest(interest2, tl);
		
		// Test that an "in-between" prefix gets registered properly
		gotInterest = false;
		putHandle.getNetworkManager().cancelInterestFilter(this, testName2, tfl);
		putHandle.getNetworkManager().setInterestFilter(this, testName3, tfl);
		putHandle.getNetworkManager().setInterestFilter(this, testName4, tfl);
		putHandle.getNetworkManager().setInterestFilter(this, testName5, tfl);
		putHandle.getNetworkManager().setInterestFilter(this, testName2, tfl);
		putHandle.getNetworkManager().setInterestFilter(this, testName1, tfl);
		gotInterest = false;
		filterSema.drainPermits();
		getHandle.expressInterest(interest6, tl);		
		filterSema.tryAcquire(WAIT_MILLIS, TimeUnit.MILLISECONDS);
		getHandle.checkError(TEST_TIMEOUT);
		Assert.assertTrue(gotInterest);
		getHandle.cancelInterest(interest6, tl);
		
		// Make sure that a filter that is a prefix of a registered filter
		// doesn't get registered separately.
		gotInterest = false;
		filterSema.drainPermits();
		putHandle.getNetworkManager().setInterestFilter(this, testName7, tfl);
		ArrayList<ContentName> prefixes = putHandle.getNetworkManager().getRegisteredPrefixes();
		Assert.assertFalse(prefixes.contains(testName7));
		getHandle.expressInterest(interest4, tl);
		filterSema.tryAcquire(WAIT_MILLIS, TimeUnit.MILLISECONDS);
		getHandle.checkError(TEST_TIMEOUT);
		Assert.assertTrue(gotInterest);
		getHandle.cancelInterest(interest4, tl);
		gotInterest = false;
		filterSema.drainPermits();
		getHandle.expressInterest(interest6, tl);
		filterSema.tryAcquire(WAIT_MILLIS, TimeUnit.MILLISECONDS);
		getHandle.checkError(TEST_TIMEOUT);
		Assert.assertTrue(gotInterest);
		getHandle.cancelInterest(interest6, tl);
		putHandle.getNetworkManager().cancelInterestFilter(this, testName1, tfl);
		putHandle.getNetworkManager().cancelInterestFilter(this, testName2, tfl);
		putHandle.getNetworkManager().cancelInterestFilter(this, testName3, tfl);
		putHandle.getNetworkManager().cancelInterestFilter(this, testName5, tfl);
		putHandle.getNetworkManager().cancelInterestFilter(this, testName7, tfl);
		
		// Make sure nothing is registered after a /
		ContentName slashName = ContentName.fromNative("/");
		putHandle.getNetworkManager().setInterestFilter(this, testName1, tfl);
		putHandle.getNetworkManager().setInterestFilter(this, slashName, tfl);
		putHandle.getNetworkManager().setInterestFilter(this, testName5, tfl);
		
		// Temporarily disable this test until issues causing it to fail intermittently can be
		// fixed.
		
		//prefixes = putHandle.getNetworkManager().getRegisteredPrefixes();
		//Assert.assertFalse(prefixes.contains(testName5));
		
		putHandle.getNetworkManager().cancelInterestFilter(this, testName1, tfl);
		putHandle.getNetworkManager().cancelInterestFilter(this, slashName, tfl);
		putHandle.getNetworkManager().cancelInterestFilter(this, testName5, tfl);
		
	}

	@Test
	public void testNetworkManager() throws Exception {
		
		/*
		 * Test re-expression of interest
		 */
		CCNWriter writer = new CCNWriter(testPrefix, putHandle);
		ContentName testName = ContentName.fromNative(testPrefix, "aaa");
		
		testInterest = new Interest(testName);
		TestListener tl = new TestListener();
		getHandle.expressInterest(testInterest, tl);
		Thread.sleep(80);  
		writer.put(testName, "aaa");
		sema.tryAcquire(WAIT_MILLIS, TimeUnit.MILLISECONDS);
		Assert.assertTrue(gotData);
		writer.close();
	}
	
	@Test
	public void testNetworkManagerFixedPrefix() throws Exception {
		CCNWriter writer = new CCNWriter(putHandle);
		ContentName testName = ContentName.fromNative(testPrefix, "ddd");
		testInterest = new Interest(testName);
		TestListener tl = new TestListener();
		getHandle.expressInterest(testInterest, tl);
		Thread.sleep(80);  
		writer.put(testName, "ddd");
		sema.tryAcquire(WAIT_MILLIS, TimeUnit.MILLISECONDS);
		Assert.assertTrue(gotData);
		writer.close();
	}
	
	@Test
	public void testNetworkManagerBackwards() throws Exception {
		
		CCNWriter writer = new CCNWriter(testPrefix, putHandle);
		// Shouldn't have to do this -- need to refactor test. Had to add it after
		// fixing CCNWriter to do proper flow control.
		writer.disableFlowControl();
		
		ContentName testName = ContentName.fromNative(testPrefix, "bbb");

		testInterest = new Interest(testName);
		TestListener tl = new TestListener();
		writer.put(testName, "bbb");
		Thread.sleep(80);  
		getHandle.expressInterest(testInterest, tl);
		sema.tryAcquire(WAIT_MILLIS, TimeUnit.MILLISECONDS);
		Assert.assertTrue(gotData);
		writer.close();
	}
	
	@Test
	public void testFreshnessSeconds() throws Exception {
		CCNWriter writer = new CCNWriter(testPrefix, putHandle);
		writer.disableFlowControl();
		
		ContentName testName = ContentName.fromNative(testPrefix, "freshnessTest");
		writer.put(testName, "freshnessTest", 3);
		Thread.sleep(80);
		ContentObject co = getHandle.get(testName, 1000);
		Assert.assertFalse(co == null);
		Thread.sleep(WAIT_MILLIS);
		co = getHandle.get(testName, 1000);
		Assert.assertTrue(co == null);
		writer.close();
	}

	@Test
	public void testInterestReexpression() throws Exception {
		
		/*
		 * Test re-expression of interest
		 */
		CCNWriter writer = new CCNWriter(testPrefix, putHandle);
		ContentName testName = ContentName.fromNative(testPrefix, "ccc");
		testInterest = new Interest(testName);
		TestListener tl = new TestListener();
		getHandle.expressInterest(testInterest, tl);
		// Sleep long enough that the interest must be re-expressed
		Thread.sleep(WAIT_MILLIS);  
		writer.put(testName, "ccc");
		sema.tryAcquire(WAIT_MILLIS, TimeUnit.MILLISECONDS);
		Assert.assertTrue(gotData);
		writer.close();
	}
	
	/**
	 * Test flooding the system with a bunch of content. Only works for TCP
	 * @throws Exception
	 */
	@Test
	public void testFlood() throws Exception {
		if (getHandle.getNetworkManager().getProtocol() == NetworkProtocol.TCP) {
			System.out.println("Testing TCP flooding");
			TreeSet<ContentObject> cos = new TreeSet<ContentObject>();
			for (int i = 0; i < FLOOD_ITERATIONS; i++) {
				ContentName name = ContentName.fromNative(testPrefix, (new Integer(i)).toString());
				cos.add(ContentObject.buildContentObject(name, new byte[]{(byte)i}));
			}
			for (ContentObject co : cos)
				putHandle.put(co);
			for (int i = 0; i < FLOOD_ITERATIONS; i++) {
				ContentObject co = getHandle.get(ContentName.fromNative(testPrefix, new Integer(i).toString()), 2000);
				Assert.assertNotNull(co);
			}
		}
	}
	
	class TestFilterListener implements CCNFilterListener {

		public boolean handleInterest(Interest interest) {
			gotInterest = true;
			filterSema.release();
			return true;
		}
		
	}
	
	class TestListener implements CCNInterestListener {

		public Interest handleContent(ContentObject co,
				Interest interest) {
			Assert.assertFalse(co == null);
			ContentName nameBase = SegmentationProfile.segmentRoot(co.name());
			Assert.assertEquals(nameBase.stringComponent(nameBase.count()-1), new String(co.content()));
			gotData = true;
			sema.release();
			
			/*
			 * Test call of cancel in handler doesn't hang
			 */
			getHandle.cancelInterest(testInterest, this);
			return null;
		}
	}
	
}
