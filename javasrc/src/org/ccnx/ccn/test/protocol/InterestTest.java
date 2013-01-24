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

package org.ccnx.ccn.test.protocol;


import java.security.InvalidParameterException;
import java.util.ArrayList;

import org.ccnx.ccn.KeyManager;
import org.ccnx.ccn.impl.security.crypto.CCNDigestHelper;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.profiles.SegmentationProfile;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.protocol.BloomFilter;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Exclude;
import org.ccnx.ccn.protocol.ExcludeComponent;
import org.ccnx.ccn.protocol.Interest;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;
import org.ccnx.ccn.protocol.PublisherID;
import org.ccnx.ccn.test.CCNTestBase;
import org.ccnx.ccn.test.impl.encoding.XMLEncodableTester;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;


/**
 * Test the generation and matching functionality of Interests.
  */
@SuppressWarnings("deprecation")
public class InterestTest extends CCNTestBase {
	
	public static String testName = "/test/parc/home/smetters/interestingData.txt/v/5";
	public static ContentName tcn = null;
	public static PublisherID pubID = null;
	
	private byte [] bloomSeed = "burp".getBytes();
	private Exclude ef = null;
	
	private String [] bloomTestValues = {
            "one", "two", "three", "four",
            "five", "six", "seven", "eight",
            "nine", "ten", "eleven", "twelve",
            "thirteen"
      	};

	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		CCNTestBase.setUpBeforeClass();
		byte [] testID = CCNDigestHelper.digest(testName.getBytes());
		
