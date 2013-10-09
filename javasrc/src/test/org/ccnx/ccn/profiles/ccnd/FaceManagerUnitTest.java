/*
 * A CCNx library test.
 *
 * Copyright (C) 2009-2011, 2013 Palo Alto Research Center, Inc.
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

package org.ccnx.ccn.profiles.ccnd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import org.ccnx.ccn.encoding.XMLEncodableTester;
import org.ccnx.ccn.impl.CCNNetworkManager.NetworkProtocol;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.ContentDecodingException;
import org.ccnx.ccn.io.content.ContentEncodingException;
import org.ccnx.ccn.profiles.ccnd.FaceManager.ActionType;
import org.ccnx.ccn.profiles.ccnd.FaceManager.FaceInstance;
import org.junit.Test;

/**
 * Test basic version manipulation.
 */
public class FaceManagerUnitTest {
	
	
	/**
	 * Test method for org.ccnx.ccn.profiles.VersioningProfile#addVersion(org.ccnx.ccn.protocol.ContentName, long).
	 */
	@Test
	public void testEncodeOutputStream() {
		Log.info(Log.FAC_TEST, "Starting testEncodeOutputStream");

		FaceInstance face = new FaceInstance(ActionType.NewFace, null, NetworkProtocol.TCP, "TheNameDoesntMatter", 
				new Integer(5),	"WhoCares", new Integer(42), new Integer(100));
		// ActionType.NewFace, _ccndId, ipProto, host, port,  multicastInterface, multicastTTL, freshnessSeconds
		System.out.println("Encoding: " + face);
		assertNotNull(face);
		
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		try {
			face.encode(baos);
		} catch (ContentEncodingException e) {
			System.out.println("Exception " + e.getClass().getName() + ", message: " + e.getMessage());
			e.printStackTrace();
		}
		System.out.println("Encoded: " );
		System.out.println(baos.toString());
		
		Log.info(Log.FAC_TEST, "Completed testEncodeOutputStream");
	}

	@Test
	public void testDecodeInputStream() {
		Log.info(Log.FAC_TEST, "Starting testDecodeInputStream");

		FaceInstance faceToEncode = new FaceInstance(ActionType.NewFace, null, NetworkProtocol.TCP, "TheNameDoesntMatter", 
				new Integer(5),	"WhoCares", new Integer(42), new Integer(100));
		System.out.println("Encoding: " + faceToEncode);
		assertNotNull(faceToEncode);
		
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		try {
			faceToEncode.encode(baos);
		} catch (ContentEncodingException e) {
			System.out.println("Exception " + e.getClass().getName() + ", message: " + e.getMessage());
			e.printStackTrace();
		}
		System.out.println("Encoded: " );
		System.out.println(baos.toString());
		
		System.out.println("Decoding: ");
		ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
		FaceInstance faceToDecodeTo = new FaceInstance();  /* We need an empty one to decode into */
		try {
			faceToDecodeTo.decode(bais);
		} catch (ContentDecodingException e) {
			System.out.println("Exception " + e.getClass().getName() + ", message: " + e.getMessage());
			e.printStackTrace();
		}
		System.out.println("Decoded: " + faceToDecodeTo);
		assertEquals(faceToEncode, faceToDecodeTo);
		
		Log.info(Log.FAC_TEST, "Completed testDecodeInputStream");
	}
	
	@Test
	public void testEncodingDecoding() {
		Log.info(Log.FAC_TEST, "Starting testEncodingDecoding");

		FaceInstance faceToEncode = new FaceInstance(ActionType.NewFace, null, NetworkProtocol.TCP, "TheNameDoesntMatter", 
				new Integer(5),	"WhoCares", new Integer(42), new Integer(100));
		System.out.println("Encoding: " + faceToEncode);

		FaceInstance  textFaceToDecodeInto = new FaceInstance();
		assertNotNull(textFaceToDecodeInto);
		FaceInstance  binaryFaceToDecodeInto = new FaceInstance();
		assertNotNull(binaryFaceToDecodeInto);
		XMLEncodableTester.encodeDecodeTest("FaceIntance", faceToEncode, textFaceToDecodeInto, binaryFaceToDecodeInto);
		
		Log.info(Log.FAC_TEST, "Completed testEncodingDecoding");
	}
}
