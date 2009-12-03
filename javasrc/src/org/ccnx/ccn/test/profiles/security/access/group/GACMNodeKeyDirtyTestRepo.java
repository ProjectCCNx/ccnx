package org.ccnx.ccn.test.profiles.security.access.group;


import java.util.ArrayList;
import java.util.Random;

import junit.framework.Assert;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.RepositoryVersionedOutputStream;
import org.ccnx.ccn.io.content.Link;
import org.ccnx.ccn.profiles.security.access.group.ACL;
import org.ccnx.ccn.profiles.security.access.group.GroupAccessControlManager;
import org.ccnx.ccn.profiles.security.access.group.GroupAccessControlProfile;
import org.ccnx.ccn.profiles.security.access.group.Group;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.test.profiles.security.TestUserData;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * This test relies on org.ccn.ccn.test.profiles.access.TestUserData to generate users
 * for the access control test.
 *
 */

public class GACMNodeKeyDirtyTestRepo {

	static GroupAccessControlManager acm;
	static ContentName directoryBase, userKeyStorePrefix, userNamespace, groupStore;
	static final int numberOfusers = 3;
	static TestUserData td;
	static String[] friendlyNames;
	static final int numberOfGroups = 3;
	static String[] groupName = new String[numberOfGroups];
	static Group[] group = new Group[numberOfGroups];
	static ContentName[] node = new ContentName[numberOfGroups];
	static CCNHandle handle;
	
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		directoryBase = ContentName.fromNative("/test/ACMNodeKeyDirtyTestRepo");
		groupStore = GroupAccessControlProfile.groupNamespaceName(directoryBase);
		userKeyStorePrefix = ContentName.fromNative(directoryBase, "_access_");
		userNamespace = ContentName.fromNative(directoryBase, "home");

		// create user identities with TestUserData		
		Log.info("Creating {0} test users, if they do not already exist.", numberOfusers);
		td = new TestUserData(userKeyStorePrefix, numberOfusers, true, "password".toCharArray(), CCNHandle.open());
		Log.info("Created {0} test users, or retrieved them from repository.", numberOfusers);
		td.saveUserPK2Repo(userNamespace);
		friendlyNames = td.friendlyNames().toArray(new String[0]);				
		
