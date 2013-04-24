/*
 * A CCNx library test.
 *
 * Copyright (C) 2008-2012 Palo Alto Research Center, Inc.
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

package org.ccnx.ccn.test.profiles.search;

import junit.framework.Assert;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.impl.CCNFlowControl.SaveType;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.CCNStringObject;
import org.ccnx.ccn.profiles.search.Pathfinder;
import org.ccnx.ccn.profiles.search.Pathfinder.SearchResults;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.test.CCNTestHelper;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class PathfinderTestRepo {
	
	static final String TARGET_POSTFIX = "/TheTarget";
	static ContentName TARGET_POSTFIX_NAME;
	
	static CCNTestHelper testHelper = new CCNTestHelper(PathfinderTestRepo.class);
	static CCNHandle writeHandle;
	static CCNHandle readHandle;
	
	/**
	 * @throws java.lang.Exception
	 */
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		writeHandle = CCNHandle.open();
		readHandle = CCNHandle.open();
		TARGET_POSTFIX_NAME  = ContentName.fromNative(TARGET_POSTFIX);
	}
	
	@AfterClass
	public static void tearDownAfterClass() {
		writeHandle.close();
		readHandle.close();
	}

	@Test
	public void testPathfinder() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testPathfinder");

		// Make the content
		ContentName testRoot = testHelper.getTestNamespace("testPathfinder");
		ContentName startingPoint = new ContentName(testRoot, "This", "is", "a", "longer", "path", "than", "necessary.");
		
		CCNStringObject targetObject = new CCNStringObject(
				new ContentName(startingPoint.parent().parent().parent(), TARGET_POSTFIX_NAME), "The target!", SaveType.REPOSITORY, writeHandle);
		targetObject.save();
		
		Pathfinder finder = new Pathfinder(startingPoint,null, TARGET_POSTFIX_NAME, true, false, 
									SystemConfiguration.SHORT_TIMEOUT, null, readHandle);
		SearchResults results = finder.waitForResults();
		Assert.assertNotNull(results.getResult());
		
		Log.info(Log.FAC_TEST, "Completed testPathfinder");
	}
	

}
