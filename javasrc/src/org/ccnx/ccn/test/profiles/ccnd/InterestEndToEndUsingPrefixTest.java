/*
 * A CCNx library test.
 *
 * Copyright (C) 2008, 2009, 2011 Palo Alto Research Center, Inc.
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

import org.ccnx.ccn.CCNContentHandler;
import org.ccnx.ccn.CCNInterestHandler;
import org.ccnx.ccn.impl.support.Log;
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
public class InterestEndToEndUsingPrefixTest extends LibraryTestBase implements CCNInterestHandler, CCNContentHandler {
	private Interest _interestSent;
	private String _prefix = "/interestEtoETestUsingPrefix/test-" + rand.nextInt(10000);
	private boolean _interestSeen = false;
	private final static int TIMEOUT = 3000;
	
	@Test
	public void testInterestEndToEnd() throws MalformedContentNameStringException, IOException, InterruptedException {
		Log.info(Log.FAC_TEST, "Starting testInterestEndToEnd");

		Interest i;
		getHandle.registerFilter(ContentName.fromNative(_prefix), this);
		i = new Interest(ContentName.fromNative(_prefix + "/simpleTest"));
		doTest(i);
		i = new Interest(ContentName.fromNative(_prefix + "/simpleTest2"));
		i.maxSuffixComponents(4);
		i.minSuffixComponents(3); 
		doTest(i);
		i = new Interest(ContentName.fromNative(_prefix + "/simpleTest3"));
		i.maxSuffixComponents(1);
		doTest(i);
		i = new Interest(ContentName.fromNative(_prefix + "/simpleTest4"));
		getHandle.unregisterFilter(ContentName.fromNative(_prefix), this);
		doTestFail(i);
		
		Log.info(Log.FAC_TEST, "Completed testInterestEndToEnd");
	}

	public boolean handleInterest(Interest interest) {
		synchronized(this) {
			if (! _interestSent.equals(interest))
				return false;
			_interestSeen = true;
			notify();
		}
		return true;
	}
	
	public Interest handleContent(ContentObject data,
			Interest interest) {
		return null;
	}
	
	private void doTest(Interest interest) throws IOException, InterruptedException {
		_interestSeen = false;
		_interestSent = interest;
		putHandle.expressInterest(interest, this);
		Thread.sleep(TIMEOUT);
		Assert.assertTrue(_interestSeen);
	}

	private void doTestFail(Interest interest) throws IOException, InterruptedException {
		_interestSeen = false;
		_interestSent = interest;
		putHandle.expressInterest(interest, this);
		Thread.sleep(TIMEOUT);
		Assert.assertFalse(_interestSeen);
	}

}
