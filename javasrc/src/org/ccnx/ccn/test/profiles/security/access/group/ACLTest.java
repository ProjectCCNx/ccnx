/*
 * A CCNx library test.
 *
 * Copyright (C) 2008, 2009, 2011 Palo Alto Research Center, Inc.
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
import java.util.LinkedList;

import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.Link;
import org.ccnx.ccn.profiles.security.access.group.ACL;
import org.ccnx.ccn.profiles.security.access.group.ACL.ACLOperation;
import org.ccnx.ccn.protocol.ContentName;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;


/**
 * Tests functionality of ACL class.
 * 
 * @author pgolle
 *
 */


public class ACLTest {
	
	static Link lr1 = null;
	static Link lr2 = null;
	static Link lr3 = null;
	
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		lr1 = new Link(ContentName.fromNative("/parc/sds/pgolle"));
		lr2 = new Link(ContentName.fromNative("/parc/sds/eshi"));
		lr3 = new Link(ContentName.fromNative("/parc/sds/smetters"));		
	}
	
	@Test
	public void testACLCreation() throws Exception {
		ACL testACL = new ACL();
		
		testACL.addReader(lr1);
		testACL.addReader(lr1);
		testACL.addWriter(lr1);
		testACL.addManager(lr1);
		testACL.addWriter(lr2);
		testACL.addManager(lr3);
		
		Assert.assertTrue(testACL.validate());
	}

	@Test
	public void testACLCreationFromArrayList() throws Exception {
		ArrayList<Link> alr = new ArrayList<Link>();
		alr.add(new Link(ContentName.fromNative("/parc/sds/pgolle"), "r", null));
		alr.add(new Link(ContentName.fromNative("/parc/sds/eshi"), "rw", null));
		ACL testACL = new ACL(alr);
		Assert.assertTrue(testACL.validate());
	}
	
	@Test
	public void testUpdate() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testUpdate");

		ACL testACL = new ACL();
		ArrayList<ACL.ACLOperation> userList = new ArrayList<ACL.ACLOperation>();
		
		// add lr1 and lr2 as readers (2 new readers)
		userList.add(ACLOperation.addReaderOperation(lr1));
		userList.add(ACLOperation.addReaderOperation(lr2));
		LinkedList<Link> result = 
			testACL.update(userList);
		Assert.assertEquals(2, result.size());

		// add the same 2 readers again (0 new reader)
		result = testACL.update(userList);
		Assert.assertEquals(0, result.size());
		
		// delete reader lr1 and add reader lr3
		// (null result indicates some read privileges lost)
		ArrayList<ACL.ACLOperation> ops = new ArrayList<ACL.ACLOperation>();
		ops.add(ACLOperation.removeReaderOperation(lr1));
		ops.add(ACLOperation.addReaderOperation(lr3));		
		result = testACL.update(ops);
		Assert.assertEquals(null, result);
		
		// add readers lr1 and lr2 (only lr1 is new)
		result = testACL.update(userList);
		Assert.assertEquals(1, result.size());
		
		Log.info(Log.FAC_TEST, "Completed testUpdate");
	}
}
