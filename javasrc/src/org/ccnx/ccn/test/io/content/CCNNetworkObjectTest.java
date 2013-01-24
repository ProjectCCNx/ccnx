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

package org.ccnx.ccn.test.io.content;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InvalidObjectException;
import java.util.logging.Level;

import org.bouncycastle.util.Arrays;
import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.impl.CCNFlowServer;
import org.ccnx.ccn.impl.CCNFlowControl.SaveType;
import org.ccnx.ccn.impl.security.crypto.util.DigestHelper;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.CCNVersionedInputStream;
import org.ccnx.ccn.io.content.CCNNetworkObject;
import org.ccnx.ccn.io.content.CCNStringObject;
import org.ccnx.ccn.io.content.Collection;
import org.ccnx.ccn.io.content.Link;
import org.ccnx.ccn.io.content.LinkAuthenticator;
import org.ccnx.ccn.io.content.UpdateListener;
import org.ccnx.ccn.io.content.Collection.CollectionObject;
import org.ccnx.ccn.profiles.SegmentationProfile;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.protocol.CCNTime;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.PublisherID;
import org.ccnx.ccn.protocol.SignedInfo;
import org.ccnx.ccn.protocol.PublisherID.PublisherType;
import org.ccnx.ccn.test.CCNTestHelper;
import org.ccnx.ccn.test.Flosser;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;


/**
 * Test basic network object functionality, writing objects to a Flosser.
 * Much slower than it needs to be -- seems to hit some kind of ordering
 * bug which requires waiting for interest reexpression before it can go
 * forward (shows up as mysterious 4-second delays in the log).  The corresponding
 * repo-backed test, CCNNetorkObjectTestRepo runs much faster to do exactly
 * the same work.
 * TODO track down slowness
 */
public class CCNNetworkObjectTest extends CCNNetworkObjectTestBase {
	
	/**
	 * Handle naming for the test
	 */
	static CCNTestHelper testHelper = new CCNTestHelper(CCNNetworkObjectTest.class);
	
	static void setupNamespace(ContentName name) throws IOException {
		flosser.handleNamespace(name);
	}

	static void removeNamespace(ContentName name) throws IOException {
		flosser.stopMonitoringNamespace(name);
	}

	@AfterClass
	public static void tearDownAfterClass() throws Exception {
		try {
			Log.info(Log.FAC_TEST, "Tearing down CCNNetworkObjectTest, prefix {0}", testHelper.getClassNamespace());
			Log.flush();
			if (flosser != null) {
				flosser.stop();
				flosser = null;
			}
			Log.info(Log.FAC_TEST, "Finished tearing down CCNNetworkObjectTest, prefix {0}", testHelper.getClassNamespace());
			Log.flush();
		} catch (Exception e) {
			Log.severe(Log.FAC_TEST, "Exception in tearDownAfterClass: type {0} msg {0}", e.getClass().getName(), e.getMessage());
			Log.warningStackTrace(e);
		}
	}

	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		Log.info(Log.FAC_TEST, "Setting up CCNNetworkObjectTest, prefix {0}", testHelper.getClassNamespace());
		
		handle = CCNHandle.open();
		
		ns = new ContentName[NUM_LINKS];
		for (int i=0; i < NUM_LINKS; ++i) {
			ns[i] = new ContentName(testHelper.getClassNamespace(), "Links", prefix+Integer.toString(i));
		}
		Arrays.fill(publisherid1, (byte)6);
		Arrays.fill(publisherid2, (byte)3);

		pubID1 = new PublisherID(publisherid1, PublisherType.KEY);
		pubID2 = new PublisherID(publisherid2, PublisherType.ISSUER_KEY);

		las[0] = new LinkAuthenticator(pubID1);
		las[1] = null;
		las[2] = new LinkAuthenticator(pubID2, null, null,
				SignedInfo.ContentType.DATA, contenthash1);
		las[3] = new LinkAuthenticator(pubID1, null, CCNTime.now(),
				null, contenthash1);
		
		for (int j=4; j < NUM_LINKS; ++j) {
			las[j] = new LinkAuthenticator(pubID2, null, CCNTime.now(), null, null);
 		}

