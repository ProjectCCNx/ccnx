/**
 * A CCNx library test.
 *
 * Copyright (C) 2008, 2009, 2011, 2012 Palo Alto Research Center, Inc.
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

package org.ccnx.ccn.test.protocol;

import static org.ccnx.ccn.protocol.ContentName.ROOT;
import static org.ccnx.ccn.protocol.ContentName.SEPARATOR;
import static org.ccnx.ccn.protocol.ContentName.fromNative;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URISyntaxException;
import java.util.Arrays;

import org.ccnx.ccn.impl.support.DataUtils;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.ContentDecodingException;
import org.ccnx.ccn.io.content.ContentEncodingException;
import org.ccnx.ccn.profiles.versioning.VersionNumber;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;
import org.ccnx.ccn.test.impl.encoding.XMLEncodableTester;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;


/**
 * Test basic ContentName operation.
 */
public class ContentNameTest {

	public String baseName = "test";
	public String subName1 = "briggs";
	public String subName2 = "smetters";
	public String document1 = "test.txt";
	public String document2 = "test2.txt";
	public byte [] document3 = new byte[]{0x01, 0x02, 0x03, 0x04,
				0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c,
				0x0d, 0x0e, 0x0f, 0x1f, 0x1b, 0x1c, 0x1d, 0x1e,
				0x1f, 0x2e, 0x3c, 0x4a, 0x5c, 0x6d, 0x7e, 0xf};
	// invalid: try byte sequences that should be invalid values in platform
	// character set.  Note java.lang.String is documented as having
	// unspecified behavior when given bytes that are not valid in the
	// default charset.  These values are chosen as invalid for UTF-8.
	// Java String encoding from UTF-8 replaces these invalid characters
	// with the Unicode "Replacement Character" U+FFFD so a simple
	// trip through String() to URI encoding and back will not be lossless!
	// Note that there is nothing invalid about these sequences in CCN names
	// since CCN names can include any values whatsoever, which is why we need
	// to test round-trip through URI encoding.
	public byte [] invalid = new byte[]{0x01, 0x00, 0x00, // valid  but with 0's
				(byte) 0x80, (byte) 0xbc, // can't be first byte
				(byte) 0xc0, (byte) 0x8a, // overlong encoding
				(byte) 0xf5, (byte) 0xf9, (byte) 0xfc, // RFC3629 restricted
			    (byte) 0xfe, (byte) 0xff}; // invalid: not defined
	public byte [][] invalids = new byte[][]{ {0x01, 0x00, 0x00}, // valid  but with 0's
			{(byte) 0x80, (byte) 0xbc}, // can't be first byte
			{(byte) 0xc0, (byte) 0x8a}, // overlong encoding
			{(byte) 0xf5, (byte) 0xf9, (byte) 0xfc}, // RFC3629 restricted
		    {(byte) 0xfe, (byte) 0xff}, // invalid: not defined
			{(byte) 0xe0, (byte) 0x8e, (byte) 0xb7},
			};
	public String escapedSubName1 = "%62%72%69%67%67%73";
	public String withScheme = "ccnx:/test/briggs/test.txt";
	public String dotSlash = "ccnx:/.../.%2e./...././.....///?...";
	public String dotSlashResolved = "ccnx:/.../.../..../.....";
	public String withQuery = "/abc/def/q?foo=bar";
	public String withFragment = "/abc/def/ghi#rst";
	public String withQueryAndFragment = "/abc/def/qr?st=bat#notch";
	public String veryEscapedName = "ccnx:/test/%C1.%77%00A%8C%B4B%8D%0A%AC%8E%14%8C%07%88%E4%E2%3Dn/%23%00%19/%C1.%76%00t%DF%F63/%FE%23/%C1.M.K%00%1E%90%EAh%E9%FB%AE%A3%9E%17F%20%CF%AB%A0%29%E9%DE%FAZ%DCA%FBZ%F5%DD%F5A%D2%D7%9F%D1/%FD%04%CB%F5qR%7B/%00";

	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
	}

	@AfterClass
	public static void tearDownAfterClass() throws Exception {
	}

	@Before
	public void setUp() throws Exception {
	}

	@After
	public void tearDown() throws Exception {
	}

	@Test
	public void testContentNameString() {
		Log.info(Log.FAC_TEST, "Starting testContentNameString");

		ContentName name;

		//----------------------------- Simple strings: identical as URI-encoded and native Java

		// simple string: /test/briggs/test.txt is legal as both URI-encoded and native Java
		String testString = ContentName.SEPARATOR + baseName + ContentName.SEPARATOR +
				subName1 + ContentName.SEPARATOR +
				document1;

		System.out.println("ContentName: parsing name string \"" + testString+"\"");
		// test URI-encoded (the canonical interpretation)
		try {
			name = ContentName.fromURI(testString);
		} catch (MalformedContentNameStringException e) {
			Log.warning(Log.FAC_TEST, "Exception " + e.getClass().getName() + ", message: " + e.getMessage());
			Log.warningStackTrace(Log.FAC_TEST, e);
			name = null;
		}
		assertNotNull(name);
		System.out.println("Name: " + name);
		assertEquals(name.toString(), testString);

		// test as native Java String
		try {
			name = ContentName.fromNative(testString);
		} catch (MalformedContentNameStringException e) {
			Log.warning(Log.FAC_TEST, "Exception on native " + e.getClass().getName() + ", message: " + e.getMessage());
			Log.warningStackTrace(Log.FAC_TEST, e);
			name = null;
		}
		assertNotNull(name);
		System.out.println("Name (native): " + name);
		assertEquals(name.toString(), testString);

		// alternate simple string: / only, also legal as both URI-encoded and native Java
		String testString2 = ContentName.SEPARATOR;
		ContentName name2 = null;

		// test as URI-encoded (canonical)
		System.out.println("ContentName: parsing name string \"" + testString2+"\"");
		try {
			name2 = ContentName.fromURI(testString2);
		} catch (MalformedContentNameStringException e) {
			Log.warning(Log.FAC_TEST, "Exception " + e.getClass().getName() + ", message: " + e.getMessage());
			Log.warningStackTrace(Log.FAC_TEST, e);
			name2 = null;
		}
		assertNotNull(name2);
		System.out.println("Name: " + name2);
		assertEquals(name2.toString(), testString2);

		// test as native Java String
		System.out.println("ContentName: parsing name string \"" + testString2+"\"");
		try {
			name2 = ContentName.fromNative(testString2);
		} catch (MalformedContentNameStringException e) {
			Log.warning(Log.FAC_TEST, "Exception " + e.getClass().getName() + ", message: " + e.getMessage());
			Log.warningStackTrace(Log.FAC_TEST, e);
			name2 = null;
		}
		assertNotNull(name2);
		System.out.println("Name: " + name2);
		assertEquals(name2.toString(), testString2);


		//----------------------------- tests specific to URI-encoded cases

		// string with ccnx: scheme on front
		ContentName name3 = null;
		System.out.println("ContentName: parsing name string \"" + withScheme +"\"");
		try {
			name3 = ContentName.fromURI(withScheme);
		} catch (MalformedContentNameStringException e) {
			Log.warning(Log.FAC_TEST, "Exception " + e.getClass().getName() + ", message: " + e.getMessage());
			Log.warningStackTrace(Log.FAC_TEST, e);
			name3 = null;
		}
		assertNotNull(name3);
		System.out.println("Name: " + name3);
		assertEquals(name3.toString(), withScheme.substring(withScheme.indexOf(":") + 1));

		ContentName input3 = null;
		try {
			input3 = ContentName.fromURI(name3.toString());
		} catch (MalformedContentNameStringException e) {
			Log.warning(Log.FAC_TEST, "Exception " + e.getClass().getName() + ", message: " + e.getMessage());
			Log.warningStackTrace(Log.FAC_TEST, e);
			input3 = null;
		}
		assertEquals(input3,name3);

		// string with dots and slashes
		ContentName name4 = null;
		System.out.println("ContentName: parsing name string \"" + dotSlash +"\"");
		try {
			name4 = ContentName.fromURI(dotSlash);
		} catch (MalformedContentNameStringException e) {
			Log.warning(Log.FAC_TEST, "Exception " + e.getClass().getName() + ", message: " + e.getMessage());
			Log.warningStackTrace(Log.FAC_TEST, e);
			name4 = null;
		}
		assertNotNull(name4);
		System.out.println("Name: " + name4);
		assertEquals(name4.toString(), dotSlashResolved.substring(dotSlashResolved.indexOf(":") + 1));

		// empty name
		System.out.println("ContentName: testing empty name round trip: /");
		ContentName name5 = null;
		try {
			name5 = ContentName.fromURI("/");
		} catch (MalformedContentNameStringException e) {
			Log.warning(Log.FAC_TEST, "Exception " + e.getClass().getName() + ", message: " + e.getMessage());
			Log.warningStackTrace(Log.FAC_TEST, e);
			name5 = null;
		}
		assertNotNull(name5);
		assertEquals(name5.count(), 0);
		assertEquals(name5.toString(), "/");

		// empty name with scheme
		System.out.println("ContentName: testing empty name round trip: /");
		ContentName name6= null;
		try {
			name6 = ContentName.fromURI("ccnx:/");
		} catch (MalformedContentNameStringException e) {
			Log.warning(Log.FAC_TEST, "Exception " + e.getClass().getName() + ", message: " + e.getMessage());
			Log.warningStackTrace(Log.FAC_TEST, e);
			name6 = null;
		}
		assertNotNull(name6);
		assertEquals(name6.count(), 0);
		assertEquals(name6.toString(), "/");

		// query and fragment parts
		try {
			assertEquals(ContentName.fromURI(withQuery).toString(), withQuery.split("\\?")[0]);
			assertEquals(ContentName.fromURI(withFragment).toString(), withFragment.split("\\#")[0]);
			assertEquals(ContentName.fromURI(withQueryAndFragment).toString(), withQueryAndFragment.split("\\?")[0]);
		} catch (MalformedContentNameStringException e) {
			Log.warning(Log.FAC_TEST, "Exception " + e.getClass().getName() + ", message: " + e.getMessage());
			Log.warningStackTrace(Log.FAC_TEST, e);
			fail(e.getMessage());
		}

		Log.info(Log.FAC_TEST, "Completed testContentNameString");
	}

	public void parseWithException(String input) {
		System.out.println("ContentName: parsing illegal name string \"" + input+"\"");
		try {
			ContentName.fromURI(input);
			fail("Illegal string parsed without error");
		} catch (MalformedContentNameStringException ex) {
			// expected
			System.out.println("Exception expected msg: " + ex.getMessage());
		}
	}

	@Test
	public void testContentNameStringException() throws MalformedContentNameStringException {
		Log.info(Log.FAC_TEST, "Starting testContentNameStringException");

		// Require an absolute URI
		parseWithException("expectingAnException");
		parseWithException("ccnx:a/relative/name");
		parseWithException("relative/no/scheme");
		// Not too many .. components
		parseWithException("ccnx:/a/b/c/../../../..");
		// Broken percent encodings
		parseWithException("/a/short/percent/%e");
		parseWithException("/a/bogus/%AQE/hex/value");
		parseWithException("/try/negative/%-A3/value");

		Log.info(Log.FAC_TEST, "Completed testContentNameStringException");
	}

	@Test
	@SuppressWarnings( "deprecation" ) // this method tests a deprecated method
	public void testContentNameStringArray() throws MalformedContentNameStringException {
		Log.info(Log.FAC_TEST, "Starting testContentNameStringArray");

		ContentName name;
		ContentName name2;
		String testString = SEPARATOR + baseName + SEPARATOR + subName1 + SEPARATOR + document1;
		String [] testStringParts = new String[]{baseName,subName1,document1};

		// as URI format
		name = ContentName.fromURI(testStringParts);
		name2 = ContentName.fromURI(testString);
		assertEquals(name, name2);

		// as native Java
		name = new ContentName(baseName,subName1,document1);
		name2 = ContentName.fromNative(testString);
		assertEquals(name, name2);

		Log.info(Log.FAC_TEST, "Completed testContentNameStringArray");
	}

	@Test
	public void testEncoding() {
		Log.info(Log.FAC_TEST, "Starting testEncoding");

		String name1 = ContentName.SEPARATOR + subName1;
		String name2 = ContentName.SEPARATOR + escapedSubName1;
		System.out.println("ContentName: comparing parsed \"" + name1 + "\" and \"" + name2 + "\"");
		try {
			assertEquals(ContentName.fromURI(name1), ContentName.fromURI(name2));
		} catch (MalformedContentNameStringException e) {
			fail("Unexpected exception MalformedContentNameStringException during ContentName parsing");
		}

		Log.info(Log.FAC_TEST, "Completed testEncoding");
	}

	@Test
	public void testContentNameByteArrayArray() throws MalformedContentNameStringException {
		Log.info(Log.FAC_TEST, "Starting testContentNameByteArrayArray");

		byte [][] arr = new byte[4][];
		arr[0] = baseName.getBytes();
		arr[1] = subName1.getBytes();
		arr[2] = document1.getBytes();
		System.out.println("Creating name from byte arrays.");
		ContentName name = new ContentName(arr[0], arr[1], arr[2]);
		assertNotNull(name);
		System.out.println("Name: " + name);
		assertEquals(name, ContentName.fromURI(ContentName.SEPARATOR + baseName + ContentName.SEPARATOR +
				subName1 + ContentName.SEPARATOR + document1));

		arr[3] = document3;
		ContentName name2 = new ContentName(arr);
		assertNotNull(name2);
		System.out.println("Name 2: " + name2);
		assertEquals(name2.count(), 4);
		assert(DataUtils.arrayEquals(name2.component(0), arr[0]));
		assert(DataUtils.arrayEquals(name2.component(1), arr[1]));
		assert(DataUtils.arrayEquals(name2.component(2), arr[2]));
		assert(DataUtils.arrayEquals(name2.component(3), arr[3]));

		Log.info(Log.FAC_TEST, "Completed testContentNameByteArrayArray");
	}

	@Test
	@SuppressWarnings( "deprecation" ) // this method tests a deprecated method
	public void testMultilevelString() throws MalformedContentNameStringException {
		Log.info(Log.FAC_TEST, "Starting testMultilevelString");

		// Test multilevel construction with Strings,
		// i.e. methods that take a parent and then additional components
		ContentName parent = ContentName.fromURI(ContentName.SEPARATOR + baseName + ContentName.SEPARATOR + subName1);
		assertNotNull(parent);
		assertEquals(parent.count(), 2);
		String childExtension = document1 + ContentName.SEPARATOR + document2;
		String[] childComps = new String[]{document1, document2};
		ContentName child = null;

		// URI case
		// Note: fromURI takes only one additional component, ignores rest
		child = ContentName.fromURI(parent, childExtension);
		System.out.println("Child is (URI): " + child);
		assertNotNull(child);
		assertEquals(child.count(), 3); // lose the last component
		assertTrue(parent.isPrefixOf(child));
		assertEquals(child.cut(document1.getBytes()), parent);
		assertTrue(DataUtils.arrayEquals(child.component(2), document1.getBytes()));

		child = new ContentName(parent, document1);
		System.out.println("Child is (native, one additional comp): " + child);
		assertNotNull(child);
		assertTrue(parent.isPrefixOf(child));
		assertEquals(child.count(), 3);
		assertEquals(child.cut(document1.getBytes()), parent);
		assertTrue(DataUtils.arrayEquals(child.component(2), document1.getBytes()));

		child = new ContentName(parent, childComps[0], childComps[1]);
		System.out.println("Child is (native): " + child);
		assertNotNull(child);
		ContentName child2 = new ContentName(parent, document1, document2);
		System.out.println("Child2 is (native): " + child2);
		assertNotNull(child2);
		assertEquals(child, child2);
		assertEquals(child2, child);
		assertTrue(parent.isPrefixOf(child));
		assertEquals(child.count(), 4);
		assertEquals(child.cut(document1.getBytes()), parent);
		assertTrue(DataUtils.arrayEquals(child.component(2), document1.getBytes()));
		assertTrue(DataUtils.arrayEquals(child.component(3), document2.getBytes()));

		Log.info(Log.FAC_TEST, "Completed testMultilevelString");
	}

	@Test
	public void testInvalidContentNameByteArrayArray() throws MalformedContentNameStringException {
		Log.info(Log.FAC_TEST, "Starting testInvalidContentNameByteArrayArray");

		byte [][] arr = new byte[4][];
		// First valid prefix
		arr[0] = baseName.getBytes();
		arr[1] = subName1.getBytes();
		arr[2] = document1.getBytes();
		System.out.println("Creating name from byte arrays with invalid values.");
		ContentName name = new ContentName(arr[0], arr[1], arr[2]);
		assertNotNull(name);
		System.out.println("Name: " + name);
		// Now add invalid component and test round-trip
		arr[3] = invalid;
		ContentName name2 = new ContentName(arr);
		assertNotNull(name2);
		System.out.println("Name 2: " + name2);
		ContentName input = ContentName.fromURI(name2.toString());
		assertEquals(input,name2);

		// Now test individual invalid cases
		for (int i = 0; i < invalids.length; i++) {
			arr[3] = invalids[i];
			ContentName name3 = new ContentName(arr);
			assertNotNull(name3);
			System.out.println("Name with invalid component " + i + ": " + name3);
			input = ContentName.fromURI(name3.toString());
			assertEquals(input,name3);
		}

		Log.info(Log.FAC_TEST, "Completed testInvalidContentNameByteArrayArray");
	}

	@Test
	public void testParent() {
		Log.info(Log.FAC_TEST, "Starting testParent");

		byte [][] arr = new byte[4][];
		arr[0] = baseName.getBytes();
		arr[1] = subName1.getBytes();
		arr[2] = document1.getBytes();
		arr[3] = document3;
		ContentName name = new ContentName(arr);
		System.out.println("Name: " + name);
		assertNotNull(name);
		ContentName parent = name.parent();
		assertNotNull(parent);
		System.out.println("Parent: " + parent);
		ContentName grandparent = parent.parent();
		assertNotNull(grandparent);
		System.out.println("Grandparent: " + grandparent);

		Log.info(Log.FAC_TEST, "Completed testParent");
	}

	@Test
	public void testEncodeOutputStream() {
		Log.info(Log.FAC_TEST, "Starting testEncodeOutputStream");

		byte [][] arr = new byte[4][];
		arr[0] = baseName.getBytes();
		arr[1] = subName1.getBytes();
		arr[2] = document1.getBytes();
		arr[3] = document3;
		ContentName name = new ContentName(arr);
		System.out.println("Encoding name: " + name);
		assertNotNull(name);

		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		try {
			name.encode(baos);
		} catch (ContentEncodingException e) {
			Log.warning(Log.FAC_TEST, "Exception " + e.getClass().getName() + ", message: " + e.getMessage());
			Log.warningStackTrace(Log.FAC_TEST, e);
		}
		System.out.println("Encoded name: " );
		System.out.println(baos.toString());

		Log.info(Log.FAC_TEST, "Completed testEncodeOutputStream");
	}

	@Test
	public void testDecodeInputStream() throws ContentDecodingException {
		Log.info(Log.FAC_TEST, "Starting testDecodeInputStream");

		byte [][] arr = new byte[4][];
		arr[0] = baseName.getBytes();
		arr[1] = subName1.getBytes();
		arr[2] = document1.getBytes();
		arr[3] = document3;
		ContentName name = new ContentName(arr);
		System.out.println("Encoding name: " + name);
		assertNotNull(name);

		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		try {
			name.encode(baos);
		} catch (ContentEncodingException e) {
			Log.warning("Exception " + e.getClass().getName() + ", message: " + e.getMessage());
			Log.warningStackTrace(Log.FAC_TEST, e);
		}
		System.out.println("Encoded name: " );
		System.out.println(baos.toString());

		System.out.println("Decoding name: ");
		ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
		ContentName name2 = new ContentName();
		name2.decode(bais);
		System.out.println("Decoded name: " + name2);
		assertEquals(name, name2);

		Log.info(Log.FAC_TEST, "Completed testDecodeInputStream");
	}

	@Test
	public void testEncodingDecoding() {
		Log.info(Log.FAC_TEST, "Starting testEncodingDecoding");

		byte [][] arr = new byte[4][];
		arr[0] = baseName.getBytes();
		arr[1] = subName1.getBytes();
		arr[2] = document1.getBytes();
		arr[3] = document3;
		ContentName name = new ContentName(arr);
		System.out.println("Encoding name: " + name);
		assertNotNull(name);

		ContentName tn = new ContentName();
		ContentName bn = new ContentName();

		XMLEncodableTester.encodeDecodeTest("ContentName", name, tn, bn);

		Log.info(Log.FAC_TEST, "Completed testEncodingDecoding");
	}

	/**
	 * Test relations like isPrefixOf()
	 * @throws MalformedContentNameStringException
	 */
	@Test
	public void testRelations() throws MalformedContentNameStringException {
		Log.info(Log.FAC_TEST, "Starting testRelations");

		ContentName small = ContentName.fromURI(ContentName.SEPARATOR + baseName + ContentName.SEPARATOR + subName1);
		ContentName small2 = ContentName.fromURI(ContentName.SEPARATOR + baseName + ContentName.SEPARATOR + subName1);
		ContentName middle = new ContentName(small, subName2);
		ContentName large = new ContentName(middle, document1);

		assertEquals(small, small);
		assertEquals(middle, middle);
		assertEquals(large, large);

		assertTrue(small.isPrefixOf(middle));
		assertTrue(small.isPrefixOf(large));
		assertTrue(middle.isPrefixOf(large));
		assertFalse(middle.isPrefixOf(small));
		assertFalse(large.isPrefixOf(small));
		assertFalse(large.isPrefixOf(middle));

		assertTrue(small.isPrefixOf(small));
		assertTrue(middle.isPrefixOf(middle));
		assertTrue(large.isPrefixOf(large));

		assertEquals(small.compareTo(small), 0);
		assertEquals(small.compareTo(small2), 0);
		assertEquals(middle.compareTo(middle), 0);
		assertEquals(large.compareTo(large), 0);

		assertEquals(small.compareTo(middle), -1);
		assertEquals(small.compareTo(large), -1);
		assertEquals(middle.compareTo(large), -1);
		assertEquals(middle.compareTo(small), 1);
		assertEquals(large.compareTo(small), 1);
		assertEquals(large.compareTo(middle), 1);

		Log.info(Log.FAC_TEST, "Completed testRelations");
	}

	@Test
	public void testContentNameParsePerformance() {
		Log.info(Log.FAC_TEST, "Starting testContentNameParsePerformance");

		ContentName name = null;
		long loops = 0;
		long elapsed = 0;
		int i;
		while (elapsed < 1000) // run for about 1s elapsed
		try {
			long time = System.currentTimeMillis();
			for (i = 0; i < 10000; i++) {
				name = ContentName.fromURI(veryEscapedName);
			}
			elapsed += System.currentTimeMillis() - time;
			loops += i;
		}catch (MalformedContentNameStringException e) {
			name = null;
		}
		System.out.println("Executed "+ loops + " ContentName.fromURI in " + elapsed + " ms; " + ((loops * 1000) / elapsed) + "/s");
		assertNotNull(name);

		Log.info(Log.FAC_TEST, "Completed testContentNameParsePerformance");
	}

	@Test
	public void testContentNamePrintPerformance() {
		Log.info(Log.FAC_TEST, "Starting testContentNamePrintPerformance");

		ContentName name;
		String nameString = null;
		long loops = 0;
		long elapsed = 0;
		int i;
		try {
				name = ContentName.fromURI(veryEscapedName);
		} catch (MalformedContentNameStringException e) {
			name = null;
		}
		assertNotNull(name);
		while (elapsed < 1000) { // run for about 1s elapsed
			long time = System.currentTimeMillis();
			for (i = 0; i < 10000; i++) {
				nameString = name.toString();
			}
			elapsed += System.currentTimeMillis() - time;
			loops += i;
		}
		System.out.println("Testing with " + nameString);
		System.out.println("Executed "+ loops + " ContentName.toString() in " + elapsed + " ms; " + ((loops * 1000) / elapsed) + "/s");

		Log.info(Log.FAC_TEST, "Completed testContentNamePrintPerformance");
	}

	@Test
	public void testPostfix() throws MalformedContentNameStringException {
		Log.info(Log.FAC_TEST, "Starting testPostfix");

		assertEquals( fromNative("/a/b/c/d/e").postfix(fromNative("/a/b/c")), fromNative("/d/e") );
		assertEquals( fromNative("/a/b/c").postfix(fromNative("/a/b/c")), ROOT );
		assertEquals( fromNative("/a/b/c").postfix(fromNative("/a/b/d/e")), null );

		Log.info(Log.FAC_TEST, "Completed testPostfix");
	}

	@SuppressWarnings("deprecation")
	@Test
	public void testNameManipulation() throws MalformedContentNameStringException, URISyntaxException {
		Log.info(Log.FAC_TEST, "Starting testNameManipulation");

		assertTrue(ContentName.fromNative("/a/b/c/d/e").contains("d"));
		assertFalse(ContentName.fromNative("/a/b/c/d/e").contains("f"));
		assertTrue(ContentName.fromNative("/aa/bb/cc/dd/ee").contains("dd".getBytes()));
		assertFalse(ContentName.fromNative("/aa/bb/cc/dd/ee").contains("f".getBytes()));
		VersionNumber vn = new VersionNumber();
		ContentName name = new ContentName("contentNameTest", "test", vn);
		assertTrue(name.contains(vn));

		assertTrue(Arrays.equals(ContentName.fromNative("/a/b/c/d").lastComponent(), "d".getBytes()));

		assertEquals(ContentName.fromNative("/a/b/c/d/e").right(2), ContentName.fromNative("/c/d/e"));

		assertTrue(ContentName.fromNative("/aa/bb/cc/dd/ee").componentStartsWith("dd".getBytes()));
		assertEquals(ContentName.fromNative("/aa/bb/cc/dd/ee").componentStartsWithWhere("cc".getBytes()), 2);

		assertEquals(ContentName.fromNative("/a/b/c/d/e/f").subname(1, 3), ContentName.fromNative("/b/c"));

		assertEquals(ContentName.fromNative("/aa/bb/cc/dd/aa/bb/cc").whereLast("aa".getBytes()), 4);
		name = new ContentName("xxx", vn, "yyy", vn);
		assertEquals(name.whereLast(vn), 3);
		assertEquals(ContentName.fromNative("/aa/bb/cc/dd/ee/bb/cc").whereLast("aa".getBytes()), 0);
		assertEquals(ContentName.fromNative("/aa/bb/cc/dd/ee/bb/cc").whereLast("ff".getBytes()), -1);

		Log.info(Log.FAC_TEST, "Completed testNameManipulation");
	}
}
