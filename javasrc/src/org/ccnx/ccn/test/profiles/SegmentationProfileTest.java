package org.ccnx.ccn.test.profiles;

import junit.framework.Assert;

import org.ccnx.ccn.profiles.SegmentationProfile;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.Interest;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

public class SegmentationProfileTest {

	/**
	 * @throws java.lang.Exception
	 */
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
	}

	/**
	 * @throws java.lang.Exception
	 */
	@AfterClass
	public static void tearDownAfterClass() throws Exception {
	}

	/**
	 * @throws java.lang.Exception
	 */
	@Before
	public void setUp() throws Exception {
	}

	/**
	 * @throws java.lang.Exception
	 */
	@After
	public void tearDown() throws Exception {
	}
	
	/**
	 * Test method for creating an interest for a specific segment
	 */
	@Test
	public void testSegmentInterest() {
		ContentName name = null;
		ContentName segmentName = null;
		ContentName nextSegmentName = null;
		ContentName longerName = null;
		Interest interest = null;
		long segmentNumber = 27;
		long nextSegmentNumber = 28;
		
		try {
			name = ContentName.fromURI("/ccnx.org/test/segmentationProfile/");
			segmentName = SegmentationProfile.segmentName(name, segmentNumber);
			nextSegmentName = SegmentationProfile.segmentName(name, nextSegmentNumber);
			longerName = SegmentationProfile.segmentRoot(segmentName);
		} catch (MalformedContentNameStringException e) {
			Assert.fail("could not create ContentName for test");
		}
		
		interest = SegmentationProfile.segmentInterest(name, segmentNumber, null);
		
		Assert.assertTrue(interest.name().equals(segmentName));
		Assert.assertFalse(interest.name().equals(nextSegmentName));
		
		Assert.assertTrue(interest.matches(segmentName, null));
		Assert.assertFalse(interest.matches(name, null));
		Assert.assertFalse(interest.matches(nextSegmentName, null));
		Assert.assertFalse(interest.matches(longerName, null));
	}
	
	/**
	 * Test method for creating an interest with baseSegment
	 */
	@Test
	public void testSegmentInterestWithNullSegmentNumber() {
		ContentName name = null;
		ContentName segmentName = null;
		ContentName nextSegmentName = null;
		ContentName longerName = null;
		Interest interest = null;
		long nextSegmentNumber = 1;
		
		try {
			name = ContentName.fromURI("/ccnx.org/test/segmentationProfile/");
			segmentName = SegmentationProfile.segmentName(name, SegmentationProfile.baseSegment());
			nextSegmentName = SegmentationProfile.segmentName(name, nextSegmentNumber);
			longerName = SegmentationProfile.segmentRoot(segmentName);
		} catch (MalformedContentNameStringException e) {
			Assert.fail("could not create ContentName for test");
		}
		
		interest = SegmentationProfile.segmentInterest(name, null, null);
		
		Assert.assertTrue(interest.name().equals(segmentName));
		Assert.assertFalse(interest.name().equals(nextSegmentName));
		
		Assert.assertTrue(interest.matches(segmentName, null));
		Assert.assertFalse(interest.matches(name, null));
		Assert.assertFalse(interest.matches(nextSegmentName, null));
		Assert.assertFalse(interest.matches(longerName, null));
	}
	
	/**
	 * Test first segment creation in SegmentationProfile
	 */
	@Test
	public void testFirstSegmentInterest(){
		ContentName name = null;
		ContentName segmentName = null;
		ContentName nextSegmentName = null;
		ContentName longerName = null;
		Interest interest = null;
		long nextSegmentNumber = 1;
		
		try {
			name = ContentName.fromURI("/ccnx.org/test/segmentationProfile/");
			segmentName = SegmentationProfile.segmentName(name, SegmentationProfile.baseSegment());
			nextSegmentName = SegmentationProfile.segmentName(name, nextSegmentNumber);
			longerName = SegmentationProfile.segmentRoot(segmentName);
		} catch (MalformedContentNameStringException e) {
			Assert.fail("could not create ContentName for test");
		}
		
		interest = SegmentationProfile.firstSegmentInterest(name, null);
		
		//System.out.println("segmentName: "+segmentName+" interestName: "+interest.name());
		
		Assert.assertTrue(interest.name().equals(segmentName));
		Assert.assertFalse(interest.name().equals(nextSegmentName));
		
		Assert.assertTrue(interest.matches(segmentName, null));
		Assert.assertFalse(interest.matches(name, null));
		Assert.assertFalse(interest.matches(nextSegmentName, null));
		Assert.assertFalse(interest.matches(longerName, null));
	}
	
	
}
