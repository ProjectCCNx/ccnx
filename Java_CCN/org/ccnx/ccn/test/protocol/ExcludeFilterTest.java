package org.ccnx.ccn.test.protocol;

import static org.junit.Assert.*;

import java.util.ArrayList;

import javax.xml.stream.XMLStreamException;

import org.ccnx.ccn.protocol.BloomFilter;
import org.ccnx.ccn.protocol.ExcludeAny;
import org.ccnx.ccn.protocol.ExcludeComponent;
import org.ccnx.ccn.protocol.ExcludeFilter;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;


public class ExcludeFilterTest {

	static ArrayList<ExcludeFilter.Element> al;
	static final byte [] b0 = "0".getBytes();
	static final byte [] b1 = "1".getBytes();
	static final byte [] b3 = "3".getBytes();
	static final byte [] b06 = "06".getBytes();
	static final byte [] b000 = "000".getBytes();
	static ExcludeComponent c1 = new ExcludeComponent(b1);
	static ExcludeComponent c3 = new ExcludeComponent(b3);
	static ExcludeComponent c06 = new ExcludeComponent(b06);
	static ExcludeAny any = new ExcludeAny();
	static BloomFilter bloom;
	static ExcludeFilter ef;
	static byte [][] array = { b1, b06 };
	
	private static byte [] bloomSeed = "test".getBytes();
	
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		al = new ArrayList<ExcludeFilter.Element>();
		bloom = new BloomFilter(13, bloomSeed);
		bloom.insert(b3);
	}

	@AfterClass
	public static void tearDownAfterClass() throws Exception {
	}
	
	@Before
	public void setUpBefore() {
		al.clear();
	}

	@Test (expected=IllegalArgumentException.class)
	public void testExcludeFilterArrayListOfElementFails() {
		al.add(c06);
		al.add(c1);
		new ExcludeFilter(al);
	}

	@Test (expected=IllegalArgumentException.class)
	public void testExcludeFilterArrayListOfElementFails2() {
		al.add(c1);
		al.add(c06);
		al.add(bloom);
		al.add(any);
		new ExcludeFilter(al);
	}

	@Test (expected=IllegalArgumentException.class)
	public void testExcludeFilterArrayListOfElementFails3() {
		al.add(c1);
		al.add(any);
		al.add(bloom);
		al.add(c06);
		new ExcludeFilter(al);
	}

	@Test
	public void testExcludeFilterArrayListOfElement() {
		al.add(c1);
		al.add(any);
		al.add(c3);
		al.add(c06);
		al.add(bloom);
		new ExcludeFilter(al);
	}

	@Test
	public void testExcludeFilterByteArrayArray() {
		ef = new ExcludeFilter(array);
		assertTrue(ef.match(b1));
		assertFalse(ef.match(b3));
		assertTrue(ef.match(b06));
	}

	@Test
	public void testUptoFactory() {
		ef = ExcludeFilter.uptoFactory(b3);
		assertTrue(ef.match(b1));
		assertTrue(ef.match(b3));
		assertFalse(ef.match(b06));
	}

	@Test
	public void testFactory() {
		ef = ExcludeFilter.factory(null);
		assertNull(ef);
		ef = ExcludeFilter.factory(array);
		assertTrue(ef.match(b1));
		assertFalse(ef.match(b3));
		assertTrue(ef.match(b06));
	}

	@Test
	public void testMatchBloom() {
		al.add(c1);
		al.add(bloom);
		al.add(c06);
		ef = new ExcludeFilter(al);
		assertFalse(ef.match(b0));
		assertTrue(ef.match(b3));
		assertTrue(ef.match(b06));
		assertFalse(ef.match(b000));
	}

	@Test
	public void testAdd() {
		ef = ExcludeFilter.uptoFactory(b0);
		assertFalse(ef.match(b1));
		ef.add(array);
		assertTrue(ef.match(b0));
		assertTrue(ef.match(b1));
		assertTrue(ef.match(b06));
	}

	@Test
	public void testEmpty() {
		ef = ExcludeFilter.uptoFactory(b0);
		assertFalse(ef.empty());
		ArrayList<ExcludeFilter.Element> empty = new ArrayList<ExcludeFilter.Element>();
		ef = new ExcludeFilter(empty);
		assertTrue(ef.empty());
	}

	//@Test
	//public void testCompareTo() {
	//	fail("Not yet implemented"); // TODO
	//}

	@Test
	public void testEqualsObject() {
		ef = ExcludeFilter.uptoFactory(b1);
		ArrayList<ExcludeFilter.Element> a = new ArrayList<ExcludeFilter.Element>();
		a.add(any);
		a.add(c1);
		assertEquals(ef, new ExcludeFilter(a));
	}

	@Test
	public void testSize() {
		ef = ExcludeFilter.uptoFactory(b1);
		assertTrue(ef.size() == 2);
	}

	@Test
	public void testEncodeDecode() throws XMLStreamException {
		al.add(c1);
		al.add(any);
		al.add(c3);
		al.add(c06);
		al.add(bloom);
		ef = new ExcludeFilter(al);
		ExcludeFilter ef2 = new ExcludeFilter();
		ef2.decode(ef.encode());
		assertTrue(ef.equals(ef2));
	}
}
