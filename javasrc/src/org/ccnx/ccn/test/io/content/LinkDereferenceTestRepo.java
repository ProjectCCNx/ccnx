/*
 * A CCNx library test.
 *
 * Copyright (C) 2010, 2011, 2012 Palo Alto Research Center, Inc.
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


import java.util.Random;

import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.impl.CCNFlowControl.SaveType;
import org.ccnx.ccn.impl.security.crypto.CCNDigestHelper;
import org.ccnx.ccn.impl.support.DataUtils;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.CCNReader;
import org.ccnx.ccn.io.CCNRepositoryWriter;
import org.ccnx.ccn.io.content.CCNStringObject;
import org.ccnx.ccn.io.content.Link;
import org.ccnx.ccn.io.content.LinkAuthenticator;
import org.ccnx.ccn.io.content.Link.LinkObject;
import org.ccnx.ccn.profiles.SegmentationProfile;
import org.ccnx.ccn.protocol.CCNTime;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.PublisherID;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;
import org.ccnx.ccn.test.CCNTestBase;
import org.ccnx.ccn.test.CCNTestHelper;
import org.ccnx.ccn.test.TestUtils;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class LinkDereferenceTestRepo extends CCNTestBase {

	static CCNTestHelper testHelper = new CCNTestHelper(LinkDereferenceTestRepo.class);

	// Make some data, and some links. Test manual and automated dereferencing.
	static CCNStringObject data[] = new CCNStringObject[3];
	static CCNStringObject gone;
	static ContentName bigData;
	static final int bigDataLength = SegmentationProfile.DEFAULT_BLOCKSIZE * 4 + 137;
	static String STRING_VALUE_NAME = "Value";
	static String BIG_VALUE_NAME = "BigValue";
	static String GONE_VALUE_NAME = "Gone";
	static byte [] bigValueDigest;
	static byte [] bigDataContent;

	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		CCNTestBase.setUpBeforeClass();
		CCNTime version = new CCNTime();
		ContentName stringName = testHelper.getClassChildName(STRING_VALUE_NAME);
		for (int i=0; i < data.length; ++i) {
			// save multiple versions of the same object.
			data[i] = new CCNStringObject(stringName,
											"Value " + i, SaveType.REPOSITORY, putHandle);
			Log.info(Log.FAC_TEST, "Saving as version " + version);
			data[i].save(version);
			version.increment(1); // avoid version collisions
		}

		gone = new CCNStringObject(testHelper.getClassChildName(GONE_VALUE_NAME), GONE_VALUE_NAME, SaveType.REPOSITORY, putHandle);
		gone.saveAsGone();
		TestUtils.checkObject(putHandle, gone);

		bigData = testHelper.getClassChildName(BIG_VALUE_NAME);

		bigDataContent = new byte [bigDataLength];
		Random rand = new Random();
		rand.nextBytes(bigDataContent);
		bigValueDigest = CCNDigestHelper.digest(bigDataContent);

		// generate some segmented data
		CCNRepositoryWriter writer = new CCNRepositoryWriter(putHandle);
		writer.newVersion(bigData, bigDataContent);
	}

	@Test
	public void testDereference() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testDereference");

		Link versionedLink = new Link(data[1].getVersionedName());

		// Should get back a segment, ideally first, of that specific version.
		ContentObject versionedTarget = versionedLink.dereference(SystemConfiguration.getDefaultTimeout(), putHandle);
		Log.info(Log.FAC_TEST, "Dereferenced link {0}, retrieved content {1}", versionedLink, ((null == versionedTarget) ? "null" : versionedTarget.name()));
		Assert.assertNotNull(versionedTarget);
		Assert.assertTrue(versionedLink.targetName().isPrefixOf(versionedTarget.name()));
		Assert.assertTrue(SegmentationProfile.isFirstSegment(versionedTarget.name()));

		Link unversionedLink = new Link(data[1].getBaseName(), "unversioned", null);
		ContentObject unversionedTarget = unversionedLink.dereference(SystemConfiguration.getDefaultTimeout(), getHandle);
		Log.info(Log.FAC_TEST, "Dereferenced link {0}, retrieved content {1}", unversionedLink, ((null == unversionedTarget) ? "null" : unversionedTarget.name()));
		Assert.assertNotNull(unversionedTarget);
		Assert.assertTrue(unversionedLink.targetName().isPrefixOf(unversionedTarget.name()));
		Assert.assertTrue(data[data.length-1].getVersionedName().isPrefixOf(unversionedTarget.name()));
		Assert.assertTrue(SegmentationProfile.isFirstSegment(unversionedTarget.name()));

		Link bigDataLink = new Link(bigData, "big", new LinkAuthenticator(new PublisherID(putHandle.keyManager().getDefaultKeyID())));
		ContentObject bigDataTarget = bigDataLink.dereference(SystemConfiguration.getDefaultTimeout(), putHandle);
		Log.info(Log.FAC_TEST, "BigData: Dereferenced link " + bigDataLink + ", retrieved content " + ((null == bigDataTarget) ? "null" : bigDataTarget.name()));
		Assert.assertNotNull(bigDataTarget);
		Assert.assertTrue(bigDataLink.targetName().isPrefixOf(bigDataTarget.name()));
		Assert.assertTrue(SegmentationProfile.isFirstSegment(bigDataTarget.name()));

		Log.info(Log.FAC_TEST, "Completed testDereference");
	}

	@Test
	public void testMissingTarget() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testMissingTarget");

		Link linkToNowhere = new Link(testHelper.getTestChildName("testMissingTarget", "linkToNowhere"));
		ContentObject nothing = linkToNowhere.dereference(SystemConfiguration.SHORT_TIMEOUT, getHandle);
		Assert.assertNull(nothing);

		Log.info(Log.FAC_TEST, "Completed testMissingTarget");
	}

	@Test
	public void testWrongPublisher() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testWrongPublisher");

		byte [] fakePublisher = new byte[PublisherID.PUBLISHER_ID_LEN];
		PublisherID wrongPublisher = new PublisherID(new PublisherPublicKeyDigest(fakePublisher));

		Link linkToWrongPublisher = new Link(bigData, new LinkAuthenticator(wrongPublisher));
		ContentObject nothing = linkToWrongPublisher.dereference(SystemConfiguration.SHORT_TIMEOUT, getHandle);
		Assert.assertNull(nothing);

		Log.info(Log.FAC_TEST, "Completed testWrongPublisher");
	}

	@Test
	public void testLinkToUnversioned() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testLinkToUnversioned");

		// test dereferencing link to unversioned data.
		CCNRepositoryWriter writer = new CCNRepositoryWriter(putHandle);
		ContentName unversionedDataName = testHelper.getTestChildName("testLinkToUnversioned", "UnversionedBigData");
		// generate some segmented data; doesn't version the name
		writer.newVersion(unversionedDataName, bigDataContent);

		Link uvBigDataLink = new Link(unversionedDataName, "big", new LinkAuthenticator(new PublisherID(putHandle.keyManager().getDefaultKeyID())));
		ContentObject bigDataTarget = uvBigDataLink.dereference(SystemConfiguration.SETTABLE_SHORT_TIMEOUT, getHandle);
		Log.info(Log.FAC_TEST, "BigData: Dereferenced link " + uvBigDataLink + ", retrieved content " + ((null == bigDataTarget) ? "null" : bigDataTarget.name()));
		Assert.assertNotNull(bigDataTarget);
		Assert.assertTrue(uvBigDataLink.targetName().isPrefixOf(bigDataTarget.name()));
		Assert.assertTrue(SegmentationProfile.isFirstSegment(bigDataTarget.name()));

		Log.info(Log.FAC_TEST, "Completed testLinkToUnversioned");
	}

	@Test
	public void testAutomatedDereferenceForStreams() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testAutomatedDereferenceForStreams");

		Link bigDataLink = new Link(bigData, "big", new LinkAuthenticator(new PublisherID(putHandle.keyManager().getDefaultKeyID())));
		LinkObject bigDataLinkObject = new LinkObject(testHelper.getTestChildName("testAutomatedDereferenceForStreams", "bigDataLink"), bigDataLink, SaveType.REPOSITORY, putHandle);
		bigDataLinkObject.save();
		TestUtils.checkObject(putHandle, bigDataLinkObject);

		Link twoHopLink = new Link(bigDataLinkObject.getBaseName());
		LinkObject twoHopLinkObject = new LinkObject(testHelper.getTestChildName("testAutomatedDereferenceForStreams", "twoHopLink"), twoHopLink, SaveType.REPOSITORY, putHandle);
		twoHopLinkObject.save();
		TestUtils.checkObject(putHandle, twoHopLinkObject);

		CCNReader reader = new CCNReader(putHandle);
		byte [] bigDataReadback = reader.getVersionedData(bigDataLinkObject.getVersionedName(), null, SystemConfiguration.getDefaultTimeout());
		byte [] bdrdigest = CCNDigestHelper.digest(bigDataReadback);
		Log.info(Log.FAC_TEST, "Read back big data via link, got " + bigDataReadback.length +
				" bytes of an expected " + bigDataLength + ", digest match? " + (0 == DataUtils.compare(bdrdigest, bigValueDigest)));
		Assert.assertEquals(bigDataLength, bigDataReadback.length);
		Assert.assertArrayEquals(bdrdigest, bigValueDigest);

		byte [] bigDataReadback2 = reader.getVersionedData(twoHopLinkObject.getBaseName(), null, SystemConfiguration.getDefaultTimeout());
		byte [] bdr2digest = CCNDigestHelper.digest(bigDataReadback);
		Log.info(Log.FAC_TEST, "Read back big data via two links, got " + bigDataReadback2.length +
				" bytes of an expected " + bigDataLength + ", digest match? " + (0 == DataUtils.compare(bdr2digest, bigValueDigest)));
		Assert.assertEquals(bigDataLength, bigDataReadback2.length);
		Assert.assertArrayEquals(bdr2digest, bigValueDigest);

		Log.info(Log.FAC_TEST, "Completed testAutomatedDereferenceForStreams");
	}

	@Test
	public void testAutomatedDereferenceForObjects() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testAutomatedDereferenceForObjects");

		Link versionedLink = new Link(data[1].getVersionedName());
		LinkObject versionedLinkObject = new LinkObject(testHelper.getTestChildName("testAutomatedDereferenceForObjects", "versionedLink"), versionedLink, SaveType.REPOSITORY, putHandle);
		versionedLinkObject.save();
		TestUtils.checkObject(putHandle, versionedLinkObject);

		Link unversionedLink = new Link(data[1].getBaseName());
		LinkObject unversionedLinkObject = new LinkObject(testHelper.getTestChildName("testAutomatedDereferenceForObjects", "unversionedLink"), unversionedLink, SaveType.REPOSITORY, putHandle);
		unversionedLinkObject.save();
		TestUtils.checkObject(putHandle, unversionedLinkObject);

		Link twoHopLink = new Link(unversionedLinkObject.getBaseName());
		LinkObject twoHopLinkObject = new LinkObject(testHelper.getTestChildName("testAutomatedDereferenceForObjects", "twoHopLink"), twoHopLink, SaveType.REPOSITORY, putHandle);
		twoHopLinkObject.save();
		TestUtils.checkObject(putHandle, twoHopLinkObject);

		// read via the name iself
		CCNStringObject readObjectControl = new CCNStringObject(data[data.length-1].getBaseName(), null);
		Assert.assertEquals(readObjectControl.getVersionedName(), data[data.length-1].getVersionedName());
		Assert.assertEquals(readObjectControl.string(), data[data.length-1].string());

		// read via the versioned link.
		CCNStringObject versionedReadObject = new CCNStringObject(versionedLinkObject.getBaseName(), getHandle);
		Assert.assertEquals(versionedReadObject.getVersionedName(), data[1].getVersionedName());
		Assert.assertEquals(versionedReadObject.string(), data[1].string());
		Assert.assertNotNull(versionedReadObject.getDereferencedLink());
		Assert.assertEquals(versionedReadObject.getDereferencedLink(), versionedLinkObject);

		// read latest version via the unversioned link
		CCNStringObject unversionedReadObject = new CCNStringObject(unversionedLinkObject.getBaseName(), getHandle);
		Assert.assertEquals(unversionedReadObject.getVersionedName(), data[data.length-1].getVersionedName());
		Assert.assertEquals(unversionedReadObject.string(), data[data.length-1].string());
		Assert.assertNotNull(unversionedReadObject.getDereferencedLink());
		Assert.assertEquals(unversionedReadObject.getDereferencedLink(), unversionedLinkObject);

		// read via the two-hop link
		CCNStringObject twoHopReadObject = new CCNStringObject(twoHopLinkObject.getBaseName(), getHandle);
		Assert.assertEquals(twoHopReadObject.getVersionedName(), data[data.length-1].getVersionedName());
		Assert.assertEquals(twoHopReadObject.string(), data[data.length-1].string());
		Assert.assertNotNull(twoHopReadObject.getDereferencedLink());
		Assert.assertEquals(twoHopReadObject.getDereferencedLink(), unversionedLinkObject);
		Assert.assertNotNull(twoHopReadObject.getDereferencedLink().getDereferencedLink());
		Assert.assertEquals(twoHopReadObject.getDereferencedLink().getDereferencedLink(), twoHopLinkObject);

		Log.info(Log.FAC_TEST, "Completed testAutomatedDereferenceForObjects");

	}


	@Test
	public void testAutomatedDereferenceForGone() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testAutomatedDereferenceForGone");

		Link versionedLink = new Link(gone.getVersionedName());
		LinkObject versionedLinkObject =
			new LinkObject(testHelper.getTestChildName("testAutomatedDereferenceForGone", "versionedLink"),
						   versionedLink, SaveType.REPOSITORY, putHandle);
		versionedLinkObject.save();
		TestUtils.checkObject(putHandle, versionedLinkObject);

		Link unversionedLink = new Link(gone.getBaseName());
		LinkObject unversionedLinkObject =
			new LinkObject(testHelper.getTestChildName("testAutomatedDereferenceForGone", "unversionedLink"), unversionedLink, SaveType.REPOSITORY, putHandle);
		unversionedLinkObject.save();
		TestUtils.checkObject(putHandle, unversionedLinkObject);

		Link twoHopLink = new Link(unversionedLinkObject.getBaseName());
		LinkObject twoHopLinkObject = new LinkObject(testHelper.getTestChildName("testAutomatedDereferenceForGone", "twoHopLink"), twoHopLink, SaveType.REPOSITORY, putHandle);
		twoHopLinkObject.save();
		TestUtils.checkObject(putHandle, twoHopLinkObject);

		// read via the name iself
		CCNStringObject readObjectControl = new CCNStringObject(gone.getBaseName(), null);
		Assert.assertEquals(readObjectControl.getVersionedName(), gone.getVersionedName());
		Assert.assertTrue(readObjectControl.isGone());

		// read via the versioned link.
		CCNStringObject versionedReadObject = new CCNStringObject(versionedLinkObject.getBaseName(), getHandle);
		Assert.assertEquals(versionedReadObject.getVersionedName(), gone.getVersionedName());
		Assert.assertTrue(versionedReadObject.isGone());
		Assert.assertNotNull(versionedReadObject.getDereferencedLink());
		Assert.assertEquals(versionedReadObject.getDereferencedLink(), versionedLinkObject);

		// read latest version via the unversioned link
		CCNStringObject unversionedReadObject = new CCNStringObject(unversionedLinkObject.getBaseName(), getHandle);
		Assert.assertEquals(unversionedReadObject.getVersionedName(), gone.getVersionedName());
		Assert.assertTrue(unversionedReadObject.isGone());
		Assert.assertNotNull(unversionedReadObject.getDereferencedLink());
		Assert.assertEquals(unversionedReadObject.getDereferencedLink(), unversionedLinkObject);

		// read via the two-hop link
		CCNStringObject twoHopReadObject = new CCNStringObject(twoHopLinkObject.getBaseName(), getHandle);
		Assert.assertEquals(twoHopReadObject.getVersionedName(), gone.getVersionedName());
		Assert.assertTrue(twoHopReadObject.isGone());
		Assert.assertNotNull(twoHopReadObject.getDereferencedLink());
		Assert.assertEquals(twoHopReadObject.getDereferencedLink(), unversionedLinkObject);
		Assert.assertNotNull(twoHopReadObject.getDereferencedLink().getDereferencedLink());
		Assert.assertEquals(twoHopReadObject.getDereferencedLink().getDereferencedLink(), twoHopLinkObject);

		Log.info(Log.FAC_TEST, "Completed testAutomatedDereferenceForGone");
	}
}
