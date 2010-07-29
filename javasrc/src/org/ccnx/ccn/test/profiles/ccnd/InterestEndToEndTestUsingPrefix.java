/*
 * A CCNx library test.
 *
 * Copyright (C) 2008, 2009 Palo Alto Research Center, Inc.
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

package org.ccnx.ccn.test.profiles.ccnd;

import java.io.IOException;
import org.ccnx.ccn.CCNFilterListener;
import org.ccnx.ccn.CCNInterestListener;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
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
public class InterestEndToEndTestUsingPrefix extends LibraryTestBase implements CCNFilterListener, CCNInterestListener {
	private Interest _interestSent;
	private String _prefix = "/interestEtoETestUsingPrefix/test-" + rand.nextInt(10000);
	private final static int TIMEOUT = 3000;

	
	@Test
	public void testInterestEndToEnd() throws MalformedContentNameStringException, IOException, InterruptedException {
		getHandle.registerFilter(ContentName.fromNative(_prefix), this);
		_interestSent = new Interest(ContentName.fromNative(_prefix + "/simpleTest"));
		doTest();
		_interestSent = new Interest(ContentName.fromNative(_prefix + "/simpleTest2"));
		_interestSent.maxSuffixComponents(4);
		_interestSent.minSuffixComponents(3);
		doTest();
		_interestSent = new Interest(ContentName.fromNative(_prefix + "/simpleTest2"));
		_interestSent.maxSuffixComponents(1);
		doTest();
		getHandle.unregisterFilter(ContentName.fromNative(_prefix), this);
		_interestSent = new Interest(ContentName.fromNative(_prefix + "/simpleTest"));
		doTestFail();
	}

	public boolean handleInterest(Interest interest) {
		Assert.assertTrue(_interestSent.equals(interest));
		synchronized(this) {
			notify();
		}
		return true;
	}
	
	public Interest handleContent(ContentObject data,
			Interest interest) {
		// TODO Auto-generated method stub
		return null;
	}
	
	private void doTest() throws IOException, InterruptedException {
		long startTime = System.currentTimeMillis();
		putHandle.expressInterest(_interestSent, this);
		synchronized (this) {
			wait(TIMEOUT);
		}
		Assert.assertTrue((System.currentTimeMillis() - startTime) < TIMEOUT);
	}

	private void doTestFail() throws IOException, InterruptedException {
		long startTime = System.currentTimeMillis();
		putHandle.expressInterest(_interestSent, this);
		synchronized (this) {
			wait(TIMEOUT);
		}
		Assert.assertFalse((System.currentTimeMillis() - startTime) < TIMEOUT);
	}

}
