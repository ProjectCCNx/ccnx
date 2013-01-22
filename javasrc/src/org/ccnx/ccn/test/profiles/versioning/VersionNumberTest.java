/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2011, 2012 Palo Alto Research Center, Inc.
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

import java.lang.reflect.Method;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Random;
import java.util.TreeSet;

import junit.framework.Assert;

import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.profiles.versioning.VersionNumber;
import org.ccnx.ccn.protocol.CCNTime;
import org.ccnx.ccn.protocol.ContentName;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * unit tests for VersionNumber class.
 * 
 * Not all tests implemented yet.
 */
public class VersionNumberTest {

	protected final Random _rnd = new Random();

	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		String javaVersion = System.getProperty("java.version");
		System.out.println("Java version: " + javaVersion);
		
		Class<?> c = TreeSet.class;
		Method [] methods = c.getMethods();
		for( Method m : methods ) {
			System.out.println(m.toString());
		}
	}

	@Before
	public void setUp() throws Exception {
	}

	@After
	public void tearDown() throws Exception {
	}

	@Test
	public void testHashCode()  throws Exception {

		long t = CCNTime.now().getTime();
		VersionNumber a1 = new VersionNumber(t);
		VersionNumber a2 = new VersionNumber(t);

		Assert.assertTrue(a1.hashCode() == a2.hashCode());

		for(int i = 0; i < 1000000; i++) {
			VersionNumber x = new VersionNumber(_rnd.nextInt() + t);
			Assert.assertFalse(a1.hashCode() == x.hashCode());
		}
	}

	// test that a new VersionNUmber has about the same timestamp as
	// a new CCNTime.
	@Test
	public void testVersionNumber()  throws Exception {
		VersionNumber vn = new VersionNumber();
		CCNTime t = CCNTime.now();
		Assert.assertTrue( t.getTime() - vn.getAsMillis() < 10);
	}

	@Test
	public void testVersionNumberCCNTime() {
		CCNTime t = CCNTime.now();
		VersionNumber vn = new VersionNumber(t);

		Assert.assertTrue(t.equals(vn.getAsTime()));
	}

	@Test
	public void testVersionNumberLong() throws Exception {

		for(int i = 0; i < 10000; i++) {
			CCNTime t = CCNTime.now();
			long v = t.getTime();

			VersionNumber vn = new VersionNumber(v);

			// tests on the value "v" will not always work, because CCNTime has
			// rounding errors converting to its internal binaryTime.  But it
			// shouldn't be off by much.

			Assert.assertTrue( Math.abs(v - vn.getAsMillis()) <= 1 );

			// to make sure we get different now() values
			Thread.sleep(1);
		}
	}

	@Test
	public void testVersionNumberContentName()  throws Exception  {
		ContentName name = ContentName.fromNative("/hello/world");

		for( int i = 0; i < 10000; i++ ) {
			CCNTime t = CCNTime.now();

			ContentName versionedName = new ContentName(name, t);

			VersionNumber vn = new VersionNumber(versionedName);

			Assert.assertTrue( t.equals(vn.getAsTime()) );

			// to make sure we get different now() values
			Thread.sleep(1);
		}
	}

	@Test
	public void testVersionNumberByteArray() {
		CCNTime now = CCNTime.now();
		long t = now.toBinaryTimeAsLong();

		for(int i = 0; i < 100000; i++) {
			t += _rnd.nextInt(10000) + 1;
			CCNTime then = CCNTime.fromBinaryTimeAsLong(t);

			VersionNumber x = new VersionNumber(then);

			byte [] truth = VersioningProfile.timeToVersionComponent(then);
			byte [] test  = x.getVersionBytes();

			Assert.assertTrue( Arrays.equals(truth, test));
		}
	}

	@Test
	public void testAddAndReturn() {
	}

	@Test
	public void testAddMillisecondsAndreturn() {
	}

	@Test
	public void testCompareTo() {
		int count = 100;
		long [] values = new long [count];
		CCNTime now = CCNTime.now();
		long t = now.toBinaryTimeAsLong();
		for(int i = 0; i < count; i++) {
			t += _rnd.nextInt(10000) + 1;
			values[i] = t;			
		}
		// shuffle them
		for(int i = 0; i < count-1; i++) {
			int pos = _rnd.nextInt(count - i) + i;
			long x = values[i];
			values[i] = values[pos];
			values[pos] = x;
		}

		// add to a TreeSet and make sure in right order
		TreeSet<VersionNumber> set = new TreeSet<VersionNumber>();
		for(long vv : values) {
			CCNTime tt = CCNTime.fromBinaryTimeAsLong(vv);
			VersionNumber vn = new VersionNumber(tt);
			Assert.assertTrue(set.add(vn));
		}

		Iterator<VersionNumber> iter = set.iterator();
		VersionNumber prev = null;
		while(iter.hasNext()) {
			VersionNumber current = iter.next();
			if( null != prev ) {
				Assert.assertTrue(prev.before(current));
			}
			prev = current;
		}
	}

	@Test
	public void testEqualsObject() {
		CCNTime now = CCNTime.now();
		long t = now.toBinaryTimeAsLong();
		CCNTime prev = CCNTime.fromBinaryTimeAsLong(t);
		for(int i = 0; i < 10000; i++) {
			t += _rnd.nextInt(100000) + 1;
			CCNTime next = CCNTime.fromBinaryTimeAsLong(t);
			CCNTime next2 = CCNTime.fromBinaryTimeAsLong(t);

			Assert.assertTrue(next.equals(next));
			Assert.assertTrue(next.equals(next2));
			Assert.assertTrue(next2.equals(next));
			Assert.assertFalse(next.equals(prev));
			Assert.assertFalse(prev.equals(next));

			prev = next;
		}
	}

	@Test
	public void testGetMaximumVersion() {
	}

	@Test
	public void testGetMinimumVersion() {
	}

	@Test
	public void testBeforeVersionNumber() {
	}

	@Test
	public void testAfterVersionNumber() {
	}

	@Test
	public void testBeforeCCNTime() {
	}

	@Test
	public void testAfterCCNTime() {
	}

