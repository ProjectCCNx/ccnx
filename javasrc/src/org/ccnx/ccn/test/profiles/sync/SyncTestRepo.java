/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2012 Palo Alto Research Center, Inc.
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
package org.ccnx.ccn.test.profiles.sync;

import java.io.IOException;
import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.Vector;

import junit.framework.Assert;

import org.ccnx.ccn.CCNContentHandler;
import org.ccnx.ccn.CCNSync;
import org.ccnx.ccn.CCNSyncHandler;
import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.ConfigSlice;
import org.ccnx.ccn.io.content.SyncNodeComposite;
import org.ccnx.ccn.profiles.SegmentationProfile;
import org.ccnx.ccn.profiles.metadata.MetadataProfile;
import org.ccnx.ccn.profiles.sync.Sync;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Interest;
import org.ccnx.ccn.test.CCNTestBase;
import org.ccnx.ccn.test.CCNTestHelper;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Test;

public class SyncTestRepo extends CCNTestBase implements CCNSyncHandler, CCNContentHandler {
	
	public static CCNTestHelper testHelper = new CCNTestHelper(SyncTestRepo.class);
	
	ContentName prefix;
	ContentName topo;
	Vector<ContentName> callbackNames = new Vector<ContentName>();
	String errorMessage = null;

	static Vector<ConfigSlice> slices = new Vector<ConfigSlice>();
	
	@Before
	public void setUpNameSpace() {
		prefix = testHelper.getTestNamespace("ccnSyncTest");
		topo = testHelper.getTestNamespace("topoPrefix");
		Log.fine(Log.FAC_TEST, "setting up namespace for sync test  data: {0} syncControlTraffic: {1}", prefix, topo);
	}
	
	@AfterClass
	public static void cleanup() {
		//need to delete the slices to clean up after the test.
		for(ConfigSlice s: slices)
			try {
				s.deleteSlice(getHandle);
			} catch (IOException e) {
				Log.warning(Log.FAC_TEST, "failed to get handle to clean up sync slices:{0}", e.getMessage());
			}
		
	}
	
	@Test
	public void testSyncStartWithHandle() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testSyncStartWithHandle");
		ContentName prefix1;
		prefix1 = prefix.append("slice1");

		CCNSync sync1 = new CCNSync();
		ConfigSlice slice1 = sync1.startSync(getHandle, topo, prefix1, this);
		slices.add(slice1);
		
		sync1.stopSync(this, slice1);
			
