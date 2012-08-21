/*
 * A CCNx library test.
 *
 * Copyright (C) 2009-2012 Palo Alto Research Center, Inc.
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
 
package org.ccnx.ccn.test.profiles.security.access.group;


import java.util.ArrayList;
import java.util.Random;

import junit.framework.Assert;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.KeyManager;
import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.config.UserConfiguration;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.RepositoryVersionedOutputStream;
import org.ccnx.ccn.io.content.Link;
import org.ccnx.ccn.profiles.security.access.group.ACL;
import org.ccnx.ccn.profiles.security.access.group.Group;
import org.ccnx.ccn.profiles.security.access.group.GroupAccessControlManager;
import org.ccnx.ccn.profiles.security.access.group.GroupAccessControlProfile;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.test.CCNTestHelper;
import org.ccnx.ccn.utils.CreateUserData;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * This test relies on org.ccn.ccn.test.profiles.access.TestUserData to generate users
 * for the access control test.
 *
 */

public class GACMNodeKeyDirtyTestRepo {

	static GroupAccessControlManager acm;
	static ContentName directoryBase, userKeyStorePrefix, userNamespace, groupNamespace;
	static final int numberOfusers = 3;
	static CreateUserData td;
	static String[] friendlyNames;
	static final int numberOfGroups = 3;
	static String[] groupName = new String[numberOfGroups];
	static Group[] group = new Group[numberOfGroups];
	static ContentName[] node = new ContentName[numberOfGroups];
	static CCNHandle handle;
	
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {

		CCNTestHelper testHelper = new CCNTestHelper(GACMNodeKeyDirtyTestRepo.class);
		directoryBase = testHelper.getTestNamespace("testInOrder");
		userNamespace = GroupAccessControlProfile.userNamespaceName(UserConfiguration.defaultNamespace());
		groupNamespace = GroupAccessControlProfile.groupNamespaceName(UserConfiguration.defaultNamespace());
		userKeyStorePrefix = new ContentName(UserConfiguration.defaultNamespace(), "_keystore_"); 

		// create user identities with TestUserData		
		Log.info("Creating {0} test users, if they do not already exist.", numberOfusers);
		td = new CreateUserData(userKeyStorePrefix, numberOfusers, true, "password".toCharArray());
		Log.info("Created {0} test users, or retrieved them from repository.", numberOfusers);
		td.publishUserKeysToRepository(userNamespace);
		friendlyNames = td.friendlyNames().toArray(new String[0]);				
		
		// create and register ACM
		handle = td.getHandleForUser(friendlyNames[0]);
		acm = new GroupAccessControlManager(directoryBase, groupNamespace, userNamespace, handle);
		acm.publishMyIdentity(new ContentName(userNamespace, friendlyNames[0]), handle.keyManager().getDefaultPublicKey());
		handle.keyManager().publishKeyToRepository();
		
		// create the root ACL
		// The root has user0 as a manager
		Link lk = new Link(new ContentName(userNamespace, friendlyNames[0]), ACL.LABEL_MANAGER, null);
		ArrayList<Link> rootACLcontents = new ArrayList<Link>();
		rootACLcontents.add(lk);
		// it also has me as a manager, which means I'd better publish my identity as well
		String myUserName = UserConfiguration.userName();
		acm.publishMyIdentity(GroupAccessControlProfile.userNamespaceName(userNamespace, myUserName), KeyManager.getDefaultKeyManager().getDefaultPublicKey());
		Link mlk = new Link(new ContentName(userNamespace, myUserName), ACL.LABEL_MANAGER, null);
		rootACLcontents.add(mlk);
		ACL rootACL = new ACL(rootACLcontents);
		acm.initializeNamespace(rootACL);
		
		// Register ACM so it can be found
		handle.keyManager().rememberAccessControlManager(acm);
	}

	@AfterClass
	public static void tearDownAfterClass() throws Exception {
		td.closeAll();
		handle.close();
	}
	
	/**
	 * Ensures that the tests run in the correct order.
	 * @throws Exception
	 */
	@Test
	public void testInOrder() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testInOrder");

		createUserGroups();
		createNodeACLs();
		writeNodeContent();
		addMemberToGroup0();
		writeMoreNodeContent();
		removeMemberFromGroup0();
		writeEvenMoreNodeContent();
		
