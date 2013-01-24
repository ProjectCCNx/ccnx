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
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import junit.framework.Assert;

import org.ccnx.ccn.CCNFilterListener;
import org.ccnx.ccn.CCNInterestListener;
import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Interest;
import org.ccnx.ccn.test.CCNTestBase;
import org.ccnx.ccn.test.CCNTestHelper;
import org.junit.Test;

/**
 * Since the deprecated interfaces need coding support, we need to test they still work
 * as long as they aren't removed.
 */
@SuppressWarnings("deprecation")
public class DeprecatedInterfaceTest extends CCNTestBase implements CCNFilterListener, CCNInterestListener {

	static final int QUICK_TIMEOUT = 400;
	static final int MORE_THAN_RETRY_TIMEOUT = SystemConfiguration.INTEREST_REEXPRESSION_DEFAULT * 2;
	static CCNTestHelper testHelper = new CCNTestHelper(DeprecatedInterfaceTest.class);
	static ContentName prefix = testHelper.getTestNamespace("testDeprecatedInterfaces");

	boolean sawInterest = false;
	boolean sawContent = false;
	Semaphore interestSema = null;
	Semaphore contentSema = null;
	boolean putNow = false;
	int counter = 0;

	@Test
	public void testDeprecatedMethods() throws Throwable {
		Log.info(Log.FAC_TEST, "Starting testDeprecatedMethods");

		interestSema = new Semaphore(0);
		contentSema = new Semaphore(0);
		Interest interest = new Interest(prefix);

		// Check that we can register a filter and see an interest using the old interface
		putHandle.registerFilter(prefix, this);
		getHandle.expressInterest(interest, this);
		Assert.assertTrue("Couldnt get semaphore", interestSema.tryAcquire(QUICK_TIMEOUT, TimeUnit.MILLISECONDS));
		Assert.assertTrue("Interest never seen", sawInterest);

		// Check that we can see content using the old interface - we wait for interest reexpression
		// to get it
		sawInterest = false;
		putNow = true;
		contentSema.drainPermits();
		Assert.assertTrue("Couldn't get semaphore", contentSema.tryAcquire(MORE_THAN_RETRY_TIMEOUT, TimeUnit.MILLISECONDS));
		getHandle.checkError(0);
		Assert.assertTrue("Content never seen", sawContent);

		// Make sure that we don't get back content after we cancel the interest
		Interest nextInterest = new Interest(new ContentName(prefix, 
				Integer.toString(counter)));
		getHandle.expressInterest(nextInterest, this);
		sawContent = false;
		putNow = true;
		getHandle.cancelInterest(nextInterest, this);
		Assert.assertFalse("Should not have got the semaphore", contentSema.tryAcquire(MORE_THAN_RETRY_TIMEOUT, TimeUnit.MILLISECONDS));
		getHandle.checkError(0);
		Assert.assertFalse("Content seen when it should not have been", sawContent);

		// Now check that we don't see an interest after we unregister its filter
		interestSema.drainPermits();
		sawInterest = false;
		nextInterest = new Interest(new ContentName(prefix, Integer.toString(counter)));
		putHandle.unregisterFilter(prefix, this);
		getHandle.expressInterest(nextInterest, this);
		Assert.assertFalse("Should not have got the semaphore", interestSema.tryAcquire(MORE_THAN_RETRY_TIMEOUT, TimeUnit.MILLISECONDS));
		getHandle.checkError(0);
		Assert.assertFalse("Interest seen after cancel", sawInterest);
		getHandle.cancelInterest(nextInterest, this);

		Log.info(Log.FAC_TEST, "Completed testDeprecatedMethods");
	}

	public boolean handleInterest(Interest interest) {
		Log.info(Log.FAC_TEST, "Saw interest: {0}", interest.name());
		sawInterest = true;
		interestSema.release();
		if (putNow) {
			ContentObject co = ContentObject.buildContentObject(new ContentName(prefix, 
						Integer.toString(counter++)), "deprecationTest".getBytes());
			try {
				putNow = false;
				putHandle.put(co);
			} catch (IOException e) {
				Assert.fail(e.getMessage());
			}
		}
		return true;
	}

	public Interest handleContent(ContentObject data, Interest interest) {
		Log.info(Log.FAC_TEST, "Saw content: {0}", data.name());
		sawContent = true;
		contentSema.release();
		return null;
	}
}
