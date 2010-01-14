package org.ccnx.ccn.test.protocol;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.logging.Level;

import org.ccnx.ccn.CCNFilterListener;
import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.ContentVerifier;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.profiles.SegmentationProfile;
import org.ccnx.ccn.profiles.VersionMissingException;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.protocol.CCNTime;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Interest;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;
import org.ccnx.ccn.impl.support.Log;

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

	static CCNHandle getHandle;
	static CCNHandle responderHandle;
	ContentName baseName;
	
	//CCNHandle putHandle;
	
	public static ContentObject lastVersionPublished = null;
	public static long attempts = 0;
	public static ContentName pingResponder = null;
	
	public static ArrayList<ContentObject> responseObjects = null;
	
	private Thread t;
	
	public static ContentObject failVerify = null;
	public static ContentObject failVerify1 = null;
	public static ContentObject failVerify2 = null;
	
	/**
	 * @throws java.lang.Exception
	 */
	@Before
	public void setUp() throws Exception {
		Log.setLevel(Level.FINEST);
		
		getHandle = CCNHandle.open();
		//putHandle = CCNHandle.open();
		baseName = ContentName.fromURI("/ccnx.org/test/latestVersionTest/"+(new CCNTime()).toShortString());
		t = setUpResponder();
	}
	
	@After
	public void close() {
		getHandle.close();
		//putHandle.close();
		responderHandle.close();
	}
	
	/**
	 * Test to check if the getLatestVersion method in VersioningProfile gets the latest version with a ccnd involved.
	 */
	@Test
	public void getLatestVersion() {
		ContentName one = null;
		ContentName two = null;
		ContentName three = null;
		ContentName four = null;
		ContentName skipSegment = null;
		ContentName skipSegment0 = null;
		ContentName skipSegment2 = null;

		ContentObject baseObject = null;
		
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
		
		
		baseObject = ContentObject.buildContentObject(baseName, "base object".getBytes(), null, null, SegmentationProfile.getSegmentNumberNameComponent(0));
		
		t1 = new CCNTime();
		one = SegmentationProfile.segmentName(VersioningProfile.addVersion(baseName, t1), 0);
		obj1 = ContentObject.buildContentObject(one, "here is version 1".getBytes(), null, null, SegmentationProfile.getSegmentNumberNameComponent(0));

		t2 = new CCNTime();
		two = SegmentationProfile.segmentName(VersioningProfile.addVersion(baseName, t2), 0);
		obj2 = ContentObject.buildContentObject(two, "here is version 2".getBytes(), null, null, SegmentationProfile.getSegmentNumberNameComponent(0));

		t3 = new CCNTime();
		three = SegmentationProfile.segmentName(VersioningProfile.addVersion(baseName, t3), 0);
		obj3 = ContentObject.buildContentObject(three, "here is version 3".getBytes(), null, null, SegmentationProfile.getSegmentNumberNameComponent(0));
	
		t4 = new CCNTime();
		four = SegmentationProfile.segmentName(VersioningProfile.addVersion(baseName, t4), 0);
		obj4 = ContentObject.buildContentObject(four, "here is version 4".getBytes(), null, null, SegmentationProfile.getSegmentNumberNameComponent(0));
	
		skipTime = new CCNTime();
		skipSegment = SegmentationProfile.segmentName(VersioningProfile.addVersion(baseName, skipTime), 5);
		objSkip = ContentObject.buildContentObject(skipSegment, "here is skip".getBytes(), null, null, SegmentationProfile.getSegmentNumberNameComponent(5));
		skipSegment0 = SegmentationProfile.segmentName(VersioningProfile.addVersion(baseName, skipTime), 0);
		objSkip0 = ContentObject.buildContentObject(skipSegment0, "here is skip".getBytes(), null, null, SegmentationProfile.getSegmentNumberNameComponent(5));
	
		skipTime2 = new CCNTime();
		skipSegment2 = SegmentationProfile.segmentName(VersioningProfile.addVersion(baseName, skipTime2), 5);
		objSkip2 = ContentObject.buildContentObject(skipSegment2, "here is skip 2".getBytes(), null, null, SegmentationProfile.getSegmentNumberNameComponent(5));
		
		
		System.out.println("made versions: "+one +" "+two+" "+three+" "+four);
		
		try {
			//putHandle.put(obj1);
			//putHandle.put(obj2);
			responderHandle.put(obj1);
			responderHandle.put(obj2);
			
			object = VersioningProfile.getLatestVersion(baseName, null, timeout, getHandle.defaultVerifier(), getHandle); 
			
			Assert.assertTrue(VersioningProfile.getLastVersionAsLong(object.name()) == VersioningProfile.getLastVersionAsLong(two));
			System.out.println("passed test for getLatestVersion with 2 versions available");
			
			object = VersioningProfile.getFirstBlockOfLatestVersion(baseName, null, null, timeout, getHandle.defaultVerifier(), getHandle);
			Assert.assertTrue(VersioningProfile.getLastVersionAsLong(object.name()) == VersioningProfile.getLastVersionAsLong(two));
			System.out.println("passed test for getFirstBlockOfLatestVersion with 2 versions available");
			
		} catch (IOException e) {
			Assert.fail("Failed to get latest version: "+e.getMessage());
		} catch (VersionMissingException e) {
			Assert.fail("Failed to get version from object: "+e.getMessage());
		}
		

		responseObjects.add(obj3);
		System.out.println("added: "+obj3.name());
		
		//now put third version
		try {
			System.out.println("calling gLV at: "+System.currentTimeMillis());			
			object = VersioningProfile.getLatestVersion(baseName, null, timeout, getHandle.defaultVerifier(), getHandle);
			System.out.println("got: "+object.name());
			System.out.println("expecting to get: "+three);
			/*
			try {
				System.out.println("go to sleep");
				Thread.sleep(5000);
				System.out.println("wakeup");
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			*/
			Assert.assertTrue(VersioningProfile.getLastVersionAsLong(object.name()) == VersioningProfile.getLastVersionAsLong(three));
			System.out.println("passed test for getLatestVersion with 3 versions available");
			
			object = VersioningProfile.getFirstBlockOfLatestVersion(baseName, null, null, timeout, getHandle.defaultVerifier(), getHandle);
			Assert.assertTrue(VersioningProfile.getLastVersionAsLong(object.name()) == VersioningProfile.getLastVersionAsLong(three));
			System.out.println("passed test for getFirstBlockOfLatestVersion with 3 versions available");
						
		} catch (VersionMissingException e) {
			Assert.fail("Failed to get version from object: "+e.getMessage());
		
		} catch (IOException e) {
			Assert.fail("Failed to get latest version: "+e.getMessage());
		}
		
		
		//now make sure we can get the latest version with an explicit request for
		//something after version 2
		try {
			object = VersioningProfile.getLatestVersion(two, null, timeout, getHandle.defaultVerifier(), getHandle); 
			
			Assert.assertTrue(VersioningProfile.getLastVersionAsLong(object.name()) == VersioningProfile.getLastVersionAsLong(three));
		} catch (IOException e) {
			Assert.fail("Failed to get latest version: "+e.getMessage());
		} catch (VersionMissingException e) {
			Assert.fail("Failed to get version from object: "+e.getMessage());
		}
		
		
		//now check to make sure when we ask for a later version (that does not exist) that we don't wait too long.
		
		//first wait for 5 seconds, then 200ms
		try {
			long doneTime;
			long checkTime = System.currentTimeMillis();
			object = VersioningProfile.getFirstBlockOfLatestVersion(three, null, null, 5000, getHandle.defaultVerifier(), getHandle);
			doneTime = System.currentTimeMillis();
			System.out.println("took us "+(doneTime - checkTime)+"ms to get nothing back "+" check: "+checkTime+" done: "+doneTime);
			Assert.assertNull(object);
			Assert.assertTrue((doneTime - checkTime) < 5500 && (doneTime - checkTime) > 5000);
			System.out.println("passed test for waiting 5 seconds");
			
			checkTime = System.currentTimeMillis();
			object = VersioningProfile.getFirstBlockOfLatestVersion(three, null, null, 200, getHandle.defaultVerifier(), getHandle);
			checkTime = System.currentTimeMillis() - checkTime;
			System.out.println("took us "+checkTime+"ms to get nothing back");
			Assert.assertNull(object);
			Assert.assertTrue(checkTime > 200 && checkTime < 300);
			System.out.println("passed test for waiting 200ms");
			
		} catch (IOException e) {
			Assert.fail("failed to test with different timeouts: "+e.getMessage());
		}
		
		
		responseObjects.add(obj4);
		
		//add a fourth responder and make sure we don't get it back.
		try {
			//Thread thread4 = setUpResponder(obj4);
			lastVersionPublished = obj4;
			
			object = VersioningProfile.getFirstBlockOfLatestVersion(baseName, null, null, 0, getHandle.defaultVerifier(), getHandle);
			Assert.assertTrue(VersioningProfile.getLastVersionAsLong(object.name()) == VersioningProfile.getLastVersionAsLong(three));
			System.out.println("passed test for timeout 0 test");
			
			object = VersioningProfile.getFirstBlockOfLatestVersion(baseName, null, null, 0, getHandle.defaultVerifier(), getHandle);
			Assert.assertTrue(VersioningProfile.getLastVersionAsLong(object.name()) == VersioningProfile.getLastVersionAsLong(three));
			System.out.println("passed test for timeout 0 test");
			
			//stopResponder(obj4, thread4);
			
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
			
			Assert.assertTrue(VersioningProfile.getLastVersionAsLong(object.name()) == VersioningProfile.getLastVersionAsLong(four));
		} catch (IOException e) {
			Assert.fail("Failed to get latest version: "+e.getMessage());
		} catch (VersionMissingException e) {
			Assert.fail("Failed to get version from object: "+e.getMessage());
		}
		
		
	
		//test for getting the first segment of a version that does not have a first segment
		//put a later segment of a later version and make sure it comes back with null or an earlier version
		
		try {
			System.out.println("=========testing skip segment!");
			
			//putHandle.put(objSkip);
			responderHandle.put(objSkip);
			
			object = VersioningProfile.getLatestVersion(baseName, null, timeout, getHandle.defaultVerifier(), getHandle); 
			
			Assert.assertTrue(VersioningProfile.getLastVersionAsLong(object.name()) == VersioningProfile.getLastVersionAsLong(skipSegment));
			System.out.println("passed test for getLatestVersion with skipped segment available");
			
			System.out.println("adding: "+objSkip0.name());
			responseObjects.add(objSkip0);
			System.out.println("adding: "+objSkip2.name());
			responseObjects.add(objSkip2);
			
			object = VersioningProfile.getFirstBlockOfLatestVersion(baseName, null, null, timeout, getHandle.defaultVerifier(), getHandle);
			Assert.assertTrue(VersioningProfile.getLastVersionAsLong(object.name()) == VersioningProfile.getLastVersionAsLong(skipSegment0));
			System.out.println("passed test for getFirstBlockOfLatestVersion with skipped segment available");
			
		} catch (IOException e) {
			Assert.fail("Failed to get latest version: "+e.getMessage());
		} catch (VersionMissingException e) {
			Assert.fail("Failed to get version from object: "+e.getMessage());
		}
		
		//now put 15 responses in....
		for(int i = 0; i < VersioningProfile.GET_LATEST_VERSION_ATTEMPTS + 5; i++)
			responseObjects.add(ContentObject.buildContentObject(SegmentationProfile.segmentName(VersioningProfile.addVersion(baseName), 0), "here is version generated".getBytes(), null, null, SegmentationProfile.getSegmentNumberNameComponent(0)));
		
		lastVersionPublished = responseObjects.get(responseObjects.size()-1);
		
		//test for sending in a null timeout
		try {
			
			//reset attempts
			attempts = 0;
			
			object = VersioningProfile.getLatestVersion(baseName, null, SystemConfiguration.NO_TIMEOUT, getHandle.defaultVerifier(), getHandle);
			System.out.println("got back :"+object.name() + " responder count: "+attempts);
			Assert.assertTrue(object.name().equals(lastVersionPublished.name()));
			//Assert.assertTrue(attempts == VersioningProfile.GET_LATEST_VERSION_ATTEMPTS + 5);
			System.out.println("passed test for no timeout");
			
			//reset attempts
			attempts = 0;
			for(int i =0; i < VersioningProfile.GET_LATEST_VERSION_ATTEMPTS; i++)
				responseObjects.add(ContentObject.buildContentObject(SegmentationProfile.segmentName(VersioningProfile.addVersion(baseName), 0), "here is version generated".getBytes(), null, null, SegmentationProfile.getSegmentNumberNameComponent(0)));
			
			lastVersionPublished = responseObjects.get(responseObjects.size()-1);
			
			object = VersioningProfile.getFirstBlockOfLatestVersion(baseName, null, null, SystemConfiguration.NO_TIMEOUT, getHandle.defaultVerifier(), getHandle);
			Assert.assertTrue(object.name().equals(lastVersionPublished.name()));
			//Assert.assertTrue(attempts == VersioningProfile.GET_LATEST_VERSION_ATTEMPTS + 5);
			System.out.println("passed test for no timeout");
			
		} catch (IOException e) {
			Assert.fail("failed to test with no timeout: "+e.getMessage());
		}
		
		//TODO add test for something that fails to verify
		ContentVerifier ver = new TestVerifier(); 
		
		//have the verifier fail the newest object make sure we get back the most recent verified version
		//responseObjects.add(obj3);
		//System.out.println("added: "+obj3.name());
		failVerify = ContentObject.buildContentObject(SegmentationProfile.segmentName(VersioningProfile.addVersion(baseName), 0), "here is failVerify".getBytes(), null, null, SegmentationProfile.getSegmentNumberNameComponent(0));
		responseObjects.add(failVerify);
		
		//now put a unverifiable version
		try {
						
			object = VersioningProfile.getLatestVersion(baseName, null, timeout, ver, getHandle);
			System.out.println("got: "+object.name());
			System.out.println("expecting to get: "+lastVersionPublished.name());
			Assert.assertTrue(VersioningProfile.getLastVersionAsLong(object.name()) == VersioningProfile.getLastVersionAsLong(lastVersionPublished.name()));
			System.out.println("passed test for failed verification");
			
			object = VersioningProfile.getFirstBlockOfLatestVersion(baseName, null, null, timeout, ver, getHandle);
			System.out.println("got: "+object.name());
			System.out.println("expecting to get: "+lastVersionPublished.name());
			Assert.assertTrue(VersioningProfile.getLastVersionAsLong(object.name()) == VersioningProfile.getLastVersionAsLong(lastVersionPublished.name()));
			System.out.println("passed test for getFirstBlockOfLatestVersion failed verification");

		
		} catch (VersionMissingException e) {
			Assert.fail("Failed to get version from object: "+e.getMessage());
		
		} catch (IOException e) {
			Assert.fail("Failed to get latest version: "+e.getMessage());
		}
		
		

		
		//then call again and make sure we have a newer version that passes

		//have the verifier fail the newest object make sure we get back the most recent verified version
		//responseObjects.add(obj3);
		//System.out.println("added: "+obj3.name());
		ContentObject verify = ContentObject.buildContentObject(SegmentationProfile.segmentName(VersioningProfile.addVersion(baseName), 0), "here is verify".getBytes(), null, null, SegmentationProfile.getSegmentNumberNameComponent(0));
		responseObjects.add(verify);
		
		//now put a verifiable version
		try {
						
			object = VersioningProfile.getLatestVersion(baseName, null, timeout, ver, getHandle);
			System.out.println("got: "+object.name());
			System.out.println("expecting to get: "+verify.name());
			Assert.assertTrue(VersioningProfile.getLastVersionAsLong(object.name()) == VersioningProfile.getLastVersionAsLong(verify.name()));
			System.out.println("passed test for failed verification with newer version available");
			
			object = VersioningProfile.getFirstBlockOfLatestVersion(baseName, null, null, timeout, ver, getHandle);
			Assert.assertTrue(VersioningProfile.getLastVersionAsLong(object.name()) == VersioningProfile.getLastVersionAsLong(verify.name()));
			System.out.println("passed test for getFirstBlockOfLatestVersion failed verification with newer version available");
		
		} catch (VersionMissingException e) {
			Assert.fail("Failed to get version from object: "+e.getMessage());
		
		} catch (IOException e) {
			Assert.fail("Failed to get latest version: "+e.getMessage());
		}
		
		
		
		
		//TODO  add another test that also has a content object that fails to verify (maybe do 2)
		//and then also add one that does verify - same version.  make sure we get the verifiable one back
		
		ContentObject failVerify1 = ContentObject.buildContentObject(SegmentationProfile.segmentName(VersioningProfile.addVersion(baseName), 0), "here is failVerify".getBytes(), null, null, SegmentationProfile.getSegmentNumberNameComponent(0));
		responseObjects.add(failVerify1);

		long versionToAdd = System.currentTimeMillis();
		try {
			versionToAdd = VersioningProfile.getLastVersionAsLong(failVerify1.name());
		} catch (VersionMissingException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}
		
		ContentObject failVerify2 = ContentObject.buildContentObject(SegmentationProfile.segmentName(VersioningProfile.addVersion(baseName, versionToAdd), 0), "here is a second failVerify".getBytes(), null, null, SegmentationProfile.getSegmentNumberNameComponent(0));
		responseObjects.add(failVerify2);
		
		ContentObject failVerify3 = ContentObject.buildContentObject(SegmentationProfile.segmentName(VersioningProfile.addVersion(baseName, versionToAdd), 0), "here is a third, but it should pass".getBytes(), null, null, SegmentationProfile.getSegmentNumberNameComponent(0));
		responseObjects.add(failVerify3);
	
		System.out.println("failVerify1*: "+failVerify1.fullName());
		System.out.println("failVerify2*: "+failVerify2.fullName());
		System.out.println("failVerify3: "+failVerify3.fullName());
		
		try {
			
			object = VersioningProfile.getLatestVersion(baseName, null, timeout, ver, getHandle);
			System.out.println("got: "+object.fullName());
			System.out.println("expecting to get: "+failVerify3.fullName());
			Assert.assertTrue(VersioningProfile.getLastVersionAsLong(object.fullName()) == VersioningProfile.getLastVersionAsLong(failVerify3.fullName()));
			System.out.println("passed test for failed verification with multiple failures and a success");
			
			object = VersioningProfile.getFirstBlockOfLatestVersion(baseName, null, null, timeout, ver, getHandle);
			System.out.println("got: "+object.fullName());
			System.out.println("expecting to get: "+failVerify3.fullName());
			Assert.assertTrue(VersioningProfile.getLastVersionAsLong(object.fullName()) == VersioningProfile.getLastVersionAsLong(failVerify3.fullName()));
			System.out.println("passed test for getFirstBlockOfLatestVersion failed verification with multiple failures and a success");

		
		} catch (VersionMissingException e) {
			Assert.fail("Failed to get version from object: "+e.getMessage());
		
		} catch (IOException e) {
			Assert.fail("Failed to get latest version: "+e.getMessage());
		}
		
		
		
	}
	
	
	/**
	 * Method to set up a responder for the get latest version test.  Only responds to Interests
	 * with a single ContentObject.
	 * 
	 * @param obj ContentObject to respond to Interests with.
	 * @throws IOException 
	 */
	private Thread setUpResponder() throws IOException {
		attempts = 0;
		
		Thread t = new Thread(new Responder());
		t.run();
		
		return t;
	}

	private void checkResponder() {
		try {
			ContentName test = ContentName.fromNative(baseName, "testResponder");
			ContentObject co = ContentObject.buildContentObject(SegmentationProfile.segmentName(VersioningProfile.addVersion(test), 0), "test content responder".getBytes(), null, null, SegmentationProfile.getSegmentNumberNameComponent(0));
			responseObjects.add(co);
			Interest i = new Interest(co.name());
			ContentObject co2 = getHandle.get(i, 5000 );
			if (co2 == null) {
				System.out.println("test responder object is null, failed to start responder in 5 seconds");
				Assert.fail("test responder did not start up in 5 seconds.");
			}
			Assert.assertTrue(co2.fullName().equals(co.fullName()));
			System.out.println("Responder is up and ready!");
		} catch (IOException e) {
			Assert.fail("could not get test responder object: "+e.getMessage());
		}
	}
	
	/**
	 * Runnable class for the single ContentObject responder.
	 */
	class Responder implements Runnable, CCNFilterListener {
		CCNHandle handle;

		public Responder() throws IOException {
			try {
				handle = CCNHandle.open();
				LatestVersionTest.responderHandle = handle;
			} catch (Exception e) {
				Assert.fail("could not create handle for responder: " + e.getMessage());
			}

			handle.registerFilter(baseName, this);
		}

		public void run() {
		}

		public int handleInterests(ArrayList<Interest> interests) {
			System.out.println(System.currentTimeMillis()+ " handling interest "+ interests.get(0).name());
			
			for (Interest i : interests) {
				if(responseObjects.size() == 0) {
					System.out.println("responseObjects size == 0");
					return 0;
				}
				
				if (i.matches(responseObjects.get(0))) {
					try {
						System.out.println("returning: "+ responseObjects.get(0).fullName());
						//LatestVersionTest.attempts++;
						handle.put(responseObjects.remove(0));
					} catch (IOException e) {
						Assert.fail("could not put object in responder");
					}
				} else {
					System.out.println("didn't have a match with: "+responseObjects.get(0).fullName());
					System.out.println("full interest: "+i.toString());
				}
			}
			return 0;
		}

	}
	
	class TestVerifier implements ContentVerifier{

		public boolean verify(ContentObject content) {
			ContentName contentName = content.fullName();
			//if(content.name().equals(LatestVersionTest.failVerify.name()))
			if ( failVerify != null ) {
				if (contentName.equals(failVerify.fullName()))
					return false;
			}
			
			if ( failVerify2 != null ) {
				if (contentName.equals(failVerify1.fullName()) || contentName.equals(failVerify2.fullName()))
					return false;
			}
			
			return LatestVersionTest.getHandle.defaultVerifier().verify(content);
		}
		
	}

}
