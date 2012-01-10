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
package org.ccnx.ccn.test.repo;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.impl.CCNFlowServer;
import org.ccnx.ccn.impl.repo.RepositoryInfo;
import org.ccnx.ccn.impl.support.Log;

import static org.ccnx.ccn.impl.repo.RepositoryInfo.RepoInfoType.DATA;
import org.ccnx.ccn.io.CCNOutputStream;
import static org.ccnx.ccn.profiles.repo.RepositoryControl.doLocalCheckedWrite;
import org.ccnx.ccn.protocol.ContentName;
import org.junit.Assert;
import org.junit.Test;

public class CheckedWriteTest {

	@Test
	public void testCheckedWrite() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testCheckedWrite");

		CCNHandle handle = CCNHandle.getHandle();
		ContentName baseName = ContentName.fromNative("/testChecked");
		CCNFlowServer server = new CCNFlowServer(baseName, 1, true, handle);
		CCNOutputStream os = new CCNOutputStream(baseName, null, null, null, null, server);
		os.close();

		Long startingSegmentNumber = 0L;

		RepositoryInfo ri = doLocalCheckedWrite(baseName, startingSegmentNumber, os.getFirstDigest(), handle);
		Assert.assertFalse(ri.getType() == DATA);

		Thread.sleep(2000);

		ri = doLocalCheckedWrite(baseName, startingSegmentNumber, os.getFirstDigest(), handle);
		Assert.assertTrue(ri.getType() == DATA);
		
		Log.info(Log.FAC_TEST, "Completed testCheckedWrite");
	}
}
