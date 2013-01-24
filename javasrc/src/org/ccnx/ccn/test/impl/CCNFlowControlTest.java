/*
 * A CCNx library test.
 *
 * Copyright (C) 2008-2011, 2013 Palo Alto Research Center, Inc.
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

import junit.framework.Assert;

import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.impl.CCNFlowControl;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Interest;
import org.ccnx.ccn.test.ThreadAssertionRunner;
import org.junit.Before;
import org.junit.Test;

/**
 * Test flow controller functionality.
 */
public class CCNFlowControlTest extends CCNFlowControlTestBase {
	
	@Before
	public void setUp() throws Exception {
		fc = new CCNFlowControl(_handle);
	}

	/**
	 * Test method for an order case that failed in practice for 
	 * RepoIOTest when matching was broken.
	 * @throws Throwable
	 */
	@Test
	public void testMixedOrderInterestPut() throws Throwable {	
		Log.info(Log.FAC_TEST, "Starting testMixedOrderInterestPut");
		normalReset(name1);
		
		// First one normal order exchange: put first, interest next
		fc.put(segments[0]);
		ContentObject co = testExpected(_handle.get(segment_names[0], 0), segments[0]);

		// Next we get the interest for the next segment before the data
		interestList.add(Interest.next(co.name(), 3, null));
		fc.handleInterests(interestList);

		// Data arrives for the waiting interest, should be sent out
		fc.put(segments[1]);
		testExpected(queue.poll(), segments[1]);

		// Remainder in order, puts first
		fc.put(segments[2]);
		co = testNext(co, segments[2]);
		fc.put(segments[3]);
		co = testNext(co, segments[3]);
		Log.info(Log.FAC_TEST, "Completed testMixedOrderInterestPut");
	}

	@Test
	public void testWaitForPutDrain() throws Throwable {	
		Log.info(Log.FAC_TEST, "Starting testWaitForPutDrain");
		normalReset(name1);
		fc.put(segments[1]);
		fc.put(segments[3]);
		fc.put(segments[0]);
		fc.put(segments[2]);
		testLast(segments[0], segments[3]);
		testLast(segments[0], segments[2]);
		testLast(segments[0], segments[1]);
		ContentObject lastOne = _handle.get(new Interest(segment_names[0]), 0);
		Log.info(Log.FAC_TEST, "Retrieved final object {0}, blocks still in fc: {1}", lastOne.name(), fc.getCapacity()-fc.availableCapacity());
		
		try {
			// can't call waitForPutDrain directly; call it via afterClose
			fc.afterClose();
		} catch (IOException ioe) {
			Assert.fail("WaitforPutDrain threw unexpected exception");
		}
		fc.put(obj1);
		try {
			// can't call waitForPutDrain directly; call it via afterClose
			fc.afterClose();
			Assert.fail("WaitforPutDrain succeeded when it should have failed");
		} catch (IOException ioe) {}
		Log.info(Log.FAC_TEST, "Completed testWaitForPutDrain");
	}
	
	@Test
	public void testHighwaterWait() throws Throwable {
		Log.info(Log.FAC_TEST, "Starting testHighwaterWait");
		
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
		
		// Test that put over highwater succeeds when buffer is
		// drained
		normalReset(name1);
		fc.setCapacity(4);
		fc.put(segments[0]);
		fc.put(segments[1]);
		fc.put(segments[2]);

		HighWaterHelper hwh = new HighWaterHelper();
		ThreadAssertionRunner tar = new ThreadAssertionRunner(hwh);
		tar.start();
		fc.put(segments[3]);
		synchronized (hwh) {
			hwh.notify();
		}
		hwh.readyForOurWait();
		synchronized (hwh) {
			hwh.wait(SystemConfiguration.MAX_TIMEOUT);
		}
		fc.put(segments[4]);
		tar.join();
		Log.info(Log.FAC_TEST, "Completed testMixedOrderInterestPut");
	}
	
	protected void normalReset(ContentName n) throws IOException {
		_handle.reset();
		interestList.clear();
		fc = new CCNFlowControl(n, _handle);
	}
}