		Log.info(Log.FAC_TEST, "Completed testSyncStartWithHandle");
	}
	
	@Test
	public void testSyncStartWithoutHandle() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testSyncStartWithoutHandle");
		
		ContentName prefix1;
		prefix1 = prefix.append("slice2");
		CCNSync sync1 = new CCNSync();
		ConfigSlice slice2 = sync1.startSync(getHandle, topo, prefix1, this);
		slices.add(slice2);
		
		sync1.stopSync(this, slice2);

		Log.info(Log.FAC_TEST,"Finished running testSyncStartWithoutHandle");
	}

	@Test
	public void testSyncClose() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testSyncClose");
		
		ContentName prefix1;
		prefix1 = prefix.append("slice3");
		CCNSync sync1 = new CCNSync();
		ConfigSlice slice3 = sync1.startSync(getHandle, topo, prefix1, this);
		slices.add(slice3);
		
		//the slice should be written..  now save content and get a callback.
		Log.fine(Log.FAC_TEST, "writing out file: {0}", prefix1);
		
		// Write a 100 block file to test a true sync tree
		int segments = SyncTestCommon.writeFile(prefix1, false, SystemConfiguration.BLOCK_SIZE * 100, putHandle);
		int segmentCheck = checkCallbacks(prefix1, segments, 0);
		if (segmentCheck!=0)
			Assert.fail("Did not receive all of the callbacks");
		else
			Log.fine(Log.FAC_TEST, "I got all the callbacks for part 1 of testSyncClose!");
		
		//now close the callback interface
		sync1.stopSync(this, slice3);
		
		//then close and make sure we don't get a callback
		prefix1 = prefix1.append("round2");
		Log.fine(Log.FAC_TEST, "writing out file: {0}", prefix1);
		segments = SyncTestCommon.writeFile(prefix1, true, 0, putHandle);  //this should be a new version
		segmentCheck = checkCallbacks(prefix1, segments, 0);
		if (segmentCheck != segments) {
			//we must have gotten callbacks...  bad.
			Assert.fail("received callbacks after interface was closed.  ERROR");
		}
		Log.fine(Log.FAC_TEST, "I didn't get callbacks after I stopped sync for myself!");

		Log.info(Log.FAC_TEST,"Finished running testSyncStop");
	}
	
	@Test
	public void testCallbackRegistration() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testCallbackRegistration");
		
		ContentName prefix1;
		ContentName prefix2;
		prefix1 = prefix.append("slice4");
		CCNSync sync1 = new CCNSync();
		ConfigSlice slice4 = sync1.startSync(getHandle, topo, prefix1, this);
		slices.add(slice4);
		prefix2 = prefix.append("slice5");
		ConfigSlice slice5 = sync1.startSync(getHandle, topo, prefix2, this);
		
		//the slice should be written..  now save content and get a callback.
		Log.fine(Log.FAC_TEST, "writing out file: {0}", prefix1);
		int segments = SyncTestCommon.writeFile(prefix1, true, 0, putHandle);
		int segments2 = SyncTestCommon.writeFile(prefix2, true, 0, putHandle);
		int segmentCheck = checkCallbacks(prefix1, segments, 0);
		if (segmentCheck!=0)
			Assert.fail("Did not receive all of the callbacks");
		segmentCheck = checkCallbacks(prefix2, segments2, 0);
		if (segmentCheck!=0)
			Assert.fail("Did not receive all of the callbacks");
		
		//now close the callback interface
		sync1.stopSync(this, slice4);
		sync1.stopSync(this, slice5);
			
		Log.info(Log.FAC_TEST,"Finished running testSyncStop");
	}
	

	@Test
	public void testSyncRestart() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testSyncRestart");
		
		ContentName prefix1;
		prefix1 = prefix.append("slice6");
		CCNSync sync1 = new CCNSync();
		ConfigSlice slice6 = sync1.startSync(getHandle, topo, prefix1, this);
		slices.add(slice6);
		
		//the slice should be written..  now save content and get a callback.
		Log.fine(Log.FAC_TEST, "writing out file: {0}", prefix1);
		int segments = SyncTestCommon.writeFile(prefix1, true, 0, putHandle);
		int segmentCheck = checkCallbacks(prefix1, segments, 0);
		if (segmentCheck!=0)
			Assert.fail("Did not receive all of the callbacks");
		else
			Log.fine(Log.FAC_TEST, "I got all the callbacks for part 1 of testSyncRestart!");
		
		//now close the callback interface
		sync1.stopSync(this, slice6);
		callbackNames.clear();
		
		//then close and make sure we don't get a callback
		ContentName prefix1a = prefix1.append("round2");
		Log.fine(Log.FAC_TEST, "writing out file: {0}", prefix1a);
		segments = SyncTestCommon.writeFile(prefix1a, true, 0, putHandle);  //this should be a new version
		segmentCheck = checkCallbacks(prefix1a, segments, 0);
		if (segmentCheck != segments) {
			//we must have gotten callbacks...  bad.
			Assert.fail("received callbacks after interface was closed.  ERROR");
		}
		Log.fine(Log.FAC_TEST, "I didn't get callbacks after I stopped sync for myself!");
		
		//now restart sync and make sure i get everything
		ContentName prefix1b = prefix1.append("round3");
		//ConfigSlice slice3 = sync1.startSync(topo, prefix1, null, this);
		ConfigSlice slice6b = sync1.startSync(getHandle, topo, prefix1, this);
		
		Log.fine(Log.FAC_TEST, "check if slice 6 == slice 6b, they should be equal!");
		if (slice6.equals(slice6b)) {
			Log.fine(Log.FAC_TEST, "the slices are equal!");
		} else {
			Log.warning("the slices are not equal!!!!");
			//what makes them different?
			Log.fine(Log.FAC_TEST, "slice6: {0}", slice6);
			Log.fine(Log.FAC_TEST, "slice6b: {0}", slice6b);
			Assert.fail("the slices were not equal, they should have been.");
		}
		
		//the slice should be written..  now save content and get a callback.
		Log.fine(Log.FAC_TEST, "writing out file: {0}", prefix1b);
		segments = SyncTestCommon.writeFile(prefix1b, true, 0, putHandle);
		segmentCheck = checkCallbacks(prefix1b, segments, 0);
		if (segmentCheck!=0)
			Assert.fail("Did not receive all of the callbacks");
		else
			Log.fine(Log.FAC_TEST, "I got all the callbacks for part 2 of testSyncRestart!");
		
		//now close the callback interface
		sync1.stopSync(this, slice6b);

			
		Log.info(Log.FAC_TEST,"Finished running testSyncRestart");
	}
	
	// @Test - disable this test for 0.6.2 release
	public void testSyncRoot() throws Exception {
		Log.info(Log.FAC_TEST,"Starting testSyncRoot");
		ContentName prefix1;
		CCNSync sync1 = new CCNSync();
		prefix1 = prefix.append("slice7");
		ConfigSlice slice7 = sync1.startSync(getHandle, topo, prefix1, this);
		slices.add(slice7);
		ContentName prefix1a = prefix1.append("round1");
		Log.fine(Log.FAC_TEST, "writing out file: {0}", prefix1a);
		int segments = SyncTestCommon.writeFile(prefix1a, true, 0, putHandle);
		int segmentCheck = checkCallbacks(prefix1a, segments, 0);
		if (segmentCheck!=0)
			Assert.fail("Did not receive all of the callbacks");
		sync1.stopSync(this, slice7);
		synchronized (callbackNames) {
			callbackNames.clear();
		}
		
		/*
		 * Test for start with empty root.  This should start at the time we start sync, but not give
		 * us anything before that.
		 */
		ContentName rootAdvise = new ContentName(slice7.topo, Sync.SYNC_ROOT_ADVISE_MARKER, slice7.getHash());
		Interest interest = new Interest(rootAdvise);
		interest.scope(1);
		getHandle.registerInterest(interest, this);
		slice7 = sync1.startSync(getHandle, topo, prefix1, null, new byte[]{}, null, this);
		synchronized (this) {
			wait(SystemConfiguration.EXTRA_LONG_TIMEOUT);
		}
		ContentName prefix1b = prefix1.append("round2");
		segments = SyncTestCommon.writeFile(prefix1b, true, 0, putHandle);
		segmentCheck = checkCallbacks(prefix1b, segments, 0);
		if (segmentCheck!=0)
			Assert.fail("Did not receive all of the callbacks");
		synchronized (callbackNames) {
			for (ContentName n: callbackNames) {
				Assert.assertTrue("Saw unexpected data in zero length root test: " + n, prefix1b.isPrefixOf(n));
			}		
		}
		sync1.stopSync(this, slice7);
	
		SyncNodeComposite snc = SyncTestCommon.getRootAdviseNode(slice7, getHandle);
		Assert.assertTrue(null != snc);

		synchronized (callbackNames) {
			callbackNames.clear();
		}
		slice7 = sync1.startSync(getHandle, topo, prefix1, null, snc.getHash(), null, this);
		ContentName prefix1c = prefix1.append("round3");
		segments = SyncTestCommon.writeFile(prefix1c, true, 0, putHandle);
		segmentCheck = checkCallbacks(prefix1c, segments, 0);
		if (segmentCheck!=0)
			Assert.fail("Did not receive all of the callbacks");
		sync1.stopSync(this, slice7);
		for (ContentName n: callbackNames) {
			Assert.assertFalse("Saw unexpected data in arbitrary root test: " + n, prefix1a.isPrefixOf(n));
		}		

		Log.info(Log.FAC_TEST,"Finished running testSyncRoot");
	}
	
	@Test
	public void testSyncStartName() throws Exception {
		Log.info(Log.FAC_TEST,"Starting testSyncStartName");
		ContentName prefix1;
		prefix1 = prefix.append("slice8");
		CCNSync sync1 = new CCNSync();
		
		Log.fine(Log.FAC_TEST, "writing out file: {0}", prefix1);
		
		// Write a 100 block file to test a true sync tree
		int segments = SyncTestCommon.writeFile(prefix1, false, SystemConfiguration.BLOCK_SIZE * 100, putHandle);
		ContentObject checkObj = getHandle.get(prefix1, SystemConfiguration.MEDIUM_TIMEOUT);
		Assert.assertNotNull("Didn't get back what we wrote to the repo", checkObj);
		ContentName name = checkObj.name();
		name = name.cut(name.count() - 1);
		ContentName startName = SegmentationProfile.segmentName(name, 20);
		
		// We should start looking starting with segment 20
		ConfigSlice slice8 = sync1.startSync(getHandle, topo, prefix1, null, null, startName, this);
		slices.add(slice8);
		int segmentCheck = checkCallbacks(prefix1, segments - 20, 20);
		if (segmentCheck!=0)
			Assert.fail("Did not receive all of the callbacks");
		else
			Log.fine(Log.FAC_TEST, "I got all the callbacks for part 1 of testSyncRestart!");
		sync1.stopSync(this, slice8);
		Log.info(Log.FAC_TEST,"Finished running testSyncStartName");
	}
	
	public void handleContentName(ConfigSlice syncSlice, ContentName syncedContent) {
		if ( MetadataProfile.isHeader(syncedContent)) {
			Log.info(Log.FAC_TEST, "Callback for name: {0} - number is header", syncedContent);
		} else {
			Log.info(Log.FAC_TEST, "Callback for name: {0} - number is {1}", syncedContent, SegmentationProfile.getSegmentNumber(syncedContent));
		}
		synchronized (callbackNames) {
			for (ContentName name : callbackNames) {
				if (name.equals(syncedContent)) {
					errorMessage = "Saw duplicate name: " + syncedContent;
				}
			}
			callbackNames.add(syncedContent);
		}
	}
	
	private int checkCallbacks(ContentName prefix, int segments, int firstSegment) {
		Log.fine(Log.FAC_TEST, "checking for callbacks:  {0} segments: {1}", prefix, segments);
		boolean[] received = (boolean[]) Array.newInstance(boolean.class, segments);
		Arrays.fill(received, false);
		boolean[]finished = (boolean[]) Array.newInstance(boolean.class, segments);
		Arrays.fill(finished, true);
		int loopsToTry = (segments * 2) + 20;
		while (segments != 0 && loopsToTry > 0) {
			if (null != errorMessage)
				Assert.fail(errorMessage);
			try {
				Thread.sleep(500);
			} catch (InterruptedException e) {
				Log.warning(Log.FAC_TEST, "interrupted while waiting for names on callback");
			}
			synchronized(callbackNames) {
				for (ContentName n: callbackNames) {
					if (prefix.isPrefixOf(n)) {
						//this is one of our names
						if ( MetadataProfile.isHeader(n)) {
							//this is the header!
							received[segments-1] = true;
							Log.fine(Log.FAC_TEST, "got the header");
						} else {
							//this is not the header...  get the segment number
							Assert.assertTrue("Saw segment " + SegmentationProfile.getSegmentNumber(n) + " which should not have been seen", 
									SegmentationProfile.getSegmentNumber(n) >= firstSegment);
							received[(int) SegmentationProfile.getSegmentNumber(n) - firstSegment] = true;
							Log.fine(Log.FAC_TEST, "got segment {0}", SegmentationProfile.getSegmentNumber(n));
						}
						Log.fine(Log.FAC_TEST, "received: {0} finished: {1}", Arrays.toString(received), Arrays.toString(finished));
						if (Arrays.equals(received, finished)) {
							//all done!
							segments = 0;
							Log.fine(Log.FAC_TEST, "got all the segments!");
							break;
						}
					}
				}
			}
			loopsToTry = loopsToTry - 1;
			Log.fine(Log.FAC_TEST, "trying to loop again looking for segments");
		}
		int outstanding = 0;
		String unreceived = "";
		for (int i = 0; i < received.length; i++) {
			if (received[i] != finished[i]) {
				outstanding++;
				unreceived += (i == segments - (firstSegment + 1) ? "header " : i + firstSegment + ", ");
			}
		}
		Log.fine(Log.FAC_TEST, "done looping, returning.");
		if (! unreceived.equals("")) {
			Log.info(Log.FAC_TEST, "{0} outstanding segments = {1}", outstanding, unreceived);
		}
		return segments;
	}

	public Interest handleContent(ContentObject data, Interest interest) {
		synchronized(this) {
			notify();
		}
		return null;
	}
}
