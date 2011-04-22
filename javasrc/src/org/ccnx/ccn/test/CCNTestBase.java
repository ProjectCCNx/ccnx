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

package org.ccnx.ccn.test;

import java.io.IOException;
import java.util.Random;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.KeyManager;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.impl.support.DataUtils.Waiter;
import org.ccnx.ccn.protocol.CCNTime;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.KeyLocator;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;
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
	
	protected static CCNHandle putHandle = null;
	protected static CCNHandle getHandle = null;
	
	protected static String _testDir;
	
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		try {
			putHandle = CCNHandle.open();
			getHandle = CCNHandle.open();
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
	
	/**
	 * Just wait for timeout ms handling interrupts
	 * @param timeout ms
	 * @throws InterruptedException 
	 */
	public void doWait(int timeout) throws InterruptedException {
		try {
			new Waiter(timeout) {
				@Override
				protected boolean check(Object o, Object check) throws Exception {
					return false;
				}
			}.wait(this, this);
		} catch (InterruptedException ie) {throw ie;}
		  catch (Exception e) {} // Others can't happen
	}
	
	@AfterClass
	public static void tearDownAfterClass() throws Exception {
		putHandle.close();
		getHandle.close();
		KeyManager.closeDefaultKeyManager();
	}
}
