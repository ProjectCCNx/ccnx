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
 
package org.ccnx.ccn.test.profiles.versioning;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Random;

import org.ccnx.ccn.impl.support.TreeSet6;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class TreeSet6Test {
	
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		String javaVersion = System.getProperty("java.version");
		System.out.println("Java version: " + javaVersion);
	}
	
	@Test
	public void testFloor() throws Exception {
		TreeSet6<String> set = new TreeSet6<String>();
		
		set.add("bbb");
		set.add("aaa");
		set.add("ccc");
		
		String s;
		s = set.floorCompatible("ccc");
		System.out.println("floor of ccc is : " + s);
		Assert.assertTrue("ccc".equals(s));	
		
		s = set.floorCompatible("b");
		System.out.println("floor of b is : " + s);
		Assert.assertTrue("aaa".equals(s));	
		
	}
	
	@Test
	public void testLower() throws Exception {
		TreeSet6<String> set = new TreeSet6<String>();
		
		set.add("bbb");
		set.add("aaa");
		set.add("ccc");
		
		String s;
		s = set.lowerCompatible("ccc");
		System.out.println("lower of ccc is : " + s);
		Assert.assertTrue("bbb".equals(s));		
		s = set.lowerCompatible("b");
		System.out.println("lower of b is : " + s);
		Assert.assertTrue("aaa".equals(s));	
	}
	
	@Test
	public void testCeiling() throws Exception {
		TreeSet6<String> set = new TreeSet6<String>();
		
		set.add("bbb");
		set.add("aaa");
		set.add("ccc");
		
		String s;
		s = set.ceilingCompatible("bbb");
		System.out.println("ceiling of bbb is : " + s);
		Assert.assertTrue("bbb".equals(s));		
		s = set.ceilingCompatible("b");
		System.out.println("ceiling of b is : " + s);
		Assert.assertTrue("bbb".equals(s));	
	}
	
	@Test
	public void testHigher() throws Exception {
		TreeSet6<String> set = new TreeSet6<String>();
		
		set.add("bbb");
		set.add("aaa");
		set.add("ccc");
		
		String s;
		s = set.higherCompatible("bbb");
		System.out.println("higher of bbb is : " + s);
		Assert.assertTrue("ccc".equals(s));	

		s = set.higherCompatible("b");
		System.out.println("higher of b is : " + s);
		Assert.assertTrue("bbb".equals(s));	
	
	}
	
	@Test
	public void testInternalFloor() throws Exception {
		TestTreeSet6<String> set = new TestTreeSet6<String>();
		
		set.add("bbb");
		set.add("aaa");
		set.add("ccc");
		
		String s;
		s = set.exposeInternalFloor("ccc");
		System.out.println("internal floor of ccc is : " + s);
		Assert.assertTrue("ccc".equals(s));	
		
		s = set.exposeInternalFloor("b");
		System.out.println("internal floor of b is : " + s);
		Assert.assertTrue("aaa".equals(s));	
		
	}
	
	@Test
	public void testInternalLower() throws Exception {
		TestTreeSet6<String> set = new TestTreeSet6<String>();
		
		set.add("bbb");
		set.add("aaa");
		set.add("ccc");
		
		String s;
		s = set.exposeInternalLower("ccc");
		System.out.println("internal lower of ccc is : " + s);
		Assert.assertTrue("bbb".equals(s));		
		s = set.exposeInternalLower("b");
		System.out.println("internal lower of b is : " + s);
		Assert.assertTrue("aaa".equals(s));	
	}
	
	@Test
	public void testIntenralCeiling() throws Exception {
		TestTreeSet6<String> set = new TestTreeSet6<String>();
		
		set.add("bbb");
		set.add("aaa");
		set.add("ccc");
		
		String s;
		s = set.exposeInternalCeiling("bbb");
		System.out.println("internal ceiling of bbb is : " + s);
		Assert.assertTrue("bbb".equals(s));		
		s = set.exposeInternalCeiling("b");
		System.out.println("internal ceiling of b is : " + s);
		Assert.assertTrue("bbb".equals(s));	
	}
	
	@Test
	public void testInternalHigher() throws Exception {
		TestTreeSet6<String> set = new TestTreeSet6<String>();
		
		set.add("bbb");
		set.add("aaa");
		set.add("ccc");
		
		String s;
		s = set.exposeInternalHigher("bbb");
		System.out.println("internal higher of bbb is : " + s);
		Assert.assertTrue("ccc".equals(s));	

		s = set.exposeInternalHigher("b");
		System.out.println("internal higher of b is : " + s);
		Assert.assertTrue("bbb".equals(s));	
	}
	
	@Test
	public void testDescendingIterator() throws Exception {
		TreeSet6<Integer> set = new TreeSet6<Integer>();
		int count = 1000;
		int [] truth = new int [count];
		Random rnd = new Random();

		for(int i = 0; i < count; i++) {
			truth[i] = rnd.nextInt();
			set.add(truth[i]);
		}
		
		Arrays.sort(truth);
		
		// verify ascending iterator
		int index = 0;
		Iterator<Integer> iter = set.iterator();
		while(iter.hasNext()) {
			Integer test = iter.next();
			Assert.assertEquals(truth[index], test.intValue());
			index++;
		}
		Assert.assertEquals(count, index);
		
		// verify descending iterator
		index = count;
		iter = set.descendingIteratorCompatible();
		while(iter.hasNext()) {
			index--;
			Integer test = iter.next();
			Assert.assertEquals(truth[index], test.intValue());
		}
		Assert.assertEquals(0, index);
	}
	
	@Test
	public void testDescendingIteratorRemoveFirst() throws Exception {
		TreeSet6<Integer> set = new TreeSet6<Integer>();
		int count = 1000;
		int [] truth = new int [count];
		Random rnd = new Random();

		for(int i = 0; i < count; i++) {
			truth[i] = rnd.nextInt();
			set.add(truth[i]);
		}
		
		Arrays.sort(truth);
		// verify descending iterator
		int index = count;
		Iterator<Integer> iter = set.descendingIteratorCompatible();
		while(iter.hasNext()) {
			index--;
			Integer test = iter.next();
			Assert.assertEquals(truth[index], test.intValue());
			if( index == count - 1 )
				iter.remove();
		}
		Assert.assertEquals(0, index);
		
		// do it again and verify missing.  Start at top -1 and
		// got down to 0
		index = count - 1;
		iter = set.descendingIteratorCompatible();
		while(iter.hasNext()) {
			index--;
			Integer test = iter.next();
			Assert.assertEquals(truth[index], test.intValue());
		}
		Assert.assertEquals(0, index);
	}
	
	@Test
	public void testDescendingIteratorRemoveLast() throws Exception {
		TreeSet6<Integer> set = new TreeSet6<Integer>();
		int count = 1000;
		int [] truth = new int [count];
		Random rnd = new Random();

		for(int i = 0; i < count; i++) {
			truth[i] = rnd.nextInt();
			set.add(truth[i]);
		}
		
		Arrays.sort(truth);
		// verify descending iterator
		int index = count;
		Iterator<Integer> iter = set.descendingIteratorCompatible();
		while(iter.hasNext()) {
			index--;
			Integer test = iter.next();
			Assert.assertEquals(truth[index], test.intValue());
			if( index == 0 )
				iter.remove();
		}
		Assert.assertEquals(0, index);
		
		// do it again and verify missing.  Start at top and
		// should go down to index 1
		index = count;
		iter = set.descendingIteratorCompatible();
		while(iter.hasNext()) {
			index--;
			Integer test = iter.next();
			Assert.assertEquals(truth[index], test.intValue());
		}
		Assert.assertEquals(1, index);

	}

	@Test
	public void testDescendingIteratorRemoveRandom() throws Exception {
		TreeSet6<Integer> set = new TreeSet6<Integer>();
		int count = 1000;
		int [] truth = new int [count];
		Random rnd = new Random();
		ArrayList<Integer> afterremove = new ArrayList<Integer>();
		

		for(int i = 0; i < count; i++) {
			truth[i] = rnd.nextInt();
			set.add(truth[i]);
		}
		
		Arrays.sort(truth);
		// verify descending iterator
		int index = count;
		Iterator<Integer> iter = set.descendingIteratorCompatible();
		while(iter.hasNext()) {
			index--;
			Integer test = iter.next();
			Assert.assertEquals(truth[index], test.intValue());

			if( rnd.nextDouble() < 0.5 )
				iter.remove();
			else
				afterremove.add(test);
		}
		Assert.assertEquals(0, index);
		
		// afterremove is in descending order already, so
		// walk up the indexes from 0.
		index = 0;
		iter = set.descendingIteratorCompatible();
		while(iter.hasNext()) {
			Integer test = iter.next();
			Assert.assertEquals(afterremove.get(index).intValue(), test.intValue());
			index++;
		}
		Assert.assertEquals(afterremove.size(), index);

	}
	
	// =========================
	private static class TestTreeSet6<E> extends TreeSet6<E> {
		private static final long serialVersionUID = -1063824568410275527L;
		
		public E exposeInternalFloor(E key) {
			return internalFloor(key);
		}
		public E exposeInternalCeiling(E key) {
			return internalCeiling(key);
		}
		public E exposeInternalHigher(E key) {
			return internalHigher(key);
		}
		public E exposeInternalLower(E key) {
			return internalLower(key);
		}
	}

}
