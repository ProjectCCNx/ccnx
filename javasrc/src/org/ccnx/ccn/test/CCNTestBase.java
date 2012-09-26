/*
 * A CCNx library test.
 *
 * Copyright (C) 2008, 2009, 2011, 2012 Palo Alto Research Center, Inc.
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
import java.util.Random;

import org.ccnx.ccn.KeyManager;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.protocol.Signature;
import org.junit.AfterClass;
import org.junit.BeforeClass;

/**
 * A place to put generic things needed by different tests
 */
public class CCNTestBase {
	
	public static final String TEST_DIR = "ccn.test.dir";
	
	// A signature just used to allow tests to pass validation in which there are no keys
	// corresponding to the publisherID's used.
	static public Signature fakeSignature = null;
	
	protected static AssertionCCNHandle putHandle = null;
	protected static AssertionCCNHandle getHandle = null;
	
	protected static String _testDir;
	
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		try {
			putHandle = AssertionCCNHandle.open();
			getHandle = AssertionCCNHandle.open();
		} catch (ConfigurationException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}

		Random rnd = new Random();
		byte [] fakeSigBytes = new byte[128];
		byte [] publisher = new byte[32];
		rnd.nextBytes(fakeSigBytes);
		rnd.nextBytes(publisher);
		fakeSignature = new Signature(fakeSigBytes);
		
		// Let test directory  be set centrally so it can be overridden by property
		_testDir = System.getProperty(TEST_DIR);
		if (null == _testDir)
			_testDir = "./";
	}
	
	@AfterClass
	public static void tearDownAfterClass() throws Exception {
		if (null != putHandle)
			putHandle.close();
		if (null != getHandle)
			getHandle.close();
		KeyManager.closeDefaultKeyManager();
	}
}