//	@Test
//	public void testBinaryTime() throws Exception {
//		CCNTime start = new CCNTime(1294600000000L);
//
//		long current = start.toBinaryTimeAsLong();	
//
//		for(int i = 0; i < 100000000; i++) {
//			current++;
//			CCNTime t_current = CCNTime.fromBinaryTimeAsLong(current);
//			Assert.assertEquals(current, t_current.toBinaryTimeAsLong());
//
//			for(int j = 1; j <= 4; j++) {
//				long next = current + j;
//				CCNTime t_next = CCNTime.fromBinaryTimeAsLong(next);
//
//				Assert.assertEquals(next, t_next.toBinaryTimeAsLong());
//				
////				byte [] bytes_current = longToVersion(current);
////				byte [] bytes_next    = longToVersion(next);
//				
//				byte [] bytes_current = VersioningProfile.timeToVersionComponent(t_current);
//				byte [] bytes_next    = VersioningProfile.timeToVersionComponent(t_next);
//				
////				byte [] true_current = longToVersion(current);
////				byte [] true_next    = longToVersion(next);
//				
//				if( Arrays.equals(bytes_current, bytes_next)) {
//
//					String s =
//						String.format("%s (%d) == %s (%d)",
//								ContentName.componentPrintURI(bytes_current),
//								current,
//								ContentName.componentPrintURI(bytes_next),
//								next);
//					System.out.println(s);
//
//					CCNTime verify_current = VersioningProfile.getVersionComponentAsTimestamp(bytes_current);
//					CCNTime verify_next = VersioningProfile.getVersionComponentAsTimestamp(bytes_next);
//					Assert.assertFalse(verify_current.equals(verify_next));
//				}
//			}
//		}
//	}
	
	// this was to look at the behavior of CCNTime for a specific version
//	@Test
//	public void testSpecificVersion() throws Exception {
//		String uri = "/%FD%04%D2%A4S9%0E";
//		
//		ContentName component = ContentName.fromURI(uri);
//		
//		CCNTime t = VersioningProfile.getVersionComponentAsTimestamp(component.lastComponent());
//		
//		System.out.println(String.format("Componet %s (%s) msec %d binary %d", 
//				uri,
//				component.toURIString(),
//				t.getTime(),
//				t.toBinaryTimeAsLong()));
//		
//		VersionNumber vn = new VersionNumber(t);
//		System.out.println("VersionNumber " + vn.toString());
//		
//		VersionNumber minusone = vn.addAndReturn(-1);
//		System.out.println("Minusone      " + minusone.toString());
//		
//				
//	}

	protected byte [] longToVersion(long v) {
		byte [] varr = BigInteger.valueOf(v).toByteArray();
		byte [] vcomp = new byte[varr.length + 1];
		vcomp[0] = VersioningProfile.VERSION_MARKER;
		System.arraycopy(varr, 0, vcomp, 1, varr.length);
		return vcomp;
	}

}
