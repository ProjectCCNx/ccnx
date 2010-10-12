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

package org.ccnx.ccn.test.endtoend;

import static org.junit.Assert.assertEquals;

import java.util.Random;

import org.ccnx.ccn.CCNInterestListener;
import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Interest;
import org.junit.Test;


/**
 * Part of the end to end test infrastructure.
 * NOTE: This test requires ccnd to be running and complementary source process
 */
public class EndToEndTestSink extends BaseLibrarySink implements CCNInterestListener {
	
	@Test
	public void sink() throws Throwable {
		gets();
		server();
	}
	
	public void gets() throws Throwable {
		Log.info("Get sequence started");
		Random rand = new Random();
		for (int i = 0; i < BaseLibrarySource.count; i++) {
			Thread.sleep(rand.nextInt(50));
			ContentObject contents = handle.get(ContentName.fromNative("/BaseLibraryTest/gets/" + i), SystemConfiguration.NO_TIMEOUT);
			int value = contents.content()[0];
			// Note that we cannot be guaranteed to pick up every value:
			// due to timing we may miss a value that arrives while we are not
			// in the get()
			assertEquals(true, value >= i);
			i = value;
			Log.info("Got " + i);
			checkGetResults(contents);
		}
		System.out.println("Get sequence finished");
	}
	
	public void server() throws Throwable {
		Log.info("GetServer started");
		Interest interest = new Interest("/BaseLibraryTest/server");
		// Register interest
		handle.expressInterest(interest, this);
		// Block on semaphore until enough data has been received
		sema.acquire();
		handle.cancelInterest(interest, this);
		if (null != error) {
			throw error;
		}
	}
	
	public synchronized Interest handleContent(ContentObject contentObject, Interest matchInterest) {
		Interest interest = null;
		try {
			String objString = contentObject.name().toString();
			interest = new Interest(objString.substring(0, "/BaseLibraryTest/server".length()) + "/" + new Integer(next).toString());
			// Register interest
			checkGetResults(contentObject);
			if (next >= BaseLibrarySource.count) {
				sema.release();
			}
			next++;
		} catch (Throwable e) {
			error = e;
		}
		return interest;
	}
}
