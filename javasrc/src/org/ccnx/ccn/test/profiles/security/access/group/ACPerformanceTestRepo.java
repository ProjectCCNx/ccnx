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

import java.util.ArrayList;
import java.util.Random;

import junit.framework.Assert;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.config.UserConfiguration;
import org.ccnx.ccn.impl.CCNFlowControl.SaveType;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.CCNFileInputStream;
import org.ccnx.ccn.io.CCNInputStream;
import org.ccnx.ccn.io.CCNOutputStream;
import org.ccnx.ccn.io.RepositoryFileOutputStream;
import org.ccnx.ccn.io.content.Link;
import org.ccnx.ccn.profiles.namespace.NamespaceManager;
import org.ccnx.ccn.profiles.namespace.ParameterizedName;
import org.ccnx.ccn.profiles.security.access.AccessControlManager;
import org.ccnx.ccn.profiles.security.access.AccessDeniedException;
import org.ccnx.ccn.profiles.security.access.group.ACL;
import org.ccnx.ccn.profiles.security.access.group.GroupAccessControlManager;
import org.ccnx.ccn.profiles.security.access.group.GroupAccessControlProfile;
import org.ccnx.ccn.profiles.security.access.group.ACL.ACLOperation;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.test.CCNTestHelper;
import org.ccnx.ccn.utils.CreateUserData;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class ACPerformanceTestRepo {

	static ContentName domainPrefix, userKeystore, userNamespace, groupNamespace;
	static String[] userNames = {"Alice", "Bob", "Carol"};
	static ContentName baseDirectory, nodeName;
	static CreateUserData cua;
	static final int blockSize = 8096;
	static final int contentSizeInBlocks = 100;
	static Random rnd;
	static CCNHandle _AliceHandle;
	static GroupAccessControlManager _AliceACM;

	int readsize = 1024;
	byte [] buffer = new byte[readsize];
		
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		rnd = new Random();
		
		CCNTestHelper testHelper = new CCNTestHelper(ACPerformanceTestRepo.class);
		domainPrefix = testHelper.getTestNamespace("testInOrder");
		
		userNamespace = GroupAccessControlProfile.userNamespaceName(UserConfiguration.defaultNamespace());
		groupNamespace = GroupAccessControlProfile.groupNamespaceName(UserConfiguration.defaultNamespace());
		userKeystore = new ContentName(UserConfiguration.defaultNamespace(), "_keystore_"); 
	
		cua = new CreateUserData(userKeystore, userNames, userNames.length, true, "password".toCharArray());
		cua.publishUserKeysToRepositorySetLocators(userNamespace);

		// The root ACL at domainPrefix has Alice as a manager
		ArrayList<Link> ACLcontents = new ArrayList<Link>();
		Link lk = new Link(new ContentName(userNamespace, userNames[0]), ACL.LABEL_MANAGER, null);
		ACLcontents.add(lk);
		ACL rootACL = new ACL(ACLcontents);
		
		// Set user and group storage locations as parameterized names
		ArrayList<ParameterizedName> parameterizedNames = new ArrayList<ParameterizedName>();
		ParameterizedName uName = new ParameterizedName("User", userNamespace, null);
		parameterizedNames.add(uName);
		ParameterizedName gName = new ParameterizedName("Group", groupNamespace, null);
		parameterizedNames.add(gName);
		
		// Set access control policy marker, written as default user.
		ContentName profileName = ContentName.fromNative(GroupAccessControlManager.PROFILE_NAME_STRING);
		GroupAccessControlManager.create(domainPrefix, profileName, rootACL, parameterizedNames, null, SaveType.REPOSITORY, CCNHandle.getHandle());
		
		// get handle and ACM for Alice
		_AliceHandle = cua.getHandleForUser(userNames[0]);
		Assert.assertNotNull(_AliceHandle);
		NamespaceManager.clearSearchedPathCache();
		AccessControlManager.loadAccessControlManagerForNamespace(domainPrefix, _AliceHandle);
		
		_AliceACM = (GroupAccessControlManager) AccessControlManager.findACM(domainPrefix, _AliceHandle);
		Assert.assertNotNull(_AliceACM);
		
		// load an ACM for the other users.
		CCNHandle userHandle = null;
		for (int i=1; i < userNames.length; ++i) {
			userHandle = cua.getHandleForUser(userNames[i]);
			AccessControlManager.loadAccessControlManagerForNamespace(domainPrefix, userHandle);
		}
	}
	
	@AfterClass
	public static void tearDownAfterClass() throws Exception {
		cua.closeAll();
	}
	
	@Test
	public void performanceTest() throws Exception {
		Log.info(Log.FAC_TEST, "Starting performanceTest");

		createBaseDirectoryACL();
		writeContentInDirectory();

		// Alice and Bob have permission to read the file
		readFileAs(userNames[0]);
		readFileAs(userNames[1]);

		// Carol does not have permission to read the file
		try {
			readFileAs(userNames[2]);
			Assert.fail();
		} 
		catch (AccessDeniedException ade) {}

		updateACL();
		
		// Carol now has permission to read the file
		readFileAs(userNames[2]);
		
		Log.info(Log.FAC_TEST, "Completed performanceTest");
	}
	
	/**
	 * Create a new ACL at baseDirectory with Alice as a manager and Bob as a reader
	 */
	public void createBaseDirectoryACL() throws Exception {
		long startTime = System.currentTimeMillis();

		baseDirectory = domainPrefix.append(ContentName.fromNative("/Alice/documents/images/"));
		ArrayList<Link> ACLcontents = new ArrayList<Link>();
		ACLcontents.add(new Link(new ContentName(userNamespace, userNames[0]), ACL.LABEL_MANAGER, null));
		ACLcontents.add(new Link(new ContentName(userNamespace, userNames[1]), ACL.LABEL_READER, null));		
		ACL baseDirACL = new ACL(ACLcontents);
		_AliceACM.setACL(baseDirectory, baseDirACL);

		System.out.println("createACL: " + (System.currentTimeMillis() - startTime));
	}
	
	/**
	 * write a file in the baseDirectory
	 */
	public void writeContentInDirectory() {
		long startTime = System.currentTimeMillis();
		
		try {
			nodeName = new ContentName(baseDirectory, "randomContent");
			CCNOutputStream ostream = new RepositoryFileOutputStream(nodeName, _AliceHandle);
			ostream.setTimeout(SystemConfiguration.MAX_TIMEOUT);
			
			byte [] buffer = new byte[blockSize];
			for (int i=0; i<contentSizeInBlocks; i++) {
				rnd.nextBytes(buffer);
				ostream.write(buffer, 0, blockSize);
			}
			ostream.close();
		} 
		catch (Exception e) {
			Log.warningStackTrace(Log.FAC_TEST, e);
			Assert.fail();
		}
		
		System.out.println("writeContent: " + (System.currentTimeMillis() - startTime));		
	}
	
	/**
	 * Read the file as the specified user
	 * @param userName the name of the user
	 * @throws AccessDeniedException
	 */
	public void readFileAs(String userName) throws Exception {
		long startTime = System.currentTimeMillis();
		
		try {
			CCNHandle handle = cua.getHandleForUser(userName);
			CCNInputStream input = new CCNFileInputStream(nodeName, handle);
			input.setTimeout(SystemConfiguration.MAX_TIMEOUT);
			int readcount = 0;
			int readtotal = 0;
			while ((readcount = input.read(buffer)) != -1){
				readtotal += readcount;
			}
			Assert.assertEquals(blockSize * contentSizeInBlocks, readtotal);
		}
		// we want to propagate AccessDeniedException, but not IOException.
		// Since AccessDeniedException is a subclass of IOException, we catch and re-throw it.
		catch (AccessDeniedException ade) {
			System.out.println("Failed to read file as " + userName + ": " + (System.currentTimeMillis() - startTime));		
			throw ade;
		}
		catch (Exception e) {
			throw e;
		}

		System.out.println("read file as " + userName + ": " + (System.currentTimeMillis() - startTime));		
	}
	
	/**
	 * Add Carol as a reader to the ACL on baseDirectory
	 */
	public void updateACL() throws Exception {
		long startTime = System.currentTimeMillis();
		
		ArrayList<ACLOperation> ACLUpdates = new ArrayList<ACLOperation>();
		Link lk = new Link(new ContentName(userNamespace, userNames[2]));
		ACLUpdates.add(ACLOperation.addReaderOperation(lk));
		_AliceACM.updateACL(baseDirectory, ACLUpdates);

		System.out.println("updateACL: " + (System.currentTimeMillis() - startTime));		
	}
	
}
