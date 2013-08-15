/*
 * A CCNx library test.
 *
 * Copyright (C) 2009-2011, 2013 Palo Alto Research Center, Inc.
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

package org.ccnx.ccn.profiles.ccnd;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import org.ccnx.ccn.LibraryTestBase;
import org.ccnx.ccn.impl.CCNNetworkManager;
import org.ccnx.ccn.impl.CCNNetworkManager.NetworkProtocol;
import org.ccnx.ccn.impl.support.Log;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Test basic version manipulation.
 */
public class FaceManagerTest extends LibraryTestBase {
	
	FaceManager fm;


	/**
	 * @throws java.lang.Exception
	 */
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		LibraryTestBase.setUpBeforeClass();
	}

	/**
	 * @throws java.lang.Exception
	 */
	@AfterClass
	public static void tearDownAfterClass() throws Exception {
		LibraryTestBase.tearDownAfterClass();
	}

	/**
	 * @throws java.lang.Exception
	 */
	@Before
	public void setUp() throws Exception {
		fm = new FaceManager();
	}

	/**
	 * @throws java.lang.Exception
	 */
	@After
	public void tearDown() throws Exception {
	}
	
	@Test
	public void testCreation() {
		Log.info(Log.FAC_TEST, "Starting testCreation");

		Integer faceID = new Integer(-142);
		FaceManager mgr = null;
		try {
			mgr = new FaceManager(putHandle);
			faceID = mgr.createFace(NetworkProtocol.UDP, "10.1.1.1", new Integer(CCNNetworkManager.DEFAULT_AGENT_PORT));
			System.out.println("Created face: " + faceID);
		} catch (CCNDaemonException e) {
			System.out.println("Exception " + e.getClass().getName() + ", message: " + e.getMessage());
			System.out.println("Failed to create face.");
			e.printStackTrace();
			fail("Failed to create face.");
		}
		assertNotNull(mgr);
		try {
			mgr.deleteFace(faceID);
		}catch (CCNDaemonException e) {
			System.out.println("Exception " + e.getClass().getName() + ", message: " + e.getMessage());
			System.out.println("Failed to delete face.");
			e.printStackTrace();
			fail("Failed to delete face.");
		}
		
		try {
			mgr.deleteFace(faceID);
			fail("Failed to receive expected CCNDaemonException deleting already deleted face.");
		}catch (CCNDaemonException e) {
			System.out.println("Received expected exception " + e.getClass().getName() + ", message: " + e.getMessage());
		}
		
		Log.info(Log.FAC_TEST, "Completed testCreation");
	}
}
