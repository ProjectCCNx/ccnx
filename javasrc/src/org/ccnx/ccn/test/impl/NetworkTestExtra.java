/*
 * A CCNx library test.
 *
 * Copyright (C) 2011-2012 Palo Alto Research Center, Inc.
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
package org.ccnx.ccn.test.impl;

import org.ccnx.ccn.CCNContentHandler;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Interest;
import org.ccnx.ccn.test.CCNTestBase;
import org.ccnx.ccn.test.CCNTestHelper;
import org.junit.Test;

/**
 * This class contains tests that can be used for diagnosis or other purposes which should
 * not be run as part of the standard test suite
 */
public class NetworkTestExtra extends CCNTestBase implements CCNContentHandler {
	
	static CCNTestHelper testHelper = new CCNTestHelper(NetworkTestExtra.class);
	
	@Test
	public void testThreadOverflow() {
		ContentName name = new ContentName(testHelper.getClassNamespace(), "overflow-test");
		int i = 0;
		while (true) {
			ContentObject obj = ContentObject.buildContentObject(name, ("test-" + i).getBytes());
			try {
				getHandle.expressInterest(new Interest(obj.name()), this);
				putHandle.put(obj);
				Thread.sleep(100);
			} catch (Exception e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
	}
	
	public Interest handleContent(ContentObject data, Interest interest) {
		while (true) {
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {}
		}
	}

}
