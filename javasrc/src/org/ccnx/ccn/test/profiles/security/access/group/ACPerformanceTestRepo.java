/**
 * A CCNx library test.
 *
 * Copyright (C) 2008, 2009, 2010 Palo Alto Research Center, Inc.
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

import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Random;
import java.util.logging.Level;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.config.UserConfiguration;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.CCNFileInputStream;
import org.ccnx.ccn.io.CCNInputStream;
import org.ccnx.ccn.io.CCNOutputStream;
import org.ccnx.ccn.io.RepositoryFileOutputStream;
import org.ccnx.ccn.io.content.Link;
import org.ccnx.ccn.profiles.security.access.AccessDeniedException;
import org.ccnx.ccn.profiles.security.access.group.ACL;
import org.ccnx.ccn.profiles.security.access.group.GroupAccessControlManager;
import org.ccnx.ccn.profiles.security.access.group.GroupAccessControlProfile;
import org.ccnx.ccn.profiles.security.access.group.ACL.ACLOperation;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.utils.CreateUserData;
import org.junit.BeforeClass;
import org.junit.Test;

public class ACPerformanceTestRepo {

	static CCNHandle _AliceHandle;
	static ContentName domainPrefix, userKeystore, userNamespace, groupNamespace;
	static GroupAccessControlManager _acm;
	static String[] userNames = {"Alice", "Bob", "Carol"};
	static ContentName baseDirectory, nodeName;
	static CreateUserData cua;
	static int blockSize = 8096;
	static Random rnd;
	static final String fileName = "./src/org/ccnx/ccn/test/profiles/security/access/group/earth.jpg";
	static final int fileSize = 101783;
	
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		Log.setDefaultLevel(Level.WARNING);
		rnd = new Random();
		
		domainPrefix = UserConfiguration.defaultNamespace();
		userNamespace = GroupAccessControlProfile.userNamespaceName(domainPrefix);
		userKeystore = ContentName.fromNative(userNamespace, "_keystore_");
		groupNamespace = GroupAccessControlProfile.groupNamespaceName(domainPrefix);
		cua = new CreateUserData(userKeystore, userNames, userNames.length, true, "password".toCharArray(), CCNHandle.open());
		cua.publishUserKeysToRepository(userNamespace);
		
		// Initialize the root ACL at domainPrefix with Alice as a manager
		ArrayList<Link> ACLcontents = new ArrayList<Link>();
		Link lk = new Link(ContentName.fromNative(userNamespace, userNames[0]), ACL.LABEL_MANAGER, null);
		ACLcontents.add(lk);
		ACL rootACL = new ACL(ACLcontents);
		_AliceHandle = cua.getHandleForUser(userNames[0]);
		_acm = new GroupAccessControlManager(domainPrefix, groupNamespace, userNamespace, _AliceHandle);
		_acm.initializeNamespace(rootACL);
		_AliceHandle.keyManager().rememberAccessControlManager(_acm);
	}
	
	@Test
	public void performanceTest() throws Exception {
		createBaseDirectoryACL();
		writeFileInDirectory();
		readFileAs(userNames[0]);
		readFileAs(userNames[1]);
		readFileAs(userNames[2]);
		updateACL();
		readFileAs(userNames[2]);
	}
	
	/**
	 * Create a new ACL at baseDirectory with Alice as a manager and Bob as a reader
	 * @throws Exception
	 */
	public void createBaseDirectoryACL() throws Exception {
		long startTime = System.currentTimeMillis();

		baseDirectory = ContentName.fromNative("/ccnx.org/Alice" + rnd.nextInt(100000) + "/documents/images/");
		ArrayList<Link> ACLcontents = new ArrayList<Link>();
		ACLcontents.add(new Link(ContentName.fromNative(userNamespace, userNames[0]), ACL.LABEL_MANAGER, null));
		ACLcontents.add(new Link(ContentName.fromNative(userNamespace, userNames[1]), ACL.LABEL_READER, null));		
		ACL baseDirACL = new ACL(ACLcontents);
		_acm.setACL(baseDirectory, baseDirACL);

		System.out.println("createACL: " + (System.currentTimeMillis() - startTime));
	}
	
	/**
	 * write a file in the baseDirectory
	 * @throws Exception
	 */
	public void writeFileInDirectory() throws Exception {
		long startTime = System.currentTimeMillis();
		InputStream is = new FileInputStream(fileName);
		nodeName = ContentName.fromNative(baseDirectory, "earth.jpg");
		CCNOutputStream ostream = new RepositoryFileOutputStream(nodeName, _AliceHandle);
		ostream.setTimeout(SystemConfiguration.MAX_TIMEOUT);
		
		int size = blockSize;
		int readLen = 0;
		byte [] buffer = new byte[blockSize];
		Log.finer("do_write: " + is.available() + " bytes left.");
		while ((readLen = is.read(buffer, 0, size)) != -1){	
			ostream.write(buffer, 0, readLen);
		}
		ostream.close();
		
		System.out.println("writeFile: " + (System.currentTimeMillis() - startTime));		
	}
	
	public void readFileAs(String userName) throws Exception {
		long startTime = System.currentTimeMillis();
		
		try {
			CCNHandle handle = cua.getHandleForUser(userName);
			CCNInputStream input = new CCNFileInputStream(nodeName, handle);
			input.setTimeout(SystemConfiguration.MAX_TIMEOUT);
			int readsize = 1024;
			byte [] buffer = new byte[readsize];
			int readcount = 0;
			int readtotal = 0;
			while ((readcount = input.read(buffer)) != -1){
				readtotal += readcount;
			}
			if (readtotal != fileSize) throw new Exception("Failure to retrieve file as " + userName);
		} catch (AccessDeniedException ade) {
			System.out.println("Access denied for user: " + userName);
		}
		System.out.println("read file as " + userName + ": " + (System.currentTimeMillis() - startTime));		
	}
	
	/**
	 * Add Carol as a reader to the ACL on baseDirectory
	 * @throws Exception
	 */
	public void updateACL() throws Exception {
		long startTime = System.currentTimeMillis();
		
		ArrayList<ACLOperation> ACLUpdates = new ArrayList<ACLOperation>();
		Link lk = new Link(ContentName.fromNative(userNamespace, userNames[2]));
		ACLUpdates.add(ACLOperation.addReaderOperation(lk));
		_acm.updateACL(baseDirectory, ACLUpdates);

		System.out.println("updateACL: " + (System.currentTimeMillis() - startTime));		
	}
	
}
