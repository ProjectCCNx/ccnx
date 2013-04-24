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

import junit.framework.Assert;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.impl.support.DataUtils;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.CCNFileInputStream;
import org.ccnx.ccn.io.RepositoryFileOutputStream;
import org.ccnx.ccn.protocol.ContentName;
import org.junit.BeforeClass;
import org.junit.Test;

public class GroupAccessControlTestRepo {
	static ContentName acName;
	static ContentName fileName;
	static byte content[] = "the network is built around me".getBytes();

	/**
	 * Create a new root of an access controlled namespace in the repo
	 * and write a file out there
	 */
	@BeforeClass
	public static void createAC() throws Exception {
		// mark the namespace as under access control
    //  ACL acl = new ACL();
		acName = ContentName.fromNative("/parc.com/ac_repo");
	//	NamespaceManager.Root.create(acName, acl, SaveType.REPOSITORY, CCNHandle.getHandle());

		// create a file in the namespace under access control
		fileName = new ContentName(acName, "test.txt");
		CCNHandle h = CCNHandle.getHandle();
		RepositoryFileOutputStream os = new RepositoryFileOutputStream(fileName, h);
		os.write(content);
		os.close();
	}

	@Test
	public void read() throws Exception {
		Log.info(Log.FAC_TEST, "Starting read");

		CCNFileInputStream is = new CCNFileInputStream(fileName);
		byte received[] = new byte[content.length];
		Assert.assertEquals(is.read(received), content.length);
		Assert.assertTrue(DataUtils.arrayEquals(content, received));
		
		Log.info(Log.FAC_TEST, "Completed read");
	}
}
