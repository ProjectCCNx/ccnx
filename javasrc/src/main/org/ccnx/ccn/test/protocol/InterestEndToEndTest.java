/*
 * A CCNx library test.
 *
 * Copyright (C) 2008-2012 Palo Alto Research Center, Inc.
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

import java.io.IOException;

import org.ccnx.ccn.CCNContentHandler;
import org.ccnx.ccn.CCNInterestHandler;
import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.impl.support.ConcurrencyUtils.Waiter;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Exclude;
import org.ccnx.ccn.protocol.Interest;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;
import org.ccnx.ccn.test.LibraryTestBase;
import org.junit.Assert;
import org.junit.Test;



/**
 * Test sending interests across ccnd.
 * Requires a running ccnd
 *
 */
public class InterestEndToEndTest extends LibraryTestBase implements CCNInterestHandler, CCNContentHandler {
	private Interest _interestSent;
	private String _prefix = "/interestEtoETest/test-" + rand.nextInt(10000);
	private final static int TIMEOUT = 3000;
	
	private int interestCount = 0;
	
	private ContentObject toReturn;

	
	@Test
	public void testInterestEndToEnd() throws MalformedContentNameStringException, IOException, InterruptedException {
		Log.info(Log.FAC_TEST, "Starting testInterestEndToEnd");

		getHandle.registerFilter(ContentName.fromNative(_prefix), this);
		_interestSent = new Interest(ContentName.fromNative(_prefix + "/simpleTest"));
		doTest(1);
		System.out.println("count: "+interestCount);

		_interestSent = new Interest(ContentName.fromNative(_prefix + "/simpleTest2"));
		_interestSent.maxSuffixComponents(4);
		_interestSent.minSuffixComponents(3);
		doTest(2);
		System.out.println("count: "+interestCount);

		_interestSent = new Interest(ContentName.fromNative(_prefix + "/simpleTest2"));
		_interestSent.maxSuffixComponents(1);
		doTest(3);
		System.out.println("count: "+interestCount);

		getHandle.unregisterFilter(ContentName.fromNative(_prefix), this);
		_interestSent = new Interest(ContentName.fromNative(_prefix + "/simpleTest"));
		doTestFail(4);
		System.out.println("count: "+interestCount);
		
		Log.info(Log.FAC_TEST, "Completed testInterestEndToEnd");
	}

	@Test
	public void testExcludeDigestEndToEnd() throws MalformedContentNameStringException, IOException {
		Log.info(Log.FAC_TEST, "Starting testExcludeDigestEndToEnd");
		ContentName cname = ContentName.fromNative(_prefix +"/excludeDigest");
		
		putHandle.registerFilter(ContentName.fromNative(_prefix), this);
			
		Interest firstInterest = Interest.constructInterest(cname, null, null, null, null, null);
		
		ContentObject co = ContentObject.buildContentObject(cname, "here is content 1".getBytes());
		ContentObject co2 = ContentObject.buildContentObject(cname, "Here is content 2".getBytes());
		toReturn = co;
		
		ContentObject returned = getHandle.get(firstInterest, SystemConfiguration.MEDIUM_TIMEOUT);
		Assert.assertNotNull(returned);

		Interest excludeInterest =  Interest.constructInterest(cname, new Exclude(), null, null, null, null);
		excludeInterest.exclude().add(new byte[][] {returned.digest()});
		
		toReturn = co2;
		
		returned = getHandle.get(excludeInterest, SystemConfiguration.MEDIUM_TIMEOUT);
		Assert.assertEquals(co2, returned);
		
		toReturn = null;
		putHandle.unregisterFilter(ContentName.fromNative(_prefix), this);
	
		Log.info(Log.FAC_TEST, "Completed testExcludeDigestEndToEnd");
	}
	
	public boolean handleInterest(Interest interest) {
		if (toReturn != null && interest.matches(toReturn)) {
			try {
				putHandle.put(toReturn);
			} catch (IOException e) {
				Assert.fail("IOException while handling testExcludeDigestEndToEnd interest: "+e.getMessage());
			}
			return true;
		}
		Assert.assertTrue(_interestSent.equals(interest));
		synchronized(this) {
			interestCount++;
			notify();
		}
		return true;
	}
	
	public Interest handleContent(ContentObject data,
			Interest interest) {
		// TODO Auto-generated method stub
		return null;
	}	
	
	private void doTest(int c) throws IOException, InterruptedException {
		long startTime = System.currentTimeMillis();
		putHandle.expressInterest(_interestSent, this);
		doWait(c);
		long stopTime = System.currentTimeMillis();
		long duration = stopTime - startTime;
		System.out.println("doTest time: "+duration+" and count:" +interestCount +" should be "+c);
		Assert.assertTrue(interestCount == c);
		Assert.assertTrue(duration < TIMEOUT + (int)(TIMEOUT*0.1));
	}

	private void doTestFail(int c) throws IOException, InterruptedException {
		long startTime = System.currentTimeMillis();
		putHandle.expressInterest(_interestSent, this);
		doWait(c);
		long stopTime = System.currentTimeMillis();
		long duration = stopTime - startTime;
		System.out.println("doTestFail time: "+duration+" and count:" +interestCount +" should not be "+c);

		Assert.assertTrue(interestCount != c);
		//could be slightly less, no guarantees.  API says "more or less" after timeout
		Assert.assertFalse(duration < TIMEOUT - (int)(TIMEOUT*0.01));
	}
	
	private void doWait(int c) {
		try {
			new Waiter(TIMEOUT) {
				@Override
				protected boolean check(Object o, Object check) throws Exception {
					return (Integer)check == interestCount;
				}
			}.wait(this, c);
		} catch (Exception e) {} // Can't happen
	}
}
