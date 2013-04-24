/*
 * A CCNx library test.
 *
 * Copyright (C) 2010-2012 Palo Alto Research Center, Inc.
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

import junit.framework.Assert;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.profiles.SegmentationProfile;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.protocol.CCNTime;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;


/**
 * This set of tests checks the implementation of getLastSegment with ccnd involved.
 */
public class LastSegmentTest {

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
		baseName = ContentName.fromURI("/lastSegmentTest");
	}
	
	@After
	public void tearDown() {
		handle.close();
		putHandle.close();
	}
	
	/**
	 * Creates an interest for the last segment of content that does not exist so we can test the timeout.
	 */
	@Test
	public void lastSegmentTestCheckTimeout(){
		Log.info(Log.FAC_TEST, "Starting lastSegmentTestCheckTimeout");
		
		//first, attempt to get a segment for something that isn't there...  should time out
		
		ContentName timeoutName = null;
		ContentObject object = null;
		
		long timeout = 2000;
		
		long starttime = System.currentTimeMillis();
		long stoptime;
		try {
			timeoutName = ContentName.fromURI("/ccnx.org/test/segmentationProfile/baseName/checkTimeout");
		} catch (MalformedContentNameStringException e) {
			Assert.fail("could not create ContentName for getLastSegment test");
		}
		timeoutName = VersioningProfile.addVersion(timeoutName);
		try {
			object = SegmentationProfile.getLastSegment(timeoutName, null, timeout, handle.defaultVerifier(), handle);
		} catch (IOException e) {
			Assert.fail("Failed to get last segment: "+e.getMessage());
		}
		stoptime = System.currentTimeMillis();
	
		Assert.assertNull(object);
		
		Assert.assertTrue("Returned too soon in : " + (stoptime - starttime) + " ms", stoptime - starttime >=  timeout);
		
		if (stoptime - starttime > 2*timeout) {
			Log.warning("lastSegmentTimeoutTest was more than twice the timeout length");
		}
		
		Log.info(Log.FAC_TEST, "Completed lastSegmentTestCheckTimeout");
	}
	
	
	/**
	 * Test to verify the segment is the last one
	 */
	@Test
	public void testIsLastSegment(){
		Log.info(Log.FAC_TEST, "Starting testIsLastSegment");
		
		//test to make sure the last segment check is correct
		
		ContentName name = null;
		try {
			name = ContentName.fromURI("/ccnx.org/test/segmentationProfile/testIsLastSegment/");
			name = VersioningProfile.addVersion(name);
			name = SegmentationProfile.segmentName(name, SegmentationProfile.baseSegment());
		} catch (MalformedContentNameStringException e) {
			Assert.fail("could not create ContentName for test");
		}
		ContentObject obj = ContentObject.buildContentObject(name, "here is a segment".getBytes(), null, null, SegmentationProfile.FIRST_SEGMENT_MARKER);
		
		Assert.assertTrue(SegmentationProfile.isLastSegment(obj));
		
		Log.info(Log.FAC_TEST, "Completed testIsLastSegment");	
	}
	
	/**
	 * Test to verify the segment is not the last one
	 */
	@Test
	public void testIsNotLastSegment(){
		Log.info(Log.FAC_TEST, "Starting testIsNotLastSegment");
		
		//test to make sure the last segment check is correct
		
		ContentName name = null;
		try {
			name = ContentName.fromURI("/ccnx.org/test/segmentationProfile/testIsNotLastSegment/");
			name = VersioningProfile.addVersion(name);
			name = SegmentationProfile.segmentName(name, 3);
		} catch (MalformedContentNameStringException e) {
			Assert.fail("could not create ContentName for test");
		}
		ContentObject obj = ContentObject.buildContentObject(name, "here is a segment".getBytes(), null, null, SegmentationProfile.getSegmentNumberNameComponent(4));
		
		Assert.assertFalse(SegmentationProfile.isLastSegment(obj));
		
		Log.info(Log.FAC_TEST, "Completed testIsNotLastSegment");
	}
	
	/**
	 * Test to verify that if we put something that is two segments long, we get the second, and last
	 * segment back - unversioned.
	 */
	@Test
	public void testGetLastSegmentWithMultipleSegmentsUnversioned() {
		Log.info(Log.FAC_TEST, "Starting testGetLastSegmentWithMultipleSegmentsUnversioned");

		ContentName one = null;
		ContentName last = null;
		ContentName name = null;
		
		ContentObject obj0 = null;
		ContentObject obj2 = null;
		ContentObject object = null;
		
		long timeout = 5000;
		
		try {
			name = ContentName.fromURI("/ccnx.org/test/segmentationProfile/"+(new CCNTime()).toShortString()+"/testGetLastMultiUnversioned/");
			one = name;
			last = one;
			one = SegmentationProfile.segmentName(one, SegmentationProfile.baseSegment());
			last = SegmentationProfile.segmentName(last, 2);
		} catch (MalformedContentNameStringException e) {
			Assert.fail("could not create ContentName for test");
		}
		
		obj0 = ContentObject.buildContentObject(one, "here is segment 0".getBytes(), null, null, SegmentationProfile.getSegmentNumberNameComponent(2));
		obj2 = ContentObject.buildContentObject(last, "here is segment 2".getBytes(), null, null, SegmentationProfile.getSegmentNumberNameComponent(2));
		
		
		try {
			putHandle.put(obj0);
			putHandle.put(obj2);
			
			object = SegmentationProfile.getLastSegment(name, null, timeout, handle.defaultVerifier(), handle);
			
			Assert.assertTrue(obj2.equals(object));
		} catch (IOException e) {
			Assert.fail("Failed to get last segment: "+e.getMessage());
		}
		
		Log.info(Log.FAC_TEST, "Completed testGetLastSegmentWithMultipleSegmentsUnversioned");
	}
	
	/**
	 * Test to verify that if we put something that is two segments long, we get the second, and last
	 * segment back - versioned.
	 */
	@Test
	public void testGetLastSegmentWithMultipleSegmentsVersioned() {
		Log.info(Log.FAC_TEST, "Starting testGetLastSegmentWithMultipleSegmentsVersioned");

		ContentName one = null;
		ContentName last = null;
		ContentName name = null;
		
		ContentObject obj0 = null;
		ContentObject obj2 = null;
		ContentObject object = null;
		
		long timeout = 5000;
		
		try {
			name = ContentName.fromURI("/ccnx.org/test/segmentationProfile/"+(new CCNTime()).toShortString()+"/testGetLastMultiVersioned/");
			name = VersioningProfile.addVersion(name);
			one = name;
			last = one;
			one = SegmentationProfile.segmentName(one, SegmentationProfile.baseSegment());
			last = SegmentationProfile.segmentName(last, 2);
		} catch (MalformedContentNameStringException e) {
			Assert.fail("could not create ContentName for test");
		}
		
		obj0 = ContentObject.buildContentObject(one, "here is segment 0".getBytes(), null, null, SegmentationProfile.getSegmentNumberNameComponent(2));
		obj2 = ContentObject.buildContentObject(last, "here is segment 2".getBytes(), null, null, SegmentationProfile.getSegmentNumberNameComponent(2));
		
		try {
			putHandle.put(obj0);
			putHandle.put(obj2);
			
			object = SegmentationProfile.getLastSegment(name, null, timeout, handle.defaultVerifier(), handle);
		
			Assert.assertTrue(obj2.equals(object));
			
		} catch (IOException e) {
			Assert.fail("Failed to get last segment: "+e.getMessage());
		}
		
		Log.info(Log.FAC_TEST, "Completed testGetLastSegmentWithMultipleSegmentsVersioned");
	}
		
				
	/**
	 * Test to verify that if we put something that is one segment long, we get it back - versioned.
	 */
	@Test
	public void testGetLastSegmentWithSingleSegmentVersioned() {
		Log.info(Log.FAC_TEST, "Starting testGetLastSegmentWithSingleSegmentVersioned");

		ContentName one = null;
		ContentName name = null;
		
		ContentObject obj0 = null;
		ContentObject object = null;
		
		long timeout = 5000;
		
		try {
			name = ContentName.fromURI("/ccnx.org/test/segmentationProfile/"+(new CCNTime()).toShortString()+"/testGetLastSingleVersioned/");
			name = VersioningProfile.addVersion(name);
			one = name;
			one = SegmentationProfile.segmentName(one, SegmentationProfile.baseSegment());
		} catch (MalformedContentNameStringException e) {
			Assert.fail("could not create ContentName for test");
		}
		
		obj0 = ContentObject.buildContentObject(one, "here is segment 0".getBytes(), null, null, SegmentationProfile.getSegmentNumberNameComponent(SegmentationProfile.baseSegment()));
		
		try {
			putHandle.put(obj0);
			
			object = SegmentationProfile.getLastSegment(name, null, timeout, handle.defaultVerifier(), handle);
			
			Assert.assertTrue(obj0.equals(object));
			
		} catch (IOException e) {
			Assert.fail("Failed to get last segment: "+e.getMessage());
		}
		
		Log.info(Log.FAC_TEST, "Completed testGetLastSegmentWithSingleSegmentVersioned");
	}
}
