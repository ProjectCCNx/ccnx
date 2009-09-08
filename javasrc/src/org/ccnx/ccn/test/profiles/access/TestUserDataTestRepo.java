package org.ccnx.ccn.test.profiles.access;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.KeyManager;
import org.ccnx.ccn.config.UserConfiguration;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.PublicKeyObject;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;
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
		//testPrefix = ContentName.fromNative("/parc/test");
		//userKeyStorePrefix = ContentName.fromNative("/parc/test/_access_/Users");
		//userNamespace = ContentName.fromNative("/parc/test/Users/");
		testPrefix = UserConfiguration.defaultNamespace();
		userKeyStorePrefix = ContentName.fromNative(UserConfiguration.defaultNamespace(), "_access_");
		userNamespace = ContentName.fromNative(testPrefix, "home");
		System.out.println("testPrefix = " + testPrefix);
		System.out.println("userKeyStorePrefix =" + userKeyStorePrefix);
		System.out.println("userNamespace = " + userNamespace);
	}
	
   @Test
	public void testUserCreation() throws Exception {
		TestUserData td = new TestUserData(userKeyStorePrefix, userCount,
				true,
				"password".toCharArray(), CCNHandle.open());
		StringBuffer sb = new StringBuffer("Users: ");
		for (String s : td.friendlyNames()) {
			sb.append(" " + s);
		}
		System.out.println(sb.toString());
		// OK, now let's make a library using one of these users and make sure the publisher ID
		// and such defaults correctly.
		// Should we pick randomly?
		String testUser = td.friendlyNames().iterator().next();
		CCNHandle userLibrary = td.getLibraryForUser(testUser);
		KeyManager userKeyManager = userLibrary.keyManager();
		
		Assert.assertNotNull(userKeyManager.getDefaultKeyID());
		
		System.out.println("Attempting to recover stored users.");
		TestUserData td2 = new TestUserData(userKeyStorePrefix, userCount,
				true,
				"password".toCharArray(), CCNHandle.open());
		Assert.assertEquals(td.friendlyNames(), td2.friendlyNames());

		CCNHandle userLibrary2 = td2.getLibraryForUser(testUser);
		KeyManager userKeyManager2 = userLibrary2.keyManager();

		Assert.assertNotNull(userKeyManager.getDefaultKeyID());
		Assert.assertNotNull(userKeyManager2.getDefaultKeyID());

		CCNHandle standardLibrary = CCNHandle.open();
		KeyManager standardKeyManager = standardLibrary.keyManager();

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
			CCNHandle uLibrary = td.getLibraryForUser(friendlyName);
			KeyManager uKeyManager = uLibrary.keyManager();
			//ContentName keyName = ContentName.fromNative(userNamespace, "PublicKey:" + userKeyManager.getDefaultKeyID());
			ContentName keyName = ContentName.fromNative(userNamespace, friendlyName);
			PublicKeyObject pko = new PublicKeyObject(keyName, uKeyManager.getDefaultPublicKey(), uLibrary);
			pko.saveToRepository();
			
			System.out.println("Object key locator: " + pko.publisherKeyLocator());
			System.out.println("Object key ID: " + pko.contentPublisher());

			// Canaries -- things getting altered somehow.
			Assert.assertTrue("Checkpoint 2", userKeyManager2.getDefaultKeyID().equals(userKeyManager.getDefaultKeyID()));

			PublicKeyObject pkr = new PublicKeyObject(pko.getVersionedName(), standardLibrary);
			if (!pkr.available()) {
				Log.info("Can't read back object " + pko.getVersionedName());
			} else {
				System.out.println("Retrieved object key locator: " + pkr.publisherKeyLocator());
				System.out.println("Retrieved object key ID: " + pkr.contentPublisher());
				Assert.assertEquals(pkr.contentPublisher(), uKeyManager.getDefaultKeyID());
				Assert.assertEquals(pkr.publisherKeyLocator(), uKeyManager.getDefaultKeyLocator());
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
	}

}
