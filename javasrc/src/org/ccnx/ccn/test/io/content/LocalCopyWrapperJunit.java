/*
 * A CCNx library test.
 *
 * Copyright (C) 2011 Palo Alto Research Center, Inc.
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

package org.ccnx.ccn.test.io.content;

import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.impl.CCNFlowControl.SaveType;
import org.ccnx.ccn.impl.security.keys.BasicKeyManager;
import org.ccnx.ccn.io.content.CCNStringObject;
import org.ccnx.ccn.io.content.LocalCopyWrapper;
import org.ccnx.ccn.protocol.ContentName;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Test for deadlocks in LocalCopyWrapper.
 * 
 * This test does not end in "TestRepo" or "Test" so it will not be
 * run by the normal Ant "test-io" target.
 */
public class LocalCopyWrapperJunit {
	
	private CCNHandle readhandle;
	private CCNHandle writehandle;
	private BasicKeyManager bkm;
	private Random _rnd = new Random();
	
	@Before
	public void setUp() throws Exception {
		bkm = new BasicKeyManager();
		bkm.initialize();
		readhandle = CCNHandle.open(bkm);
		writehandle = CCNHandle.open(bkm);
	}
	
	@After
	public void tearDown() throws Exception {
		readhandle.close();
		writehandle.close();
		bkm.close();
	}
	
	@Test
	public void testLCW() throws Exception {
		final String prefix = String.format("/test_%016X", _rnd.nextLong());
		final ContentName soname = ContentName.fromNative(prefix + "/obj");
		final CCNStringObject so1 = new CCNStringObject(soname, "Hello", SaveType.LOCALREPOSITORY, writehandle);
		final LocalCopyWrapper lcw = new LocalCopyWrapper(so1);
		final CountDownLatch latch = new CountDownLatch(1);
		
		
		Runnable updater = new Runnable() {

			public void run() {
				for(int i = 0; i < 10000; i++) {
					try {
						Thread.sleep(_rnd.nextInt(20));
						String x = Integer.toString(i);
						so1.setData(x);
						lcw.save();
					} catch(Exception e) {
						e.printStackTrace();
					}
						
					output(".");
				}
				System.out.println("\nupdater exits");
				latch.countDown();
			}
		};
		
		Thread thd = new Thread(updater);
		thd.start();
		
		while( latch.getCount() > 0 ) {	
			Thread.sleep(_rnd.nextInt(20));
			output("-");
			lcw.isSaved();
		}
		
		latch.await();
		System.out.println("Finished");
	}

	protected AtomicInteger outcount = new AtomicInteger(0);
	protected void output(String s) {
		int count = outcount.incrementAndGet();
		if( count % 10 == 0 ) {
			if( count % 800 == 0 )
				System.out.println();
			System.out.print(s);
		}
	}

}
