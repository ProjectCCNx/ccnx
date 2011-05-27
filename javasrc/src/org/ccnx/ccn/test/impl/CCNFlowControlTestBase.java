/*
 * A CCNx library test.
 *
 * Copyright (C) 2011 Palo Alto Research Center, Inc.
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
package org.ccnx.ccn.test.impl;

import java.io.IOException;
import java.security.InvalidParameterException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Random;

import junit.framework.Assert;

import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.impl.CCNFlowControl;
import org.ccnx.ccn.io.CCNReader;
import org.ccnx.ccn.profiles.SegmentationProfile;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.protocol.CCNTime;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Interest;
import org.ccnx.ccn.protocol.KeyLocator;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;
import org.ccnx.ccn.protocol.Signature;
import org.ccnx.ccn.protocol.SignedInfo;
import org.ccnx.ccn.test.CCNLibraryTestHarness;
import org.ccnx.ccn.test.CCNTestBase;

/**
 * Shared between the flow control tests
 */
public class CCNFlowControlTestBase extends CCNTestBase {
	static protected CCNLibraryTestHarness _handle ;
	static protected CCNReader _reader;
	static protected int _capacity;
	static protected ContentName name1;
	static final int VERSION_COUNT = 2;
	static final int NANO_INCREMENT = 54321;
	static protected ContentName versions[] = new ContentName[VERSION_COUNT];
	static final int SEGMENT_COUNT = 5;
	static protected ContentName segment_names[] = new ContentName[SEGMENT_COUNT];
	static protected ContentObject segments[] = new ContentObject[SEGMENT_COUNT];
	static protected ContentObject obj1 = null;
	static protected Signature fakeSignature = null;
	static protected SignedInfo fakeSignedInfo = null;
	
	protected ArrayList<Interest> interestList = new ArrayList<Interest>();
	protected CCNFlowControl fc = null;
	
	public static void setUpBeforeClass() throws Exception {
		try {
			Random rnd = new Random();
			byte [] fakeSigBytes = new byte[128];
			byte [] publisher = new byte[32];
			rnd.nextBytes(fakeSigBytes);
			rnd.nextBytes(publisher);
			PublisherPublicKeyDigest pub = new PublisherPublicKeyDigest(publisher);
			fakeSignature = new Signature(fakeSigBytes);
			CCNTime now = CCNTime.now();
			KeyLocator locator = new KeyLocator(ContentName.fromNative("/key/" + pub.digest().toString()));
			fakeSignedInfo = new SignedInfo(pub, now, SignedInfo.ContentType.DATA, locator);

			_handle = new CCNLibraryTestHarness();
			_reader = new CCNReader(_handle);
			
			name1 = ContentName.fromNative("/foo/bar");
			// DKS remove unnecessary sleep, force separate versions.
			CCNTime time = new CCNTime();
			Timestamp afterTime = null;
			for (int i=0; i < VERSION_COUNT; ++i) {
				versions[i] = VersioningProfile.addVersion(name1, time);
				afterTime = new Timestamp(time.getTime());
				afterTime.setNanos(time.getNanos() + NANO_INCREMENT);
				time = new CCNTime(afterTime);
			}
			
			obj1 = new ContentObject(name1, fakeSignedInfo, "test".getBytes(), fakeSignature);
			int version = 0;
			for (int j=0; j < SEGMENT_COUNT; ++j) {
				segment_names[j] = SegmentationProfile.segmentName(versions[version], j);
				segments[j] = new ContentObject(segment_names[j], fakeSignedInfo, new String("v" + version + "s" + j).getBytes(), fakeSignature);
			}
		} catch (ConfigurationException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		} catch (MalformedContentNameStringException e) {
			e.printStackTrace();
		}
	}
	
	protected void normalReset(ContentName n) throws IOException {
		_handle.reset();
		interestList.clear();
		fc = new CCNFlowControl(n, _handle);
	}
	
	protected ContentObject testNext(ContentObject co, ContentObject expected) throws InvalidParameterException, IOException {
		co = _reader.get(Interest.next(co.name(), 3, null), 0);
		return testExpected(co, expected);
	}
	
	protected void testLast(ContentObject co, ContentObject expected) throws InvalidParameterException, IOException {
		co = _reader.get(Interest.last(co.name(), 3, null), 0);
		testExpected(co, expected);
	}
	
	protected ContentObject testExpected(ContentObject co, ContentObject expected) {
		Assert.assertTrue(co != null);
		Assert.assertEquals(co, expected);
		return co;
	}

}