		Log.info(Log.FAC_TEST, "Completed testInOrder");
	}
	
	/**
	 * We create the following group structure:
	 * 
	 *         group1
	 *           |
	 *         group0            group2
	 *          /  \              /  \
	 *       user0 user1      user0  user2
	 * 
	 * @throws Exception
	 */
	public void createUserGroups() throws Exception {
		Random rand = new Random();

		// create group0 containing user0 and user1
		ArrayList<Link> G0Members = new ArrayList<Link>();
		G0Members.add(new Link(new ContentName(userNamespace, friendlyNames[0])));
		G0Members.add(new Link(new ContentName(userNamespace, friendlyNames[1])));
		groupName[0] = "usergroup0-" + rand.nextInt(10000);
		group[0] = acm.groupManager().createGroup(groupName[0], G0Members, 0);
		
		// create group1 containing group0
		ArrayList<Link> G1Members = new ArrayList<Link>();
		G1Members.add(new Link(new ContentName(groupNamespace, groupName[0])));
		groupName[1] = "usergroup1-" + rand.nextInt(10000);
		group[1] = acm.groupManager().createGroup(groupName[1], G1Members, 0);
		
		// create group2 containing user0 and user2
		ArrayList<Link> G2Members = new ArrayList<Link>();
		G2Members.add(new Link(new ContentName(userNamespace, friendlyNames[0])));
		G2Members.add(new Link(new ContentName(userNamespace, friendlyNames[2])));
		groupName[2] = "usergroup2-" + rand.nextInt(10000);
		group[2] = acm.groupManager().createGroup(groupName[2], G2Members, 0);

		// check the size of the groups
		Assert.assertEquals(2, group[0].membershipList().membershipList().size());
		Assert.assertEquals(1, group[1].membershipList().membershipList().size());
		Assert.assertEquals(2, group[2].membershipList().membershipList().size());
	}
	
	public void createNodeACLs() throws Exception {
		Random rand = new Random();
		
		// create nodes [0-2] and corresponding ACLs that make group[i] a manager of node[i].
		for (int i=0; i<numberOfGroups; i++) {
			String nodeName = "node" + i + "-" + rand.nextInt(10000) + ".txt";
			node[i] = new ContentName(directoryBase, nodeName);
			ContentName groupCN = new ContentName(groupNamespace, groupName[i]);
			Link lk = new Link(groupCN, ACL.LABEL_MANAGER, null);
			ArrayList<Link> ACLcontents = new ArrayList<Link>();
			ACLcontents.add(lk);
			ACL aclNode = new ACL(ACLcontents);
			acm.setACL(node[i], aclNode);
		}
	}
	
	public void writeNodeContent() throws Exception {
		// write some content in nodes
		for (int i=0; i<numberOfGroups; i++) {
			RepositoryVersionedOutputStream rvos = new RepositoryVersionedOutputStream(node[i], handle);
			rvos.setTimeout(SystemConfiguration.MAX_TIMEOUT);
			byte [] data = "content".getBytes();
			rvos.write(data, 0, data.length);
			rvos.close();			
		}
				
		// The node keys are not dirty
		for (int i=0; i<numberOfGroups; i++) {
			Assert.assertFalse(acm.nodeKeyIsDirty(node[i]));				
		}
	}
	
	public void addMemberToGroup0() throws Exception {
		// add user2 to group0
		ArrayList<Link> membersToAdd = new ArrayList<Link>();
		membersToAdd.add(new Link(new ContentName(userNamespace, friendlyNames[2])));
		group[0].addMembers(membersToAdd);
		
		// check that group0 is now of size 3
		Assert.assertEquals(3, group[0].membershipList().membershipList().size());
	}

	public void writeMoreNodeContent() throws Exception {
		// The node keys are not dirty
		for (int i=0; i<numberOfGroups; i++) {
			Assert.assertFalse(acm.nodeKeyIsDirty(node[i]));				
		}
		
		// write some content in nodes
		for (int i=0; i<numberOfGroups; i++) {
			RepositoryVersionedOutputStream rvos = new RepositoryVersionedOutputStream(node[i], handle);
			rvos.setTimeout(SystemConfiguration.MAX_TIMEOUT);
			byte [] data = "more content".getBytes();
			rvos.write(data, 0, data.length);
			rvos.close();			
		}
		
		// The node keys are still not dirty
		for (int i=0; i<numberOfGroups; i++) {
			Assert.assertFalse(acm.nodeKeyIsDirty(node[i]));				
		}
	}
	
	public void removeMemberFromGroup0() throws Exception {
		// delete user1 from group0
		ArrayList<Link> membersToRemove = new ArrayList<Link>();
		membersToRemove.add(new Link(new ContentName(userNamespace, friendlyNames[1])));
		group[0].removeMembers(membersToRemove);
		
		// check group0 is of size 2 again
		Assert.assertEquals(2, group[0].membershipList().membershipList().size());
	}
	
	public void writeEvenMoreNodeContent() throws Exception {
		// The node keys are dirty for nodes 0 and 1, but not 2.
		Thread.sleep(SystemConfiguration.MAX_TIMEOUT);
		Assert.assertTrue(acm.nodeKeyIsDirty(node[0]));
		Assert.assertTrue(acm.nodeKeyIsDirty(node[1]));
		Assert.assertFalse(acm.nodeKeyIsDirty(node[2]));

		// write some content in nodes
		for (int i=0; i<numberOfGroups; i++) {
			RepositoryVersionedOutputStream rvos = new RepositoryVersionedOutputStream(node[i], handle);
			rvos.setTimeout(SystemConfiguration.MAX_TIMEOUT);
			byte [] data = "content".getBytes();
			rvos.write(data, 0, data.length);
			rvos.close();			
		}
		
		// The node keys are no longer dirty
		for (int i=0; i<numberOfGroups; i++) {
			Assert.assertFalse(acm.nodeKeyIsDirty(node[i]));				
		}
	}


}
