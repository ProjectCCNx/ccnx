/*
 * A CCNx library test.
 *
 * Copyright (C) 2008, 2009 Palo Alto Research Center, Inc.
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
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.CCNInterestListener;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.io.CCNWriter;
import org.ccnx.ccn.profiles.SegmentationProfile;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Interest;
import org.ccnx.ccn.test.CCNTestHelper;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;


/**
 * Test CCNNetworkManager.
 * 
 * This should eventually have more tests but for now at least we will
 * test the re-expression of interests
 * 
 * Note - this test requires ccnd to be running
 *
 */
public class NetworkTest {
	
	protected static final int WAIT_MILLIS = 8000;
	
	protected static CCNHandle putLibrary = null;
	protected static CCNHandle getLibrary = null;
	private Semaphore sema = new Semaphore(0);
	private boolean gotData = false;
	Interest testInterest = null;
	
	// Fix test so it doesn't use static names.
	static CCNTestHelper testHelper = new CCNTestHelper(NetworkTest.class);
	static ContentName testPrefix = testHelper.getClassChildName("networkTest");
	
	static {
		try {
			putLibrary = CCNHandle.open();
			getLibrary = CCNHandle.open();
		} catch (ConfigurationException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
	}
	
	@AfterClass
	public static void tearDownAfterClass() {
		putLibrary.close();
		getLibrary.close();
	}

	@Before
	public void setUp() throws Exception {
	}

	@Test
	public void testNetworkManager() throws Exception {
		
		/*
		 * Test re-expression of interest
		 */
		CCNWriter writer = new CCNWriter(testPrefix, putLibrary);
		ContentName testName = ContentName.fromNative(testPrefix, "aaa");
		
		testInterest = new Interest(testName);
		TestListener tl = new TestListener();
		getLibrary.expressInterest(testInterest, tl);
		Thread.sleep(80);  
		writer.put(testName, "aaa");
		sema.tryAcquire(WAIT_MILLIS, TimeUnit.MILLISECONDS);
		Assert.assertTrue(gotData);
	}
	
	@Test
	public void testNetworkManagerFixedPrefix() throws Exception {
		
		/*
		 * Test re-expression of interest
		 */
		CCNWriter writer = new CCNWriter(putLibrary);
		ContentName testName = ContentName.fromNative(testPrefix, "ddd");
		testInterest = new Interest(testName);
		TestListener tl = new TestListener();
		getLibrary.expressInterest(testInterest, tl);
		Thread.sleep(80);  
		writer.put(testName, "ddd");
		sema.tryAcquire(WAIT_MILLIS, TimeUnit.MILLISECONDS);
		Assert.assertTrue(gotData);
	}
	
	@Test
	public void testNetworkManagerBackwards() throws Exception {
		
		/*
		 * Test re-expression of interest
		 */
		CCNWriter writer = new CCNWriter(testPrefix, putLibrary);
		// Shouldn't have to do this -- need to refactor test. Had to add it after
		// fixing CCNWriter to do proper flow control.
		writer.disableFlowControl();
		
		ContentName testName = ContentName.fromNative(testPrefix, "bbb");

		testInterest = new Interest(testName);
		TestListener tl = new TestListener();
		writer.put(testName, "bbb");
		Thread.sleep(80);  
		getLibrary.expressInterest(testInterest, tl);
		sema.tryAcquire(WAIT_MILLIS, TimeUnit.MILLISECONDS);
		Assert.assertTrue(gotData);
	}
	
	@Test
	public void testFreshnessSeconds() throws Exception {
		CCNWriter writer = new CCNWriter(testPrefix, putLibrary);
		writer.disableFlowControl();
		
		ContentName testName = ContentName.fromNative(testPrefix, "freshnessTest");
		writer.put(testName, "freshnessTest", 3);
		Thread.sleep(80);
		ContentObject co = getLibrary.get(testName, 1000);
		Assert.assertFalse(co == null);
		Thread.sleep(WAIT_MILLIS);
		co = getLibrary.get(testName, 1000);
		Assert.assertTrue(co == null);
	}

	@Test
	public void testInterestReexpression() throws Exception {
		
		/*
		 * Test re-expression of interest
		 */
		CCNWriter writer = new CCNWriter(testPrefix, putLibrary);
		ContentName testName = ContentName.fromNative(testPrefix, "ccc");
		testInterest = new Interest(testName);
		TestListener tl = new TestListener();
		getLibrary.expressInterest(testInterest, tl);
		// Sleep long enough that the interest must be re-expressed
		Thread.sleep(WAIT_MILLIS);  
		writer.put(testName, "ccc");
		sema.tryAcquire(WAIT_MILLIS, TimeUnit.MILLISECONDS);
		Assert.assertTrue(gotData);
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
			getLibrary.cancelInterest(testInterest, this);
			return null;
		}
	}
	
}
