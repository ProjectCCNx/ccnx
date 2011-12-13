/*
 * A CCNx library test.
 *
 * Copyright (C) 2008-2011 Palo Alto Research Center, Inc.
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

package org.ccnx.ccn.test;

import org.ccnx.ccn.impl.support.Log;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Part of older test infrastructure. Simple object-base read/write test.
 */
public class BasePutGetTest extends LibraryTestBase {
	
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		LibraryTestBase.setUpBeforeClass();
	}
	
	@Test
	public void testGetPut() throws Throwable {
		Log.info(Log.FAC_TEST, "Starting testGetPut");
		int id = getUniqueId();
		Thread putter = new Thread(new PutThread(count, id));
		Thread getter = new Thread(new GetThread(count, id));
		genericGetPut(putter, getter);
		Log.info(Log.FAC_TEST, "Completed testGetPut");
	}
	
	@Test
	public void testGetServPut() throws Throwable {
		Log.info(Log.FAC_TEST, "Starting testGetServPut");
		int id = getUniqueId();

		//Library.setLevel(Level.FINEST);
		Thread putter = new Thread(new PutThread(count, id));
		Thread getter = new Thread(new GetServer(count, id));
		genericGetPut(putter, getter);
		Log.info(Log.FAC_TEST, "Completed testGetServPut");
	}

	@Test
	public void testGetPutServ() throws Throwable {
		Log.info(Log.FAC_TEST, "Starting testGetPutServ");
		int id = getUniqueId();
		Thread putter = new Thread(new PutServer(count, id));
		Thread getter = new Thread(new GetThread(count, id));
		genericGetPut(putter, getter);
		Log.info(Log.FAC_TEST, "Completed testGetPutServ");
	}
}
