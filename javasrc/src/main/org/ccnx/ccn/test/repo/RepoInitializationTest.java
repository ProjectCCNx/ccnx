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

package org.ccnx.ccn.test.repo;

import java.io.IOException;

import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.protocol.ContentName;
import org.junit.Assert;
import org.junit.Test;


/**
 * Test to ensure repo is up before continuing
 */
public class RepoInitializationTest extends RepoTestBase {
	public static final int TEST_TIME = 15000; // ms - 30 seconds
	public static final int TEST_INTERVAL = 3000;
	public int countDown = TEST_TIME;
	
	@Test
	public void testRepoIsUp() throws Throwable {
		Log.info(Log.FAC_TEST, "Starting testRepoIsUp");

		while (countDown >= 0) {
			try {
				writeToRepo(ContentName.fromNative("/repoTest/upTest"));
				Log.info(Log.FAC_TEST, "Completed testRepoIsUp");
				return;
			} catch (IOException ioe) {}
			Thread.sleep(TEST_INTERVAL);
			countDown -= TEST_INTERVAL;
		}
		Assert.fail("Repo never came up");
	}
}
