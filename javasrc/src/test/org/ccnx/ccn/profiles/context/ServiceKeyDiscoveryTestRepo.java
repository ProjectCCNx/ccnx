/*
 * A CCNx library test.
 *
 * Copyright (C) 2010-2012 Palo Alto Research Center, Inc.
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

import java.io.File;
import java.util.ArrayList;

import junit.framework.Assert;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.config.UserConfiguration;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.profiles.context.ServiceDiscoveryProfile;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.test.CCNTestBase;
import org.ccnx.ccn.utils.CreateUserData;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Test localhost key discovery. Need to use a multi-user test to find multiple keys.
 *
 */
public class ServiceKeyDiscoveryTestRepo {
	
	public static final String PUBLISHED_SERVICE = "PuffyPoodles";
	public static final String OTHER_PUBLISHED_SERVICE = "FluffyKittens";
	public static final String NOT_A_SERVICE = "NotAService";
	
	static CreateUserData serviceProviders = null;
	public static final String USER_DIRECTORY = "TestUsers";
	public static final String [] SERVICE_PROVIDERS = new String[]{
		"PoodleServer", "KittenServer", "Repository", "Server"};

	public static final int TEST_TIMEOUT = SystemConfiguration.MEDIUM_TIMEOUT;
	
	public static String _testDir = "./";
	
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		if (null != System.getProperty(CCNTestBase.TEST_DIR))
			_testDir = System.getProperty(CCNTestBase.TEST_DIR);
		UserConfiguration.setPublishKeys(false);
		serviceProviders = new CreateUserData(new File(_testDir + USER_DIRECTORY), SERVICE_PROVIDERS, SERVICE_PROVIDERS.length,
				UserConfiguration.keystorePassword().toCharArray(), true);
	}

	@Test
	public void testGetLocalServiceKeys() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testGetLocalServiceKeys");

		CCNHandle server1Handle = serviceProviders.getHandleForUser(SERVICE_PROVIDERS[0]);
		CCNHandle server2Handle = serviceProviders.getHandleForUser(SERVICE_PROVIDERS[1]);
		CCNHandle server3Handle = serviceProviders.getHandleForUser(SERVICE_PROVIDERS[2]);
		CCNHandle readerHandle = CCNHandle.getHandle(); // use default
		
		ServiceDiscoveryProfile.publishLocalServiceKey(PUBLISHED_SERVICE, null, server1Handle.keyManager());
		
		ArrayList<ContentObject> results = ServiceDiscoveryProfile.getLocalServiceKeys(PUBLISHED_SERVICE, 
				TEST_TIMEOUT, readerHandle);
		
		printResults(results);
		Assert.assertEquals(1, results.size());
		
		// Expect to get nothing back.
		results = ServiceDiscoveryProfile.getLocalServiceKeys(NOT_A_SERVICE, 
				TEST_TIMEOUT, readerHandle);
		
		Assert.assertEquals(0, results.size());
		System.out.println("Got no results for a nonexistent service.");
		
		ServiceDiscoveryProfile.publishLocalServiceKey(OTHER_PUBLISHED_SERVICE, null, server1Handle.keyManager());
		results = ServiceDiscoveryProfile.getLocalServiceKeys(OTHER_PUBLISHED_SERVICE, 
				TEST_TIMEOUT, readerHandle);
		
		printResults(results);
		Assert.assertEquals(1, results.size());		

		ServiceDiscoveryProfile.publishLocalServiceKey(PUBLISHED_SERVICE, null, server2Handle.keyManager());
		ServiceDiscoveryProfile.publishLocalServiceKey(PUBLISHED_SERVICE, null, server3Handle.keyManager());
		
		results = ServiceDiscoveryProfile.getLocalServiceKeys(PUBLISHED_SERVICE, 
				TEST_TIMEOUT, readerHandle);
		
		printResults(results);
		Assert.assertEquals(3, results.size());
		
		Log.info(Log.FAC_TEST, "Completed testGetLocalServiceKeys");
	}
	
	@Test
	public void testGetRepoKeys() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testGetRepoKeys");

		CCNHandle readerHandle = CCNHandle.getHandle(); // use default
		
		System.out.println("Retrieving " + ServiceDiscoveryProfile.REPOSITORY_SERVICE_NAME + " keys.");
		// we required the repo to be running merely so we can get its keys
		ArrayList<ContentObject> results = 
			ServiceDiscoveryProfile.getLocalServiceKeys(ServiceDiscoveryProfile.REPOSITORY_SERVICE_NAME, 
					TEST_TIMEOUT, readerHandle);
		printResults(results);
		Assert.assertEquals(1, results.size());		
		
		Log.info(Log.FAC_TEST, "Completed testGetRepoKeys");
	}

	@Test
	public void testGetCcndKeys() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testGetCcndKeys");

		CCNHandle readerHandle = CCNHandle.getHandle(); // use default
		
		System.out.println("Retrieving " + ServiceDiscoveryProfile.CCND_SERVICE_NAME + " keys.");
		ArrayList<ContentObject> results = 
			ServiceDiscoveryProfile.getLocalServiceKeys(ServiceDiscoveryProfile.CCND_SERVICE_NAME, 
					TEST_TIMEOUT, readerHandle);
		
		printResults(results);
		Assert.assertEquals(1, results.size());
		
		Log.info(Log.FAC_TEST, "Completed testGetCcndKeys");
	
	}

	public void printResults(ArrayList<ContentObject> results) {
		if (null == results) 
			return;
		if (0 == results.size()) {
			System.out.println("No results found.");
			return;
		}
		System.out.println("Found " + results.size() + 
				((results.size() == 1) ? " key" : " keys") + " for a local service of name " + 
				ServiceDiscoveryProfile.getLocalServiceName(results.get(0).name()));
		for (ContentObject result : results) {
			System.out.println("Result: " + result.name());
		}
	}
}
