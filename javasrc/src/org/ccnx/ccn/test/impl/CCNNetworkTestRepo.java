/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2011 Palo Alto Research Center, Inc.
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License version 2.1
 * as published by the Free Software Foundation. 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details. You should have received
 * a copy of the GNU Lesser General Public License along with this library;
 * if not, write to the Free Software Foundation, Inc., 51 Franklin Street,
 * Fifth Floor, Boston, MA 02110-1301 USA.
 */

package org.ccnx.ccn.test.impl;

import java.util.ArrayList;
import java.util.Random;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.impl.CCNFlowControl.SaveType;
import org.ccnx.ccn.io.content.CCNStringObject;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.test.CCNTestHelper;
import org.junit.Test;

/**
 * This test once uncovered an error in the network manager due to prefix registration timing
 */
public class CCNNetworkTestRepo {
	
	protected static Random _rnd = new Random();
	static CCNTestHelper testHelper = new CCNTestHelper(CCNNetworkTestRepo.class);
	
	@Test
	public void testObjectIOLoop() throws Exception {
	   CCNHandle handle = CCNHandle.getHandle();
	   ContentName basename = testHelper.getTestNamespace("content_"  + _rnd.nextLong());

	   // Send a stream of string objects
	   ArrayList<CCNStringObject> sent = new ArrayList<CCNStringObject>();
	         int tosend = 100;
	   for(int i = 0; i < tosend; i++) {
	       // Save content
	       try {
	    	   System.out.println("Trying for object " + i);
	           CCNStringObject so = new CCNStringObject(basename,
	                   String.format("string object %d", i),
	                   SaveType.LOCALREPOSITORY, handle);
	           so.save();
	           so.close();
	           sent.add(so);
	       } catch(Exception e) {
	           e.printStackTrace();
	           throw e;
	       }
	       System.out.println(i);
	   }
	}

}
