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
package org.ccnx.ccn.test.impl;

import java.io.IOException;
import java.security.InvalidParameterException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Queue;
import java.util.Random;

import junit.framework.Assert;

import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.impl.CCNFlowControl;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.CCNReader;
import org.ccnx.ccn.profiles.SegmentationProfile;
import org.ccnx.ccn.protocol.CCNTime;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Interest;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;
import org.ccnx.ccn.test.CCNLibraryTestHarness;
import org.ccnx.ccn.test.CCNTestBase;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Shared between the flow control tests
 */
public abstract class CCNFlowControlTestBase extends CCNTestBase {
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
	
	protected ArrayList<Interest> interestList = new ArrayList<Interest>();
	protected CCNFlowControl fc = null;
	protected Queue<ContentObject> queue = _handle.getOutputQueue();
	
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		CCNTestBase.setUpBeforeClass();
		try {
			Random rnd = new Random();
			byte [] fakeSigBytes = new byte[128];
			byte [] publisher = new byte[32];
			rnd.nextBytes(fakeSigBytes);
			rnd.nextBytes(publisher);

			_handle = new CCNLibraryTestHarness();
			_reader = new CCNReader(_handle);
			
			name1 = ContentName.fromNative("/foo/bar");
			// DKS remove unnecessary sleep, force separate versions.
			CCNTime time = new CCNTime();
			Timestamp afterTime = null;
			for (int i=0; i < VERSION_COUNT; ++i) {
				versions[i] = new ContentName(name1, time);
				afterTime = new Timestamp(time.getTime());
				afterTime.setNanos(time.getNanos() + NANO_INCREMENT);
				time = new CCNTime(afterTime);
			}
			
			obj1 = ContentObject.buildContentObject(name1, "test".getBytes());
			int version = 0;
			for (int j=0; j < SEGMENT_COUNT; ++j) {
				segment_names[j] = SegmentationProfile.segmentName(versions[version], j);
				segments[j] = ContentObject.buildContentObject(segment_names[j], new String("v" + version + "s" + j).getBytes());
			}
		} catch (ConfigurationException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		} catch (MalformedContentNameStringException e) {
			e.printStackTrace();
		}
	}
	
	@Test
	public void testBasicControlFlow() throws Throwable {		
		Log.info(Log.FAC_TEST, "Starting testBasicControlFlow");
		
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
		
		Log.info(Log.FAC_TEST, "Completed testBasicControlFlow");
	}
	
	@Test
	public void testInterestFirst() throws Throwable {	
		Log.info(Log.FAC_TEST, "Starting testInterestFirst");
		
		normalReset(name1);
		interestList.add(new Interest("/bar"));
		fc.handleInterests(interestList);
		fc.put(obj1);
		Assert.assertTrue(queue.poll() == null);
		interestList.add(new Interest("/foo"));
		fc.handleInterests(interestList);
		fc.put(obj1);
		testExpected(queue.poll(), obj1);
		
		Log.info(Log.FAC_TEST, "Completed testInterestFirst");
	}
	
	@Test
	public void testNextBeforePut() throws Exception {	
		Log.info(Log.FAC_TEST, "Starting testNextBeforePut");

		normalReset(name1);
		interestList.add(Interest.next(segment_names[1], null, null));
		fc.handleInterests(interestList);
		fc.put(segments[0]);
		Assert.assertTrue(queue.poll() == null);
		fc.put(segments[2]);
		testExpected(queue.poll(), segments[2]);
		
		Log.info(Log.FAC_TEST, "Completed testNextBeforePut");
	}
	
	@Test
	public void testLastBeforePut() throws Exception {	
		Log.info(Log.FAC_TEST, "Starting testLastBeforePut");

		normalReset(name1);
		interestList.add(Interest.last(segment_names[1], null, null));
		fc.handleInterests(interestList);
		fc.put(segments[0]);
		Assert.assertTrue(queue.poll() == null);
		fc.put(segments[2]);
		testExpected(queue.poll(), segments[2]);
		
		Log.info(Log.FAC_TEST, "Completed testLastBeforePut");
	}
	
	@Test
	public void testPutsOrdered() throws Throwable {	
		Log.info(Log.FAC_TEST, "Starting testPutsOrdered");

		normalReset(name1);
		interestList.add(new Interest("/foo"));
		fc.handleInterests(interestList);
		fc.put(obj1);
		testExpected(queue.poll(), obj1);
		
		Log.info(Log.FAC_TEST, "Starting testPutsOrdered");
	} 
	
	@Test
	public void testRandomOrderPuts() throws Throwable {	
		Log.info(Log.FAC_TEST, "Starting testRandomOrderPuts");

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
		
		Log.info(Log.FAC_TEST, "Completed testRandomOrderPuts");
	}
	
	public class HighWaterHelper extends Thread {
		
		boolean _waitStarted = false;
		boolean _readyForOurWait = false;

		public void run() {
			synchronized (this) {
				_waitStarted = true;
				try {
					wait(SystemConfiguration.MAX_TIMEOUT);
				} catch (InterruptedException e) {}
			}
			try {
				while (!_readyForOurWait)
					Thread.sleep(10);
				_handle.get(segments[0].name(), 0);
			} catch (Exception e) {
				// Note this assertion is OK due to use of ThreadAssertionRunner
				synchronized (this) {
					notifyAll();
				}
				Assert.fail("Caught exception: " + e.getMessage());
			}
			synchronized (this) {
				notifyAll();
			}
		}
		
		public synchronized boolean getWaiting() {
			return _waitStarted;	
		}
		
		public synchronized void readyForOurWait() {
			_readyForOurWait = true;	
		}
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
	
	protected abstract void normalReset(ContentName n) throws IOException;
}
