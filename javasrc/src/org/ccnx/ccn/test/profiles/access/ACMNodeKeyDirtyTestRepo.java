package org.ccnx.ccn.test.profiles.access;


import java.util.ArrayList;
import java.util.Random;

import junit.framework.Assert;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.io.RepositoryOutputStream;
import org.ccnx.ccn.io.RepositoryVersionedOutputStream;
import org.ccnx.ccn.io.content.Link;
import org.ccnx.ccn.profiles.access.ACL;
import org.ccnx.ccn.profiles.access.AccessControlManager;
import org.ccnx.ccn.profiles.access.AccessControlProfile;
import org.ccnx.ccn.profiles.access.Group;
import org.ccnx.ccn.protocol.ContentName;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * This test relies on org.ccn.ccn.test.profiles.access.TestUserData to generate users
 * for the access control test.
 *
 */

public class ACMNodeKeyDirtyTestRepo {

	static AccessControlManager acm;
	static ContentName directoryBase, userKeyStorePrefix, userNamespace, groupStore, baseNode;
	static int userCount = 3;
	static TestUserData td;
	static String[] friendlyNames;
	static String groupName;
	static Group userGroup;
	static CCNHandle handle;
	
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		directoryBase = ContentName.fromNative("/test/ACMNodeKeyDirtyTestRepo");
		
		// create user identities with TestUserData		
		userKeyStorePrefix = ContentName.fromNative(directoryBase, "_access_");
		userNamespace = ContentName.fromNative(directoryBase, "home");
		td = new TestUserData(userKeyStorePrefix, userCount, true, "password".toCharArray(), CCNHandle.open());
		td.saveUserPK2Repo(userNamespace);
		friendlyNames = td.friendlyNames().toArray(new String[0]);		
	}
	
	@Test
	public void createUserGroup() throws Exception {
		// create a group containing user0 and user1
		ArrayList<Link> newMembers = new ArrayList<Link>();
		newMembers.add(new Link(ContentName.fromNative(userNamespace, friendlyNames[0])));
		newMembers.add(new Link(ContentName.fromNative(userNamespace, friendlyNames[1])));
		groupStore = AccessControlProfile.groupNamespaceName(directoryBase);
		handle = td.getHandleForUser(friendlyNames[0]);
		acm = new AccessControlManager(directoryBase, groupStore, userNamespace, handle);
		acm.publishMyIdentity(friendlyNames[0], handle.keyManager().getDefaultPublicKey());
		Random rand = new Random();
		groupName = "usergroup" + rand.nextInt(10000);
		userGroup = acm.groupManager().createGroup(groupName, newMembers);

		// check the group is of size 2
		Assert.assertEquals(2, userGroup.membershipList().membershipList().size());
	}
	
	@Test
	public void createNodeACL() throws Exception {
		Random rand = new Random();
		String baseNodeName = "baseNode" + rand.nextInt(10000);
		baseNode = ContentName.fromNative(directoryBase, baseNodeName);
		
		// create ACL for base node: make userGroup a reader
		ContentName userGroup = ContentName.fromNative(groupStore, groupName);
		Link lk = new Link(userGroup, "rw+", null);
		ArrayList<Link> ACLcontents = new ArrayList<Link>();
		ACLcontents.add(lk);
		ACL baseACL = new ACL(ACLcontents);
		acm.initializeNamespace(baseACL);
		acm.setACL(baseNode, baseACL);		
	}
	
	@Test
	public void writeNodeContent() throws Exception {
		// write some content in base node
		RepositoryVersionedOutputStream rvos = new RepositoryVersionedOutputStream(baseNode, handle);
		rvos.setTimeout(5000);
		byte [] data = "base node content".getBytes();
		rvos.write(data, 0, data.length);
		rvos.close();
		
		// The node key is not dirty
		ContentName nodeKeyName = AccessControlProfile.nodeKeyName(baseNode);
		Assert.assertFalse(acm.nodeKeyIsDirty(nodeKeyName));	
	}
	
	@Test
	public void addMemberToUserGroup() throws Exception {
		// add user2 to the group
		ArrayList<Link> membersToAdd = new ArrayList<Link>();
		membersToAdd.add(new Link(ContentName.fromNative(userNamespace, friendlyNames[2])));
		userGroup.addMembers(membersToAdd);
		
		// check the group is now of size 3
		Assert.assertEquals(3, userGroup.membershipList().membershipList().size());
	}

	@Test
	public void writeMoreNodeContent() throws Exception {
		RepositoryVersionedOutputStream rvos = new RepositoryVersionedOutputStream(baseNode, CCNHandle.open());
		rvos.setTimeout(5000);
		byte [] data = "More base node content".getBytes();
		rvos.write(data, 0, data.length);
		rvos.close();
		
		// The node key is not dirty
		ContentName nodeKeyName = AccessControlProfile.nodeKeyName(baseNode);
		Assert.assertFalse(acm.nodeKeyIsDirty(nodeKeyName));	
	}
	
	@Test
	public void removeMemberFromUserGroup() throws Exception {
		// delete user1 from the group
		ArrayList<Link> membersToRemove = new ArrayList<Link>();
		membersToRemove.add(new Link(ContentName.fromNative(userNamespace, friendlyNames[1])));
		userGroup.removeMembers(membersToRemove);
		
		// check the group is of size 2 again
		Assert.assertEquals(2, userGroup.membershipList().membershipList().size());
	}
	
	@Test
	public void writeEvenMoreNodeContent() throws Exception {
		RepositoryVersionedOutputStream rvos = new RepositoryVersionedOutputStream(baseNode, CCNHandle.open());
		rvos.setTimeout(5000);
		byte [] data = "Even more base node content".getBytes();
		rvos.write(data, 0, data.length);
		rvos.close();
		
		// The node key is dirty
		ContentName nodeKeyName = AccessControlProfile.nodeKeyName(baseNode);
		Assert.assertTrue(acm.nodeKeyIsDirty(nodeKeyName));
	}


}
