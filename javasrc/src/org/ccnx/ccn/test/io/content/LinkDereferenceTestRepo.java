package org.ccnx.ccn.test.io.content;


import java.util.Random;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.impl.CCNFlowControl.SaveType;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.CCNWriter;
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
import org.ccnx.ccn.test.CCNTestHelper;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class LinkDereferenceTestRepo {
	
	static CCNTestHelper testHelper = new CCNTestHelper(LinkDereferenceTestRepo.class);
	
	// Make some data, and some links. Test manual and automated dereferencing.
	static CCNStringObject data[] = new CCNStringObject[3];
	static ContentName bigData;
	static CCNHandle writeHandle;
	static CCNHandle readHandle;
	static String STRING_VALUE_NAME = "Value";
	static String BIG_VALUE_NAME = "BigValue";

	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		readHandle = CCNHandle.open();
		writeHandle = CCNHandle.open();
		
		CCNTime version = new CCNTime();
		ContentName stringName = testHelper.getClassChildName(STRING_VALUE_NAME);
		for (int i=0; i < data.length; ++i) {
			// save multiple versions of the same object.
			data[i] = new CCNStringObject(stringName, 
											"Value " + i, SaveType.REPOSITORY, writeHandle);
			data[i].save(version);
			version.addNanos(10000); // avoid version collisions
		}
		
		CCNWriter writer = new CCNWriter(writeHandle);
		bigData = testHelper.getClassChildName(BIG_VALUE_NAME);
		
		byte [] bigDataContent = new byte [SegmentationProfile.DEFAULT_BLOCKSIZE * 4];
		Random rand = new Random();
		rand.nextBytes(bigDataContent);
		// generate some segmented data; doesn't version the name
		writer.put(bigData, bigDataContent);
	}
	
	@Test
	public void testDereference() throws Exception {
		
		Link versionedLink = new Link(data[1].getVersionedName());
		
		// Should get back a segment, ideally first, of that specific version.
		ContentObject versionedTarget = versionedLink.dereference(SystemConfiguration.MEDIUM_TIMEOUT, readHandle);
		Log.info("Dereferenced link {0}, retrieved content {1}", versionedLink, ((null == versionedTarget) ? "null" : versionedTarget.name()));
		Assert.assertNotNull(versionedTarget);
		Assert.assertTrue(versionedLink.targetName().isPrefixOf(versionedTarget.name()));
		Assert.assertTrue(SegmentationProfile.isFirstSegment(versionedTarget.name()));
		
		Link unversionedLink = new Link(data[1].getBaseName(), "unversioned", null);
		ContentObject unversionedTarget = unversionedLink.dereference(SystemConfiguration.MEDIUM_TIMEOUT, readHandle);
		Log.info("Dereferenced link {0}, retrieved content {1}", unversionedLink, ((null == unversionedTarget) ? "null" : unversionedTarget.name()));
		Assert.assertNotNull(unversionedTarget);
		Assert.assertTrue(unversionedLink.targetName().isPrefixOf(unversionedTarget.name()));
		Assert.assertTrue(data[data.length-1].getVersionedName().isPrefixOf(unversionedTarget.name()));
		Assert.assertTrue(SegmentationProfile.isFirstSegment(unversionedTarget.name()));
		
		Link bigDataLink = new Link(bigData, "big", new LinkAuthenticator(new PublisherID(writeHandle.keyManager().getDefaultKeyID())));
		ContentObject bigDataTarget = bigDataLink.dereference(SystemConfiguration.MEDIUM_TIMEOUT, readHandle);
		Log.info("Dereferenced link {0}, retrieved content {1}", unversionedLink, ((null == unversionedTarget) ? "null" : unversionedTarget.name()));
		Assert.assertNotNull(bigDataTarget);
		Assert.assertTrue(bigDataLink.targetName().isPrefixOf(bigDataTarget.name()));
		Assert.assertTrue(SegmentationProfile.isFirstSegment(bigDataTarget.name()));
	}
	
	@Test
	public void testMissingTarget() throws Exception {
		
		Link linkToNowhere = new Link(testHelper.getTestChildName("testMissingTarget", "linkToNowhere"));
		ContentObject nothing = linkToNowhere.dereference(SystemConfiguration.SHORT_TIMEOUT, readHandle);
		Assert.assertNull(nothing);
	}
	
	@Test
	public void testWrongPublisher() throws Exception {
		byte [] fakePublisher = new byte[PublisherID.PUBLISHER_ID_LEN];
		PublisherID wrongPublisher = new PublisherID(new PublisherPublicKeyDigest(fakePublisher));
		
		Link linkToWrongPublisher = new Link(bigData, new LinkAuthenticator(wrongPublisher));
		ContentObject nothing = linkToWrongPublisher.dereference(SystemConfiguration.SHORT_TIMEOUT, readHandle);
		Assert.assertNull(nothing);
	}

	@Test
	public void testAutomatedDereference() throws Exception {
		Link versionedLink = new Link(data[1].getVersionedName());
		LinkObject versionedLinkObject = new LinkObject(ContentName.fromNative(testHelper.getTestNamespace("testDereference"), "versionedLink"), versionedLink, SaveType.REPOSITORY, writeHandle);
		versionedLinkObject.save();
		
	}

}
