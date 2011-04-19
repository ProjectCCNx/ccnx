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

package org.ccnx.ccn.test;

import java.util.Random;

import org.junit.Assert;
import org.junit.Test;

/**
 * Test cases for ThreadAssertionRunner.  Also shows how to use it.
 * 
 * ThreadAssertionRunner will capture any Exception or Error.  We just use NullPointer and
 * Runtime as two examples here.
 */
public class ThreadAssertionTest {
	
	@Test
	public void testNoError() throws Exception {
		ThreadAssertionRunner tar = new ThreadAssertionRunner(new InnerRunner(TestType.NoError));
		tar.start();
		tar.join();
	}
	
	@Test(expected=AssertionError.class)
	public void testAssertFail() throws Exception {
		ThreadAssertionRunner tar = new ThreadAssertionRunner(new InnerRunner(TestType.AssertFail));
		tar.start();
		tar.join();
	}
	
	@Test(expected=AssertionError.class)
	public void testAssertError() throws Exception {
		ThreadAssertionRunner tar = new ThreadAssertionRunner(new InnerRunner(TestType.AssertError));
		tar.start();
		tar.join();
	}
	
	@Test(expected=NullPointerException.class)
	public void testNullPointer() throws Exception {
		ThreadAssertionRunner tar = new ThreadAssertionRunner(new InnerRunner(TestType.NullPointer));
		tar.start();
		tar.join();
	}
	
	@Test(expected=RuntimeException.class)
	public void testRuntimeException() throws Exception {
		ThreadAssertionRunner tar = new ThreadAssertionRunner(new InnerRunner(TestType.RuntimeException));
		tar.start();
		tar.join();
	}
	
	// ===========================================
	private enum TestType {
		AssertFail,
		AssertError,
		NullPointer,
		RuntimeException,
		NoError
	}
	
	private static class InnerRunner implements Runnable {
		InnerRunner(TestType type) {
			_type = type;
		}
		
		@Override
		public void run() {
			// Add a little random delay to pretend to do something
			Random rnd = new Random();
			try {
				Thread.sleep(rnd.nextInt(200) + 1);
			} catch (InterruptedException e) {}
			
			switch(_type) {
			case AssertFail:
				Assert.fail("assertion failure");
				break;
			case AssertError:
				Assert.assertTrue("assertion error", false);
				break;
			case NullPointer:
				throw new NullPointerException("null pointer exception");
			case RuntimeException:
				throw new RuntimeException("runtime exception");
			case NoError:
				break;
			}	
		}
		
		private final TestType _type;
	}

}
