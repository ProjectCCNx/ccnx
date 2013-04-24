/*
 * A CCNx library test.
 *
 * Copyright (C) 2011-2012 Palo Alto Research Center, Inc.
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
package org.ccnx.ccn.test;

import junit.framework.Assert;
import junit.framework.AssertionFailedError;

import org.ccnx.ccn.CCNContentHandler;
import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.CCNInterestHandler;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Interest;
import org.junit.Before;
import org.junit.Test;

public class AssertionCCNHandleTest {
	static CCNHandle putHandle;
	static CCNTestHelper testHelper = new CCNTestHelper(AssertionCCNHandleTest.class);
	static final int WAIT_TIME = 500;
	
	@Before
	public void setUp() throws Exception {
		putHandle = CCNHandle.open();
	}
	
	@Test
	public void testFilterListenerNoError() throws Exception {
		AssertionCCNHandle getHandle = AssertionCCNHandle.open();
		ContentName filter = testHelper.getTestNamespace("testNoError");
		FilterListenerTester flt = new FilterListenerTester();
		getHandle.registerFilter(filter, flt);
		putHandle.expressInterest(new Interest(filter), new InterestListenerTester());
		getHandle.checkError(WAIT_TIME);
	}
	
	@Test
	public void testFilterListenerAssertError() throws Exception {
		AssertionCCNHandle getHandle = AssertionCCNHandle.open();
		ContentName filter = testHelper.getTestNamespace("testFilterListenerAssertError");
		FilterListenerTester flt = new FilterListenerTester();
		getHandle.registerFilter(filter, flt);
		putHandle.expressInterest(new Interest(filter), new InterestListenerTester());
		getHandle.checkError(WAIT_TIME);
		ContentName pastFilter = new ContentName(filter, "pastFilter");
		putHandle.expressInterest(new Interest(pastFilter), new InterestListenerTester());
		try {
			getHandle.checkError(WAIT_TIME);
		} catch (AssertionFailedError afe) {
			return;
		}
		Assert.fail("Missed an assertion error we should have seen");
	}
	
	private class InterestListenerTester implements CCNContentHandler {
		public Interest handleContent(ContentObject data, Interest interest) {
			// TODO Auto-generated method stub
			return null;
		}
	}
	
	private class FilterListenerTester implements CCNInterestHandler {
		private int _interestsSeen = 0;
		public boolean handleInterest(Interest interest) {
			Assert.assertTrue("Assertion in handleInterest", ++_interestsSeen < 2);
			return false;
		}
		
	}
}
