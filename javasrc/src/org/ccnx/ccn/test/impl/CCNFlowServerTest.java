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
import java.util.Queue;

import junit.framework.Assert;

import org.ccnx.ccn.impl.CCNFlowServer;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Interest;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

public class CCNFlowServerTest extends CCNFlowControlTestBase {

	Queue<ContentObject> queue = _handle.getOutputQueue();
	CCNFlowServer fc = null;

	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		CCNFlowControlTestBase.setUpBeforeClass();
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
}
