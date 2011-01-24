package org.ccnx.ccn.test.profiles.versioning;

import org.ccnx.ccn.profiles.versioning.TreeSet6;
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
		s = set.xfloor("ccc");
		System.out.println("floor of ccc is : " + s);
		Assert.assertTrue("ccc".equals(s));	
		
		s = set.xfloor("b");
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
		s = set.xlower("ccc");
		System.out.println("lower of ccc is : " + s);
		Assert.assertTrue("bbb".equals(s));		
		s = set.xlower("b");
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
		s = set.xceiling("bbb");
		System.out.println("ceiling of bbb is : " + s);
		Assert.assertTrue("bbb".equals(s));		
		s = set.xceiling("b");
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
		s = set.xhigher("bbb");
		System.out.println("higher of bbb is : " + s);
		Assert.assertTrue("ccc".equals(s));	

		s = set.xhigher("b");
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
	
	// =========================
	private static class TestTreeSet6<E> extends TreeSet6<E> {
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
