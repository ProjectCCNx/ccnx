/*
 * A CCNx library test.
 *
 * Copyright (C) 2010, 2011 Palo Alto Research Center, Inc.
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
package org.ccnx.ccn.test.profiles.context;


import junit.framework.Assert;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.impl.CCNFlowControl.SaveType;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.CCNStringObject;
import org.ccnx.ccn.profiles.context.ServiceDiscoveryProfile;
import org.ccnx.ccn.profiles.repo.RepositoryControl;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.test.CCNTestHelper;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Test writing data under the localhost scope in the local repo and reading
 * it back again.
 */
public class LocalDataTestRepo {
	
	static CCNHandle defaultHandle;
	static CCNHandle readHandle;
	static CCNTestHelper testHelper = new CCNTestHelper(
					ServiceDiscoveryProfile.localhostScopeName(), 
					LocalDataTestRepo.class);

	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		defaultHandle = CCNHandle.getHandle();
		readHandle = CCNHandle.open();
	}
	
	@AfterClass
	public static void tearDownAfterClass() {
		readHandle.close();
	}
	
	@Test
	public void testWriteLocalData() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testWriteLocalData");

		ContentName localStringName = testHelper.getTestChildName("testWriteLocalData", "a string");
		
		CCNStringObject localString = new CCNStringObject(localStringName, "Some local data.", 
					SaveType.REPOSITORY, defaultHandle);
		localString.save();
		
		CCNStringObject readString = new CCNStringObject(localStringName, readHandle);
		
		Assert.assertTrue(readString.available());
			
		Boolean inRepo = RepositoryControl.localRepoSync(defaultHandle, localString);
		
		Assert.assertTrue("Data is in the repo", inRepo);
		
		Log.info(Log.FAC_TEST, "Completed testWriteLocalData");
	}

}
