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

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Date;

import javax.security.auth.x500.X500Principal;

import junit.framework.Assert;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.ccnx.ccn.impl.security.crypto.util.MinimalCertificateGenerator;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.protocol.CCNTime;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.KeyLocator;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;
import org.ccnx.ccn.protocol.SignedInfo;
import org.ccnx.ccn.protocol.SignedInfo.ContentType;
import org.ccnx.ccn.test.impl.encoding.XMLEncodableTester;
import org.junit.BeforeClass;
import org.junit.Test;


/**
 * Test the SignedInfo data structure.
 *
 */
public class SignedInfoTest {

	static final String baseName = "test";
	static final String subName2 = "smetters";
	static final String document2 = "test2.txt";	
	static public byte [] document3 = new byte[]{0x01, 0x02, 0x03, 0x04,
				0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c,
				0x0d, 0x0e, 0x0f, 0x1f, 0x1b, 0x1c, 0x1d, 0x1e,
				0x1f, 0x2e, 0x3c, 0x4a, 0x5c, 0x6d, 0x7e, 0xf};

	static ContentName name = null;

	static final String rootDN = "C=US,O=Organization,OU=Organizational Unit,CN=Issuer";
	static final String endDN = "C=US,O=Final Org,L=Locality,CN=Fred Jones,E=fred@final.org";
	static final Date start = new Date(); 
	static final Date end = new Date(start.getTime() + (60*60*24*365));
	static final  String keydoc = "key";	
	static ContentName keyname = null;

	static KeyPair pair = null;
	static X509Certificate cert = null;
	static KeyLocator nameLoc = null;
	static KeyLocator keyLoc = null;
	static KeyLocator certLoc = null;
	
	static public byte [] signature = new byte[256];
	static public byte [] publisherid = new byte[32];
	static PublisherPublicKeyDigest pubkey = null;	
	
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
			
			MinimalCertificateGenerator mg = new MinimalCertificateGenerator(
																endDN, pair.getPublic(), 
																new X500Principal(rootDN), 
																MinimalCertificateGenerator.MSEC_IN_YEAR, 
																false, null, false);
			cert = mg.sign(null, pair.getPrivate());
			nameLoc = new KeyLocator(keyname);
			keyLoc = new KeyLocator(pair.getPublic());
			certLoc = new KeyLocator(cert);
			
			Arrays.fill(signature, (byte)1);
			Arrays.fill(publisherid, (byte)3);
			
			pubkey = new PublisherPublicKeyDigest(publisherid);
		} catch (Exception ex) {
			XMLEncodableTester.handleException(ex);
			System.out.println("Unable To Initialize Test!!!");
		}	
	}
	
	@Test
	public void testTypes() {
		Log.info(Log.FAC_TEST, "Starting testTypes");

		Assert.assertEquals(SignedInfo.nameToType(SignedInfo.typeToName(ContentType.LINK)), SignedInfo.ContentType.LINK);
		Assert.assertEquals(SignedInfo.nameToType(SignedInfo.typeToName(ContentType.KEY)), SignedInfo.ContentType.KEY);
		Assert.assertEquals(SignedInfo.nameToType(SignedInfo.typeToName(ContentType.DATA)), SignedInfo.ContentType.DATA);
		Assert.assertEquals(SignedInfo.nameToType(SignedInfo.typeToName(ContentType.NACK)), SignedInfo.ContentType.NACK);
		Assert.assertEquals(SignedInfo.nameToType(SignedInfo.typeToName(ContentType.GONE)), SignedInfo.ContentType.GONE);
		Assert.assertEquals(SignedInfo.valueToType(SignedInfo.typeToValue(ContentType.LINK)), SignedInfo.ContentType.LINK);
		Assert.assertEquals(SignedInfo.valueToType(SignedInfo.typeToValue(ContentType.KEY)), SignedInfo.ContentType.KEY);
		Assert.assertEquals(SignedInfo.valueToType(SignedInfo.typeToValue(ContentType.DATA)), SignedInfo.ContentType.DATA);
		Assert.assertEquals(SignedInfo.valueToType(SignedInfo.typeToValue(ContentType.GONE)), SignedInfo.ContentType.GONE);
		Assert.assertEquals(SignedInfo.valueToType(SignedInfo.LINK_VAL), SignedInfo.ContentType.LINK);
		Assert.assertEquals(SignedInfo.valueToType(SignedInfo.KEY_VAL), SignedInfo.ContentType.KEY);
		Assert.assertEquals(SignedInfo.valueToType(SignedInfo.DATA_VAL), SignedInfo.ContentType.DATA);
		Assert.assertEquals(SignedInfo.valueToType(SignedInfo.NACK_VAL), SignedInfo.ContentType.NACK);
		Assert.assertEquals(SignedInfo.valueToType(SignedInfo.GONE_VAL), SignedInfo.ContentType.GONE);
		byte [] key = new byte[3];
		System.arraycopy(SignedInfo.KEY_VAL, 0, key, 0, key.length);
		Assert.assertEquals(SignedInfo.valueToType(key), SignedInfo.ContentType.KEY);
		
		Log.info(Log.FAC_TEST, "Completed testTypes");
	}

	@Test
	public void testDecodeInputStream() {
		Log.info(Log.FAC_TEST, "Starting testDecodeInputStream");

		SignedInfo nca = new SignedInfo(
				pubkey, 
				CCNTime.now(), 
				SignedInfo.ContentType.DATA, 
				nameLoc);
		SignedInfo dnca = new SignedInfo();
		SignedInfo bdnca = new SignedInfo();
		XMLEncodableTester.encodeDecodeTest("SignedInfo(name)", nca, dnca, bdnca);

		SignedInfo kca = new SignedInfo(
				pubkey, 
				CCNTime.now(), 
				SignedInfo.ContentType.KEY, 
				keyLoc);
		SignedInfo dkca = new SignedInfo();
		SignedInfo bdkca = new SignedInfo();
		XMLEncodableTester.encodeDecodeTest("SignedInfo(key)", kca, dkca, bdkca);

		SignedInfo cca = new SignedInfo(pubkey, 
				CCNTime.now(), 
				SignedInfo.ContentType.LINK, 
				certLoc);
		SignedInfo dcca = new SignedInfo();
		SignedInfo bdcca = new SignedInfo();
		XMLEncodableTester.encodeDecodeTest("SignedInfo(cert)", cca, dcca, bdcca);
		
		Log.info(Log.FAC_TEST, "Completed testDecodeInputStream");
	}
}
