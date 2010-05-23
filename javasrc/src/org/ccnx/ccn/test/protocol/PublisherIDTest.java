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

package org.ccnx.ccn.test.protocol;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Arrays;

import org.ccnx.ccn.impl.security.crypto.CCNDigestHelper;
import org.ccnx.ccn.impl.support.DataUtils;
import org.ccnx.ccn.protocol.PublisherID;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;
import org.ccnx.ccn.protocol.PublisherID.PublisherType;
import org.ccnx.ccn.test.impl.encoding.XMLEncodableTester;
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
			
	}
	
	@Test
	public void testPublisherToString() {
		
		String id = "1lplh6o3q52k6jnnr0n7utmegju6cjjg3p7jhgfn8h1siubg20r7";
		System.out.println("id length " + id.length());
		PublisherPublicKeyDigest did = new PublisherPublicKeyDigest(id);
		
		byte [] sid = CCNDigestHelper.scanBytes(id, 32);
		System.out.println("sid hex " + DataUtils.printHexBytes(sid));
		
		System.out.println("id " + id);
		System.out.println("id hex " + DataUtils.printHexBytes(id.getBytes()));
		System.out.println("did " + did);
		System.out.println("PPKD(did) " + new PublisherPublicKeyDigest(did.toString()));
		
		String id2 = "6n6m4r0f8kagqeuvc2svrmpq2fopiee0f4ue61ut247ibpe083";
		System.out.println("id2 length " + id2.length());
		PublisherPublicKeyDigest did2 = new PublisherPublicKeyDigest(id2);
		
		byte [] sid2 = CCNDigestHelper.scanBytes(id2, 32);
		System.out.println("sid2 hex " + DataUtils.printHexBytes(sid2));
		
		System.out.println("id2 " + id2);
		System.out.println("id2 hex " + DataUtils.printHexBytes(id2.getBytes()));
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
		// the PrintStream puts a CR onto the end, which causes trouble.
	//	byte [] sid3 = CCNDigestHelper.scanBytes(input, 32);
	//	System.out.println("sid3 hex " + DataUtils.printHexBytes(sid3));
		
	}

}
