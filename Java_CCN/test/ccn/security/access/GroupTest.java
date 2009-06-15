package test.ccn.security.access;

import static org.junit.Assert.*;

import org.junit.BeforeClass;
import org.junit.Test;

import com.parc.ccn.Library;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.library.CCNLibrary;

public class GroupTest {
	
	static boolean USE_REPO = true;
	static int NUM_USERS = 20;
	static char [] USER_PASSWORD = new String("password").toCharArray();
	
	static ContentName testStorePrefix = null;
	static ContentName userStore = null;
	static ContentName groupStore = null;
	
	/**
	 * Have to make a bunch of users.
	 * @throws Exception
	 */
	static TestUserData users = null;
	static CCNLibrary userLibrary = null;

	@BeforeClass
	public static void setUpBeforeClass() throws Exception { 
		try {
			testStorePrefix = ContentName.fromNative("/test/access");
			userStore = ContentName.fromNative(testStorePrefix, "Users");
			groupStore = ContentName.fromNative(testStorePrefix, "Groups");

			users = new TestUserData(userStore, NUM_USERS, USE_REPO, USER_PASSWORD, userLibrary);
		} catch (Exception e) {
			Library.logger().warning("Exception in setupBeforeClass: " + e);
			Library.warningStackTrace(e);
			throw e;
		}
	}

	@Test
	public void testReady() {
		fail("Not yet implemented");
	}

	@Test
	public void testPrivateKeyDirectory() {
		fail("Not yet implemented");
	}

	@Test
	public void testFriendlyName() {
		fail("Not yet implemented");
	}

	@Test
	public void testMembershipList() {
		fail("Not yet implemented");
	}

	@Test
	public void testMembershipListName() {
		fail("Not yet implemented");
	}

	@Test
	public void testMembershipListVersion() {
		fail("Not yet implemented");
	}

	@Test
	public void testClearCachedMembershipList() {
		fail("Not yet implemented");
	}

	@Test
	public void testPublicKey() {
		fail("Not yet implemented");
	}

	@Test
	public void testPublicKeyName() {
		fail("Not yet implemented");
	}

	@Test
	public void testPublicKeyVersion() {
		fail("Not yet implemented");
	}

	@Test
	public void testSetMembershipList() {
		fail("Not yet implemented");
	}

	@Test
	public void testNewGroupPublicKey() {
		fail("Not yet implemented");
	}

	@Test
	public void testCreateGroupPublicKey() {
		fail("Not yet implemented");
	}

	@Test
	public void testUpdateGroupPublicKey() {
		fail("Not yet implemented");
	}

	@Test
	public void testModify() {
		fail("Not yet implemented");
	}

	@Test
	public void testDelete() {
		fail("Not yet implemented");
	}

}
