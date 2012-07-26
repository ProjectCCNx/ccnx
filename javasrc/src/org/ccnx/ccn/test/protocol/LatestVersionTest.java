/*
 * A CCNx library test.
 *
 * Copyright (C) 2008, 2009, 2010, 2011, 2012 Palo Alto Research Center, Inc.
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

package org.ccnx.ccn.test.protocol;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Vector;
import java.util.logging.Level;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.CCNInterestHandler;
import org.ccnx.ccn.ContentVerifier;
import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.profiles.SegmentationProfile;
import org.ccnx.ccn.profiles.VersionMissingException;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.protocol.CCNTime;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Interest;
import org.ccnx.ccn.test.AssertionCCNHandle;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * This test checks if we can actually get the latest version using the getLatestVersion
 * method in VersioningProfile.
 *
 * The current implementation of getLatestVersion does not loop to try and find the latest version.
 * It reports the latest available with a single interest.  The second part of this test is commented out
 * due to this limitation.  This will be activated when an alternate to getLatestVersion is supplied in
 * the implementation or getLatestVersion is modified.  getLatestVersion currently does not loop looking
 * for newer version to avoid suffering timeouts when there is not an newer version available.
 */
public class LatestVersionTest {
	static final long WAIT_TIME = 500;
	static CCNHandle getHandle;
	static CCNHandle responderHandle;
	ContentName baseName;

	public static ContentObject lastVersionPublished = null;
	public static ContentName pingResponder = null;

	public static ArrayList<ContentObject> responseObjects = null;

	public static ContentObject failVerify = null;
	public static ContentObject failVerify1 = null;
	public static ContentObject failVerify2 = null;
	public static ContentObject failVerify4 = null;

	private static Vector<Interest> outstandingInterests = new Vector<Interest>();

	
	Responder responder;

	/**
	 * @throws java.lang.Exception
	 */
	@Before
	public void setUp() throws Exception {
		Log.setDefaultLevel(Level.FINEST);
		getHandle = CCNHandle.open();
		baseName = ContentName.fromURI("/ccnx.org/test/latestVersionTest/"+(new CCNTime()).toShortString());
		responder = new Responder();
	}

	@After
	public void tearDown() {

		getHandle.close();
		responderHandle.close();
	}

