/*
 * A CCNx library test.
 *
 * Copyright (C) 2008, 2009, 2011 Palo Alto Research Center, Inc.
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

package org.ccnx.ccn.test.impl.encoding;

import static org.junit.Assert.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import org.ccnx.ccn.impl.encoding.BinaryXMLCodec;
import org.ccnx.ccn.impl.encoding.TextXMLCodec;
import org.ccnx.ccn.impl.encoding.XMLEncodable;
import org.ccnx.ccn.io.content.ContentDecodingException;
import org.ccnx.ccn.io.content.ContentEncodingException;

/**
 * Helper class for writing tests for classes implementing XMLEncodable.
 * Provides a basic test harness for testing encoding and decoding under
 * both binary and text codecs; would be easy to make it test all registered codecs.
 */
public class XMLEncodableTester {

	/**
	 * Test both binary and text encodings.
	 * @param label
	 * @param toEncode
	 * @param decodeTarget
	 */
	public static void encodeDecodeTest(String label,
										XMLEncodable toEncode,
										XMLEncodable decodeTargetText,
										XMLEncodable decodeTargetBinary) {
		
		encodeDecodeTest(TextXMLCodec.codecName(), label, toEncode, decodeTargetText);
		encodeDecodeTest(BinaryXMLCodec.codecName(), label, toEncode, decodeTargetBinary);
		// Should match, both match toEncode
		assertEquals(decodeTargetText, decodeTargetBinary);
	}

	public static void encodeDecodeByteArrayTest(String label,
			XMLEncodable toEncode,
			XMLEncodable decodeTargetText,
			XMLEncodable decodeTargetBinary) {

		encodeDecodeByteArrayTest(TextXMLCodec.codecName(), label, toEncode, decodeTargetText);
		encodeDecodeByteArrayTest(BinaryXMLCodec.codecName(), label, toEncode, decodeTargetBinary);
		// Should match, both match toEncode
		assertEquals(decodeTargetText, decodeTargetBinary);
	}

	public static void encodeDecodeTest(String codec,
										String label, 
										XMLEncodable toEncode, 
										XMLEncodable decodeTarget) {
		System.out.println("Encoding " + label + "(" + codec + "):");
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		try {
			toEncode.encode(baos, codec);
		} catch (ContentEncodingException e) {
			handleException(e);
		}
		System.out.println("Encoded " + label + ": " );
		System.out.println(baos.toString());
		
		System.out.println("Decoding " + label + ": ");
		ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
		try {
			decodeTarget.decode(bais, codec);
		} catch (ContentDecodingException e) {
			handleException(e);
		}
		System.out.println("Decoded " + label + ": " + decodeTarget);
		assertEquals(toEncode, decodeTarget);
	}

	public static void encodeDecodeByteArrayTest(String codec,
			String label, 
			XMLEncodable toEncode, 
			XMLEncodable decodeTarget) {
		System.out.println("Encoding " + label + "(" + codec + "):");
		byte [] bao = null;
		try {
			bao = toEncode.encode(codec);
		} catch (ContentEncodingException e) {
			handleException(e);
		}
		System.out.println("Encoded " + label + ": " );

		System.out.println("Decoding " + label + ": ");
		try {
			decodeTarget.decode(bao, codec);
		} catch (ContentDecodingException e) {
			handleException(e);
		}
		System.out.println("Decoded " + label + ": " + decodeTarget);
		assertEquals(toEncode, decodeTarget);
	}
	
	public static void handleException(Exception ex) {
		System.out.println("Got exception of type: " + ex.getClass().getName() + " message: " +
										ex.getMessage());
		ex.printStackTrace();
	}

}
