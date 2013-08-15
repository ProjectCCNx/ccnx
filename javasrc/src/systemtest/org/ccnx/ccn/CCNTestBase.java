/*
 * A CCNx library test.
 *
 * Copyright (C) 2008, 2009, 2011-2013 Palo Alto Research Center, Inc.
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

package org.ccnx.ccn;

import java.io.IOException;

import org.ccnx.ccn.config.ConfigurationException;
import org.junit.AfterClass;
import org.junit.BeforeClass;

/**
 * Set up handles that can be used generically
 * TODO this should be renamed
 */
public class CCNTestBase {
	
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