		lrs = new Link[NUM_LINKS];
		for (int i=0; i < lrs.length; ++i) {
			lrs[i] = new Link(ns[i],las[i]);
		}
		
		empty = new Collection();
		small1 = new Collection();
		small2 = new Collection();
		for (int i=0; i < 5; ++i) {
			small1.add(lrs[i]);
			small2.add(lrs[i+5]);
		}
		big = new Collection();
		for (int i=0; i < NUM_LINKS; ++i) {
			big.add(lrs[i]);
		}
		
		flosser = new Flosser();
		Log.info(Log.FAC_TEST, "Finished setting up CCNNetworkObjectTest, prefix is: {0}.", testHelper.getClassNamespace());
	}
	
	@AfterClass
	public static void cleanupAfterClass() {
		handle.close();
	}

	@Test
	public void testVersioning() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testVersioning");

		// Testing problem of disappearing versions, inability to get latest. Use simpler
		// object than a collection.
		CCNHandle lput = CCNHandle.open();
		CCNHandle lget = CCNHandle.open();
		
		ContentName testName = new ContentName(testHelper.getTestNamespace("testVersioning"), stringObjName);
		try {

			CCNStringObject so = new CCNStringObject(testName, "First value", SaveType.RAW, lput);
			setupNamespace(testName);
			
			CCNStringObject ro = null;
			CCNStringObject ro2 = null;
			CCNStringObject ro3, ro4; // make each time, to get a new handle.
			CCNTime soTime, srTime, sr2Time, sr3Time, sr4Time, so2Time;
			for (int i=0; i < numbers.length; ++i) {
				soTime = saveAndLog(numbers[i], so, null, numbers[i]);
				if (null == ro) {
					ro = new CCNStringObject(testName, lget);
					srTime = waitForDataAndLog(numbers[i], ro);
				} else {
					srTime = updateAndLog(numbers[i], ro, null);				
				}
				if (null == ro2) {
					ro2 = new CCNStringObject(testName, null);
					sr2Time = waitForDataAndLog(numbers[i], ro2);
				} else {
					sr2Time = updateAndLog(numbers[i], ro2, null);				
				}
				ro3 = new CCNStringObject(ro.getVersionedName(), null); // read specific version
				sr3Time = waitForDataAndLog("UpdateToROVersion", ro3);
				// Save a new version and pull old
				so2Time = saveAndLog(numbers[i] + "-Update", so, null, numbers[i] + "-Update");
				ro4 = new CCNStringObject(ro.getVersionedName(), null); // read specific version
				sr4Time = waitForDataAndLog("UpdateAnotherToROVersion", ro4);
				Log.info(Log.FAC_TEST, "Update " + i + ": Times: " + soTime + " " + srTime + " " + sr2Time + " " + sr3Time + " different: " + so2Time);
				Assert.assertEquals("SaveTime doesn't match first read", soTime, srTime);
				Assert.assertEquals("SaveTime doesn't match second read", soTime, sr2Time);
				Assert.assertEquals("SaveTime doesn't match specific version read", soTime, sr3Time);
				Assert.assertFalse("UpdateTime isn't newer than read time", soTime.equals(so2Time));
				Assert.assertEquals("SaveTime doesn't match specific version read", soTime, sr4Time);
			}
		} finally {
			removeNamespace(testName);
			lput.close();
			lget.close();
		}
		
		Log.info(Log.FAC_TEST, "Completed testVersioning");
	}

	@Test
	public void testSaveToVersion() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testSaveToVersion");

		// Testing problem of disappearing versions, inability to get latest. Use simpler
		// object than a collection.
		CCNHandle lput = CCNHandle.open();
		CCNHandle lget = CCNHandle.open();
		ContentName testName = new ContentName(testHelper.getTestNamespace("testSaveToVersion"), stringObjName);
		try {

			CCNTime desiredVersion = CCNTime.now();

			CCNStringObject so = new CCNStringObject(testName, "First value", SaveType.RAW, lput);
			setupNamespace(testName);
			saveAndLog("SpecifiedVersion", so, desiredVersion, "Time: " + desiredVersion);
			Assert.assertEquals("Didn't write correct version", desiredVersion, so.getVersion());

			CCNStringObject ro = new CCNStringObject(testName, lget);
			ro.waitForData(); 
			Assert.assertEquals("Didn't read correct version", desiredVersion, ro.getVersion());
			ContentName versionName = ro.getVersionedName();

			saveAndLog("UpdatedVersion", so, null, "ReplacementData");
			updateAndLog("UpdatedData", ro, null);
			Assert.assertTrue("New version " + so.getVersion() + " should be later than old version " + desiredVersion, (desiredVersion.before(so.getVersion())));
			Assert.assertEquals("Didn't read correct version", so.getVersion(), ro.getVersion());

			CCNStringObject ro2 = new CCNStringObject(versionName, null);
			ro2.waitForData();
			Assert.assertEquals("Didn't read correct version", desiredVersion, ro2.getVersion());
		} finally {
			removeNamespace(testName);
			lput.close();
			lget.close();
		}
		
		Log.info(Log.FAC_TEST, "Completed testSaveToVersion");
	}

	@Test
	public void testEmptySave() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testEmptySave");

		boolean caught = false;
		ContentName testName = new ContentName(testHelper.getTestNamespace("testEmptySave"), collectionObjName);
		try {
			CollectionObject emptycoll = 
				new CollectionObject(testName, (Collection)null, SaveType.RAW, handle);
			setupNamespace(testName);
			try {
				emptycoll.setData(small1); // set temporarily to non-null
				saveAndLog("Empty", emptycoll, null, null);
			} catch (InvalidObjectException iox) {
				// this is what we expect to happen
				caught = true;
			}
			Assert.assertTrue("Failed to produce expected exception.", caught);	
		} finally {
			removeNamespace(testName);
		}
		
		Log.info(Log.FAC_TEST, "Completed testEmptySave");
	}

	@Test
	public void testStreamUpdate() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testStreamUpdate");

		ContentName testName = new ContentName(testHelper.getTestNamespace("testStreamUpdate"), collectionObjName);
		CCNHandle tHandle = CCNHandle.open();
		try {
			CollectionObject testCollectionObject = new CollectionObject(testName, small1, SaveType.RAW, tHandle);
			setupNamespace(testName);

			saveAndLog("testStreamUpdate", testCollectionObject, null, small1);
			Log.info(Log.FAC_TEST, "testCollectionObject name: " + testCollectionObject.getVersionedName());

			CCNVersionedInputStream vis = new CCNVersionedInputStream(testCollectionObject.getVersionedName());
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			byte [] buf = new byte[128];
			// Will incur a timeout
			while (!vis.eof()) {
				int read = vis.read(buf);
				if (read > 0)
					baos.write(buf, 0, read);
			}
			Log.info(Log.FAC_TEST, "Read " + baos.toByteArray().length + " bytes, digest: " + 
					DigestHelper.printBytes(DigestHelper.digest(baos.toByteArray()), 16));

			Collection decodedData = new Collection();
			decodedData.decode(baos.toByteArray());
			Log.info(Log.FAC_TEST, "Decoded collection data: " + decodedData);
			Assert.assertEquals("Decoding via stream fails to give expected result!", decodedData, small1);

			CCNVersionedInputStream vis2 = new CCNVersionedInputStream(testCollectionObject.getVersionedName());
			ByteArrayOutputStream baos2 = new ByteArrayOutputStream();
			// Will incur a timeout
			while (!vis2.eof()) {
				int val = vis2.read();
				if (val < 0)
					break;
				baos2.write((byte)val);
			}
			Log.info(Log.FAC_TEST, "Read " + baos2.toByteArray().length + " bytes, digest: " + 
					DigestHelper.printBytes(DigestHelper.digest(baos2.toByteArray()), 16));
			Assert.assertArrayEquals("Reading same object twice gets different results!", baos.toByteArray(), baos2.toByteArray());

			Collection decodedData2 = new Collection();
			decodedData2.decode(baos2.toByteArray());
			Assert.assertEquals("Decoding via stream byte read fails to give expected result!", decodedData2, small1);

			CCNVersionedInputStream vis3 = new CCNVersionedInputStream(testCollectionObject.getVersionedName());
			Collection decodedData3 = new Collection();
			decodedData3.decode(vis3);
			Assert.assertEquals("Decoding via stream full read fails to give expected result!", decodedData3, small1);
		} finally {
			removeNamespace(testName);
			tHandle.close();
		}
		
		Log.info(Log.FAC_TEST, "Completed testStreamUpdate");
	}
	
	@Test
	public void testVersionOrdering() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testVersionOrdering");

		ContentName testName = new ContentName(testHelper.getTestNamespace("testVersionOrdering"), collectionObjName, "name1");
		ContentName testName2 = new ContentName(testHelper.getTestNamespace("testVersionOrdering"), collectionObjName, "name2");
		CCNHandle tHandle = CCNHandle.open();
		
		try {

			CollectionObject c0 = new CollectionObject(testName, empty, SaveType.RAW, handle);
			setupNamespace(testName);
			CCNTime t0 = saveAndLog("Empty", c0, null, empty);

			CollectionObject c1 = new CollectionObject(testName2, small1, SaveType.RAW, tHandle);
			CollectionObject c2 = new CollectionObject(testName2, small1, SaveType.RAW, null);
			setupNamespace(testName2);
			CCNTime t1 = saveAndLog("Small", c1, null, small1);
			Assert.assertTrue("First version should come before second", t0.before(t1));

			CCNTime t2 = saveAndLog("Small2ndWrite", c2, null, small1);
			Assert.assertTrue("Third version should come after second", t1.before(t2));
			Assert.assertTrue(c2.contentEquals(c1));
			Assert.assertFalse(c2.equals(c1));
			Assert.assertTrue(VersioningProfile.isLaterVersionOf(c2.getVersionedName(), c1.getVersionedName()));
		} finally {
			removeNamespace(testName);
			removeNamespace(testName2);
			tHandle.close();
		}
		
		Log.info(Log.FAC_TEST, "Completed testVersionOrdering");
	}
	
	@Test
	public void testUpdateOtherName() throws Exception {
		Log.info(Log.FAC_TEST, "Started testUpdateOtherName");

		CCNHandle tHandle = CCNHandle.open();
		ContentName testName = new ContentName(testHelper.getTestNamespace("testUpdateOtherName"), collectionObjName, "name1");
		ContentName testName2 = new ContentName(testHelper.getTestNamespace("testUpdateOtherName"), collectionObjName, "name2");
		try {

			CollectionObject c0 = new CollectionObject(testName, empty, SaveType.RAW, handle);
			setupNamespace(testName);
			CCNTime t0 = saveAndLog("Empty", c0, null, empty);

			CollectionObject c1 = new CollectionObject(testName2, small1, SaveType.RAW, tHandle);
			// Cheat a little, make this one before the setupNamespace...
			CollectionObject c2 = new CollectionObject(testName2, small1, SaveType.RAW, null);
			setupNamespace(testName2);
			CCNTime t1 = saveAndLog("Small", c1, null, small1);
			Assert.assertTrue("First version should come before second", t0.before(t1));

			CCNTime t2 = saveAndLog("Small2ndWrite", c2, null, small1);
			Assert.assertTrue("Third version should come after second", t1.before(t2));
			Assert.assertTrue(c2.contentEquals(c1));
			Assert.assertFalse(c2.equals(c1));

			CCNTime t3 = updateAndLog(c0.getVersionedName().toString(), c0, testName2);
			Assert.assertTrue(VersioningProfile.isVersionOf(c0.getVersionedName(), testName2));
			Assert.assertEquals(t3, t2);
			Assert.assertTrue(c0.contentEquals(c2));

			t3 = updateAndLog(c0.getVersionedName().toString(), c0, c1.getVersionedName());
			Assert.assertTrue(VersioningProfile.isVersionOf(c0.getVersionedName(), testName2));
			Assert.assertEquals(t3, t1);
			Assert.assertTrue(c0.contentEquals(c1));	
		} finally {
			removeNamespace(testName);
			removeNamespace(testName2);
			tHandle.close();
		}
		
		Log.info(Log.FAC_TEST, "Completed testUpdateOtherName");
	}
	
	@Test
	public void testUpdateInBackground() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testUpdateInBackground");

		CCNHandle tHandle = CCNHandle.open();
		CCNHandle tHandle2 = CCNHandle.open();
		CCNHandle tHandle3 = CCNHandle.open();
		ContentName testName = new ContentName(testHelper.getTestNamespace("testUpdateInBackground"), stringObjName, "name1");
		try {
			CCNStringObject c0 = new CCNStringObject(testName, (String)null, SaveType.RAW, tHandle);
			c0.updateInBackground();
			
			CCNStringObject c1 = new CCNStringObject(testName, (String)null, SaveType.RAW, tHandle2);
			c1.updateInBackground(true);
			
			Assert.assertFalse(c0.available());
			Assert.assertFalse(c0.isSaved());
			Assert.assertFalse(c1.available());
			Assert.assertFalse(c1.isSaved());
			
			CCNStringObject c2 = new CCNStringObject(testName, (String)null, SaveType.RAW, tHandle3);
			CCNTime t1 = saveAndLog("First string", c2, null, "Here is the first string.");
			Log.info(Log.FAC_TEST, "Saved c2: " + c2.getVersionedName() + " c0 available? " + c0.available() + " c1 available? " + c1.available());
			c0.waitForData();
			Assert.assertEquals("c0 update", c0.getVersion(), c2.getVersion());
			c1.waitForData();
			Assert.assertEquals("c1 update", c1.getVersion(), c2.getVersion());
			
			CCNTime t2 = saveAndLog("Second string", c2, null, "Here is the second string.");
			
			doWait(c1, t2);
			Assert.assertEquals("c1 update 2", c1.getVersion(), c2.getVersion());
			Assert.assertEquals("c0 unchanged", c0.getVersion(), t1);
			
		} finally {
			removeNamespace(testName);
			tHandle.close();
			tHandle2.close();
			tHandle3.close();
		}
		
		Log.info(Log.FAC_TEST, "Completed testUpdateInBackground");
	}
	
	
	@Test
	public void testBackgroundVerifier() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testBackgroundVerifier");

		ContentName testName = new ContentName(testHelper.getTestNamespace("testBackgroundVerifier"), stringObjName, "name1");
		try {
			CCNStringObject c0 = new CCNStringObject(testName, (String)null, SaveType.RAW, CCNHandle.open());
			c0.updateInBackground(true);
			
			CCNStringObject c1 = new CCNStringObject(testName, (String)null, SaveType.RAW, CCNHandle.open());
			c1.updateInBackground(true);
			
			CCNTime t1 = saveAndLog("First string", c0, null, "Here is the first string.");
			
			doWait(c0, t1);
			c1.waitForData();
			CCNTime c1Version = c1.getVersion();
			
			Assert.assertTrue(c0.available());
			Assert.assertTrue(c0.isSaved());
			Assert.assertTrue(c1.available());
			Assert.assertTrue(c1.isSaved());
			Assert.assertEquals(t1, c1Version);
			
			// Test background ability to throw away bogus data.
			// change the version so a) it's later, and b) the signature won't verify
			ContentName laterName = SegmentationProfile.segmentName(VersioningProfile.updateVersion(c1.getVersionedName()),
									SegmentationProfile.baseSegment());
			CCNFlowServer server = new CCNFlowServer(testName, null, false, CCNHandle.open());
			server.addNameSpace(laterName);
			
			ContentObject bogon = 
				new ContentObject(laterName, c0.getFirstSegment().signedInfo(),
						c0.getFirstSegment().content(), c0.getFirstSegment().signature());
			Log.info(Log.FAC_TEST, "Writing bogon: {0}", bogon.fullName());
			
			server.put(bogon);
			
			Thread.sleep(300);
			
			// Should be no update
			Assert.assertEquals(c0.getVersion(), c1Version);
			Assert.assertEquals(c1.getVersion(), c1Version);

			// Now write a newer one
			CCNStringObject c2 = new CCNStringObject(testName, (String)null, SaveType.RAW, CCNHandle.open());
			CCNTime t2 = saveAndLog("Second string", c2, null, "Here is the second string.");
			Log.info(Log.FAC_TEST, "Saved c2: " + c2.getVersionedName() + " c0 available? " + c0.available() + " c1 available? " + c1.available());
			doWait(c0, t2);
			Assert.assertEquals("c0 update", c0.getVersion(), c2.getVersion());
			doWait(c1, t2);
			Assert.assertEquals("c1 update", c1.getVersion(), c2.getVersion());
			Assert.assertFalse(c1Version.equals(c1.getVersion()));
			
		} finally {
			removeNamespace(testName);
		}
		
		Log.info(Log.FAC_TEST, "Completed testBackgroundVerifier");
	}
		
	@Test
	public void testSaveAsGone() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testSaveAsGone");

		ContentName testName = new ContentName(testHelper.getTestNamespace("testSaveAsGone"), collectionObjName);
		CCNHandle tHandle = CCNHandle.open();
		CCNHandle tHandle2 = CCNHandle.open();
		try {
			Log.info(Log.FAC_TEST, "TSAG: Entering testSaveAsGone");
			CollectionObject c0 = new CollectionObject(testName, empty, SaveType.RAW, handle);
			setupNamespace(testName); // this sends the interest, doing it after the object gives it
						// a chance to catch it.
			
			
			CCNTime t0 = saveAsGoneAndLog("FirstGoneSave", c0);
			Assert.assertTrue("Should be gone", c0.isGone());
			ContentName goneVersionName = c0.getVersionedName();
			
			Log.info(Log.FAC_TEST, "T1");
			CCNTime t1 = saveAndLog("NotGone", c0, null, small1);
			Assert.assertFalse("Should not be gone", c0.isGone());
			Assert.assertTrue(t1.after(t0));
			Log.info(Log.FAC_TEST, "T2");

			CollectionObject c1 = new CollectionObject(testName, tHandle);
			CCNTime t2 = waitForDataAndLog(testName.toString(), c1);
			Assert.assertFalse("Read back should not be gone", c1.isGone());
			Assert.assertEquals(t2, t1);
			Log.info(Log.FAC_TEST, "T3");

			CCNTime t3 = updateAndLog(goneVersionName.toString(), c1, goneVersionName);
			Assert.assertTrue(VersioningProfile.isVersionOf(c1.getVersionedName(), testName));
			Assert.assertEquals(t3, t0);
			Assert.assertTrue("Read back should be gone.", c1.isGone());
			Log.info(Log.FAC_TEST, "T4");

			t0 = saveAsGoneAndLog("GoneAgain", c0);
			Assert.assertTrue("Should be gone", c0.isGone());
			Log.info(Log.FAC_TEST, "TSAG: Updating new object: {0}", testName);
			CollectionObject c2 = new CollectionObject(testName, tHandle2);
			Log.info(Log.FAC_TEST, "TSAG: Waiting for: {0}", testName);
			CCNTime t4 = waitForDataAndLog(testName.toString(), c2);
			Log.info(Log.FAC_TEST, "TSAG: Waited for: {0}", c2.getVersionedName());
			Assert.assertTrue("Read back of " + c0.getVersionedName() + " should be gone, got " + c2.getVersionedName(), c2.isGone());
			Assert.assertEquals(t4, t0);
			Log.info(Log.FAC_TEST, "TSAG: Leaving testSaveAsGone.");

		} finally {
			removeNamespace(testName);
			tHandle.close();
			tHandle2.close();
		}
		
		Log.info(Log.FAC_TEST, "Completed testSaveAsGone");
	}
	
	@Test
	public void testUpdateDoesNotExist() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testUpdateDoesNotExist");

		ContentName testName = new ContentName(testHelper.getTestNamespace("testUpdateDoesNotExist"), collectionObjName);
		CCNHandle tHandle = CCNHandle.open();
		try {
			Log.info(Log.FAC_TEST, "CCNNetworkObjectTest: Entering testUpdateDoesNotExist");
			CCNStringObject so = new CCNStringObject(testName, handle);
			// so should catch exception thrown by underlying stream when it times out.
			Assert.assertFalse(so.available());
			// try to pick up anything that happens to appear
			so.updateInBackground();
			
			CCNStringObject sowrite = new CCNStringObject(testName, "Now we write something.", SaveType.RAW, tHandle);
			setupNamespace(testName);
			saveAndLog("testUpdateDoesNotExist: Delayed write", sowrite, null, "Now we write something.");
			Log.flush();
			so.waitForData();
			Assert.assertTrue(so.available());
			Assert.assertEquals(so.string(), sowrite.string());
			Assert.assertEquals(so.getVersionedName(), sowrite.getVersionedName());
			Log.info(Log.FAC_TEST, "CCNNetworkObjectTest: Leaving testUpdateDoesNotExist");
			Log.flush();
		} finally {
			removeNamespace(testName);
			tHandle.close();
		}
		
		Log.info(Log.FAC_TEST, "Completed testUpdateDoesNotExist");
	}
	
	@Test
	public void testFirstSegmentInfo() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testFirstSegmentInfo");

		// Testing for matching info about first segment.
		CCNHandle lput = CCNHandle.open();
		CCNHandle lget = CCNHandle.open();
		ContentName testName = new ContentName(testHelper.getTestNamespace("testFirstSegmentInfo"), stringObjName);
		try {

			CCNTime desiredVersion = CCNTime.now();

			CCNStringObject so = new CCNStringObject(testName, "First value", SaveType.RAW, lput);
			setupNamespace(testName);
			saveAndLog("SpecifiedVersion", so, desiredVersion, "Time: " + desiredVersion);
			Assert.assertEquals("Didn't write correct version", desiredVersion, so.getVersion());

			CCNStringObject ro = new CCNStringObject(testName, lget);
			ro.waitForData(); 
			Assert.assertEquals("Didn't read correct version", desiredVersion, ro.getVersion());

			Assert.assertEquals("Didn't match first segment number", so.firstSegmentNumber(), ro.firstSegmentNumber());
			Assert.assertArrayEquals("Didn't match first segment digest", so.getFirstDigest(), ro.getFirstDigest());
		} finally {
			removeNamespace(testName);
			lput.close();
			lget.close();
		}
		
		Log.info(Log.FAC_TEST, "Completed testFirstSegmentInfo");
	}
		
	static class CounterListener implements UpdateListener {

		protected Integer _callbackCounter = 0;
		
		public int getCounter() { return _callbackCounter; }

		public void newVersionAvailable(CCNNetworkObject<?> newVersion, boolean wasSave) {
			synchronized (this) {
				_callbackCounter++;
				if (Log.isLoggable(Log.FAC_TEST, Level.INFO)) {
					Log.info(Log.FAC_TEST, "UPDATE CALLBACK: counter is " + _callbackCounter + " was save? " + wasSave);
				}
			}
		}		
	}
	
	@Test
	public void testUpdateListener() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testUpdateListener");

		SaveType saveType = SaveType.RAW;
		CCNHandle writeHandle = CCNHandle.open();
		CCNHandle readHandle = CCNHandle.open();
		ContentName testName = new ContentName(testHelper.getTestNamespace("testUpdateListener"), 
										stringObjName);
		
		CounterListener ourListener = new CounterListener();
		
		CCNStringObject readObject = 
			new CCNStringObject(testName, null, null, readHandle);
		readObject.addListener(ourListener);
		setupNamespace(testName);

		CCNStringObject writeObject = 
			new CCNStringObject(testName, "Something to listen to.", saveType, writeHandle);
		writeObject.save();
		
		boolean result = readObject.update();
		Assert.assertTrue(result);
		Assert.assertTrue(ourListener.getCounter() == 1);
		
		readObject.updateInBackground();
		
		writeObject.save("New stuff! New stuff!");
		synchronized(readObject) {
			while (ourListener.getCounter() == 1)
				readObject.wait();
		}
		// For some reason, we're getting two updates on our updateInBackground...
		Assert.assertTrue(ourListener.getCounter() > 1);
		writeHandle.close();
		readHandle.close();
		
		Log.info(Log.FAC_TEST, "Completed testUpdateListener");
	}

	@Test
	public void testVeryLast() throws Exception {
		Log.info("CCNNetworkObjectTest: Entering testVeryLast -- dummy test to help track down blowup. Prefix {0}", testHelper.getClassNamespace());
		Thread.sleep(1000);
		Log.info("CCNNetworkObjectTest: Leaving testVeryLast -- dummy test to help track down blowup. Prefix {0}", testHelper.getClassNamespace());	
	}
}
