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

package org.ccnx.ccn.test.profiles.security;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.KeyManager;
import org.ccnx.ccn.config.UserConfiguration;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.PublicKeyObject;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;
import org.ccnx.ccn.utils.CreateUserData;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;


public class TestUserDataTestRepo {
	
	static ContentName testPrefix = null;
	static ContentName userNamespace = null;
	static ContentName userKeyStorePrefix = null;
	static int userCount = 3;

	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		testPrefix = UserConfiguration.defaultNamespace();
		userKeyStorePrefix = new ContentName(UserConfiguration.defaultNamespace(), "home");
		userNamespace = new ContentName(testPrefix, "Users");
		System.out.println("testPrefix = " + testPrefix);
		System.out.println("userKeyStorePrefix =" + userKeyStorePrefix);
		System.out.println("userNamespace = " + userNamespace);
	}
	
   @Test
	public void testUserCreation() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testUserCreation");

	    CCNHandle thandle = CCNHandle.open();
		CreateUserData td = new CreateUserData(userKeyStorePrefix, userCount,
				true,
				"password".toCharArray());
		StringBuffer sb = new StringBuffer("Users: ");
		for (String s : td.friendlyNames()) {
			sb.append(" " + s);
		}
		System.out.println(sb.toString());
		
		td.publishUserKeysToRepository(userNamespace);
		
		// OK, now let's make a handle using one of these users and make sure the publisher ID
		// and such defaults correctly.
		// Should we pick randomly?
		String testUser = td.friendlyNames().iterator().next();
		CCNHandle userHandle = td.getHandleForUser(testUser);
		KeyManager userKeyManager = userHandle.keyManager();
		
		Assert.assertNotNull(userKeyManager.getDefaultKeyID());
		
		System.out.println("Attempting to recover stored users.");
		CreateUserData td2 = new CreateUserData(userKeyStorePrefix, userCount,
				true,
				"password".toCharArray());
		Assert.assertEquals(td.friendlyNames(), td2.friendlyNames());

		CCNHandle userHandle2 = td2.getHandleForUser(testUser);
		KeyManager userKeyManager2 = userHandle2.keyManager();

		Assert.assertNotNull(userKeyManager.getDefaultKeyID());
		Assert.assertNotNull(userKeyManager2.getDefaultKeyID());

		CCNHandle standardHandle = CCNHandle.open();
		KeyManager standardKeyManager = standardHandle.keyManager();

		System.out.println("Default key locator: " + standardKeyManager.getDefaultKeyLocator());
		System.out.println("Default key ID: " + standardKeyManager.getDefaultKeyID());
		System.out.println("Test user key locator: " + userKeyManager.getDefaultKeyLocator());
		System.out.println("Test user key ID: " + userKeyManager.getDefaultKeyID());
		System.out.println("Test user key locator2: " + userKeyManager2.getDefaultKeyLocator());
		System.out.println("Test user key ID2: " + userKeyManager2.getDefaultKeyID());

		Assert.assertFalse(standardKeyManager.getDefaultKeyLocator().equals(userKeyManager.getDefaultKeyLocator()));
		Assert.assertFalse(standardKeyManager.getDefaultKeyID().equals(userKeyManager.getDefaultKeyID()));
		Assert.assertTrue(userKeyManager2.getDefaultKeyLocator().equals(userKeyManager.getDefaultKeyLocator()));
		Assert.assertTrue(userKeyManager2.getDefaultKeyID().equals(userKeyManager.getDefaultKeyID()));

		for (String friendlyName: td.friendlyNames()){
			CCNHandle uHandle = td.getHandleForUser(friendlyName);
			KeyManager uKeyManager = uHandle.keyManager();
			ContentName keyName = new ContentName(userNamespace, friendlyName);
			//PublicKeyObject pko = new PublicKeyObject(keyName, uKeyManager.getDefaultPublicKey(), uHandle);
			PublicKeyObject pko = new PublicKeyObject(keyName, uHandle);
			//pko.saveToRepository();
			
			System.out.println("Object key locator: " + pko.getPublisherKeyLocator());
			System.out.println("Object key ID: " + pko.getContentPublisher());

			// Canaries -- things getting altered somehow.
			Assert.assertTrue("Checkpoint 2", userKeyManager2.getDefaultKeyID().equals(userKeyManager.getDefaultKeyID()));

			PublicKeyObject pkr = new PublicKeyObject(pko.getVersionedName(), standardHandle);
			if (!pkr.available()) {
				Log.info("Can't read back object " + pko.getVersionedName());
			} else {
				System.out.println("Retrieved object key locator: " + pkr.getPublisherKeyLocator());
				System.out.println("Retrieved object key ID: " + pkr.getContentPublisher());
				Assert.assertEquals(pkr.getContentPublisher(), uKeyManager.getDefaultKeyID());
				Assert.assertEquals(pkr.getPublisherKeyLocator(), uKeyManager.getDefaultKeyLocator());
			}
		}


		// Canaries -- things getting altered somehow.
		Assert.assertTrue("Checkpoint 3", userKeyManager2.getDefaultKeyID().equals(userKeyManager.getDefaultKeyID()));
		
		for (String name : td.friendlyNames()) {
			KeyManager km = td.getUser(name);
			System.out.println("User: " + name + " key fingerprint: " + 
					km.getDefaultKeyID() + 
					" recalculated key fingerprint: " + 
					new PublisherPublicKeyDigest(km.getDefaultPublicKey()));
		}

		System.out.println("Success.");
		thandle.close();
		
		Log.info(Log.FAC_TEST, "Completed testUserCreation");
	}
}
