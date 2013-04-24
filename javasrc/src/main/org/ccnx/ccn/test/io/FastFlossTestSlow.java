/*
 * A CCNx library test.
 *
 * Copyright (C) 2008-2013 Palo Alto Research Center, Inc.
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


import java.security.MessageDigest;
import java.util.Random;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.CCNOutputStream;
import org.ccnx.ccn.io.CCNVersionedInputStream;
import org.ccnx.ccn.io.CCNVersionedOutputStream;
import org.ccnx.ccn.io.RepositoryVersionedOutputStream;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.test.CCNTestHelper;
import org.ccnx.ccn.test.Flosser;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;


/**
 * Stress test comparing writing long streams to a Flosser and to a repository.
 * Deliberately named so that it will not be run as part of the normal test suite, as it
 * takes a long time.
 */
public class FastFlossTestSlow {
	
	public static final int BUF_SIZE = 1024;
	public static final int FILE_SIZE = 1024*1024; // bytes
	public static Random random = new Random();
	public static CCNHandle readLibrary;
	public static CCNHandle writeLibrary;
	public static CCNTestHelper testHelper = new CCNTestHelper(FastFlossTestSlow.class);

	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		readLibrary = CCNHandle.open();
		writeLibrary = CCNHandle.open();
	}
	
	@Test
	public void fastFlossTest() {
		Log.info(Log.FAC_TEST, "Starting fastFlossTest");

		Flosser flosser = null;
		try {
			ContentName namespace = testHelper.getTestNamespace("fastFlossTest");
			ContentName ns = new ContentName(namespace, "FlossFile");
			flosser = new Flosser(ns);
			CCNVersionedOutputStream vos = new CCNVersionedOutputStream(ns, writeLibrary);
			streamData(vos);
		} catch (Exception e) {
			Log.warning(Log.FAC_TEST, "Exception in test: " + e);
			Log.warningStackTrace(Log.FAC_TEST, e);
			Assert.fail();
		} finally {
			if (null != flosser)
				flosser.stop();
		}
		
		Log.info(Log.FAC_TEST, "Completed fastFlossTest");
	}
	
	@Test
	public void fastRepoTest() {
		Log.info(Log.FAC_TEST, "Starting fastRepoTest");

		try {
			ContentName namespace = testHelper.getTestNamespace("fastRepoTest");			
			ContentName ns = new ContentName(namespace, "RepoFile");
			RepositoryVersionedOutputStream vos = new RepositoryVersionedOutputStream(ns, writeLibrary);
			streamData(vos);
		} catch (Exception e) {
			Log.warning(Log.FAC_TEST, "Exception in test: " + e);
			Log.warningStackTrace(Log.FAC_TEST, e);
			Assert.fail();
		} finally {
		}
		
		Log.info(Log.FAC_TEST, "Completed fastRepoTest");
	}

	public void streamData(CCNOutputStream outputStream) throws Exception {
		Log.info(Log.FAC_TEST, "Streaming data to file " + outputStream.getBaseName() + 
					" using stream class: " + outputStream.getClass().getName());
		long elapsed = 0;
		byte [] buf = new byte[BUF_SIZE];
		MessageDigest digest = MessageDigest.getInstance("SHA-1");
		while (elapsed < FILE_SIZE) {
			random.nextBytes(buf);
			outputStream.write(buf);
			digest.update(buf);
			elapsed += BUF_SIZE;
		}
		outputStream.close();
		
		byte [] writeDigest = digest.digest(); // resets digest
		
		elapsed = 0;
		int read = 0;
		byte [] read_buf = new byte[BUF_SIZE]; // different increments might be useful for testing
		CCNVersionedInputStream vis = new CCNVersionedInputStream(outputStream.getBaseName(), readLibrary);
		while (elapsed < FILE_SIZE) {
			read = vis.read(read_buf);
			digest.update(read_buf, 0, read);
			elapsed += read;
		}
		
		byte [] readDigest = digest.digest();
		
		Assert.assertArrayEquals(writeDigest, readDigest);
	}
}
