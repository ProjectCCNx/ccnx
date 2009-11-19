package org.ccnx.ccn.test.protocol;

import java.io.IOException;
import java.util.ArrayList;


import org.ccnx.ccn.CCNFilterListener;
import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.profiles.SegmentationProfile;
import org.ccnx.ccn.profiles.VersionMissingException;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.protocol.CCNTime;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Interest;
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

	CCNHandle handle;
	ContentName baseName;
	
	CCNHandle putHandle;
	
	/**
	 * @throws java.lang.Exception
	 */
	@Before
	public void setUp() throws Exception {
		handle = CCNHandle.open();
		putHandle = CCNHandle.open();
		baseName = ContentName.fromURI("/ccnx.org/test/latestVersionTest/"+(new CCNTime()).toShortString());
	}
	
	/**
	 * Test to check if the getLatestVersion method in VersioningProfile gets the latest version with a ccnd involved.
	 */
	@Test
	public void getLatestVersion() {
		ContentName one = null;
		ContentName two = null;
		ContentName three = null;
		
		ContentObject obj1 = null;
		ContentObject obj2 = null;
		ContentObject obj3 = null;
		
		ContentObject object = null;
		
		CCNTime t1;
		CCNTime t2;
		CCNTime t3;
		
		long timeout = 5000;
		
		t1 = new CCNTime();
		one = VersioningProfile.addVersion(baseName, t1);
		obj1 = ContentObject.buildContentObject(one, "here is version 1".getBytes(), null, null, SegmentationProfile.getSegmentNumberNameComponent(0));

		t2 = new CCNTime();
		two = VersioningProfile.addVersion(baseName, t2);
		obj2 = ContentObject.buildContentObject(two, "here is version 2".getBytes(), null, null, SegmentationProfile.getSegmentNumberNameComponent(0));

		t3 = new CCNTime();
		three = VersioningProfile.addVersion(baseName, t3);
		obj3 = ContentObject.buildContentObject(three, "here is version 3".getBytes(), null, null, SegmentationProfile.getSegmentNumberNameComponent(0));
		
		
		try {
			putHandle.put(obj1);
			putHandle.put(obj2);
			
			object =VersioningProfile.getLatestVersion(baseName, null, timeout, handle.defaultVerifier(), handle); 
			
			Assert.assertTrue(VersioningProfile.getLastVersionAsLong(object.name()) == VersioningProfile.getLastVersionAsLong(two));
		} catch (IOException e) {
			Assert.fail("Failed to get latest version: "+e.getMessage());
		} catch (VersionMissingException e) {
			Assert.fail("Failed to get version from object: "+e.getMessage());
		}
		
		
		//now put third version
		try {
			setUpResponder(obj3);
			
			object =VersioningProfile.getLatestVersion(baseName, null, timeout, handle.defaultVerifier(), handle); 
			
			/*
			 * For now, comment this part of the test out.
			 * Get latest version does not loop to get the latest version until it
			 * experiences a timeout.  Must ask for something after the second object
			 * to get the third.
			 */
		//	Assert.assertTrue(VersioningProfile.getLastVersionAsLong(object.name()) == VersioningProfile.getLastVersionAsLong(three));
		//} catch (VersionMissingException e) {
		//	Assert.fail("Failed to get version from object: "+e.getMessage());
		//}
		} catch (IOException e) {
			Assert.fail("Failed to get latest version: "+e.getMessage());
		}
		
		
		//now make sure we can get the latest version with an explicit request for
		//something after version 2
		try {
			object = VersioningProfile.getLatestVersion(two, null, timeout, handle.defaultVerifier(), handle); 
			
			Assert.assertTrue(VersioningProfile.getLastVersionAsLong(object.name()) == VersioningProfile.getLastVersionAsLong(three));
		} catch (IOException e) {
			Assert.fail("Failed to get latest version: "+e.getMessage());
		} catch (VersionMissingException e) {
			Assert.fail("Failed to get version from object: "+e.getMessage());
		}
		
	}
	
	
	/**
	 * Method to set up a responder for the get latest version test.  Only responds to Interests
	 * with a single ContentObject.
	 * 
	 * @param obj ContentObject to respond to Interests with.
	 */
	private void setUpResponder(ContentObject obj) {
		Thread t = new Thread(new Responder(obj));
		t.run();
		
	}

	/**
	 * Runnable class for the single ContentObject responder.
	 */
	class Responder implements Runnable, CCNFilterListener {
		ContentObject object;
		CCNHandle handle;

		public Responder(ContentObject obj) {
			object = obj;
			try {
				handle = CCNHandle.open();
			} catch (Exception e) {
				Assert.fail("could not create handle for responder: " + e.getMessage());
			}

			handle.registerFilter(VersioningProfile.cutLastVersion(object.name()), this);
		}

		public void run() {

		}

		public int handleInterests(ArrayList<Interest> interests) {
			for (Interest i : interests)
				if (i.matches(object)) {
					try {
						handle.put(object);
					} catch (IOException e) {
						Assert.fail("could not put object in responder");
					}
					return 0;
				}
			return 0;
		}

	}

}
