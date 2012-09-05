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
import org.ccnx.ccn.config.SystemConfiguration;
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

public class MLACReadWriteTestRepo {

	static CCNHandle _handle;
	static int domainCount = 2;
	static ContentName[] domainPrefix, userKeystore, userNamespace, groupNamespace;
	static String[] userNames = {"Alice", "Bob", "Carol"};
	static ContentName baseDirectory, subdirectory, nodeName;
	static CreateUserData[] cua;
	static final int blockSize = 8096;
	static final int contentSizeInBlocks = 100;
	static Random rnd;
	static CCNHandle _AliceHandle;
	static GroupAccessControlManager _AliceACM;

	int _readsize = 1024;
	byte [] _read_buffer = new byte[_readsize];

	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		
		rnd = new Random();
		_handle = CCNHandle.open();
		
		domainPrefix = new ContentName[domainCount];
		userKeystore = new ContentName[domainCount];
		userNamespace = new ContentName[domainCount];
		groupNamespace = new ContentName[domainCount];

		cua = new CreateUserData[domainCount];
		
		// create user identities in different namespaces
		for (int d=0; d<domainCount; d++) {
			domainPrefix[d] = ContentName.fromNative("/ccnx.org/domain" + d);
			userNamespace[d] = GroupAccessControlProfile.userNamespaceName(domainPrefix[d]);
			userKeystore[d] = new ContentName(userNamespace[d], "_keystore_");
			groupNamespace[d] = GroupAccessControlProfile.groupNamespaceName(domainPrefix[d]);
			cua[d] = new CreateUserData(userKeystore[d], userNames, userNames.length, true, "password".toCharArray());
			cua[d].publishUserKeysToRepositorySetLocators(userNamespace[d]);
		}
		
		// create base directory
		CCNTestHelper testHelper = new CCNTestHelper(MLACReadWriteTestRepo.class);
		baseDirectory = testHelper.getTestNamespace("PerformanceTest");
		
		// The root ACL at domainPrefix has Alice from domain 0 as a manager
		ArrayList<Link> ACLcontents = new ArrayList<Link>();
		Link lk = new Link(new ContentName(userNamespace[0], userNames[0]), ACL.LABEL_MANAGER, null);
		ACLcontents.add(lk);
		ACL rootACL = new ACL(ACLcontents);
		
		// Set user and group storage locations as parameterized names for domain 0 and domain 1
		ArrayList<ParameterizedName> parameterizedNames = new ArrayList<ParameterizedName>();
		for (int d=0; d<domainCount; d++) {
			ParameterizedName uName = new ParameterizedName("User", userNamespace[d], null);
			parameterizedNames.add(uName);
			ParameterizedName gName = new ParameterizedName("Group", groupNamespace[d], null);
			parameterizedNames.add(gName);
		}
		
		// Set access control policy marker	for base directory written as default user.
		ContentName profileName = ContentName.fromNative(GroupAccessControlManager.PROFILE_NAME_STRING);
		GroupAccessControlManager.create(baseDirectory, profileName, rootACL, parameterizedNames, null, SaveType.REPOSITORY, CCNHandle.getHandle());
		
		// get handle and ACM for Alice in domain 0
		_AliceHandle = cua[0].getHandleForUser(userNames[0]);
		Assert.assertNotNull(_AliceHandle);
		NamespaceManager.clearSearchedPathCache();
		AccessControlManager.loadAccessControlManagerForNamespace(baseDirectory, _AliceHandle);
		
		_AliceACM = (GroupAccessControlManager) AccessControlManager.findACM(baseDirectory, _AliceHandle);
		Assert.assertNotNull(_AliceACM);
		
		// load an ACM for the other users in domain 0
		CCNHandle userHandle = null;
		for (int i=1; i < userNames.length; ++i) {
			userHandle = cua[0].getHandleForUser(userNames[i]);
			AccessControlManager.loadAccessControlManagerForNamespace(baseDirectory, userHandle);
		}
		
