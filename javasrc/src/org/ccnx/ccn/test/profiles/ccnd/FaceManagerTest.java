/**
 * A CCNx library test.
 *
 * Copyright (C) 2008, 2009 Palo Alto Research Center, Inc.
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

package org.ccnx.ccn.test.profiles.ccnd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import org.ccnx.ccn.io.content.ContentDecodingException;
import org.ccnx.ccn.io.content.ContentEncodingException;
import org.ccnx.ccn.profiles.ccnd.FaceManager;
import org.ccnx.ccn.profiles.ccnd.FaceManager.ActionType;
import org.ccnx.ccn.profiles.ccnd.FaceManager.FaceInstance;
import org.ccnx.ccn.profiles.ccnd.FaceManager.NetworkProtocol;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;
import org.ccnx.ccn.test.impl.encoding.XMLEncodableTester;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Test basic version manipulation.
 */
public class FaceManagerTest {
	
	PublisherPublicKeyDigest keyDigest;
	FaceManager fm;


	/**
	 * @throws java.lang.Exception
	 */
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
	}

	/**
	 * @throws java.lang.Exception
	 */
	@AfterClass
	public static void tearDownAfterClass() throws Exception {
	}

	/**
	 * @throws java.lang.Exception
	 */
	@Before
	public void setUp() throws Exception {
		keyDigest = new PublisherPublicKeyDigest();
		fm = new FaceManager();
	}

	/**
	 * @throws java.lang.Exception
	 */
	@After
	public void tearDown() throws Exception {
	}
	
	/**
	 * Test method for org.ccnx.ccn.profiles.VersioningProfile#addVersion(org.ccnx.ccn.protocol.ContentName, long).
	 */
	@Test
	public void testEncodeOutputStream() {
		FaceInstance face = fm. new FaceInstance(ActionType.NewFace, keyDigest, NetworkProtocol.TCP, "TheNameDoesntMatter", 
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
	}

	@Test
	public void testDecodeInputStream() {
		FaceInstance face = fm. new FaceInstance(ActionType.NewFace, keyDigest, NetworkProtocol.TCP, "TheNameDoesntMatter", 
				new Integer(5),	"WhoCares", new Integer(42), new Integer(100));
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
		
		System.out.println("Decoding: ");
		ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
		FaceInstance face2 = fm. new FaceInstance(ActionType.NewFace, keyDigest, NetworkProtocol.TCP, "TheNameDoesntMatter", 
				new Integer(5),	"WhoCares", new Integer(42), new Integer(100));
		try {
			face2.decode(bais);
		} catch (ContentDecodingException e) {
			System.out.println("Exception " + e.getClass().getName() + ", message: " + e.getMessage());
			e.printStackTrace();
		}
		System.out.println("Decoded: " + face2);
		assertEquals(face, face2);
	}
	
	@Test
	public void testEncodingDecoding() {
		FaceInstance face = fm. new FaceInstance(ActionType.NewFace, keyDigest, NetworkProtocol.TCP, "TheNameDoesntMatter", 
				new Integer(5),	"WhoCares", new Integer(42), new Integer(100));
		System.out.println("Encoding: " + face);
		assertNotNull(face);

		FaceInstance  tn = new FaceManager(). new FaceInstance();
		FaceInstance  bn = new FaceManager(). new FaceInstance();
		
		XMLEncodableTester.encodeDecodeTest("FaceIntance", face, tn, bn);
	}

}
