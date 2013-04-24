/*
 * A CCNx library test.
 *
 * Copyright (C) 2008, 2009, 2010 Palo Alto Research Center, Inc.
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

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Arrays;

import org.ccnx.ccn.impl.security.crypto.CCNDigestHelper;
import org.ccnx.ccn.impl.support.DataUtils;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.protocol.PublisherID;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;
import org.ccnx.ccn.protocol.PublisherID.PublisherType;
import org.ccnx.ccn.test.impl.encoding.XMLEncodableTester;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;


/**
 * Test the PublisherID data structure.
 *
 */
public class PublisherIDTest {

	static public byte [] publisherid = new byte[32];
	
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		try {
			Arrays.fill(publisherid, (byte)3);			
		} catch (Exception ex) {
			XMLEncodableTester.handleException(ex);
			System.out.println("Unable To Initialize Test!!!");
		}	
	}

	@Test
	public void testDecodeInputStream() {
		Log.info(Log.FAC_TEST, "Starting testDecodeInputStream");

		PublisherID pubkey = new PublisherID(publisherid, PublisherType.KEY);
		PublisherID pubkeyDec = new PublisherID();
		PublisherID bpubkeyDec = new PublisherID();
		XMLEncodableTester.encodeDecodeTest("PublisherID(key)", pubkey, pubkeyDec, bpubkeyDec);
	
		PublisherID pubcert = new PublisherID(publisherid, PublisherType.CERTIFICATE);
		PublisherID pubcertDec = new PublisherID();
		PublisherID bpubcertDec = new PublisherID();
		XMLEncodableTester.encodeDecodeTest("PublisherID(cert)", pubcert, pubcertDec, bpubcertDec);
		
		PublisherID pubisskey = new PublisherID(publisherid, PublisherType.ISSUER_KEY);
		PublisherID pubisskeyDec = new PublisherID();
		PublisherID bpubisskeyDec = new PublisherID();
		XMLEncodableTester.encodeDecodeTest("PublisherID(isskey)", pubisskey, pubisskeyDec, bpubisskeyDec);
			
		PublisherID pubisscert = new PublisherID(publisherid, PublisherType.ISSUER_CERTIFICATE);
		PublisherID pubisscertDec = new PublisherID();
		PublisherID bpubisscertDec = new PublisherID();
		XMLEncodableTester.encodeDecodeTest("PublisherID(isscert)", pubisscert, pubisscertDec, bpubisscertDec);
		
		Log.info(Log.FAC_TEST, "Completed testDecodeInputStream");
	}
	
	@Test
	public void testPublisherToString() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testPublisherToString");
		
		// Start with a java base 32 representation, a form used in debug output,
		// but generally not in any actual protocols.
		String id = "1lplh6o3q52k6jnnr0n7utmegju6cjjg3p7jhgfn8h1siubg20r7";
		System.out.println("id length " + id.length());
		
		byte [] sid = CCNDigestHelper.scanBytes(id, 32);
		System.out.println("sid hex " + DataUtils.printHexBytes(sid));
		PublisherPublicKeyDigest did = new PublisherPublicKeyDigest(sid);
		Assert.assertEquals(did.digest().length, 256 / 8); // bytes in SHA-256
		
		System.out.println("id " + id);
		System.out.println("did hex " + DataUtils.printHexBytes(did.digest()));
		System.out.println("did " + did);
		System.out.println("PPKD(did) " + new PublisherPublicKeyDigest(did.toString()));
		
		String id2 = "6n6m4r0f8kagqeuvc2svrmpq2fopiee0f4ue61ut247ibpe083";
		System.out.println("id2 length " + id2.length());
		
		byte [] sid2 = CCNDigestHelper.scanBytes(id2, 32);
		System.out.println("sid2 hex " + DataUtils.printHexBytes(sid2));
		PublisherPublicKeyDigest did2 = new PublisherPublicKeyDigest(sid2);
		
		System.out.println("id2 " + id2);
		System.out.println("id2 hex " + DataUtils.printHexBytes(did2.digest()));
		System.out.println("did2 " + did2);
		System.out.println("PPKD(did2) " + new PublisherPublicKeyDigest(did2.toString()));
		
		ByteArrayOutputStream os = new ByteArrayOutputStream();
		PrintStream ps = new PrintStream(os);
		ps.println(did2.toString());
		ps.close();
		
		byte [] output = os.toByteArray();
		System.out.println("Via printstream: " + DataUtils.printHexBytes(output));
		String input = new String(output);
		System.out.println("into a string: " + input);
		byte [] sid3 = (new PublisherPublicKeyDigest(input)).digest();
		System.out.println("sid3 hex " + DataUtils.printHexBytes(sid3));
		
		byte [] otherBytes = {(byte)0xff, (byte)0xff, (byte)0xd9, (byte)0xde, (byte)0x75, (byte)0xc5, 
				(byte)0xa56, (byte)0xff, (byte)0x45, (byte)0x78,
				(byte)0x67, (byte)0xb2, (byte)0xc3, (byte)0x44, (byte)0x2b, 
				(byte)0xb0, (byte)0xb0, (byte)0xaf, (byte)0x71, (byte)0x94, (byte)0x68, (byte)0x0b,
				(byte)0x2c, (byte)0x0c, (byte)0x0e, (byte)0xef, 
				(byte)0x3b, (byte)0xa0, (byte)0x8c, (byte)0xda, (byte)0x02, (byte)0xb02};

		PublisherPublicKeyDigest p2 = new PublisherPublicKeyDigest(otherBytes);
		
		Assert.assertArrayEquals(otherBytes, p2.digest());
		
		PublisherPublicKeyDigest p3 = new PublisherPublicKeyDigest(p2.toString());
		Assert.assertEquals(p2, p3);
		
		Log.info(Log.FAC_TEST, "Completed testPublisherToString");
	}
}
