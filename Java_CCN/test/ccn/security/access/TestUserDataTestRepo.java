package test.ccn.security.access;


import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import com.parc.ccn.data.ContentName;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.security.keys.KeyManager;

public class TestUserDataTestRepo {
	
	static ContentName userNamespace = null;
	static int userCount = 10;

	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		userNamespace = ContentName.fromNative("/parc/Users");
	}
	
	@Test
	public void testUserCreation() {
		try {
			TestUserData td = new TestUserData(userNamespace, userCount,
					true,
					"password".toCharArray(), CCNLibrary.open());
			StringBuffer sb = new StringBuffer("Users: ");
			for (String s : td.friendlyNames()) {
				sb.append(" " + s);
			}
			System.out.println(sb.toString());
			System.out.println("Attempting to recover stored users.");
			TestUserData td2 = new TestUserData(userNamespace, userCount,
					true,
					"password".toCharArray(), CCNLibrary.open());
			Assert.assertEquals(td.friendlyNames(), td2.friendlyNames());
			
			// OK, now let's make a library using one of these users.
			// DKS TODO -- finish
			KeyManager userKeyManager = td2.getUser(td2.friendlyNames().iterator().next());
		} catch (Exception e) {
			e.printStackTrace();
			Assert.fail("Exception: " + e);
		}

	}

}
