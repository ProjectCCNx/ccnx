/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2012, 2013 Palo Alto Research Center, Inc.
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License version 2.1
 * as published by the Free Software Foundation. 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details. You should have received
 * a copy of the GNU Lesser General Public License along with this library;
 * if not, write to the Free Software Foundation, Inc., 51 Franklin Street,
 * Fifth Floor, Boston, MA 02110-1301 USA.
 */
package org.ccnx.ccn.test.impl.security.keys;

import java.util.Random;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

import org.ccnx.ccn.KeyManager;
import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.impl.CCNFlowControl.SaveType;
import org.ccnx.ccn.impl.security.keys.SecureKeyCache;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.CCNStringObject;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;
import org.ccnx.ccn.test.CCNTestBase;
import org.ccnx.ccn.test.CCNTestHelper;
import org.ccnx.ccn.test.Flosser;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class SymmetricKeyTest extends CCNTestBase {
	
	static Flosser flosser = null;
	
	static CCNTestHelper testHelper = new CCNTestHelper(SymmetricKeyTest.class);
	static KeyGenerator kg;
	
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		CCNTestBase.setUpBeforeClass();
		kg = KeyGenerator.getInstance("HMAC-SHA256", KeyManager.PROVIDER);
	}

	/**
	 * Tests signing and verification of data using symmetric keys.
	 * @throws Exception
	 */
	@Test
	public void testSymmetricKeys() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testSymmetricKeys");
		
		flosser = new Flosser();
		SecretKey sk = kg.generateKey();
		SecureKeyCache skc = putHandle.getNetworkManager().getKeyManager().getSecureKeyCache();
		PublisherPublicKeyDigest publisher = new PublisherPublicKeyDigest(sk);
		skc.addSecretKey(null, publisher.digest(), sk);
		ContentName name = testHelper.getTestChildName("testSymmetricKeys", "testString");
		CCNStringObject testString1 = new CCNStringObject(name, "A test!", 
									SaveType.RAW, publisher, null, putHandle);
		flosser.handleNamespace(name);
		testString1.save();
		CCNStringObject testString2 = new CCNStringObject(name, publisher,getHandle);
		testString2.waitForData(SystemConfiguration.EXTRA_LONG_TIMEOUT);
		Assert.assertEquals(testString2.string(), "A test!");
		testString1.close();
		testString2.close();
		flosser.stopMonitoringNamespaces();
		
		Log.info(Log.FAC_TEST, "Completed testSymmetricKeys");
	}
	
	@Test
	public void testCorruptContent() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testCorruptContent");

		ContentName name = testHelper.getTestChildName("testCorruptContent", "testString");
		ContentObject co = ContentObject.buildContentObject(name, "This is a test".getBytes());
		SecretKey sk = kg.generateKey();
		co.sign(sk);
		Random rand = new Random();
		int start = rand.nextInt(co.contentLength() - 8);
		System.arraycopy(new byte[] {0xd, 0xe, 0xa, 0xd, 0xb, 0xe, 0xe, 0xf}, 0, co.content(), start, 8);
		Assert.assertFalse(co.verify(sk));
		
		Log.info(Log.FAC_TEST, "Completed testCorruptContent");
	}
}