		tcn = ContentName.fromURI(testName);
		pubID = new PublisherID(testID,PublisherID.PublisherType.ISSUER_KEY);
	}

	@Before
	public void setUp() throws Exception {
	}
	
	private void excludeSetup() {
		BloomFilter bf1 = new BloomFilter(13, bloomSeed);
		ExcludeComponent e1 = new ExcludeComponent("aaa".getBytes());
		ExcludeComponent e2 = new ExcludeComponent("zzzzzzzz".getBytes());
		
		try {
			ArrayList<Exclude.Element>te = new ArrayList<Exclude.Element>(2);
			te.add(e2);
			te.add(e1);
			new Exclude(te);
			Assert.fail("Out of order exclude filter succeeded");
		} catch (InvalidParameterException ipe) {}
		
		for (String value : bloomTestValues) {
			bf1.insert(value.getBytes());
		}
		ArrayList<Exclude.Element>excludes = new ArrayList<Exclude.Element>(2);
		excludes.add(e1);
		excludes.add(bf1);
		excludes.add(e2);
		ef = new Exclude(excludes);
	}

	@Test
	public void testSimpleInterest() {
		Log.info(Log.FAC_TEST, "Starting testSimpleInterest");

		Interest plain = new Interest(tcn);
		Interest plainDec = new Interest();
		Interest plainBDec = new Interest();
		XMLEncodableTester.encodeDecodeTest("PlainInterest", plain, plainDec, plainBDec);

		Interest nplain = new Interest(tcn,pubID);
		Interest nplainDec = new Interest();
		Interest nplainBDec = new Interest();
		XMLEncodableTester.encodeDecodeTest("FancyInterest", nplain, nplainDec, nplainBDec);
		
		Interest opPlain = new Interest(tcn);
		opPlain.childSelector(Interest.CHILD_SELECTOR_RIGHT);
		Interest opPlainDec = new Interest();
		Interest opPlainBDec = new Interest();
		XMLEncodableTester.encodeDecodeTest("PreferenceInterest", opPlain, opPlainDec, opPlainBDec);
		
		Interest opMSC = new Interest(tcn);
		opMSC.maxSuffixComponents(3);
		Interest opMSCDec = new Interest();
		Interest opMSCBDec = new Interest();
		XMLEncodableTester.encodeDecodeTest("MaxSuffixComponentsInterest", opMSC, opMSCDec, opMSCBDec);	

		Interest opMinSC = new Interest(tcn);
		opMinSC.minSuffixComponents(3);
		Interest opMinSCDec = new Interest();
		Interest opMinSCBDec = new Interest();
		XMLEncodableTester.encodeDecodeTest("MinSuffixComponentsInterest", opMinSC, opMinSCDec, opMinSCBDec);
		
		Log.info(Log.FAC_TEST, "Completed testSimpleInterest");
	}
	
	@Test
	public void testProfileInterests() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testProfileInterests");

		// Should test the interests used for segments (getLower) as well.
		Interest lv = 
			VersioningProfile.latestVersionInterest(
					ContentName.fromNative("/test/InterestTest/testProfileInterests"), 
					null, KeyManager.getDefaultKeyManager().getDefaultKeyID());
		Interest lvDec = new Interest();
		Interest lvBDec = new Interest();
		XMLEncodableTester.encodeDecodeTest("LatestVersionInterest", lv, lvDec, lvBDec);
		Interest lvs = 
			VersioningProfile.latestVersionInterest(
					ContentName.fromNative("/test/InterestTest/testProfileInterests"), 
					2, KeyManager.getDefaultKeyManager().getDefaultKeyID());
		Interest lvsDec = new Interest();
		Interest lvsBDec = new Interest();
		XMLEncodableTester.encodeDecodeTest("LatestVersionInterest - Short", lvs, lvsDec, lvsBDec);
		
		Log.info(Log.FAC_TEST, "Completed testProfileInterests");
	}
		
	@Test
	public void testExclude() {
		Log.info(Log.FAC_TEST, "Starting testExclude");

		excludeSetup();
		
		Interest exPlain = new Interest(tcn);
		exPlain.exclude(ef);
		Interest exPlainDec = new Interest();
		Interest exPlainBDec = new Interest();
		XMLEncodableTester.encodeDecodeTest("ExcludeInterest", exPlain, exPlainDec, exPlainBDec);
		
		Log.info(Log.FAC_TEST, "Completed testExclude");
	}
	
	@Test
	public void testMatch() {
		Log.info(Log.FAC_TEST, "Starting testMatch");

		// paul r Comment - should really test more comprehensively
		// For now just do this to test the exclude matching
		excludeSetup();
		try {
			Interest interest = new Interest("/paul");
			interest.exclude(ef);
			Assert.assertTrue(interest.matches(ContentName.fromNative("/paul/car"), null));
			Assert.assertFalse(interest.matches(ContentName.fromNative("/paul/zzzzzzzz"), null));
			for (String value : bloomTestValues) {
				String completeName = "/paul/" + value;
				Assert.assertFalse(interest.matches(ContentName.fromNative(completeName), null));
			}
		} catch (MalformedContentNameStringException e) {
			Assert.fail(e.getMessage());
		}
		
		Log.info(Log.FAC_TEST, "Completed testMatch");
	}

	@Test
	public void testMatchDigest() throws MalformedContentNameStringException {
		Log.info(Log.FAC_TEST, "Starting testMatchDigest");

		ContentName name = ContentName.fromNative("/paul");
		byte [] content = "hello".getBytes();
		ContentObject co = ContentObject.buildContentObject(name, content);
		byte [] digest = co.digest();
		Interest interest = new Interest(new ContentName(name, digest));
		Assert.assertTrue(interest.matches(co));
		interest = new Interest(new ContentName(name, "simon"));
		Assert.assertFalse(interest.matches(co));
		
		Log.info(Log.FAC_TEST, "Completed testMatchDigest");
	}
	
	@Test
	public void testMatchWithExcludedDigest() throws MalformedContentNameStringException {
		Log.info(Log.FAC_TEST, "Starting testMatchWithExcludedDigest");

		ContentName name = SegmentationProfile.segmentName(ContentName.fromNative("/here/is/a/content/object"), SegmentationProfile.BASE_SEGMENT);
		
		ContentObject co = ContentObject.buildContentObject(name, "here is content".getBytes());

		//use the same interest construction method used in the scenario that discovered the bug
		Interest interest = Interest.lower(name, 1, null);
		
		Assert.assertTrue(interest.matches(co));
		
		interest.exclude(new Exclude());
		interest.exclude().add(new byte[][] {co.digest()});
		
		Assert.assertFalse(interest.matches(co));
		
		//now test the general interest constructor
		
		interest = new Interest(name);
		Assert.assertTrue(interest.matches(co));
		
		interest.exclude(new Exclude());
		interest.exclude().add(new byte[][] {co.digest()});
		
		Assert.assertFalse(interest.matches(co));

		
		Log.info(Log.FAC_TEST, "Completed testMatchWithExcludedDigest");
	}
}