	/**
	 * Test to check if the getLatestVersion method in VersioningProfile gets the latest version with a ccnd involved.
	 * @throws Error
	 * @throws InterruptedException
	 */
	@Test
	public void getLatestVersion() throws InterruptedException, Error {
		Log.info(Log.FAC_TEST, "Starting getLatestVersion");

		ContentName one = null;
		ContentName two = null;
		ContentName three = null;
		ContentName four = null;
		ContentName skipSegment = null;
		ContentName skipSegment0 = null;
		ContentName skipSegment2 = null;

		ContentObject obj1 = null;
		ContentObject obj2 = null;
		ContentObject obj3 = null;
		ContentObject obj4 = null;
		ContentObject objSkip = null;
		ContentObject objSkip0 = null;
		ContentObject objSkip2 = null;

		ContentObject object = null;
		responseObjects = new ArrayList<ContentObject>();

		checkResponder();


		CCNTime t1;
		CCNTime t2;
		CCNTime t3;
		CCNTime t4;
		CCNTime skipTime;
		CCNTime skipTime2;

		long timeout = 5000;

		t1 = new CCNTime();
		one = SegmentationProfile.segmentName(new ContentName(baseName, t1), 0);
		obj1 = ContentObject.buildContentObject(one, "here is version 1".getBytes(), null, null, SegmentationProfile.getSegmentNumberNameComponent(0));

		t2 = (CCNTime)t1.clone();
		t2.increment(1);
		two = SegmentationProfile.segmentName(new ContentName(baseName, t2), 0);
		obj2 = ContentObject.buildContentObject(two, "here is version 2".getBytes(), null, null, SegmentationProfile.getSegmentNumberNameComponent(0));

		t3 = (CCNTime) t1.clone();
		t3.increment(2);
		three = SegmentationProfile.segmentName(new ContentName(baseName, t3), 0);
		obj3 = ContentObject.buildContentObject(three, "here is version 3".getBytes(), null, null, SegmentationProfile.getSegmentNumberNameComponent(0));

		t4 = (CCNTime)t1.clone();
		t4.increment(3);
		four = SegmentationProfile.segmentName(new ContentName(baseName, t4), 0);
		obj4 = ContentObject.buildContentObject(four, "here is version 4".getBytes(), null, null, SegmentationProfile.getSegmentNumberNameComponent(0));

		skipTime = (CCNTime)t1.clone();
		skipTime.increment(4);
		skipSegment = SegmentationProfile.segmentName(new ContentName(baseName, skipTime), 5);
		objSkip = ContentObject.buildContentObject(skipSegment, "here is skip".getBytes(), null, null, SegmentationProfile.getSegmentNumberNameComponent(5));
		skipSegment0 = SegmentationProfile.segmentName(new ContentName(baseName, skipTime), 0);
		objSkip0 = ContentObject.buildContentObject(skipSegment0, "here is skip".getBytes(), null, null, SegmentationProfile.getSegmentNumberNameComponent(5));

		skipTime2 = (CCNTime)t1.clone();
		skipTime2.increment(5);
		skipSegment2 = SegmentationProfile.segmentName(new ContentName(baseName, skipTime2), 5);
		objSkip2 = ContentObject.buildContentObject(skipSegment2, "here is skip 2".getBytes(), null, null, SegmentationProfile.getSegmentNumberNameComponent(5));


		Log.info(Log.FAC_TEST, "made versions: "+one +" "+two+" "+three+" "+four+" "+skipTime+" "+skipTime2);

		Assert.assertTrue(t1.before(t2));
		Assert.assertTrue(t2.before(t3));
		Assert.assertTrue(t3.before(t4));
		Assert.assertTrue(t4.before(skipTime));
		Assert.assertTrue(skipTime.before(skipTime2));

		try {
			//should not force content objects into ccnd...
			//responderHandle.put(obj1);
			//responderHandle.put(obj2);

			responseObjects.add(obj1);
			responseObjects.add(obj2);
			
			ContentObject o1 = getHandle.get(obj1.name(), SystemConfiguration.MEDIUM_TIMEOUT);
			ContentObject o2 = getHandle.get(obj2.name(), SystemConfiguration.MEDIUM_TIMEOUT);

			Assert.assertNotNull(o1);
			Assert.assertNotNull(o2);
			
			object = VersioningProfile.getLatestVersion(baseName, null, timeout, getHandle.defaultVerifier(), getHandle);
			responder.checkError();
			Assert.assertNotNull(object);

			Assert.assertTrue(VersioningProfile.getLastVersionAsLong(object.name()) == VersioningProfile.getLastVersionAsLong(two));
			Log.info(Log.FAC_TEST, "passed test for getLatestVersion with 2 versions available");

			object = VersioningProfile.getFirstBlockOfLatestVersion(baseName, null, null, timeout, getHandle.defaultVerifier(), getHandle);
			responder.checkError();
			Assert.assertNotNull(object);
			Assert.assertTrue(VersioningProfile.getLastVersionAsLong(object.name()) == VersioningProfile.getLastVersionAsLong(two));
			Log.info(Log.FAC_TEST, "passed test for getFirstBlockOfLatestVersion with 2 versions available");

			Assert.assertTrue(responseObjects.size() == 0);

		} catch (IOException e) {
			Assert.fail("Failed to get latest version: "+e.getMessage());
		} catch (VersionMissingException e) {
			Assert.fail("Failed to get version from object: "+e.getMessage());
		}
		responder.checkError();

		responseObjects.add(obj3);
		Log.info(Log.FAC_TEST, "added: "+obj3.name());

		System.out.println("do we have outstanding interests? "+outstandingInterests.size() +" or response Objects? "+ responseObjects.size());
		responder.processOutstandingInterests();
		System.out.println("after processing: do we have outstanding interests? "+outstandingInterests.size() +" or response Objects? "+ responseObjects.size());

		//now put third version
		try {
			Log.info(Log.FAC_TEST, "calling gLV at: "+System.currentTimeMillis());
			object = VersioningProfile.getLatestVersion(baseName, null, timeout, getHandle.defaultVerifier(), getHandle);
			responder.checkError();
			Assert.assertNotNull(object);
			Log.info(Log.FAC_TEST, "got: "+object.name() + ", expecting to get: "+three);

			Assert.assertTrue(VersioningProfile.getLastVersionAsLong(object.name()) == VersioningProfile.getLastVersionAsLong(three));
			Log.info(Log.FAC_TEST, "passed test for getLatestVersion with 3 versions available");

			object = VersioningProfile.getFirstBlockOfLatestVersion(baseName, null, null, timeout, getHandle.defaultVerifier(), getHandle);
			responder.checkError();
			Assert.assertNotNull(object);
			Assert.assertTrue(VersioningProfile.getLastVersionAsLong(object.name()) == VersioningProfile.getLastVersionAsLong(three));
			Log.info(Log.FAC_TEST, "passed test for getFirstBlockOfLatestVersion with 3 versions available");

			if (responseObjects.size() > 0 )
				System.out.println("i have a content object...: "+responseObjects.get(0).fullName());
			
			Assert.assertTrue(responseObjects.size() == 0);

		} catch (VersionMissingException e) {
			Assert.fail("Failed to get version from object: "+e.getMessage());

		} catch (IOException e) {
			Assert.fail("Failed to get latest version: "+e.getMessage());
		}


		System.out.println("do we have outstanding interests? "+outstandingInterests.size() +" or response Objects? "+ responseObjects.size());
		responder.processOutstandingInterests();
		System.out.println("after processing: do we have outstanding interests? "+outstandingInterests.size() +" or response Objects? "+ responseObjects.size());
		
		//now make sure we can get the latest version with an explicit request for
		//something after version 2
		try {
			object = VersioningProfile.getLatestVersion(two, null, timeout, getHandle.defaultVerifier(), getHandle);
			responder.checkError();

			Assert.assertTrue(VersioningProfile.getLastVersionAsLong(object.name()) == VersioningProfile.getLastVersionAsLong(three));

			Assert.assertTrue(responseObjects.size() == 0);

		} catch (IOException e) {
			Assert.fail("Failed to get latest version: "+e.getMessage());
		} catch (VersionMissingException e) {
			Assert.fail("Failed to get version from object: "+e.getMessage());
		}

		
		System.out.println("do we have outstanding interests? "+outstandingInterests.size() +" or response Objects? "+ responseObjects.size());
		responder.processOutstandingInterests();
		System.out.println("after processing: do we have outstanding interests? "+outstandingInterests.size() +" or response Objects? "+ responseObjects.size());


		//now check to make sure when we ask for a later version (that does not exist) that we don't wait too long.

		//first wait for 5 seconds, then 200ms
		try {
			long doneTime;
			long checkTime = System.currentTimeMillis();
			object = VersioningProfile.getFirstBlockOfLatestVersion(three, null, null, 5000, getHandle.defaultVerifier(), getHandle);
			doneTime = System.currentTimeMillis();
			Log.info(Log.FAC_TEST, "took us "+(doneTime - checkTime)+"ms to get nothing back "+" check: "+checkTime+" done: "+doneTime);
			responder.checkError();
			Assert.assertNull(object);
			Assert.assertTrue((doneTime - checkTime) < 5500 && (doneTime - checkTime) >= 5000);
			Log.info(Log.FAC_TEST, "passed test for waiting 5 seconds");

			checkTime = System.currentTimeMillis();
			object = VersioningProfile.getFirstBlockOfLatestVersion(three, null, null, 200, getHandle.defaultVerifier(), getHandle);
			checkTime = System.currentTimeMillis() - checkTime;
			responder.checkError();
			Log.info(Log.FAC_TEST, "took us "+checkTime+"ms to get nothing back");
			Assert.assertNull(object);
			Assert.assertTrue(checkTime >= 200 && checkTime < 300);
			Log.info(Log.FAC_TEST, "passed test for waiting 200ms");

			Assert.assertTrue(responseObjects.size() == 0);

		} catch (IOException e) {
			Assert.fail("failed to test with different timeouts: "+e.getMessage());
		}

		Thread.sleep(4000); // Allow time for the unanswered interests to expire

		responseObjects.add(obj4);
		
		//add a fourth responder and make sure we don't get it back.
		try {
			lastVersionPublished = obj4;

			object = VersioningProfile.getFirstBlockOfLatestVersion(baseName, null, null, 0, getHandle.defaultVerifier(), getHandle);
			responder.checkError();
			Assert.assertNotNull(object);
			Assert.assertTrue(VersioningProfile.getLastVersionAsLong(object.name()) == VersioningProfile.getLastVersionAsLong(three));
			Log.info(Log.FAC_TEST, "passed test for timeout 0 test");

			object = VersioningProfile.getFirstBlockOfLatestVersion(baseName, null, null, 0, getHandle.defaultVerifier(), getHandle);
			responder.checkError();
			Assert.assertNotNull(object);
			Assert.assertTrue(VersioningProfile.getLastVersionAsLong(object.name()) == VersioningProfile.getLastVersionAsLong(three));
			Log.info(Log.FAC_TEST, "passed test for timeout 0 test");

		} catch (IOException e) {
			Assert.fail("failed to test with timeout of 0: "+e.getMessage());
		} catch (VersionMissingException e) {
			Assert.fail("failed to test with timeout of 0: "+e.getMessage());
		}

		
		//need to clear out segment 4 from our responder
		//now make sure we can get the latest version with an explicit request for
		//something after version 2
		try {
			object = VersioningProfile.getLatestVersion(two, null, timeout, getHandle.defaultVerifier(), getHandle);
			responder.checkError();
			Assert.assertNotNull(object);

			Assert.assertTrue(VersioningProfile.getLastVersionAsLong(object.name()) == VersioningProfile.getLastVersionAsLong(four));

			Assert.assertTrue(responseObjects.size() == 0);

		} catch (IOException e) {
			Assert.fail("Failed to get latest version: "+e.getMessage());
		} catch (VersionMissingException e) {
			Assert.fail("Failed to get version from object: "+e.getMessage());
		}


		//test for getting the first segment of a version that does not have a first segment
		//put a later segment of a later version and make sure it comes back with null or an earlier version

		try {
			Log.info(Log.FAC_TEST, "=========testing skip segment!");

			responderHandle.put(objSkip);

			object = VersioningProfile.getLatestVersion(baseName, null, timeout, getHandle.defaultVerifier(), getHandle);
			responder.checkError();
			Assert.assertNotNull(object);

			Assert.assertTrue(VersioningProfile.getLastVersionAsLong(object.name()) == VersioningProfile.getLastVersionAsLong(skipSegment));
			Log.info(Log.FAC_TEST, "passed test for getLatestVersion with skipped segment available");

			Log.info(Log.FAC_TEST, "adding: "+objSkip0.name());
			responseObjects.add(objSkip0);
			Log.info(Log.FAC_TEST, "adding: "+objSkip2.name());
			responseObjects.add(objSkip2);

			object = VersioningProfile.getFirstBlockOfLatestVersion(baseName, null, null, timeout, getHandle.defaultVerifier(), getHandle);
			responder.checkError();
			Assert.assertNotNull(object);

			Assert.assertTrue(VersioningProfile.getLastVersionAsLong(object.name()) == VersioningProfile.getLastVersionAsLong(skipSegment0));
			Log.info(Log.FAC_TEST, "passed test for getFirstBlockOfLatestVersion with skipped segment available");

			Assert.assertTrue(responseObjects.size() == 0);

		} catch (IOException e) {
			Assert.fail("Failed to get latest version: "+e.getMessage());
		} catch (VersionMissingException e) {
			Assert.fail("Failed to get version from object: "+e.getMessage());
		}

		//now put 15 responses in....
		CCNTime versionToAdd = new CCNTime();
		for(int i = 0; i < SystemConfiguration.GET_LATEST_VERSION_ATTEMPTS + 5; i++) {
			versionToAdd.increment(1);
			responseObjects.add(ContentObject.buildContentObject(SegmentationProfile.segmentName(new ContentName(baseName, versionToAdd), 0), "here is version generated".getBytes(), null, null, SegmentationProfile.getSegmentNumberNameComponent(0)));
			Log.info(Log.FAC_TEST, "created version with time: "+versionToAdd+" object name: "+responseObjects.get(i).fullName());
		}

		lastVersionPublished = responseObjects.get(responseObjects.size()-1);

		
		//test for sending in a null timeout
		try {

			object = VersioningProfile.getLatestVersion(baseName, null, SystemConfiguration.NO_TIMEOUT, getHandle.defaultVerifier(), getHandle);
			responder.checkError();
			Assert.assertNotNull(object);
			Log.info(Log.FAC_TEST, "got back :"+object.name());
			Assert.assertTrue(object.name().equals(lastVersionPublished.name()));
			Log.info(Log.FAC_TEST, "passed test for no timeout");


			for(int i =0; i < SystemConfiguration.GET_LATEST_VERSION_ATTEMPTS; i++) {
				versionToAdd.increment(1);
				responseObjects.add(ContentObject.buildContentObject(SegmentationProfile.segmentName(new ContentName(baseName, versionToAdd), 0), "here is version generated".getBytes(), null, null, SegmentationProfile.getSegmentNumberNameComponent(0)));
				Log.info(Log.FAC_TEST, "created version with time: "+versionToAdd+" object name: "+responseObjects.get(i).fullName());
			}

			lastVersionPublished = responseObjects.get(responseObjects.size()-1);

			object = VersioningProfile.getFirstBlockOfLatestVersion(baseName, null, null, SystemConfiguration.NO_TIMEOUT, getHandle.defaultVerifier(), getHandle);
			responder.checkError();
			Assert.assertNotNull(object);
			Assert.assertTrue(object.name().equals(lastVersionPublished.name()));
			Log.info(Log.FAC_TEST, "passed test for no timeout");

			Assert.assertTrue(responseObjects.size() == 0);

		} catch (IOException e) {
			Assert.fail("failed to test with no timeout: "+e.getMessage());
		}

		// Note by paul r. I'm not sure why we need to have a "responder" here in the first place - as opposed to just simply
		// putting the test objects to ccnd. But once we have a verifier, there's a potential race between the verifier and responder i.e.
		// we can call the verifier before the responder or vice-versa so we can't guarantee what's actually going to show up in the
		// verifier because the objects we expect to see may not have been output by the responder yet. I've fixed this (I hope!) by
		// doing an explicit get of the objects in question before we try the test so that the responder has definitely done a put
		// of the objects we are trying to test

		ContentVerifier ver = new TestVerifier();

		//have the verifier fail the newest object make sure we get back the most recent verified version

		versionToAdd.increment(1);
		failVerify = ContentObject.buildContentObject(SegmentationProfile.segmentName(new ContentName(baseName, versionToAdd), 0), "here is failVerify".getBytes(), null, null, SegmentationProfile.getSegmentNumberNameComponent(0));
		responseObjects.add(failVerify);
				
		
		try {
			getHandle.get(failVerify.fullName(), timeout);
			responder.checkError();
		} catch (IOException e1) {
			Assert.fail("Failed get: "+e1.getMessage());
		}

		//now put a unverifiable version
		try {

			object = VersioningProfile.getLatestVersion(baseName, null, timeout, ver, getHandle);
			responder.checkError();
			Assert.assertNotNull(object);
			Log.info(Log.FAC_TEST, "got: "+object.name() + ", expecting to get: "+lastVersionPublished.name());
			Assert.assertTrue(VersioningProfile.getLastVersionAsLong(object.name()) == VersioningProfile.getLastVersionAsLong(lastVersionPublished.name()));
			Log.info(Log.FAC_TEST, "passed test for failed verification");

			object = VersioningProfile.getFirstBlockOfLatestVersion(baseName, null, null, timeout, ver, getHandle);
			responder.checkError();
			Log.info(Log.FAC_TEST, "expecting to get: "+lastVersionPublished.name());
			Assert.assertNotNull(object);
			Log.info(Log.FAC_TEST, "got: "+object.name());
			Assert.assertTrue(VersioningProfile.getLastVersionAsLong(object.name()) == VersioningProfile.getLastVersionAsLong(lastVersionPublished.name()));
			Log.info(Log.FAC_TEST, "passed test for getFirstBlockOfLatestVersion failed verification");

			Assert.assertTrue(responseObjects.size() == 0);

		} catch (VersionMissingException e) {
			Assert.fail("Failed to get version from object: "+e.getMessage());

		} catch (IOException e) {
			Assert.fail("Failed to get latest version: "+e.getMessage());
		}




		//then call again and make sure we have a newer version that passes

		//have the verifier fail the newest object make sure we get back the most recent verified version
		versionToAdd.increment(1);
		ContentObject verify = ContentObject.buildContentObject(SegmentationProfile.segmentName(new ContentName(baseName, versionToAdd), 0), "here is verify".getBytes(), null, null, SegmentationProfile.getSegmentNumberNameComponent(0));
		responseObjects.add(verify);
		
		try {
			getHandle.get(verify.fullName(), timeout);
			responder.checkError();
		} catch (IOException e1) {
			Assert.fail("Failed get: "+e1.getMessage());
		}

		//now put a verifiable version
		try {

			object = VersioningProfile.getLatestVersion(baseName, null, timeout, ver, getHandle);
			responder.checkError();
			Assert.assertNotNull(object);
			Log.info(Log.FAC_TEST, "got: "+object.name() + ", expecting to get: "+verify.name());
			Assert.assertTrue(VersioningProfile.getLastVersionAsLong(object.name()) == VersioningProfile.getLastVersionAsLong(verify.name()));
			Log.info("passed test for failed verification with newer version available");

			object = VersioningProfile.getFirstBlockOfLatestVersion(baseName, null, null, timeout, ver, getHandle);
			responder.checkError();
			Assert.assertNotNull(object);
			Assert.assertTrue(VersioningProfile.getLastVersionAsLong(object.name()) == VersioningProfile.getLastVersionAsLong(verify.name()));
			Log.info(Log.FAC_TEST, "passed test for getFirstBlockOfLatestVersion failed verification with newer version available");

			Assert.assertTrue(responseObjects.size() == 0);

		} catch (VersionMissingException e) {
			Assert.fail("Failed to get version from object: "+e.getMessage());

		} catch (IOException e) {
			Assert.fail("Failed to get latest version: "+e.getMessage());
		}




		//test that also has a content object that fails to verify (maybe do 2)
		//and then also add one that does verify - same version.  make sure we get the verifiable one back
		versionToAdd.increment(1);
		failVerify1 = ContentObject.buildContentObject(SegmentationProfile.segmentName(new ContentName(baseName, versionToAdd), 0), "here is failVerify".getBytes(), null, null, SegmentationProfile.getSegmentNumberNameComponent(0));
		responseObjects.add(failVerify1);


		failVerify2 = ContentObject.buildContentObject(SegmentationProfile.segmentName(new ContentName(baseName, versionToAdd), 0), "here is a second failVerify".getBytes(), null, null, SegmentationProfile.getSegmentNumberNameComponent(0));
		responseObjects.add(failVerify2);

		ContentObject failVerify3 = ContentObject.buildContentObject(SegmentationProfile.segmentName(new ContentName(baseName, versionToAdd), 0), "here is a third, but it should pass".getBytes(), null, null, SegmentationProfile.getSegmentNumberNameComponent(0));
		responseObjects.add(failVerify3);

		try {
			getHandle.get(failVerify1.fullName(), timeout);
			responder.checkError();
			getHandle.get(failVerify2.fullName(), timeout);
			responder.checkError();
			getHandle.get(failVerify3.fullName(), timeout);
			responder.checkError();
		} catch (IOException e1) {
			Assert.fail("Failed get: "+e1.getMessage());
		}

		Log.info(Log.FAC_TEST, "failVerify1*: "+failVerify1.fullName());
		Log.info(Log.FAC_TEST, "failVerify2*: "+failVerify2.fullName());
		Log.info(Log.FAC_TEST, "failVerify3: "+failVerify3.fullName());

		try {

			object = VersioningProfile.getLatestVersion(baseName, null, timeout, ver, getHandle);
			responder.checkError();
			Assert.assertNotNull(object);
			Log.info(Log.FAC_TEST, "got: "+object.fullName() + ", expecting to get: "+failVerify3.fullName());
			Assert.assertTrue(VersioningProfile.getLastVersionAsLong(object.fullName()) == VersioningProfile.getLastVersionAsLong(failVerify3.fullName()));
			Log.info(Log.FAC_TEST, "passed test for failed verification with multiple failures and a success");

			object = VersioningProfile.getFirstBlockOfLatestVersion(baseName, null, null, timeout, ver, getHandle);
			responder.checkError();
			Assert.assertNotNull(object);
			Log.info(Log.FAC_TEST, "got: "+object.fullName() + ", expecting to get: "+failVerify3.fullName());
			Assert.assertTrue(VersioningProfile.getLastVersionAsLong(object.fullName()) == VersioningProfile.getLastVersionAsLong(failVerify3.fullName()));
			Log.info(Log.FAC_TEST, "passed test for getFirstBlockOfLatestVersion failed verification with multiple failures and a success");

			Assert.assertTrue(responseObjects.size() == 0);

		} catch (VersionMissingException e) {
			Assert.fail("Failed to get version from object: "+e.getMessage());

		} catch (IOException e) {
			Assert.fail("Failed to get latest version: "+e.getMessage());
		}

		//test that checks a combo of a version without a first segment with a failed verification
		//after it.  should return the non 0 segment for the first part, and the last published first segment
		//for the second part of the test

		responseObjects.clear();

		versionToAdd.increment(1);
		ContentObject objSkip3 = ContentObject.buildContentObject(SegmentationProfile.segmentName(new ContentName(baseName, versionToAdd), 5), "here is skip 3".getBytes(), null, null, SegmentationProfile.getSegmentNumberNameComponent(5));
		responseObjects.add(objSkip3);

		versionToAdd.increment(1);
		failVerify4 = ContentObject.buildContentObject(SegmentationProfile.segmentName(new ContentName(baseName, versionToAdd), 0), "here is a fourth verify, it should fail".getBytes(), null, null, SegmentationProfile.getSegmentNumberNameComponent(0));
		responseObjects.add(failVerify4);
		try {
			getHandle.get(objSkip3.fullName(), timeout);
			responder.checkError();
			getHandle.get(failVerify4.fullName(), timeout);
			responder.checkError();
		} catch (IOException e1) {
			Assert.fail("Failed get: "+e1.getMessage());
		}

		Log.info(Log.FAC_TEST, "objSkip3: "+ objSkip3.fullName());
		Log.info(Log.FAC_TEST, "failVerify4*: "+failVerify4.fullName());

		try {

			object = VersioningProfile.getLatestVersion(baseName, null, timeout, ver, getHandle);
			responder.checkError();
			Assert.assertNotNull(object);
			Log.info(Log.FAC_TEST, "got: "+object.fullName() + ", expecting to get: "+objSkip3.fullName());
			Assert.assertTrue(VersioningProfile.getLastVersionAsLong(object.fullName()) == VersioningProfile.getLastVersionAsLong(objSkip3.fullName()));
			Log.info("passed test for missing first segment + failed verification");

			object = VersioningProfile.getFirstBlockOfLatestVersion(baseName, null, null, timeout, ver, getHandle);
			responder.checkError();
			Assert.assertNotNull(object);
			Log.info(Log.FAC_TEST, "got: "+object.fullName() + ", expecting to get: "+failVerify3.fullName());
			Assert.assertTrue(VersioningProfile.getLastVersionAsLong(object.fullName()) == VersioningProfile.getLastVersionAsLong(failVerify3.fullName()));
			Log.info(Log.FAC_TEST, "passed test for missing first segment + failed verification");

			Assert.assertTrue(responseObjects.size() == 0);

		} catch (VersionMissingException e) {
			Assert.fail("Failed to get version from object: "+e.getMessage());

		} catch (IOException e) {
			Assert.fail("Failed to get latest version: "+e.getMessage());
		}

		responder.checkError();

		Log.info(Log.FAC_TEST, "Completed getLatestVersion");
	}

