/*
 * A CCNx library test.
 *
 * Copyright (C) 2008-2012 Palo Alto Research Center, Inc.
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

import java.security.Key;
import java.util.ArrayList;
import java.util.Random;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.KeyManager;
import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.config.UserConfiguration;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.Link;
import org.ccnx.ccn.profiles.security.access.group.ACL;
import org.ccnx.ccn.profiles.security.access.group.Group;
import org.ccnx.ccn.profiles.security.access.group.GroupAccessControlManager;
import org.ccnx.ccn.profiles.security.access.group.GroupAccessControlProfile;
import org.ccnx.ccn.profiles.security.access.group.GroupManager;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.utils.CreateUserData;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;


public class GroupTestRepo {
	
	static boolean USE_REPO = true;
	static int NUM_USERS = 3;
	static char [] USER_PASSWORD = new String("password").toCharArray();
	
	static ContentName testStorePrefix = null;
	static ContentName userNamespace = null;
	static ContentName groupStore = null;
	static ContentName userKeyStorePrefix = null;
	
	static String[] _userList = null;
	static CCNHandle _handle = null;
	
	static String myUserName = null;
	
	/**
	 * Have to make a bunch of users.
	 * @throws Exception
	 */
	static CreateUserData users = null;
	static GroupAccessControlManager _acm = null;
	static GroupManager _gm = null;
	static Random _random;
	static String _randomGroupName;

	@AfterClass
	public static void tearDownAfterClass() throws Exception {
		users.closeAll();
		KeyManager.closeDefaultKeyManager();
	}
	
	@BeforeClass
	public static void setUpBeforeClass() throws Exception { 
		try {
			myUserName = UserConfiguration.userName();
			System.out.println("Username = " + myUserName);
			testStorePrefix = UserConfiguration.defaultNamespace();
			userNamespace = new ContentName(testStorePrefix, "Users");
			userKeyStorePrefix = new ContentName(testStorePrefix, "ccnx_keystore");
			groupStore = GroupAccessControlProfile.groupNamespaceName(testStorePrefix);

			System.out.println("prefix: " + testStorePrefix);
			System.out.println("group store: " + groupStore);
			System.out.println("user store: " + userNamespace);

			_handle = CCNHandle.getHandle();
			
			users = new CreateUserData(userKeyStorePrefix, NUM_USERS,
					USE_REPO,
					USER_PASSWORD);
			users.publishUserKeysToRepository(userNamespace);
			
			_acm = new GroupAccessControlManager(testStorePrefix, groupStore, userNamespace);
			_acm.publishMyIdentity(new ContentName(userNamespace, myUserName), 
					KeyManager.getDefaultKeyManager().getDefaultPublicKey());
			_userList = users.friendlyNames().toArray(new String[0]);
			
			// create the root ACL
			// myUserName is a manager
			Link lk = new Link(new ContentName(userNamespace, myUserName), ACL.LABEL_MANAGER, null);
			ArrayList<Link> rootACLcontents = new ArrayList<Link>();
			rootACLcontents.add(lk);
			ACL rootACL = new ACL(rootACLcontents);
			_acm.initializeNamespace(rootACL);
			
			// Whose access control manager is this supposed to be?
			_handle.keyManager().rememberAccessControlManager(_acm);
			
			_gm = _acm.groupManager();
			
			_random = new Random();
			_randomGroupName = "testGroup" + _random.nextInt();

		} catch (Exception e) {
			Log.warning(Log.FAC_TEST, "Exception in setupBeforeClass: " + e);
			Log.warningStackTrace(Log.FAC_TEST, e);
			throw e;
		}
	}	
	
	@Test
	public void testGroup() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testGroup");

		ArrayList<Link> newMembers = new ArrayList<Link>();
		ContentName userID = new ContentName(userNamespace, myUserName);
		System.out.println("member to add:" + userID);
		newMembers.add(new Link(userID));

		for(int i = 0; i <2; i++){
			String name = _userList[i];
			System.out.println("member to add:" + name);
			newMembers.add(new Link(new ContentName(userNamespace, name)));
		}
		System.out.println("creating a group...");
		Group newGroup = _gm.createGroup(_randomGroupName, newMembers, 0);

		Assert.assertTrue(_gm.haveKnownGroupMemberships());
		Assert.assertTrue(_gm.amCurrentGroupMember(newGroup));
		Assert.assertTrue(_gm.amCurrentGroupMember(_randomGroupName));

		String name = _userList[2];
		System.out.println("adding member to group:"+ name);
		ArrayList<Link> addMembers = new ArrayList<Link>();
		addMembers.add(new Link(new ContentName(userNamespace, name)));
		
		// add members to the group
		newGroup.addMembers(addMembers);

		ArrayList<Link> removeMembers = new ArrayList<Link>();
		System.out.println("removing user:.................." + newMembers.get(2).targetName());
		removeMembers.add(newMembers.get(2));
		newGroup.removeMembers(removeMembers);

		Assert.assertTrue(_gm.haveKnownGroupMemberships());
		Assert.assertTrue(_gm.amCurrentGroupMember(newGroup));
		Assert.assertTrue(_gm.amCurrentGroupMember(_randomGroupName));

		//isGroup sometimes fails, there's a timing issue. 
		Assert.assertTrue(_gm.isGroup(_randomGroupName, SystemConfiguration.EXTRA_LONG_TIMEOUT));
		ContentName pkName = newGroup.publicKeyName();
		System.out.println("new group's pk name is " + pkName);
		Assert.assertTrue(_gm.isGroup(pkName));

		//test groups of group
		String randomParentGroupName = "parentGroup" + _random.nextInt();
		newMembers.clear();
		ContentName childGroupNamespace = GroupAccessControlProfile.groupName(testStorePrefix, _randomGroupName);
		System.out.println("child group namespace = " + childGroupNamespace);
		newMembers.add(new Link(childGroupNamespace));
		Group newParentGroup = _gm.createGroup(randomParentGroupName, newMembers, 0);
		
		Assert.assertTrue(_gm.amCurrentGroupMember(newParentGroup));

		System.out.println("adding users to parent group.........");
		Thread.sleep(1000);
		newParentGroup.addMembers(addMembers);

		Key privKey = _gm.getGroupPrivateKey(randomParentGroupName, null);
		System.out.println("retrieved group priv key for parent group:" + privKey.toString());

		privKey = _gm.getGroupPrivateKey(_randomGroupName, null);
		System.out.println("retrieved group priv key:" + privKey.toString());
		
		Log.info(Log.FAC_TEST, "Completed testGroup");
	}
	
	@Test
	public void testGroupUpdate() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testGroupUpdate");

		Group ourExistingGroup = _gm.getGroup(_randomGroupName, SystemConfiguration.EXTRA_LONG_TIMEOUT);
		
		// This should be a cache hit. Test that with a 0 timeout
		Group aPointerCopy = _gm.getGroup(_randomGroupName, 0);
		
		Assert.assertNotNull("Unexpected cache miss!", aPointerCopy);
		
		// testing pointer equality -- these should be the same object
		Assert.assertTrue(ourExistingGroup == aPointerCopy);
		
		GroupAccessControlManager ourACM = new GroupAccessControlManager(testStorePrefix, groupStore, userNamespace);
		ourACM.publishMyIdentity(new ContentName(userNamespace, myUserName), KeyManager.getDefaultKeyManager().getDefaultPublicKey());
		GroupManager ourGM = ourACM.groupManager();
		Thread.sleep(1000);
		
		Group aSeparateGroupCopy = ourGM.getGroup(_randomGroupName, SystemConfiguration.EXTRA_LONG_TIMEOUT);

		Assert.assertEquals(ourExistingGroup.publicKeyName(), aSeparateGroupCopy.publicKeyName());
		Log.info("Original key name {0}, copy key name {1}", ourExistingGroup.publicKeyName(), aSeparateGroupCopy.publicKeyName());
		System.out.println("Original key version: " + ourExistingGroup.publicKeyVersion());
		System.out.println("Copy key version    : " + aSeparateGroupCopy.publicKeyVersion());
		// Now we update the group public key.
		ArrayList<Link> removeMembers = new ArrayList<Link>();
		Link memberToRemove = aSeparateGroupCopy.membershipList().contents().getLast();
//		Link memberToRemove = ourExistingGroup.membershipList().contents().getLast();
		removeMembers.add(memberToRemove);
		System.out.println("removing user:.................." + memberToRemove.targetName());
		aSeparateGroupCopy.removeMembers(removeMembers);
//		ourExistingGroup.removeMembers(removeMembers);
		
		Log.info("Post-write Original key name {0}, copy key name {1}", ourExistingGroup.publicKeyName(), aSeparateGroupCopy.publicKeyName());
		System.out.println("Post-write original key version: " + ourExistingGroup.publicKeyVersion());
		System.out.println("Post-write copy key version    : " + aSeparateGroupCopy.publicKeyVersion());
		Thread.sleep(1000);
		Log.info("Post-write and sleep Original key name {0}, copy key name {1}", ourExistingGroup.publicKeyName(), aSeparateGroupCopy.publicKeyName());
		System.out.println("Post-write and sleep original key version: " + ourExistingGroup.publicKeyVersion());
		System.out.println("Post-write and sleep copy key version    : " + aSeparateGroupCopy.publicKeyVersion());
		
		Log.info(Log.FAC_TEST, "Completed testGroupUpdate");
	}
}
