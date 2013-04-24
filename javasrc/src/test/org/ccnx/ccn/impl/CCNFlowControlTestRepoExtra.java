/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2011, 2012 Palo Alto Research Center, Inc.
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License version 2.1
 * as published by the Free Software Foundation. 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details. You should have received
 * a copy of the GNU Lesser General Public License along with this library;
 * if not, write to the Free Software Foundation, Inc., 51 Franklin Street,
 * Fifth Floor, Boston, MA 02110-1301 USA.
 */
package org.ccnx.ccn.test.impl;

import java.io.FileInputStream;
import java.io.InputStream;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.impl.CCNStats;
import org.ccnx.ccn.io.RepositoryFileOutputStream;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.test.CCNTestHelper;
import org.ccnx.ccn.test.io.CCNFileStreamTestRepo;
import org.ccnx.ccn.utils.CommonParameters;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

/*
 * This test requires the file "testBigFile" to exist in the directory where the test is
 * executed. The automated tests generate this file. The file should be quite large though
 * its exact size is unimportant. The automated tests generate a 50m file
 */

public class CCNFlowControlTestRepoExtra {
	static final int N_BUFFERS = 10000;
	static ContentName _nodeName;
	static CCNTestHelper testHelper = new CCNTestHelper(CCNFileStreamTestRepo.class);
	static CCNHandle _putHandle;
	static String _fileName = "testBigFile";
	
	/**
	 * @throws java.lang.Exception
	 */
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		_putHandle = CCNHandle.open();
		_nodeName = ContentName.fromURI("ccn:/" + testHelper.getClassNamespace() + "/" + "bigFileTest");
	}

	/**
	 * This test is able to successfully detect concurrency errors in CCNFlowControl. There might be a
	 * simpler way to do it but I haven't been able to figure out how to do that.
	 * 
	 * @throws Throwable
	 */
	@Test
	public void testLargePut() throws Throwable {
		int size = CommonParameters.BLOCK_SIZE;
		InputStream is = new FileInputStream(_fileName);
		RepositoryFileOutputStream ostream = new RepositoryFileOutputStream(_nodeName, _putHandle, CommonParameters.local);

		int readLen = 0;
		byte [] buffer = new byte[CommonParameters.BLOCK_SIZE];
		while ((readLen = is.read(buffer, 0, size)) != -1){
			ostream.write(buffer, 0, readLen);
		}
		ostream.close();
		CCNStats stats = _putHandle.getNetworkManager().getStats();
		Assert.assertEquals(0, stats.getCounter("DeliverInterestFailed"));
	}
}
