/*
 * A CCNx library test.
 *
 * Copyright (C) 2011-2013 Palo Alto Research Center, Inc.
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

package org.ccnx.ccn.io;

import java.io.IOException;

import junit.framework.Assert;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.CCNTestBase;
import org.ccnx.ccn.CCNTestHelper;
import org.ccnx.ccn.ThreadAssertionRunner;
import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.CCNAbstractInputStream.FlagTypes;
import org.ccnx.ccn.profiles.SegmentationProfile;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.SignedInfo.ContentType;
import org.ccnx.ccn.utils.CreateUserData;
import org.junit.Test;

public class CCNInputStreamTest extends CCNTestBase {
	static CCNTestHelper testHelper = new CCNTestHelper(CCNInputStreamTest.class);
	
	static String USER_NAMESPACE = "TestInputUser";

	@Test
	public void testTimeouts() throws Exception {
		long t = 9000;
		Log.info(Log.FAC_TEST, "Started testTimeouts");

		ContentName testName = testHelper.getTestNamespace("testInput/timeouts");
		CCNInputStream cis = new CCNInputStream(testName, getHandle);
		long startTime = System.currentTimeMillis();
		cis.setTimeout(t);
		try {
			cis.read();
		} catch (IOException e) {}
		long stopTime = System.currentTimeMillis();
		Assert.assertTrue("Input stream timed out early", (stopTime - startTime) >= t);

		cis.close();

		testName = testHelper.getTestNamespace("testInput/no/timeout");
		CCNInputStream	stream = new CCNInputStream(testName, getHandle);
		BackgroundStreamer bas = new BackgroundStreamer(stream, new byte[0], true, SystemConfiguration.NO_TIMEOUT);
		ThreadAssertionRunner tar = new ThreadAssertionRunner(new Thread(bas));
		tar.start();
		tar.join(SystemConfiguration.EXTRA_LONG_TIMEOUT * 2);
		bas.close();

		Log.info(Log.FAC_TEST, "Completed testTimeouts");
	}

	@Test
	public void testBlocking() throws Exception {
		Log.info(Log.FAC_TEST, "Started testBlocking");

		ContentName testName = testHelper.getTestNamespace("testInput/blocking");
		CCNInputStream stream = new CCNInputStream(testName, getHandle);
		stream.addFlag(FlagTypes.BLOCKING);
		doStreaming(stream, testName, putHandle, true);

		Log.info(Log.FAC_TEST, "Completed testBlocking");
	}

	@Test
	public void testBlockAfterFirstSegment() throws Exception {
		Log.info(Log.FAC_TEST, "Started testBlockAfterFirstSegment");

		ContentName testName = testHelper.getTestNamespace("testInput/blockAfterFirst");
		CCNInputStream stream = new CCNInputStream(testName, getHandle);
		CCNOutputStream ostream = new CCNOutputStream(testName, putHandle);
		CCNInputStreamTestCommon.blockAfterFirstSegmentTest(testName, stream, ostream);

		Log.info(Log.FAC_TEST, "Completed testBlockAfterFirstSegment");
	}

	@Test
	public void testBasename() {
		Log.info(Log.FAC_TEST, "Started testBasename");

		//create a segment object to open a stream with, then check basename
		ContentName streamName = testHelper.getTestNamespace("testBasename");
		streamName = VersioningProfile.addVersion(streamName);
		ContentName fullStreamName = SegmentationProfile.segmentName(streamName, SegmentationProfile.BASE_SEGMENT);
		ContentObject co = ContentObject.buildContentObject(fullStreamName, ContentType.DATA, "here is some content to name".getBytes(), 
				null, null, null, null, SegmentationProfile.getSegmentNumberNameComponent(SegmentationProfile.BASE_SEGMENT));

		try {
			CCNInputStream stream = new CCNInputStream(co, null, getHandle);
			Assert.assertTrue(streamName.equals(stream.getBaseName()));
		} catch (IOException e) {
			Assert.fail("failed to open stream: "+e.getMessage());
		}
		Log.info(Log.FAC_TEST, "Completed testBasename");
	}
	
	@Test
	public void testKeyRetrieval() throws Exception {
		Log.info(Log.FAC_TEST, "Started testKeyRetrieval");
		ContentName streamName = testHelper.getTestNamespace("testKeyRetrieval");
		streamName = VersioningProfile.addVersion(streamName);
		CreateUserData testUsers = new CreateUserData(testHelper.getClassChildName(USER_NAMESPACE), 2, false, null);
		String [] userNames = testUsers.friendlyNames().toArray(new String[2]);
		CCNHandle newKeyHandle = testUsers.getHandleForUser(userNames[0]);
		CCNInputStream stream = new CCNInputStream(streamName, newKeyHandle);
		doStreaming(stream, streamName, putHandle, false);
		Log.info(Log.FAC_TEST, "Completed testKeyRetrieval");		
	}
	
	private void doStreaming(CCNInputStream stream, ContentName testName, CCNHandle handle, boolean sleepAtStart) throws Error, Exception {
		byte[] bytes = new byte[400];
		for (int i = 0; i < bytes.length; i++)
			bytes[i] = (byte)i;
		BackgroundStreamer bas = new BackgroundStreamer(stream, bytes, false, 0);
		ThreadAssertionRunner tar = new ThreadAssertionRunner(new Thread(bas));
		tar.start();
		if (sleepAtStart)
			Thread.sleep(SystemConfiguration.getDefaultTimeout() * 2);
		CCNOutputStream ostream = new CCNOutputStream(testName, handle);
		ostream.setBlockSize(100);
		ostream.write(bytes);
		ostream.close();
		tar.join(SystemConfiguration.EXTRA_LONG_TIMEOUT * 2);
		bas.close();
	}
}