	private void checkResponder() throws InterruptedException, Error {
		try {
			ContentName test = new ContentName(baseName, "testResponder");
			ContentObject co = ContentObject.buildContentObject(SegmentationProfile.segmentName(VersioningProfile.addVersion(test), 0), "test content responder".getBytes(), null, null, SegmentationProfile.getSegmentNumberNameComponent(0));
			responseObjects.add(co);
			Interest i = new Interest(co.name());
			ContentObject co2 = getHandle.get(i, 5000 );
			responder.checkError();
			if (co2 == null) {
				Log.warning(Log.FAC_TEST, "test responder object is null, failed to start responder in 5 seconds");
				Assert.fail("test responder did not start up in 5 seconds.");
			}
			Assert.assertTrue(co2.fullName().equals(co.fullName()));
			Log.info(Log.FAC_TEST, "Responder is up and ready!");
		} catch (IOException e) {
			Assert.fail("could not get test responder object: "+e.getMessage());
		}
	}

	/**
	 * Runnable class for the single ContentObject responder.
	 */
	class Responder implements CCNInterestHandler {
		AssertionCCNHandle handle;

		public Responder() throws IOException {
			try {
				handle = AssertionCCNHandle.open();
				LatestVersionTest.responderHandle = handle;
			} catch (Exception e) {
				Assert.fail("could not create handle for responder: " + e.getMessage());
			}

			handle.registerFilter(baseName, this);
		}

		
		public void processOutstandingInterests() {
			Vector<Interest> interestToRemove = new Vector<Interest>();
			Vector<ContentObject> responsesToRemove = new Vector<ContentObject>();

			for (Interest i: outstandingInterests) {
				for (ContentObject c: responseObjects) {
					if (i.matches(c)) {
						System.out.println("found a match, responding with stored content object");
						System.out.println("interest: "+i.toString()+" response: "+c.fullName());
						try {
							handle.put(c);
						} catch (IOException e) {
							Assert.fail("could not put object in responder");
						}
						interestToRemove.add(i);
						responsesToRemove.add(c);
					}
				}
			}
			System.out.println("removing "+interestToRemove.size() + " interests and "+responsesToRemove.size()+" contentObjects");
			System.out.println("removingI: "+outstandingInterests.removeAll(interestToRemove));
			System.out.println("removingC: "+responseObjects.removeAll(responsesToRemove));
		}
		
