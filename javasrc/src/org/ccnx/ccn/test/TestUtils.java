/*
 * A CCNx library test.
 *
 * Copyright (C) 2012 Palo Alto Research Center, Inc.
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

import java.io.IOException;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.io.CCNAbstractInputStream;
import org.ccnx.ccn.io.content.CCNNetworkObject;
import org.ccnx.ccn.profiles.repo.RepositoryControl;
import org.junit.Assert;

/**
 * Miscellaneous static utilities for testing
 */
public class TestUtils {
	public static final int WAIT_TIME = 100;

	/**
	 * Check if object has been stored in a repo.  Waits up to EXTRA_LONG_TIMEOUT - if not stored
	 * by then - assertion failure.
	 *
	 * @param handle
	 * @param cno
	 * @throws IOException
	 */
	public static void checkObject(CCNHandle handle, CCNNetworkObject<?> cno) throws IOException {
		long startTime = System.currentTimeMillis();
		boolean ok = false;
		do {
			ok = RepositoryControl.localRepoSync(handle, cno);
			if (ok)
				break;
			try {
				Thread.sleep(WAIT_TIME);
			} catch (InterruptedException e) {}
		} while((System.currentTimeMillis() - startTime) < SystemConfiguration.EXTRA_LONG_TIMEOUT);
		if (!ok)
			Assert.fail("Couldn't sync object: " + cno.getBaseName());
	}

	/**
	 * Check if file has been stored in a repo. Waits up to EXTRA_LONG_TIMEOUT - if not stored
	 * by then - assertion failure.
	 *
	 * @param handle
	 * @param stream
	 * @throws IOException
	 */
	public static void checkFile(CCNHandle handle, CCNAbstractInputStream stream) throws IOException {
		long startTime = System.currentTimeMillis();
		boolean ok = false;
		do {
			ok = RepositoryControl.localRepoSync(handle, stream);
			if (ok)
				break;
			try {
				Thread.sleep(WAIT_TIME);
			} catch (InterruptedException e) {}
		} while((System.currentTimeMillis() - startTime) < SystemConfiguration.EXTRA_LONG_TIMEOUT);
		if (!ok)
			Assert.fail("Couldn't sync stream: " + stream.getBaseName());
	}
}
