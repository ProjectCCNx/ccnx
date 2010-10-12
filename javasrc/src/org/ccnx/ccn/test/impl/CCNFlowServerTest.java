/*
 * A CCNx library test.
 *
 * Copyright (C) 2009, 2010 Palo Alto Research Center, Inc.
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
import java.util.Queue;
import java.util.Random;

import junit.framework.Assert;

import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.impl.CCNFlowServer;
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
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

public class CCNFlowServerTest {

	static private CCNLibraryTestHarness _handle ;
	static private CCNReader _reader;
	static private int _capacity;
	static private boolean _persistent = true;
	static ContentName name1;
	static final int VERSION_COUNT = 2;
	static final int NANO_INCREMENT = 54321;
	static ContentName versions[] = new ContentName[VERSION_COUNT];
	static final int SEGMENT_COUNT = 5;
	static ContentName segment_names[] = new ContentName[SEGMENT_COUNT];
	static ContentObject segments[] = new ContentObject[SEGMENT_COUNT];
	static ContentObject obj1 = null;
	static public Signature fakeSignature = null;
	static public SignedInfo fakeSignedInfo = null;

	Queue<ContentObject> queue = _handle.getOutputQueue();
	ArrayList<Interest> interestList = new ArrayList<Interest>();
	CCNFlowServer fc = null;

	@BeforeClass
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
	
	@Before
	public void setUp() throws Exception {
		_capacity = SEGMENT_COUNT*2;
		fc = new CCNFlowServer(_capacity, true, _handle);
	}
	
	@Test
	public void testBasicControlFlow() throws Throwable {	
		
		System.out.println("Testing basic control flow functionality and errors");
		_handle.reset();
		try {
			fc.put(obj1);
			Assert.fail("Put with no namespace succeeded");
		} catch (IOException e) {}
		fc.addNameSpace("/bar");
		try {
			fc.put(obj1);
			Assert.fail("Put with bad namespace succeeded");
		} catch (IOException e) {}
		fc.addNameSpace("/foo");
		try {
			fc.put(obj1);
		} catch (IOException e) {
			Assert.fail("Put with good namespace failed");
		}
		
	}
	
	@Test
	public void testInterestFirst() throws Throwable {	
		
		normalReset(name1);
		System.out.println("Testing interest arrives before a put");
		interestList.add(new Interest("/bar"));
		fc.handleInterests(interestList);
		fc.put(obj1);
		Assert.assertTrue(queue.poll() == null);
		interestList.add(new Interest("/foo"));
		fc.handleInterests(interestList);
		fc.put(obj1);
		testExpected(queue.poll(), obj1);
	}
	
	@Test
	public void testNextBeforePut() throws Exception {	

		System.out.println("Testing \"next\" interest arrives before a put");
		normalReset(name1);
		interestList.add(Interest.next(segment_names[1], null, null));
		fc.handleInterests(interestList);
		fc.put(segments[0]);
		Assert.assertTrue(queue.poll() == null);
		fc.put(segments[2]);
		testExpected(queue.poll(), segments[2]);
		
	}
	
	@Test
	public void testLastBeforePut() throws Exception {	

		System.out.println("Testing \"last\" interest arrives before a put");
		normalReset(name1);
		interestList.add(Interest.last(segment_names[1], null, null));
		fc.handleInterests(interestList);
		fc.put(segments[0]);
		Assert.assertTrue(queue.poll() == null);
		fc.put(segments[2]);
		testExpected(queue.poll(), segments[2]);
		
	}
	
	@Test
	public void testPutsOrdered() throws Throwable {	

		System.out.println("Testing puts output in correct order");
		normalReset(name1);
		interestList.add(new Interest("/foo"));
		fc.handleInterests(interestList);
		fc.put(obj1);
		testExpected(queue.poll(), obj1);
		
	} 
	
	@Test
	public void testRandomOrderPuts() throws Throwable {	

		normalReset(name1);
		
		// Put these in slightly random order. It would be nice to truly randomize this but am
		// not going to bother with that right now.
		fc.put(segments[3]);
		fc.put(segments[0]);
		fc.put(segments[1]);
		fc.put(segments[2]);
		ContentObject co = testExpected(_handle.get(versions[0], 0), segments[0]);
		co = testNext(co, segments[1]);
		co = testNext(co, segments[2]);
		co = testNext(co, segments[3]);
		
	}
	
	@Test
	public void testMultipleGets() throws Throwable {	

		normalReset(name1);
		// add data to the flow server, and make sure we can get it out multiple times
		// Put these in slightly random order. It would be nice to truly randomize this but am
		// not going to bother with that right now.
		fc.put(segments[3]);
		fc.put(segments[0]);
		fc.put(segments[1]);
		fc.put(segments[2]);
		ContentObject co = testExpected(_handle.get(versions[0], 0), segments[0]);
		co = testNext(co, segments[1]);
		co = testNext(co, segments[2]);
		co = testNext(co, segments[3]);
		co = testExpected(_handle.get(versions[0], 0), segments[0]);
		co = testNext(co, segments[1]);
		co = testNext(co, segments[2]);
		co = testNext(co, segments[3]);
		
	}

	@Test
	public void testWaitForPutDrain() throws Throwable {	

		normalReset(name1);
		fc.put(segments[1]);
		fc.put(segments[3]);
		fc.put(segments[0]);
		fc.put(segments[2]);
		testLast(segments[0], segments[3]);
		testLast(segments[0], segments[3]); // should be same, if persistent server will get back same data
		_handle.get(new Interest(segment_names[0]), 0);
		
		System.out.println("Testing \"waitForPutDrain\"");
		try {
			// can't call waitForPutDrain directly; call it via afterClose
			fc.afterClose();
		} catch (IOException ioe) {
			Assert.fail("WaitforPutDrain threw unexpected exception");
		}
		fc.put(obj1);
		try {
			// can't call waitForPutDrain directly; call it via afterClose
			fc.afterClose(); // with a flow server, shouldn't throw waitForPutDrain. We don't
				// care if anyone takes stuff
		} catch (IOException ioe) {
			Assert.fail("WaitforPutDrain threw unexpected exception");
		}
	}
	
	@Test
	public void testHighwaterWait() throws Exception {
		
		// Test that put over highwater fails with nothing draining
		// the buffer
		normalReset(name1);
		fc.setCapacity(4);
		fc.put(segments[0]);
		fc.put(segments[1]);
		fc.put(segments[2]);
		fc.put(segments[3]);
		try {
			fc.put(segments[4]);
			Assert.fail("Put over highwater mark succeeded");
		} catch (IOException ioe) {}
		
		// Test that put over highwater doesn't succeed when persistent buffer is
		// drained
		normalReset(name1);
		fc.setCapacity(4);
		fc.put(segments[0]);
		fc.put(segments[1]);
		fc.put(segments[2]);

		HighWaterHelper hwh = new HighWaterHelper();
		hwh.start();
		try {
			fc.put(segments[3]);
			fc.put(segments[4]);
			Assert.fail("Attempt to put over capacity in non-draining FC succeeded.");
		} catch (IOException ioe) {}
	}
	
	public class HighWaterHelper extends Thread {

		public void run() {
			synchronized (this) {
				try {
					Thread.sleep(500);
					_handle.get(segments[0].name(), 0);
				} catch (Exception e) {
					Assert.fail("Caught exception: " + e.getMessage());
				}
			}
		}
		
	}
	
	private void normalReset(ContentName n) throws IOException {
		_handle.reset();
		interestList.clear();
		fc = new CCNFlowServer(n, _capacity, _persistent, _handle);
		fc.setTimeout(100);
	}
	
	private ContentObject testNext(ContentObject co, ContentObject expected) throws InvalidParameterException, IOException {
		co = _reader.get(Interest.next(co.name(), 3, null), 0);
		return testExpected(co, expected);
	}
	
	private void testLast(ContentObject co, ContentObject expected) throws InvalidParameterException, IOException {
		co = _reader.get(Interest.last(co.name(), 3, null), 0);
		testExpected(co, expected);
	}
	
	private ContentObject testExpected(ContentObject co, ContentObject expected) {
		Assert.assertTrue(co != null);
		Assert.assertEquals(co, expected);
		return co;
	}
}
