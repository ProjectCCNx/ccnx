/*
 * A CCNx library test.
 *
 * Copyright (C) 2008, 2009, 2011, 2013 Palo Alto Research Center, Inc.
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

package org.ccnx.ccn.profiles;
/*
 * A CCNx library test.
 *
 * Copyright (C) 2013 Palo Alto Research Center, Inc.
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

import junit.framework.Assert;

import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.Interest;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;
import org.junit.Test;

public class SegmentationProfileUnitTest {
	
	/**
	 * Test method for creating an interest for a specific segment
	 */
	@Test
	public void testSegmentInterest() {
		Log.info(Log.FAC_TEST, "Starting testSegmentInterest");

		ContentName name = null;
		ContentName segmentName = null;
		ContentName nextSegmentName = null;
		ContentName longerName = null;
		ContentName nameEndingWithSegment = null;
		Interest interest = null;
		long segmentNumber = 27;
		long nextSegmentNumber = 28;
		
		try {
			name = ContentName.fromURI("/ccnx.org/test/segmentationProfile/");
			segmentName = SegmentationProfile.segmentName(name, segmentNumber);
			nextSegmentName = SegmentationProfile.segmentName(name, nextSegmentNumber);
			longerName = SegmentationProfile.segmentName(segmentName, nextSegmentNumber);
			nameEndingWithSegment = SegmentationProfile.segmentName(name, nextSegmentNumber+1);
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
		
		interest = SegmentationProfile.segmentInterest(nameEndingWithSegment, segmentNumber, null);
		
		Assert.assertTrue(interest.name().equals(segmentName));
		Assert.assertFalse(interest.name().equals(nextSegmentName));
		
		Assert.assertTrue(interest.matches(segmentName, null));
		Assert.assertFalse(interest.matches(name, null));
		Assert.assertFalse(interest.matches(nextSegmentName, null));
		Assert.assertFalse(interest.matches(longerName, null));
		
		Log.info(Log.FAC_TEST, "Completed testSegmentInterest");		
	}
	
	/**
	 * Test method for creating an interest with baseSegment
	 */
	@Test
	public void testSegmentInterestWithNullSegmentNumber() {
		Log.info(Log.FAC_TEST, "Starting testSegmentInterestWithNullSegmentNumber");

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
			longerName = SegmentationProfile.segmentName(segmentName, nextSegmentNumber);
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
		
		Log.info(Log.FAC_TEST, "Completed testSegmentInterestWithNullSegmentNumber");
	}
	
	/**
	 * Test first segment creation in SegmentationProfile
	 */
	@Test
	public void testFirstSegmentInterest(){
		Log.info(Log.FAC_TEST, "Starting testFirstSegmentInterest");

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
			longerName = SegmentationProfile.segmentName(segmentName, nextSegmentNumber);
		} catch (MalformedContentNameStringException e) {
			Assert.fail("could not create ContentName for test");
		}
		
		interest = SegmentationProfile.firstSegmentInterest(name, null);
			
		Assert.assertTrue(interest.name().equals(segmentName));
		Assert.assertFalse(interest.name().equals(nextSegmentName));
		
		Assert.assertTrue(interest.matches(segmentName, null));
		Assert.assertFalse(interest.matches(name, null));
		Assert.assertFalse(interest.matches(nextSegmentName, null));
		Assert.assertFalse(interest.matches(longerName, null));
		
		Log.info(Log.FAC_TEST, "Completed testFirstSegmentInterest");
	}
	
	//create a test that makes sure match is true for last on segment where final block id is set and another that fails for an earlier block
	/**
	 * Test to create Interest for last segment.
	 */
	@Test
	public void testLastSegmentInterest(){
		Log.info(Log.FAC_TEST, "Starting testLastSegmentInterest");

		ContentName name = null;
		ContentName segmentName = null;
		ContentName nextSegmentName = null;
		ContentName previousSegmentName = null;
		Interest interest = null;
		long segmentNumber = 27;
		long nextSegmentNumber = 28;
		long previousSegmentNumber = 26;
		
		try {
			name = ContentName.fromURI("/ccnx.org/test/segmentationProfile/");
			segmentName = SegmentationProfile.segmentName(name, segmentNumber);
			nextSegmentName = SegmentationProfile.segmentName(name, nextSegmentNumber);
			previousSegmentName = SegmentationProfile.segmentName(name, previousSegmentNumber);
		} catch (MalformedContentNameStringException e) {
			Assert.fail("could not create ContentName for test");
		}
		
		//create an interest with a segment number
		interest = SegmentationProfile.lastSegmentInterest(segmentName, null);
		
		Assert.assertTrue(interest.matches(nextSegmentName, null));
		Assert.assertFalse(interest.matches(segmentName, null));
		Assert.assertFalse(interest.matches(previousSegmentName, null));
		
		//create an interest with a segment number for a name that already has a segment number
		interest = SegmentationProfile.lastSegmentInterest(segmentName, segmentNumber, null);
		
		Assert.assertTrue(interest.matches(nextSegmentName, null));
		Assert.assertFalse(interest.matches(segmentName, null));
		Assert.assertFalse(interest.matches(previousSegmentName, null));
		
		//create an interest with a segment number for a name that already has a lower segment number
		interest = SegmentationProfile.lastSegmentInterest(previousSegmentName, segmentNumber, null);
		
		Assert.assertTrue(interest.matches(nextSegmentName, null));
		Assert.assertFalse(interest.matches(segmentName, null));
		Assert.assertFalse(interest.matches(previousSegmentName, null));
		
		//create an interest with a segment number for a name that is lower than the segment already in the name
		interest = SegmentationProfile.lastSegmentInterest(segmentName, previousSegmentNumber, null);
		
		Assert.assertTrue(interest.matches(nextSegmentName, null));
		Assert.assertFalse(interest.matches(segmentName, null));
		Assert.assertFalse(interest.matches(previousSegmentName, null));

		
		//create an interest without a segment number (should just be base segment)
		interest = SegmentationProfile.lastSegmentInterest(name, null);
		
		Assert.assertTrue(interest.matches(nextSegmentName, null));
		Assert.assertTrue(interest.matches(segmentName, null));
		Assert.assertTrue(interest.matches(previousSegmentName, null));
		
		Log.info(Log.FAC_TEST, "Completed testLastSegmentInterest");	
	}	
}