		// Load an ACM for all users in domain 1.
		for (int i=0; i < userNames.length; ++i) {
			userHandle = cua[1].getHandleForUser(userNames[i]);
			AccessControlManager.loadAccessControlManagerForNamespace(baseDirectory, userHandle);			
		}
	}
	
	@AfterClass
	public static void tearDownAfterClass() throws Exception {
		_handle.close();
		
		for( CreateUserData x : cua ) {
				x.closeAll();
		}	
	}
	
	@Test
	public void performanceTest() throws Exception {
		Log.info(Log.FAC_TEST, "Starting performanceTest");

		// Create a new ACL for a subdirectory of baseDirectory.
		// Set Alice (domain 0) as a manager and Bob and Carol (both domain 1) as readers
		createSubDirectoryACL();
		writeContentInSubdirectory();

		try {
			// Alice (domain 0) has permission to read the file
			readFileAs(0, userNames[0]);
			// Bob (domain 1) has permission to read the file
			readFileAs(1, userNames[1]);
		} catch (AccessDeniedException ade) {
			ade.printStackTrace();
			Assert.fail();
		}

		// Bob (domain 0) does not have permission to read the file
		try {
			readFileAs(0, userNames[1]);
			Assert.fail();
		} 
		catch (AccessDeniedException ade) {}
		
		// Alice (domain 1) does not have permission to read the file		
		try {
			readFileAs(1, userNames[0]);
			Assert.fail();
		} 
		catch (AccessDeniedException ade) {}

		// Give permission to Alice (domain 1) to read the file
		updateACL();
		
		// Alice (domain 1) now has permission to read the file
		try {
			readFileAs(1, userNames[0]);
		}
		catch (AccessDeniedException ade) {
			Assert.fail();
		}
		
		Log.info(Log.FAC_TEST, "Completed performanceTest");
	}
	
	/**
	 * Create a new ACL for a subdirectory of baseDirectory.
	 * Set Alice (domain 0) as a manager and Bob and Carol (both domain 1) as readers
	 */
	public void createSubDirectoryACL() {
		long startTime = System.currentTimeMillis();

		try {
			subdirectory = baseDirectory.append(ContentName.fromNative("/Alice/documents/images/"));
			ArrayList<Link> ACLcontents = new ArrayList<Link>();
			// Alice from domain 0 is a manager
			ACLcontents.add(new Link(new ContentName(userNamespace[0], userNames[0]), ACL.LABEL_MANAGER, null));
			// Bob from domain 1 is a reader
			ACLcontents.add(new Link(new ContentName(userNamespace[1], userNames[1]), ACL.LABEL_READER, null));
			// Carol from domain 1 is a reader
			ACLcontents.add(new Link(new ContentName(userNamespace[1], userNames[2]), ACL.LABEL_READER, null));
			ACL baseDirACL = new ACL(ACLcontents);
			_AliceACM.setACL(subdirectory, baseDirACL);
		}
		catch (Exception e) {
			Log.warningStackTrace(Log.FAC_TEST, e);
			Assert.fail();
		}

		Log.info(Log.FAC_TEST, "MLACReadWriteTestRepo: create ACL completed in {0} ms.", 
				(System.currentTimeMillis() - startTime));
	}
	
	/**
	 * write a file in the baseDirectory
	 */
	public void writeContentInSubdirectory() {
		long startTime = System.currentTimeMillis();
		byte [] _write_buffer = new byte[blockSize];
		
		try {
			nodeName = new ContentName(subdirectory, "randomContent");
			CCNOutputStream ostream = new RepositoryFileOutputStream(nodeName, _AliceHandle);
			ostream.setTimeout(SystemConfiguration.MAX_TIMEOUT);
			
			rnd.nextBytes(_write_buffer);
			for (int i=0; i<contentSizeInBlocks; i++) {
				ostream.write(_write_buffer, 0, blockSize);
			}
			ostream.close();
		} 
		catch (Exception e) {
			e.printStackTrace();
			Assert.fail();
		}
		
		Log.info(Log.FAC_TEST, "MLACReadWriteTestRepo: writing content completed in {0} ms.",
				(System.currentTimeMillis() - startTime));
	}
	
	/**
	 * Read the file as the specified user
	 * @param userName the name of the user
	 * @throws AccessDeniedException
	 */
	public void readFileAs(int domain, String userName) throws Exception {
		long startTime = System.currentTimeMillis();
		
		CCNHandle handle = null;
		try {
			handle = cua[domain].getHandleForUser(userName);
			CCNInputStream input = new CCNFileInputStream(nodeName, handle);
			input.setTimeout(SystemConfiguration.MAX_TIMEOUT);
			int readcount = 0;
			int readtotal = 0;
			while ((readcount = input.read(_read_buffer)) != -1){
				readtotal += readcount;
			}
			Assert.assertEquals(blockSize * contentSizeInBlocks, readtotal);
		}
		// we want to propagate AccessDeniedException, but not IOException.
		// Since AccessDeniedException is a subclass of IOException, we catch and re-throw it.
		catch (AccessDeniedException ade) {
			Log.info(Log.FAC_TEST, "MLACReadWriteTestRepo: determined that user {0} in domain {1} " +
					" does not read access to the content, in {2} ms.",
					userName, domain, (System.currentTimeMillis() - startTime));
			throw ade;
		}
		catch (Exception e) {
			throw e;
		}

		Log.info(Log.FAC_TEST, "MLACReadWriteTestRepo: reading content as {0} succeeded in {1} ms.",
				userName, (System.currentTimeMillis() - startTime));
	}
	
	/**
	 * Add Alice (domain 1) as a reader to the ACL on baseDirectory
	 */
	public void updateACL() {
		long startTime = System.currentTimeMillis();
		
		ArrayList<ACLOperation> ACLUpdates = new ArrayList<ACLOperation>();
		Link lk = new Link(new ContentName(userNamespace[1], userNames[0]));
		ACLUpdates.add(ACLOperation.addReaderOperation(lk));
		try {
			_AliceACM.updateACL(subdirectory, ACLUpdates);
		} 
		catch (Exception e) {
			e.printStackTrace();
			Assert.fail();
		}

		Log.info(Log.FAC_TEST, "MLACReadWriteTestRepo: updated ACL in {1} ms.",
				(System.currentTimeMillis() - startTime));
	}

	
}
