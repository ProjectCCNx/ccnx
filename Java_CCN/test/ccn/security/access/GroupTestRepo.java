package test.ccn.security.access;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Random;
import java.util.SortedSet;



import junit.framework.Assert;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.impl.support.Library;
import org.ccnx.ccn.io.content.Link;
import org.ccnx.ccn.profiles.access.AccessControlManager;
import org.ccnx.ccn.profiles.access.AccessControlProfile;
import org.ccnx.ccn.profiles.access.GroupManager;
import org.ccnx.ccn.profiles.nameenum.EnumeratedNameList;
import org.ccnx.ccn.protocol.ContentName;
import org.junit.BeforeClass;
import org.junit.Test;



public class GroupTestRepo {
	
	static boolean USE_REPO = true;
	static int NUM_USERS = 20;
	static char [] USER_PASSWORD = new String("password").toCharArray();
	
	static ContentName testStorePrefix = null;
	static ContentName userStore = null;
	
	static EnumeratedNameList _userList = null;
	static CCNHandle _library = null;
	
	/**
	 * Have to make a bunch of users.
	 * @throws Exception
	 */
	static TestUserData users = null;
	static CCNHandle userLibrary = null;
	static AccessControlManager _acm = null;
	static GroupManager _gm = null;

	@BeforeClass
	public static void setUpBeforeClass() throws Exception { 
		try {
			
			testStorePrefix = ContentName.fromNative("/parc/");
			userStore = ContentName.fromNative(testStorePrefix, "Users");
			
			_library = CCNHandle.open();
			System.out.println("group store: " + AccessControlProfile.groupNamespaceName(testStorePrefix));
			_acm = new AccessControlManager(testStorePrefix, AccessControlProfile.groupNamespaceName(testStorePrefix), userStore);
			_userList = _acm.userList();
			_gm = _acm.groupManager();

	//		users = new TestUserData(userStore, NUM_USERS, USE_REPO, USER_PASSWORD, userLibrary);
		} catch (Exception e) {
			Library.logger().warning("Exception in setupBeforeClass: " + e);
			Library.warningStackTrace(e);
			throw e;
		}
	}

	/* Elaine: comment out things that dont work for java major reorg
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
			
			ArrayList<Link> newMembers = new ArrayList<Link>();
			assertTrue(returnedBytes.size() > 3);
			Iterator<ContentName> it = returnedBytes.iterator();
			
			for(int i = 0; i <3; i++){
				ContentName name = it.next();
				String fullname = _userList.getName().toString() + name.toString();
				newMembers.add(new Link(ContentName.fromNative(fullname)));
			}
			System.out.println("creating a group...");
			Random random = new Random();

			String randomGroupName = "testGroup" + random.nextInt();
			_gm.createGroup(randomGroupName, newMembers);
			
		} catch (Exception e) {
			System.out.println("Exception : " + e.getClass().getName() + ": " + e.getMessage());
			e.printStackTrace();
		}
	}
	*/
	
	
}
