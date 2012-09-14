/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008-2012 Palo Alto Research Center, Inc.
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

package org.ccnx.ccn.test.profiles.versioning;

import java.util.Random;

import junit.framework.Assert;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.impl.CCNFlowControl.SaveType;
import org.ccnx.ccn.io.content.CCNStringObject;
import org.ccnx.ccn.profiles.versioning.InterestData;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.Interest;
import org.ccnx.ccn.test.profiles.versioning.VersioningHelper.TestFilterListener;
import org.ccnx.ccn.test.profiles.versioning.VersioningHelper.TestListener;
import org.ccnx.ccn.utils.CreateUserData;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class InterestAndFilterTestRepo {
	
	@Before
	public void setUp() throws Exception {
		System.out.println("********* Creating test user");
		
		_defaultHandle = CCNHandle.open();
		
		_prefix = ContentName.fromNative(String.format("/repotest/InterestAndFilter_%016X", _rnd.nextLong()));
		_userHandle = CCNHandle.getHandle();
		
		ContentName users = new ContentName(_prefix, "Users");
		_cud = new CreateUserData(users, _users, _users.length, true, _password);
		_userHandle = _cud.getHandleForUser(_users[0]);
				
		// Now clear the stats
		_userHandle.getNetworkManager().getStats().clearCounters();
	}
	
	@After
	public void tearDown() throws Exception {
		System.out.println(_userHandle.getNetworkManager().getStats().toString());
		_defaultHandle.close();
		_userHandle.close();
		_cud.closeAll();
	}
	
	/**
	 * Express an interest in a name, publish objects on that, using the same handle
	 * @throws Exception
	 */
	@Test
	public void testInterest() throws Exception {
		System.out.println("********* Running testInterest");
		
		ContentName name = new ContentName(_prefix, "data");
		int sendcount = 2;

		// this is needed to loop the listener
		InterestData id = new InterestData(name);
		
		final TestListener listener = new TestListener();
		listener.setInterestData(id);
		listener.debugOutput = true;
		listener.sendFirstInterest = false;
		listener.runCount = sendcount;
		
		// Create the first interest
		Interest interest = id.buildInterest();
		_userHandle.expressInterest(interest, listener);
		
		// =====================================
		System.out.println("** Sending two objects 5 seconds apart");
		
		// Now send a few things and make sure we get them.  Space them 5 seconds
		// apart to make sure we need to have re-expressed the interest
		sendObjects(name, sendcount, 5000);
		
		// make sure we got them
		Assert.assertTrue( listener.run(_userHandle, sendcount, 10000) );
		
		// now reset the count
		listener.cl.setValue(0);
		listener.received.clear();
	}
	
	/**
	 * Express an interest in a name, then create an interest filter for the same name,
	 * then destroy the filter.
	 * @throws Exception
	 */
	@Test
	public void testInterestAndFilter() throws Exception {
		System.out.println("********* Running testInterestAndFilter");
		
		ContentName name = new ContentName(_prefix, "data");
		int sendcount = 2;
		
		// this is needed to loop the listener
		InterestData id = new InterestData(name);
		
		final TestListener listener = new TestListener();
		listener.setInterestData(id);
		listener.debugOutput = true;
		listener.sendFirstInterest = false;
		listener.runCount = sendcount;
		
		// Create the first interest
		Interest interest = id.buildInterest();
		_userHandle.expressInterest(interest, listener);
		
		// =====================================
		System.out.println("** Sending two objects 5 seconds apart");

		// Now send a few things and make sure we get them.  Space them 5 seconds
		// apart to make sure we need to have re-expressed the interest
		sendObjects(name, sendcount, 5000);
		
		// make sure we got them
		Assert.assertTrue( listener.run(_userHandle, sendcount, 10000) );
				
		// now reset the count
		listener.cl.setValue(0);
		listener.received.clear();
		
		// =====================================
		System.out.println("** Registering an Interest Filter on the same namespace");
		
		TestFilterListener filter = new TestFilterListener();
		filter.debugOutput = true;
		_userHandle.registerFilter(name, filter);
		
		System.out.println("** Sending two objects 5 seconds apart");
		sendObjects(name, sendcount, 5000);
		Assert.assertTrue( listener.run(_userHandle, sendcount, 10000) );
		listener.cl.setValue(0);
		listener.received.clear();
		
		// =====================================
		System.out.println("** Removing filter and resending objects");
		_userHandle.unregisterFilter(name, filter);
		Thread.sleep(100);
		
		System.out.println("** Sending two objects 5 seconds apart");
		sendObjects(name, sendcount, 5000);
		Assert.assertTrue( listener.run(_userHandle, sendcount, 10000) );
		
		System.out.println("** Checking calls to WriteInterest");
		long c0 = _userHandle.getNetworkManager().getStats().getCounter("WriteInterest");
		Thread.sleep(10000);
		long c1 = _userHandle.getNetworkManager().getStats().getCounter("WriteInterest");
		
		System.out.println(String.format("** c0 = %d, c1 = %d, delta = %d", c0, c1, c1 - c0));
		
		// Over 10 seconds, there should be 2 or 3 interests (times 0, 4, 8)
		Assert.assertTrue(2 <= (c1 - c0) && (c1 - c0) <= 3);
	}
	
	// ===================================
	protected final static String [] _users = {"alice"};
	protected final static Random _rnd = new Random();
	protected final static char [] _password = "password".toCharArray();

	protected CCNHandle _defaultHandle = null;
	protected CCNHandle _userHandle = null;
	protected CreateUserData _cud = null;
	protected ContentName _prefix;
	
	
	protected void sendObjects(ContentName name, int count, long pauseMsec) throws Exception {
		for(int i = 0; i < count; i++) {
			CCNStringObject so = new CCNStringObject(name, "Hello " + i, SaveType.LOCALREPOSITORY, _userHandle);
			so.save();
			Thread.sleep(pauseMsec);
		}
	}
}