		// create ACM
		handle = td.getHandleForUser(friendlyNames[0]);
		acm = new GroupAccessControlManager(directoryBase, groupStore, userNamespace, handle);
		acm.publishMyIdentity(friendlyNames[0], handle.keyManager().getDefaultPublicKey());
	}
	
	/**
	 * We create the following group structure:
	 * 
	 *         group1
	 *           |
	 *         group0            group2
	 *          /  \                |
	 *       user0 user1          user2
	 * 
	 * @throws Exception
	 */
	@Test
	public void createUserGroups() throws Exception {
		Random rand = new Random();

		// create group0 containing user0 and user1
		ArrayList<Link> G1Members = new ArrayList<Link>();
		G1Members.add(new Link(ContentName.fromNative(userNamespace, friendlyNames[0])));
		G1Members.add(new Link(ContentName.fromNative(userNamespace, friendlyNames[1])));
		groupName[0] = "usergroup0-" + rand.nextInt(10000);
		group[0] = acm.groupManager().createGroup(groupName[0], G1Members);
		
		// create group1 containing group0
		ArrayList<Link> G2Members = new ArrayList<Link>();
		G2Members.add(new Link(ContentName.fromNative(groupStore, groupName[0])));
		groupName[1] = "usergroup1-" + rand.nextInt(10000);
		group[1] = acm.groupManager().createGroup(groupName[1], G2Members);
		
		// create group2 containing user2
		ArrayList<Link> G3Members = new ArrayList<Link>();
		G3Members.add(new Link(ContentName.fromNative(userNamespace, friendlyNames[2])));
		groupName[2] = "usergroup2-" + rand.nextInt(10000);
		group[2] = acm.groupManager().createGroup(groupName[2], G3Members);

		// check the size of the groups
		Assert.assertEquals(2, group[0].membershipList().membershipList().size());
		Assert.assertEquals(1, group[1].membershipList().membershipList().size());
		Assert.assertEquals(1, group[2].membershipList().membershipList().size());
	}
	
	@Test
	public void createNodeACLs() throws Exception {
		Random rand = new Random();
		
		// create nodes [0-2] and corresponding ACLs that make group[i] a reader of node[i].
		for (int i=0; i<numberOfGroups; i++) {
			String nodeName = "node" + i + "-" + rand.nextInt(10000);
			node[i] = ContentName.fromNative(directoryBase, nodeName);
			ContentName groupCN = ContentName.fromNative(groupStore, groupName[i]);
			Link lk = new Link(groupCN, "rw+", null);
			ArrayList<Link> ACLcontents = new ArrayList<Link>();
			ACLcontents.add(lk);
			ACL aclNode = new ACL(ACLcontents);
			if (i==0) acm.initializeNamespace(aclNode);
			acm.setACL(node[i], aclNode);
		}		
	}
	
	@Test
	public void writeNodeContent() throws Exception {
		// write some content in nodes
		for (int i=0; i<numberOfGroups; i++) {
			RepositoryVersionedOutputStream rvos = new RepositoryVersionedOutputStream(node[i], handle);
			rvos.setTimeout(5000);
			byte [] data = "content".getBytes();
			rvos.write(data, 0, data.length);
			rvos.close();			
		}
				
		// The node keys are not dirty
		for (int i=0; i<numberOfGroups; i++) {
			Assert.assertFalse(acm.nodeKeyIsDirty(node[i]));				
		}
	}
	
	@Test
	public void addMemberToGroup0() throws Exception {
		// add user2 to group0
		ArrayList<Link> membersToAdd = new ArrayList<Link>();
		membersToAdd.add(new Link(ContentName.fromNative(userNamespace, friendlyNames[2])));
		group[0].addMembers(membersToAdd);
		
		// check that group0 is now of size 3
		Assert.assertEquals(3, group[0].membershipList().membershipList().size());
	}

	@Test
	public void writeMoreNodeContent() throws Exception {
		// write some content in nodes
		for (int i=0; i<numberOfGroups; i++) {
			RepositoryVersionedOutputStream rvos = new RepositoryVersionedOutputStream(node[i], handle);
			rvos.setTimeout(5000);
			byte [] data = "more content".getBytes();
			rvos.write(data, 0, data.length);
			rvos.close();			
		}
		
		// The node keys are not dirty
		for (int i=0; i<numberOfGroups; i++) {
			Assert.assertFalse(acm.nodeKeyIsDirty(node[i]));				
		}
	}
	
	@Test
	public void removeMemberFromGroup0() throws Exception {
		// delete user1 from group0
		ArrayList<Link> membersToRemove = new ArrayList<Link>();
		membersToRemove.add(new Link(ContentName.fromNative(userNamespace, friendlyNames[1])));
		group[0].removeMembers(membersToRemove);
		
		// check group0 is of size 2 again
		Assert.assertEquals(2, group[0].membershipList().membershipList().size());
	}
	
	@Test
	public void writeEvenMoreNodeContent() throws Exception {
		// write some content in nodes
		for (int i=0; i<numberOfGroups; i++) {
			RepositoryVersionedOutputStream rvos = new RepositoryVersionedOutputStream(node[i], handle);
			rvos.setTimeout(5000);
			byte [] data = "content".getBytes();
			rvos.write(data, 0, data.length);
			rvos.close();			
		}
		
		// The node keys are dirty for nodes 0 and 1, but not 2.
		Thread.sleep(1000);
		Assert.assertTrue(acm.nodeKeyIsDirty(node[0]));
		Assert.assertTrue(acm.nodeKeyIsDirty(node[1]));
		Assert.assertFalse(acm.nodeKeyIsDirty(node[2]));
	}


}
