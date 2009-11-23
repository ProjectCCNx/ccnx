package org.ccnx.ccn.test.profiles.security.access;

import org.junit.BeforeClass;
import org.junit.Test;
import junit.framework.Assert;

import java.util.Random;
import java.util.ArrayList;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.UserConfiguration;
import org.ccnx.ccn.io.content.Link;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.profiles.security.access.AccessControlManager;
import org.ccnx.ccn.profiles.security.access.ACL;
import org.ccnx.ccn.profiles.security.access.ACL.ACLObject;
import org.ccnx.ccn.test.profiles.security.TestUserData;

/**
 * This test relies on org.ccn.ccn.test.profiles.access.TestUserData to generate users
 * for the access control test.
 *
 */

public class AccessControlManagerTestRepo {

	static AccessControlManager acm;
	static ContentName baseNode, childNode, grandchildNode;
	static ContentName userKeyStorePrefix, userNamespace;
	static int userCount = 3;
	static TestUserData td;
	static String[] friendlyNames;
	static ContentName user0;
	static Link lk, lk2;
	static ACL baseACL;
	
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		// create base node, child node and grandchild node
		Random rand = new Random();
		String directoryBase = "/test/AccessControlManagerTestRepo-";
		baseNode = ContentName.fromNative(directoryBase + Integer.toString(rand.nextInt(10000)));
		childNode = ContentName.fromNative(baseNode, "child");
		grandchildNode = ContentName.fromNative(childNode, "grandchild");
				
		// create user identities with TestUserData
		ContentName testPrefix = UserConfiguration.defaultNamespace();
		userKeyStorePrefix = ContentName.fromNative(testPrefix, "_access_");
		userNamespace = ContentName.fromNative(testPrefix, "home");
		td = new TestUserData(userKeyStorePrefix, userCount, true, "password".toCharArray(), CCNHandle.open());
		td.saveUserPK2Repo(userNamespace);
		friendlyNames = td.friendlyNames().toArray(new String[0]);
		Assert.assertEquals(userCount, friendlyNames.length);
	}
	
	/**
	 * Set the ACL for the base node (we make user 0 a manager of the base node).
	 * @throws Exception
	 */
	@Test
	public void testSetBaseACL() throws Exception {		
		ArrayList<Link> ACLcontents = new ArrayList<Link>();
		user0 = ContentName.fromNative(userNamespace, friendlyNames[0]);
		lk = new Link(user0, "rw+", null);
		ACLcontents.add(lk);
		baseACL = new ACL(ACLcontents);
		CCNHandle handle = td.getHandleForUser(friendlyNames[0]);
		acm = new AccessControlManager(baseNode, handle);
		acm.initializeNamespace(baseACL);
	}

	/**
	 * Retrieve the ACL for the base node.
	 * @throws Exception
	 */
	@Test
	public void testGetBaseACL() throws Exception {
		ACLObject aclo = acm.getEffectiveACLObject(baseNode);
		ACL aclRetrieved = aclo.acl();
		Assert.assertTrue(aclRetrieved.equals(baseACL));
		Link linkRetrieved = aclRetrieved.remove(0);
		Assert.assertTrue(linkRetrieved.equals(lk));		
	}
	
	/**
	 * Retrieve the ACL for the grandchild node.
	 * This ACL comes from the base node.
	 * @throws Exception
	 */
	@Test
	public void testGetACLFromAncestor() throws Exception {
		ACLObject aclo = acm.getEffectiveACLObject(grandchildNode);
		ACL aclRetrieved = aclo.acl();
		Link linkRetrieved = aclRetrieved.remove(0);
		Assert.assertTrue(linkRetrieved.equals(lk));		
	}
	
	/**
	 * Interpose a different ACL at the child node (we make user 0 a manager)
	 * Retrieve the ACL for the grandchild node and check that it now comes 
	 * from the child node.
	 * @throws Exception
	 */
	@Test
	public void testSetACL() throws Exception {
		// set interposed ACL
		ArrayList<Link> newACLContents = new ArrayList<Link>();
		Link lk2 = new Link(user0, "rw+", null);
		newACLContents.add(lk2);
		ACL newACL = new ACL(newACLContents);
		acm.setACL(childNode, newACL);
		Thread.sleep(1000);
		
		// retrieve ACL at child node
		ACLObject aclo = acm.getEffectiveACLObject(childNode);
		ACL aclRetrieved = aclo.acl();
		Link linkRetrieved = aclRetrieved.remove(0);
		Assert.assertTrue(linkRetrieved.equals(lk2));		
		
		// retrieve ACL at grandchild node
		aclo = acm.getEffectiveACLObject(grandchildNode);
		aclRetrieved = aclo.acl();
		linkRetrieved = aclRetrieved.remove(0);
		Assert.assertTrue(linkRetrieved.equals(lk2));		
	}
	
	/**
	 * Update the child ACL to add user1 as a reader and user2 as a writer.
	 * @throws Exception
	 */
	@Test
	public void testUpdateACL() throws Exception {
		ArrayList<Link> newReaders = new ArrayList<Link>();
		newReaders.add(new Link(ContentName.fromNative(userNamespace, friendlyNames[1])));
		acm.addReaders(childNode, newReaders);
		ArrayList<Link> newWriters = new ArrayList<Link>();
		newWriters.add(new Link(ContentName.fromNative(userNamespace, friendlyNames[2])));
		acm.addWriters(childNode, newWriters);
	}
	
	/**
	 * Delete the ACL at the child node.
	 * Retrieve the ACL for the grandchild node and check that it comes from the base node.
	 * @throws Exception
	 */
	@Test
	public void deleteACL() throws Exception {
		acm.deleteACL(childNode);
		// retrieve ACL at grandchild node
		ACLObject aclo = acm.getEffectiveACLObject(grandchildNode);
		ACL aclRetrieved = aclo.acl();
		Link linkRetrieved = aclRetrieved.remove(0);
		Assert.assertTrue(linkRetrieved.equals(lk));				
	}
	
}
