package test.ccn.security.access;


import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import com.parc.ccn.Library;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.security.PublicKeyObject;
import com.parc.ccn.data.security.PublisherPublicKeyDigest;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.security.keys.KeyManager;

public class TestUserDataTestRepo {
	
	static ContentName userNamespace = null;
	static ContentName dataNamespace = null;
	static int userCount = 10;

	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		userNamespace = ContentName.fromNative("/parc/Users");
		dataNamespace = ContentName.fromNative("/parc/test/data");
	}
	
	@Test
	public void testUserCreation() throws Exception {
		TestUserData td = new TestUserData(userNamespace, userCount,
				true,
				"password".toCharArray(), CCNLibrary.open());
		StringBuffer sb = new StringBuffer("Users: ");
		for (String s : td.friendlyNames()) {
			sb.append(" " + s);
		}
		System.out.println(sb.toString());
		// OK, now let's make a library using one of these users and make sure the publisher ID
		// and such defaults correctly.
		// Should we pick randomly?
		String testUser = td.friendlyNames().iterator().next();
		CCNLibrary userLibrary = td.getLibraryForUser(testUser);
		KeyManager userKeyManager = userLibrary.keyManager();
		
		Assert.assertNotNull(userKeyManager.getDefaultKeyID());
		
		System.out.println("Attempting to recover stored users.");
		TestUserData td2 = new TestUserData(userNamespace, userCount,
				true,
				"password".toCharArray(), CCNLibrary.open());
		Assert.assertEquals(td.friendlyNames(), td2.friendlyNames());

		KeyManager userKeyManager2 = userLibrary.keyManager();

		Assert.assertNotNull(userKeyManager.getDefaultKeyID());
		Assert.assertNotNull(userKeyManager2.getDefaultKeyID());

		CCNLibrary standardLibrary = CCNLibrary.open();
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

		ContentName keyName = ContentName.fromNative(dataNamespace, "PublicKey:" + userKeyManager.getDefaultKeyID());
		PublicKeyObject pko = new PublicKeyObject(keyName, userKeyManager.getDefaultPublicKey(), userLibrary);
		pko.saveToRepository();

		System.out.println("Object key locator: " + pko.publisherKeyLocator());
		System.out.println("Object key ID: " + pko.contentPublisher());

		// Canaries -- things getting altered somehow.
		Assert.assertTrue("Checkpoint 2", userKeyManager2.getDefaultKeyID().equals(userKeyManager.getDefaultKeyID()));

		PublicKeyObject pkr = new PublicKeyObject(pko.getCurrentVersionName(), standardLibrary);
		if (!pkr.available()) {
			Library.logger().info("Can't read back object " + pko.getCurrentVersionName());
		} else {
			System.out.println("Retrieved object key locator: " + pkr.publisherKeyLocator());
			System.out.println("Retrieved object key ID: " + pkr.contentPublisher());
			Assert.assertEquals(pkr.contentPublisher(), userKeyManager.getDefaultKeyID());
			Assert.assertEquals(pkr.publisherKeyLocator(), userKeyManager.getDefaultKeyLocator());
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
