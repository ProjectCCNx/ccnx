/*
 * A CCNx library test.
 *
 * Copyright (C) 2008-2012 Palo Alto Research Center, Inc.
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

import java.security.InvalidKeyException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.SignatureException;
import java.util.Arrays;
import java.util.Date;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.ccnx.ccn.impl.support.DataUtils;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.protocol.CCNTime;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.KeyLocator;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;
import org.ccnx.ccn.protocol.Signature;
import org.ccnx.ccn.protocol.SignedInfo;
import org.ccnx.ccn.test.impl.encoding.XMLEncodableTester;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;


/**
 * Test basic ContentObject functionality.
 *
 */
public class ContentObjectTest {

	static final String baseName = "test";
	static final String subName2 = "smetters";
	static final String document2 = "test2.txt";	
	static public byte [] document3 = new byte[]{0x01, 0x02, 0x03, 0x04,
				0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c,
				0x0d, 0x0e, 0x0f, 0x1f, 0x1b, 0x1c, 0x1d, 0x1e,
				0x1f, 0x2e, 0x3c, 0x4a, 0x5c, 0x6d, 0x7e, 0xf};

	static ContentName name; 

	static final String rootDN = "C=US,O=Organization,OU=Organizational Unit,CN=Issuer";
	static final String endDN = "C=US,O=Final Org,L=Locality,CN=Fred Jones,E=fred@final.org";
	static final Date start = new Date(); 
	static final Date end = new Date(start.getTime() + (60*60*24*365));
	static final  String keydoc = "key";	
	static ContentName keyname;

	static KeyPair pair = null;
	static KeyLocator nameLoc = null;
	static KeyLocator keyLoc = null;
	static public Signature signature;
	static public byte [] contenthash = new byte[32];
	static PublisherPublicKeyDigest pubkey = null;	
	static SignedInfo auth = null;
	static SignedInfo authKey = null;

	
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		try {
			name = new ContentName(baseName, subName2, document2);
			keyname = new ContentName(baseName, subName2, keydoc);
			
			Security.addProvider(new BouncyCastleProvider());
			
			// generate key pair
			KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
			kpg.initialize(512); // go for fast
			pair = kpg.generateKeyPair();
			nameLoc = new KeyLocator(keyname);
			keyLoc = new KeyLocator(pair.getPublic());
			
			byte [] signaturebuf = new byte[64];
			Arrays.fill(signaturebuf, (byte)1);
			signature = new Signature(signaturebuf);
			Arrays.fill(contenthash, (byte)2);
			
			pubkey = new PublisherPublicKeyDigest(pair.getPublic());
			
			auth = new SignedInfo(pubkey,
					CCNTime.now(), 
					SignedInfo.ContentType.DATA, 
					nameLoc);
			authKey = new SignedInfo(pubkey,
					CCNTime.now(), 
					SignedInfo.ContentType.KEY, 
					keyLoc);
		} catch (Exception ex) {
			XMLEncodableTester.handleException(ex);
			System.out.println("Unable To Initialize Test!!!");
		}	
	}

	@Test
	public void testDecodeInputStream() {
		Log.info(Log.FAC_TEST, "Starting testDecodeInputStream");

		try {
			ContentObject cokey = 
				new ContentObject(name, authKey, document3, pair.getPrivate());
			ContentObject tdcokey = new ContentObject();
			ContentObject bdcokey = new ContentObject();
			XMLEncodableTester.encodeDecodeTest("ContentObjectKey", cokey, tdcokey, bdcokey);
			Assert.assertTrue(cokey.verify(pair.getPublic()));
			ContentObject co = 
				new ContentObject(name, auth, document3, pair.getPrivate());
			ContentObject tdco = new ContentObject();
			ContentObject bdco = new ContentObject();
			XMLEncodableTester.encodeDecodeTest("ContentObject", co, tdco, bdco);
			Assert.assertTrue(co.verify(pair.getPublic()));

			ContentObject coempty = 
				new ContentObject(name, auth, new byte[0], pair.getPrivate());
			ContentObject tdcoempty = new ContentObject();
			ContentObject bdcoempty = new ContentObject();
			XMLEncodableTester.encodeDecodeTest("ContentObject - empty content", coempty, tdcoempty, bdcoempty);
			Assert.assertTrue(coempty.verify(pair.getPublic()));
			ContentObject coempty2 = 
				new ContentObject(name, auth, null, pair.getPrivate());
			ContentObject tdcoempty2 = new ContentObject();
			ContentObject bdcoempty2 = new ContentObject();
			XMLEncodableTester.encodeDecodeTest("ContentObject - empty content2", coempty2, tdcoempty2, bdcoempty2);
			Assert.assertTrue(coempty2.verify(pair.getPublic()));
// Dump one to file for testing on the C side.
		/*	java.io.FileOutputStream fdump = new java.io.FileOutputStream("ContentObject.ccnb");
			co.encode(fdump);
			fdump.flush();
			fdump.close();
			*/
		} catch (Exception e) {
			Log.warning(Log.FAC_TEST, "Exception : " + e.getClass().getName() + ": " + e.getMessage());
			Log.warningStackTrace(Log.FAC_TEST, e);
			Assert.fail("Exception: " + e.getClass().getName() + ": " + e.getMessage());
		}
		
		Log.info(Log.FAC_TEST, "Completed testDecodeInputStream");
	}
	
	@Test
	public void testImmutable() {
		Log.info(Log.FAC_TEST, "Starting testImmutable");

		try {
			ContentObject co = new ContentObject(name, auth, document2.getBytes(), pair.getPrivate());
			byte [] bs = co.content();
			bs[0] = 1;
			Signature sig = co.signature();
			sig.signature()[0] = 2;
		} catch (InvalidKeyException e) {
			Assert.fail("Invalid key exception: " + e.getMessage());
		} catch (SignatureException e) {
			Assert.fail("Signature exception: " + e.getMessage());
		}
		
		Log.info(Log.FAC_TEST, "Completed testImmutable");
	}
	
	@Test
	public void testDigest() {
		Log.info(Log.FAC_TEST, "Starting testDigest");

		try {
			ContentObject coempty = 
				new ContentObject(name, auth, new byte[0], pair.getPrivate());
			System.out.println("Created object with content of length " + coempty.contentLength() + " digest: " + DataUtils.printHexBytes(coempty.digest()));
			ContentObject coempty2 = 
				new ContentObject(name, auth, null, pair.getPrivate());
			System.out.println("Created another object with content of length " + coempty2.contentLength() + " digest: " + DataUtils.printHexBytes(coempty2.digest()));
			Assert.assertNotNull(coempty.digest());
			Assert.assertArrayEquals(coempty.digest(), coempty2.digest());
		} catch (Exception e) {
			Assert.fail("Exception in testEncDec: " + e.getClass().getName() + ": " + e.getMessage());
		}
		
		Log.info(Log.FAC_TEST, "Completed testDigest");
	}
}
