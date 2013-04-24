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

package org.ccnx.ccn.test.security.crypto;

import java.security.SecureRandom;

import javax.crypto.spec.SecretKeySpec;

import junit.framework.AssertionFailedError;

import org.ccnx.ccn.impl.security.crypto.ContentKeys;
import org.ccnx.ccn.impl.security.crypto.KeyDerivationFunction;
import org.ccnx.ccn.impl.security.crypto.ContentKeys.ContentInfo;
import org.ccnx.ccn.impl.security.crypto.ContentKeys.KeyAndIV;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Test the KeyDerivationFunction (KDF) used to map from symmetric keys
 * to keys for a particular node in the content tree.
 *
 */
public class KeyDerivationFunctionTest {

	static SecureRandom random = new SecureRandom();
	static PublisherPublicKeyDigest publisher = null;
	static ContentName testName = null;
	static ContentName testNameVersion1 = null;
	static ContentName testNameVersion2 = null;
	static String functionalLabel = "Key Function";

	static SecretKeySpec keySpec;
	static byte [] key = new byte[16];
	static KeyAndIV keyandiv;
	static KeyAndIV keyandivnolabel;
	
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		byte [] ppd = new byte[32];
		random.nextBytes(ppd);
		publisher = new PublisherPublicKeyDigest(ppd);
		testName = ContentName.fromNative("/parc/test/media/NathanAtTheBeach.m4v");
		testNameVersion1 = VersioningProfile.addVersion(testName);
		Thread.sleep(3); // make sure version is different
		testNameVersion2 = VersioningProfile.addVersion(testName);

		random.nextBytes(key);
		keySpec = new SecretKeySpec(key, ContentKeys.DEFAULT_KEY_ALGORITHM);
		Assert.assertArrayEquals("raw bytes of key not the same as the encoded key!", key, keySpec.getEncoded());
		
		keyandiv = KeyDerivationFunction.DeriveKeysForObject(ContentKeys.DEFAULT_KEY_ALGORITHM,
				keySpec.getEncoded(), 
				new ContentInfo(testName, publisher, functionalLabel));
		keyandivnolabel = KeyDerivationFunction.DeriveKeysForObject(ContentKeys.DEFAULT_KEY_ALGORITHM,
				keySpec.getEncoded(), 
				new ContentInfo(testName, publisher, null));
	}

	@Test
	public void testKeysSameTwice() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testKeysSameTwice");

		KeyAndIV keyandiv2 = KeyDerivationFunction.DeriveKeysForObject(ContentKeys.DEFAULT_KEY_ALGORITHM,
				keySpec.getEncoded(), 
				new ContentInfo(testName, publisher, functionalLabel));
		Assert.assertEquals(keyandiv, keyandiv2);
		
		Log.info(Log.FAC_TEST, "Completed testKeysSameTwice");
	}
	
	@Test(expected=AssertionFailedError.class)
	public void testLabelMakesDifference() {
		Assert.assertEquals(keyandiv, keyandivnolabel);
	}
	
	@Test
	public void testNoLabelSameTwice() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testNoLabelSameTwice");

		KeyAndIV keyandivnolabel2 = KeyDerivationFunction.DeriveKeysForObject(ContentKeys.DEFAULT_KEY_ALGORITHM,
				keySpec.getEncoded(), 
				new ContentInfo(testName, publisher, null));
		Assert.assertEquals(keyandivnolabel, keyandivnolabel2);
		
		Log.info(Log.FAC_TEST, "Completed testNoLabelSameTwice");
	}
	
	@Test(expected=AssertionFailedError.class)
	public void testVersionMakesDifference() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testVersionMakesDifference");

		KeyAndIV keyandivv1 = KeyDerivationFunction.DeriveKeysForObject(ContentKeys.DEFAULT_KEY_ALGORITHM,
				keySpec.getEncoded(), 
				new ContentInfo(testNameVersion1, publisher, null));
		KeyAndIV keyandivv2 = KeyDerivationFunction.DeriveKeysForObject(ContentKeys.DEFAULT_KEY_ALGORITHM,
				keySpec.getEncoded(), 
				new ContentInfo(testNameVersion2, publisher, null));
		Assert.assertEquals(keyandivv1, keyandivv2);
	}
}
