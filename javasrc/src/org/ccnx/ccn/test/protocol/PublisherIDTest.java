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

import java.util.Arrays;

import org.ccnx.ccn.protocol.PublisherID;
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

}
