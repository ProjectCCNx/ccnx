/*
 * A CCNx library test.
 *
 * Copyright (C) 2011 Palo Alto Research Center, Inc.
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

package org.ccnx.ccn.test.io;

import java.io.IOException;

import junit.framework.Assert;

import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.CCNInputStream;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.test.CCNTestBase;
import org.ccnx.ccn.test.CCNTestHelper;
import org.ccnx.ccn.test.ThreadAssertionRunner;
import org.junit.Test;

public class CCNInputStreamTest extends CCNTestBase {
	static CCNTestHelper testHelper = new CCNTestHelper(CCNInputStreamTest.class);
	
	@Test
	public void testTimeouts() throws Exception {
		Log.info("Started testTimeouts");

		ContentName testName = testHelper.getTestNamespace("testInput/timeouts");
		CCNInputStream cis = new CCNInputStream(testName, getHandle);
		long startTime = System.currentTimeMillis();
		cis.setTimeout(9000);
		try {
			cis.read();
		} catch (IOException e) {}
		Assert.assertTrue("Input stream timed out early", (System.currentTimeMillis() - startTime) > 9000);

		cis.close();
		
		ThreadAssertionRunner tar = new ThreadAssertionRunner(new Thread(new BackgroundStreamer()));
		tar.start();
		tar.join(SystemConfiguration.EXTRA_LONG_TIMEOUT * 2);

		Log.info("Completed testTimeouts");
	}
	
	protected class BackgroundStreamer implements Runnable {
		CCNInputStream _stream = null;
		
		public BackgroundStreamer() {
			ContentName testName = testHelper.getTestNamespace("testInput/no/timeout");
			try {
				_stream = new CCNInputStream(testName, getHandle);
			} catch (IOException e1) {
				Assert.fail(e1.getMessage());
			}
			_stream.setTimeout(SystemConfiguration.NO_TIMEOUT);
		}
		
		public void close() throws IOException {
			_stream.close();
		}

		public void run() {
			try {
				_stream.read();
			} catch (IOException e) {
				Assert.fail("Input stream timed out or read failed: " + e.getMessage());
			}
		}		
	}
}
