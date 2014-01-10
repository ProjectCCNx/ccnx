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

import java.io.IOException;

import junit.framework.Assert;

import org.ccnx.ccn.CCNInterestHandler;
import org.ccnx.ccn.ContentVerifier;
import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.profiles.SegmentationProfile;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Interest;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;
import org.ccnx.ccn.LibraryTestBase;
import org.junit.Test;

public class SegmentationProfileTest extends LibraryTestBase implements CCNInterestHandler{

	private ContentObject failVerify = null;
	private ContentObject noVerify = null;
	private ContentObject verify = null;

	/**
	 * Test to create Interest for last segment.
	 */
	@Test
	public void testRetryWithFailedVerification(){
		Log.info(Log.FAC_TEST, "Starting testRetryWithFailedVerification");

		String _prefix = "/SegmentationProfileTest/test-" + rand.nextInt(10000);
		ContentName cname = null;
		try {
			cname = ContentName.fromNative(_prefix +"/noVerifyRetry");
		} catch (MalformedContentNameStringException e) {
			Assert.fail("Failed to create test ContentName");
		}
		
		noVerify = ContentObject.buildContentObject(SegmentationProfile.segmentName(cname, SegmentationProfile.BASE_SEGMENT), "here is content 1".getBytes());
		failVerify = noVerify;
		
		verify = ContentObject.buildContentObject(SegmentationProfile.segmentName(cname, SegmentationProfile.BASE_SEGMENT), "here is content 2".getBytes());;

		//now create our verifier...
		ContentVerifier ver = new TestVerifier();
		
		try {
			putHandle.registerFilter(ContentName.fromNative(_prefix), this);
		
			ContentObject co = SegmentationProfile.getSegment(cname, null, null, SystemConfiguration.MEDIUM_TIMEOUT, ver, getHandle);
			
			Assert.assertNotNull(co);
			Assert.assertEquals(co, verify);
			
		} catch (MalformedContentNameStringException e) {
			Assert.fail("Failed to register prefix: "+e.getMessage());
		} catch (IOException e) {
			Assert.fail("Failed to register prefix: "+e.getMessage());
		}
		
		Log.info(Log.FAC_TEST, "Completed testRetryWithFailedVerification");
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

			Log.info(Log.FAC_TEST, "resorting to default verifier");

			return getHandle.defaultVerifier().verify(content);
		}
	}

	public boolean handleInterest(Interest interest) {
		try {
			if (noVerify != null && interest.matches(noVerify)) {
				putHandle.put(noVerify);
				return true;
			} else if (verify != null && interest.matches(verify)) {
				putHandle.put(verify);
				return true;
			}
		} catch (IOException e) {
			Assert.fail("Exception when responding to an received interest: "+e.getMessage());
		}
		return false;
	}
}
