/*
 * A CCNx example of extending ccnb encoding/decoding.
 *
 * Copyright (C) 2008, 2009, 2010, 2011 Palo Alto Research Center, Inc.
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


package org.ccnx.ccn.apps.examples.ccnb.test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import org.ccnx.ccn.apps.examples.ccnb.Example2Integers;
import org.ccnx.ccn.apps.examples.ccnb.ExampleDictionary;
import org.ccnx.ccn.impl.encoding.BinaryXMLDictionary;
import org.ccnx.ccn.impl.encoding.GenericXMLEncodable;
import org.ccnx.ccn.impl.support.DataUtils;
import org.ccnx.ccn.io.content.ContentDecodingException;
import org.ccnx.ccn.io.content.ContentEncodingException;
import org.ccnx.ccn.test.impl.encoding.XMLEncodableTester;
import org.junit.Test;

public class ExampleEncodeDecodeTest {
	
	static {
		BinaryXMLDictionary.pushGlobalXMLDictionary(ExampleDictionary.getDefaultInstance());
	}

	public ByteArrayOutputStream encodeOutput(String typeName, GenericXMLEncodable toEncode) {
		System.out.println("Encoding " + typeName + ": " + toEncode);

		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		try {
			toEncode.encode(baos);
		} catch (ContentEncodingException e) {
			System.out.println("Exception " + e.getClass().getName() + ", message: " + e.getMessage());
			e.printStackTrace();
			return null;
		}
		System.out.print("Encoded " + typeName + ": ");
		System.out.println(DataUtils.printHexBytes(baos.toByteArray()));
		return baos;
	}
	
	public boolean decodeOutput(String typeName, GenericXMLEncodable decodeTo, ByteArrayOutputStream baos) {
		System.out.println("Decoding " + typeName + ": " + DataUtils.printHexBytes(baos.toByteArray()));
		ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
		try {
			decodeTo.decode(bais);
		} catch (ContentDecodingException e) {
			System.out.println("Exception " + e.getClass().getName() + ", message: " + e.getMessage());
			e.printStackTrace();
		}
		System.out.println("Decoded  " + typeName + ": " + decodeTo);
		return true;
	}

	/*
	 * The first set of tests is to verify that Example2Integers correctly encodes
	 * and decodes.
	 */

	public final static Integer integer1 = 100;
	public final static Integer integer2 = 200;
	
	public Example2Integers setupE2I() {
		Example2Integers e2i = new Example2Integers(integer1, integer2);
		return e2i;
	}

	@Test
	public void testE2IEncodeOutputStream() {
		System.out.println();
		System.out.println("Running: testE2IEncodeOutputStream");
		Example2Integers e2iToEncode = setupE2I();
		encodeOutput("Example2Integers", e2iToEncode);
	}

	@Test
	public void testE2IDecodeInputStream() {
		System.out.println();
		System.out.println("Running: testE2IEncodeOutputStream");
		Example2Integers e2iToEncode = setupE2I();
		ByteArrayOutputStream baos = encodeOutput("Example2Integers", e2iToEncode);
		assertNotNull(baos);
		Example2Integers e2iToDecodeTo = new Example2Integers();
		decodeOutput("Example2Integers", e2iToDecodeTo, baos);
		assertEquals("Integer1 missmatch", integer1, e2iToDecodeTo.getInteger1());
		assertEquals("Integer2 missmatch", integer2, e2iToDecodeTo.getInteger2());
	}
	
	@Test
	public void testE2IEncodingDecoding() {
		System.out.println();
		System.out.println("Running: testE2IEncodeOutputStream");
		Example2Integers e2iToEncode = setupE2I();
		Example2Integers  e2iToDecodeInto = new Example2Integers();
		assertNotNull(e2iToDecodeInto);
		Example2Integers  binaryE2IToDecodeInto = new Example2Integers();
		assertNotNull(binaryE2IToDecodeInto);
		XMLEncodableTester.encodeDecodeTest("Example2Integers", e2iToEncode, e2iToDecodeInto, binaryE2IToDecodeInto);
		assertEquals("Integer1 missmatch", integer1, e2iToDecodeInto.getInteger1());
		assertEquals("Integer2 missmatch", integer2, e2iToDecodeInto.getInteger2());
		assertEquals("Integer1 missmatch binary form", integer1, binaryE2IToDecodeInto.getInteger1());
		assertEquals("Integer2 missmatch binary form", integer2, binaryE2IToDecodeInto.getInteger2());
	}


}