		public boolean handleInterest(Interest interest) {
			Log.info(Log.FAC_TEST, System.currentTimeMillis()+ " handling interest "+ interest.name());

			Log.info(Log.FAC_TEST, "current objects: ");
			for(ContentObject o: responseObjects)
				Log.info(Log.FAC_TEST, o.fullName().toString());

			if(responseObjects.size() == 0) {
				System.out.println("responseObjects size == 0");
				LatestVersionTest.outstandingInterests.add(interest);
				return false;
			}

			if (interest.matches(responseObjects.get(0))) {
				try {
					Log.info(Log.FAC_TEST, "returning: "+ responseObjects.get(0).fullName());
					handle.put(responseObjects.remove(0));
					return true;
				} catch (IOException e) {
					Assert.fail("could not put object in responder");
					return false;
				}
			} else {
				Log.info(Log.FAC_TEST, "didn't have a match with: "+responseObjects.get(0).fullName());
				Log.info(Log.FAC_TEST, "full interest: "+interest.toString());
				//adding interest to outstanding interests vector
				outstandingInterests.add(interest);
				return false;
			}
		}

		public void checkError() throws Error, InterruptedException {
			handle.checkError(WAIT_TIME);
		}

	}

	class TestVerifier implements ContentVerifier{

		public boolean verify(ContentObject content) {
			Log.info(Log.FAC_TEST, "VERIFIER: "+content.fullName());

			ContentName contentName = content.fullName();
			if ( failVerify != null ) {
				if (contentName.equals(failVerify.fullName()))
					return false;
			} else
				Log.info(Log.FAC_TEST, "failVerify was null");

			if ( failVerify2 != null ) {
				Log.info(Log.FAC_TEST, "failVerify1*: "+failVerify1.fullName());
				Log.info(Log.FAC_TEST, "failVerify2*: "+failVerify2.fullName());
				Log.info(Log.FAC_TEST, "contentName: "+content.fullName());
				if (contentName.equals(failVerify1.fullName()) || contentName.equals(failVerify2.fullName()))
					return false;
			}  else
					Log.info(Log.FAC_TEST, "failVerify2 was null");

			if ( failVerify4 != null ) {
				if (contentName.equals(failVerify4.fullName()))
					return false;
			} else
				Log.info(Log.FAC_TEST, "failVerify4 was null");

			Log.info(Log.FAC_TEST, "resorting to default verifier");

			return LatestVersionTest.getHandle.defaultVerifier().verify(content);
		}
	}
}
