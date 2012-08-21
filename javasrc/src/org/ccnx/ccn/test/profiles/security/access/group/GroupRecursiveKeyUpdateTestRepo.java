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

/**
 * PD org.ccnx.ccn.test.profiles.security.access.group
 */
package org.ccnx.ccn.test.profiles.security.access.group;


import java.util.ArrayList;
import java.util.Random;

import junit.framework.Assert;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.UserConfiguration;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.Link;
import org.ccnx.ccn.profiles.security.access.group.Group;
import org.ccnx.ccn.profiles.security.access.group.GroupAccessControlManager;
import org.ccnx.ccn.profiles.security.access.group.GroupAccessControlProfile;
import org.ccnx.ccn.protocol.CCNTime;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.test.CCNTestHelper;
import org.ccnx.ccn.utils.CreateUserData;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class GroupRecursiveKeyUpdateTestRepo {

	static GroupAccessControlManager acm;
	static ContentName directoryBase, userKeyStorePrefix, userNamespace, groupNamespace;

	static final int numberOfusers = 2;
	static CreateUserData td;
	static String[] friendlyNames;

	static final int numberOfGroups = 5;
	static String[] groupName = new String[numberOfGroups];
	static Group[] group = new Group[numberOfGroups];
	static CCNTime[] groupKeyCreationTime = new CCNTime[numberOfGroups];

	static CCNHandle handle;
	
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		
		CCNTestHelper testHelper = new CCNTestHelper(GroupRecursiveKeyUpdateTestRepo.class);
		directoryBase = testHelper.getTestNamespace("testInOrder");
		userNamespace = GroupAccessControlProfile.userNamespaceName(UserConfiguration.defaultNamespace());
		groupNamespace = GroupAccessControlProfile.groupNamespaceName(UserConfiguration.defaultNamespace());
		userKeyStorePrefix = new ContentName(UserConfiguration.defaultNamespace(), "_keystore_"); 
		
		// create user identities with TestUserData		
		td = new CreateUserData(userKeyStorePrefix, numberOfusers, true, "password".toCharArray());
		td.publishUserKeysToRepository(userNamespace);
		friendlyNames = td.friendlyNames().toArray(new String[0]);				
		
		// create ACM
		handle = td.getHandleForUser(friendlyNames[1]);
		acm = new GroupAccessControlManager(directoryBase, groupNamespace, userNamespace, handle);
		acm.publishMyIdentity(new ContentName(userNamespace, friendlyNames[1]), handle.keyManager().getDefaultPublicKey());
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

		createGroups();
		testRecursiveGroupAncestors();
		removeMemberFromGroup0();
		
		Log.info(Log.FAC_TEST, "Completed testInOrder");
	}
	

	/**
	 * We create the following group structure:
	 * 
	 *              Group3
	 *              /   \
	 *          Group1  Group2
	 *             \     /  \
	 *             Group0  Group4
	 *             /   \   /
	 *          User0  User1 
	 * 
	 * @throws Exception
	 */
	public void createGroups() throws Exception {
		Random rand = new Random();

		// create group0 containing user0 and user1
		ArrayList<Link> G0Members = new ArrayList<Link>();
		G0Members.add(new Link(new ContentName(userNamespace, friendlyNames[0])));
		G0Members.add(new Link(new ContentName(userNamespace, friendlyNames[1])));
		groupName[0] = "group0-" + rand.nextInt(10000);
		group[0] = acm.groupManager().createGroup(groupName[0], G0Members, 0);
		
		// create group4 containing user1
		ArrayList<Link> G4Members = new ArrayList<Link>();
		G4Members.add(new Link(new ContentName(userNamespace, friendlyNames[1])));
		groupName[4] = "group4-" + rand.nextInt(10000);
		group[4] = acm.groupManager().createGroup(groupName[4], G4Members, 0);
		
		// create group1 and group2 containing group0
		ArrayList<Link> G1G2Members = new ArrayList<Link>();
		G1G2Members.add(new Link(new ContentName(groupNamespace, groupName[0])));
		groupName[1] = "group1-" + rand.nextInt(10000);
		group[1] = acm.groupManager().createGroup(groupName[1], G1G2Members, 0);
		groupName[2] = "group2-" + rand.nextInt(10000);
		group[2] = acm.groupManager().createGroup(groupName[2], G1G2Members, 0);
		
		// create group3 containing group1 and group2
		ArrayList<Link> G3Members = new ArrayList<Link>();
		G3Members.add(new Link(new ContentName(groupNamespace, groupName[1])));
		G3Members.add(new Link(new ContentName(groupNamespace, groupName[2])));
		groupName[3] = "group3-" + rand.nextInt(10000);
		group[3] = acm.groupManager().createGroup(groupName[3], G3Members, 0);
		
		// record the creation time of the original group keys
		for (int i=0; i<numberOfGroups; i++) groupKeyCreationTime[i] = group[i].publicKeyVersion();

		// check the size of the groups
		Assert.assertEquals(2, group[0].membershipList().membershipList().size());
		Assert.assertEquals(1, group[1].membershipList().membershipList().size());
		Assert.assertEquals(1, group[2].membershipList().membershipList().size());
		Assert.assertEquals(2, group[3].membershipList().membershipList().size());
		Assert.assertEquals(1, group[4].membershipList().membershipList().size());
	}

	public void testRecursiveGroupAncestors() throws Exception {
		ArrayList<Link> ancestors = group[0].recursiveAncestorList(null);
		// Group0 should have 3 ancestors, not 4 (check that Group3 is not double-counted)
		Assert.assertEquals(3, ancestors.size());
	}
	
	/**
	 * We delete User0 from Group0 to cause a recursive key update for groups 0, 1, 2 and 3 (but not Group4).
	 * 
	 *              Group3
	 *              /   \
	 *          Group1  Group2
	 *             \     /  \
	 *             Group0   Group4
	 *             /   \   /
	 *            ---  User1 
	 * 
	 * @throws Exception
	 */
	public void removeMemberFromGroup0() throws Exception {
		// delete user0 from group0
		ArrayList<Link> membersToRemove = new ArrayList<Link>();
		membersToRemove.add(new Link(new ContentName(userNamespace, friendlyNames[0])));
		group[0].removeMembers(membersToRemove);
		Thread.sleep(1000);
		
		// check group0 is of size 1 
		Assert.assertEquals(1, group[0].membershipList().membershipList().size());
		
		// check keys of group0, group1, group2 and group3 were updated
		Assert.assertTrue(group[0].publicKeyVersion().after(groupKeyCreationTime[0]));
		Assert.assertTrue(group[1].publicKeyVersion().after(groupKeyCreationTime[1]));
		Assert.assertTrue(group[2].publicKeyVersion().after(groupKeyCreationTime[2]));
		Assert.assertTrue(group[3].publicKeyVersion().after(groupKeyCreationTime[3]));
		
		// check key of group4 was not updated
		Assert.assertTrue(group[4].publicKeyVersion().equals(groupKeyCreationTime[4]));
	}
	
}
