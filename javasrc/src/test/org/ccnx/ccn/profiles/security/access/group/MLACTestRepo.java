/*
 * A CCNx library test.
 *
 * Copyright (C) 2010-2012 Palo Alto Research Center, Inc.
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
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.Link;
import org.ccnx.ccn.profiles.security.access.group.Group;
import org.ccnx.ccn.profiles.security.access.group.GroupAccessControlManager;
import org.ccnx.ccn.profiles.security.access.group.GroupAccessControlProfile;
import org.ccnx.ccn.profiles.security.access.group.GroupManager;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.utils.CreateUserData;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class MLACTestRepo {

	static CCNHandle _handle;
	static int domainCount = 2;
	static ContentName[] domainPrefix, userKeystore, userNamespace, groupNamespace;
	static CreateUserData td;
	static GroupAccessControlManager _acm;
	static String[] userNames = {"Alice", "Bob", "Carol"};
	static Random rnd;
	static String firstGroupName;
	
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		
		rnd = new Random();
		_handle = CCNHandle.open();
		
		domainPrefix = new ContentName[domainCount];
		userKeystore = new ContentName[domainCount];
		userNamespace = new ContentName[domainCount];
		groupNamespace = new ContentName[domainCount];
		
		// create user identities in different namespaces
		for (int d=0; d<domainCount; d++) {
			domainPrefix[d] = ContentName.fromNative("/ccnx.org/domain" + d);
			userNamespace[d] = GroupAccessControlProfile.userNamespaceName(domainPrefix[d]);
			userKeystore[d] = new ContentName(userNamespace[d], "_keystore_");
			groupNamespace[d] = GroupAccessControlProfile.groupNamespaceName(domainPrefix[d]);
			td = new CreateUserData(userKeystore[d], userNames, userNames.length, true, "password".toCharArray());
			td.publishUserKeysToRepositorySetLocators(userNamespace[d]);			
		}
	}
	
	@AfterClass
	public static void tearDownAfterClass() throws Exception {
		_handle.close();
	}

	@Test
	public void testInOrder() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testInOrder");

		createMixedGroup();
		createMixedGroupOfGroup();
		
		Log.info(Log.FAC_TEST, "Completed testInOrder");
	}
	
	/**
	 * This test creates a group in domain0 consisting of Alice and Bob (from domain 0) and Alice (from domain 1)
	 * @throws Exception
	 */
	public void createMixedGroup() throws Exception {
		_acm = new GroupAccessControlManager(ContentName.fromNative("/ccnx.org"), groupNamespace, userNamespace, _handle);
		GroupManager _gm0 = _acm.groupManager(groupNamespace[0]);
		Assert.assertNotNull(_gm0);
		
		ArrayList<Link> groupMembers = new ArrayList<Link>();
		// add Alice from domain0
		groupMembers.add(new Link(new ContentName(userNamespace[0], userNames[0])));
		// add Bob from domain0
		groupMembers.add(new Link(new ContentName(userNamespace[0], userNames[1])));
		// add Alice from domain1
		groupMembers.add(new Link(new ContentName(userNamespace[1], userNames[0])));

		firstGroupName = "group-" + rnd.nextInt(10000);
		Group mixedGroup = _gm0.createGroup(firstGroupName, groupMembers, 0);
		Assert.assertNotNull(mixedGroup);
	}
	
	/**
	 * This test creates a group in domain1 consisting of the group in domain0 created above and Bob (from domain1)
	 * @throws Exception
	 */
	public void createMixedGroupOfGroup() throws Exception {
		GroupManager _gm1 = _acm.groupManager(groupNamespace[1]);
		Assert.assertNotNull(_gm1);
		
		ArrayList<Link> groupMembers = new ArrayList<Link>();
		// add the group from domain0 created above
		groupMembers.add(new Link(new ContentName(groupNamespace[0], firstGroupName)));
		// add Bob from domain1
		groupMembers.add(new Link(new ContentName(userNamespace[1], userNames[1])));
		
		Group mixedGroupOfGroup = _gm1.createGroup("group-" + rnd.nextInt(10000), groupMembers, 0);
		Assert.assertNotNull(mixedGroupOfGroup);
	}
	
}
