package test.ccn.security.access;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.SortedSet;



import junit.framework.Assert;

import org.junit.BeforeClass;
import org.junit.Test;

import com.parc.ccn.Library;

import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.content.LinkReference;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.library.EnumeratedNameList;
import com.parc.ccn.security.access.AccessControlManager;
import com.parc.ccn.security.access.GroupManager;

public class GroupTestRepo {
	
	static boolean USE_REPO = true;
	static int NUM_USERS = 20;
	static char [] USER_PASSWORD = new String("password").toCharArray();
	
	static ContentName testStorePrefix = null;
	static ContentName userStore = null;
	static ContentName groupStore = null;
	
	static EnumeratedNameList _userList = null;
	static CCNLibrary _library = null;
	
	/**
	 * Have to make a bunch of users.
	 * @throws Exception
	 */
	static TestUserData users = null;
	static CCNLibrary userLibrary = null;
	static AccessControlManager _acm = null;
	static GroupManager _gm = null;

	@BeforeClass
	public static void setUpBeforeClass() throws Exception { 
		try {
			
			testStorePrefix = ContentName.fromNative("/parc/");
			userStore = ContentName.fromNative(testStorePrefix, "Users");
			groupStore = ContentName.fromNative(testStorePrefix, "Groups");
			
			_library = CCNLibrary.open();
			_acm = new AccessControlManager(testStorePrefix, groupStore, userStore);
			_userList = _acm.userList();
			_gm = _acm.groupManager();

	//		users = new TestUserData(userStore, NUM_USERS, USE_REPO, USER_PASSWORD, userLibrary);
		} catch (Exception e) {
			Library.logger().warning("Exception in setupBeforeClass: " + e);
			Library.warningStackTrace(e);
			throw e;
		}
	}

	@Test
	public void testCreateGroup() {
		try {
			Assert.assertNotNull(_userList);
			ContentName prefixTest = _userList.getName();
			Assert.assertNotNull(prefixTest);
			Library.logger().info("***************** Prefix is "+ prefixTest.toString());
			Assert.assertEquals(prefixTest, userStore);
			
			_userList.waitForData();
			Assert.assertTrue(_userList.hasNewData());
			SortedSet<ContentName> returnedBytes = _userList.getNewData();
			Assert.assertNotNull(returnedBytes);
			Assert.assertFalse(returnedBytes.isEmpty());
			
			System.out.println("Got " + returnedBytes.size() + " children: " + returnedBytes);
			System.out.print("names in list:");
			for(ContentName n: returnedBytes)
				System.out.print(" "+n);
			System.out.println();
			
			ArrayList<LinkReference> newMembers = new ArrayList<LinkReference>();
			assertTrue(returnedBytes.size() > 3);
			Iterator<ContentName> it = returnedBytes.iterator();
			
			for(int i = 0; i <3; i++){
				newMembers.add(new LinkReference(it.next()));
			}
			System.out.println("creating a group...");
			_gm.createGroup("testGroup", newMembers);
			
		} catch (Exception e) {
			System.out.println("Exception : " + e.getClass().getName() + ": " + e.getMessage());
			e.printStackTrace();
		}
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
