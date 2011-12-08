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

package org.ccnx.ccn.test.io.content;

import java.security.Key;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Security;

import javax.crypto.spec.SecretKeySpec;

import org.bouncycastle.asn1.nist.NISTObjectIdentifiers;
import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.impl.CCNFlowControl.SaveType;
import org.ccnx.ccn.impl.security.crypto.jce.CCNCryptoProvider;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.WrappedKey;
import org.ccnx.ccn.io.content.WrappedKey.WrappedKeyObject;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.profiles.security.access.group.GroupAccessControlProfile;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.PublisherID;
import org.ccnx.ccn.test.CCNTestHelper;
import org.ccnx.ccn.test.Flosser;
import org.ccnx.ccn.test.impl.encoding.XMLEncodableTester;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;


/**
 * Test both encoding/decoding of WrappedKey data structures and writing them
 * to CCN using WrappedKeyObjects. Move tests that require either unlimited-strength
 * crypto or algorithms that BouncyCastle does not support on all platforms/versions
 * to the expanded tests. See apps/examples/ExpandedCryptoTests.
 */
public class WrappedKeyTest {

	/**
	 * Handle naming for the test
	 */
	static CCNTestHelper testHelper = new CCNTestHelper(PublicKeyObjectTestRepo.class);

	public static boolean setupDone = false;
	public static KeyPair wrappingKeyPair = null;
	public static KeyPair wrappedKeyPair = null;
	public static KeyPair wrappedDHKeyPair = null;
	public static KeyPair wrappedDSAKeyPair = null;
	public static SecretKeySpec wrappingAESKey = null;
	public static SecretKeySpec wrappedAESKey = null;
	public static String aLabel = "FileEncryptionKeys";
	public static byte [] wrappingKeyID = null;
	public static ContentName wrappingKeyName = null;
	public static ContentName storedKeyName = null;
	public static byte [] dummyWrappedKey = new byte[64];
	
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		Security.addProvider(new CCNCryptoProvider());
	}
	
	/**
	 * Do this in the first test. Were doing it in setupBeforeClass, but I think
	 * it was failing sometimes, possibly because it was too slow.
	 * @throws Exception
	 */
	public void setupTest() throws Exception {
		if (setupDone) {
			return;
		}
		
		SecureRandom sr = new SecureRandom();
		sr.nextBytes(dummyWrappedKey);
		
		// generate key pair
		KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
		kpg.initialize(1024); // go for fast
		wrappingKeyPair = kpg.generateKeyPair();
		wrappedKeyPair = kpg.generateKeyPair();
		wrappingKeyID = PublisherID.generatePublicKeyDigest(wrappingKeyPair.getPublic());
		wrappingKeyName = VersioningProfile.addVersion(ContentName.fromNative("/parc/Users/briggs/KEY"));
				
		kpg = KeyPairGenerator.getInstance("DSA");
        kpg.initialize(1024);
        wrappedDSAKeyPair = kpg.genKeyPair();
    
        // Generate a 576-bit DH key pair
        kpg = KeyPairGenerator.getInstance("DH");
        kpg.initialize(576);
        wrappedDHKeyPair = kpg.genKeyPair();

        byte [] key = new byte[16];
		sr.nextBytes(key);
		wrappingAESKey = new SecretKeySpec(key, "AES");
		sr.nextBytes(key);
		wrappedAESKey = new SecretKeySpec(key, "AES");
		
		ContentName nodeName = testHelper.getClassNamespace().append(
				ContentName.fromNative("/test/content/File1.txt"));
		
		storedKeyName = GroupAccessControlProfile.nodeKeyName(nodeName);
		setupDone = true;
		Log.info(Log.FAC_TEST, "Initialized keys for WrappedKeyTest");		
	}

	@Test
	public void testWrapUnwrapKey() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testWrapUnwrapKey");

		// don't use setUpBeforeClass, may not be handling slow initialization well
		setupTest(); 
		// for each wrap case, wrap, unwrap, and make sure it matches.
		// Wrap secret in secret
		Log.info(Log.FAC_TEST, "Wrap secret key in secret key.");
		WrappedKey wks = WrappedKey.wrapKey(wrappedAESKey, null, aLabel, wrappingAESKey);
		Key unwrappedKey = wks.unwrapKey(wrappingAESKey);
		Assert.assertArrayEquals(wrappedAESKey.getEncoded(), unwrappedKey.getEncoded());
		// wrap secret in public			
		Log.info(Log.FAC_TEST, "Wrap secret key in public key.");
		WrappedKey wksp = WrappedKey.wrapKey(wrappedAESKey, null, aLabel, wrappingKeyPair.getPublic());
		unwrappedKey = wksp.unwrapKey(wrappingKeyPair.getPrivate());
		Assert.assertArrayEquals(wrappedAESKey.getEncoded(), unwrappedKey.getEncoded());
		// wrap private in public
		Log.info(Log.FAC_TEST, "Wrap private key in public key.");
		WrappedKey wkpp = WrappedKey.wrapKey(wrappingKeyPair.getPrivate(), null, aLabel, wrappingKeyPair.getPublic());
		unwrappedKey = wkpp.unwrapKey(wrappingKeyPair.getPrivate());
		Assert.assertArrayEquals(wrappingKeyPair.getPrivate().getEncoded(), unwrappedKey.getEncoded());
		// wrap private in secret
		Log.info(Log.FAC_TEST, "Wrap private key in secret key.");
		Log.info(Log.FAC_TEST, "wpk length " + wrappingKeyPair.getPrivate().getEncoded().length);
		WrappedKey wkp = WrappedKey.wrapKey(wrappingKeyPair.getPrivate(), null, aLabel, wrappingAESKey);
		unwrappedKey = wkp.unwrapKey(wrappingAESKey);
		Assert.assertArrayEquals(wrappingKeyPair.getPrivate().getEncoded(), unwrappedKey.getEncoded());
		// ditto for el gamal
		/*
		 * ElGamal encryption requires unlimited strength crypto. This used to be installed
		 * by default on OSX, but not anymore, and not on Ubuntu or Windows. Moved to expanded tests.
		 * 
			wksp = WrappedKey.wrapKey(wrappedAESKey, null, aLabel, wrappingEGKeyPair.getPublic());
			unwrappedKey = wksp.unwrapKey(wrappingEGKeyPair.getPrivate());
			Assert.assertEquals(new BigInteger(1, wrappedAESKey.getEncoded()), new BigInteger(1, unwrappedKey.getEncoded()));
			wkpp = WrappedKey.wrapKey(wrappingEGKeyPair.getPrivate(), null, aLabel, wrappingKeyPair.getPublic());
			unwrappedKey = wkpp.unwrapKey(wrappingKeyPair.getPrivate());
			Assert.assertArrayEquals(wrappingEGKeyPair.getPrivate().getEncoded(), unwrappedKey.getEncoded());
		 */		
		// wrap DSA private in public key
		Log.info(Log.FAC_TEST, "Wrap DSA private in private.");
		wkpp = WrappedKey.wrapKey(wrappedDSAKeyPair.getPrivate(), null, aLabel, wrappingKeyPair.getPublic());
		unwrappedKey = wkpp.unwrapKey(wrappingKeyPair.getPrivate());
		Assert.assertArrayEquals(wrappedDSAKeyPair.getPrivate().getEncoded(), unwrappedKey.getEncoded());
		Log.info(Log.FAC_TEST, "Wrap DSA private in secret.");
		wkp = WrappedKey.wrapKey(wrappedDSAKeyPair.getPrivate(), null, aLabel, wrappingAESKey);
		unwrappedKey = wkp.unwrapKey(wrappingAESKey);
		Assert.assertArrayEquals(wrappedDSAKeyPair.getPrivate().getEncoded(), unwrappedKey.getEncoded());
		
		// wrap DH private in public key
		Log.info(Log.FAC_TEST, "Wrap DH private in private.");
		wkpp = WrappedKey.wrapKey(wrappedDHKeyPair.getPrivate(), null, aLabel, wrappingKeyPair.getPublic());
		unwrappedKey = wkpp.unwrapKey(wrappingKeyPair.getPrivate());
		Assert.assertArrayEquals(wrappedDHKeyPair.getPrivate().getEncoded(), unwrappedKey.getEncoded());
		Log.info(Log.FAC_TEST, "Wrap DH private in secret.");
		wkp = WrappedKey.wrapKey(wrappedDHKeyPair.getPrivate(), null, aLabel, wrappingAESKey);
		unwrappedKey = wkp.unwrapKey(wrappingAESKey);
		Assert.assertArrayEquals(wrappedDHKeyPair.getPrivate().getEncoded(), unwrappedKey.getEncoded());
		
		Log.info(Log.FAC_TEST, "Completed testWrapUnwrapKey");
	}

	@Test
	public void testWrappedKeyByteArrayStringStringStringByteArrayByteArray() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testWrappedKeyByteArrayStringStringStringByteArrayByteArray");

		// don't use setUpBeforeClass, may not be handling slow initialization well
		setupTest(); 
		WrappedKey wka = null;
		wka = WrappedKey.wrapKey(wrappedAESKey, null, aLabel, 
				wrappingKeyPair.getPublic());
		WrappedKey wk2 = new WrappedKey(wrappingKeyID,
				WrappedKey.wrapAlgorithmForKey(wrappingKeyPair.getPublic().getAlgorithm()),
				wrappedAESKey.getAlgorithm(),
				aLabel,
				wka.encryptedNonceKey(),
				wka.encryptedKey());

		WrappedKey dwk = new WrappedKey();
		WrappedKey bdwk = new WrappedKey();
		XMLEncodableTester.encodeDecodeTest("WrappedKey(full)", wk2, dwk, bdwk);
		wka.setWrappingKeyIdentifier(wrappingKeyID);
		
		Log.info(Log.FAC_TEST, "Completed testWrappedKeyByteArrayStringStringStringByteArrayByteArray");
	}
	
	@Test
	public void testDecodeInputStream() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testDecodeInputStream");

		// don't use setUpBeforeClass, may not be handling slow initialization well
		setupTest(); 
		WrappedKey wk = new WrappedKey(wrappingKeyID, dummyWrappedKey);
		WrappedKey dwk = new WrappedKey();
		WrappedKey bdwk = new WrappedKey();
		XMLEncodableTester.encodeDecodeTest("WrappedKey(dummy)", wk, dwk, bdwk);

		WrappedKey wks = WrappedKey.wrapKey(wrappedAESKey, null, aLabel, wrappingAESKey);

		WrappedKey dwks = new WrappedKey();
		WrappedKey bdwks = new WrappedKey();
		XMLEncodableTester.encodeDecodeTest("WrappedKey(symmetric, real)", wks, dwks, bdwks);

		WrappedKey wka = WrappedKey.wrapKey(wrappedAESKey, NISTObjectIdentifiers.id_aes128_CBC.toString(), 
										aLabel, wrappingKeyPair.getPublic());

		wka.setWrappingKeyIdentifier(wrappingKeyID);
		wka.setWrappingKeyName(wrappingKeyName);
		WrappedKey dwka = new WrappedKey();
		WrappedKey bdwka = new WrappedKey();
		XMLEncodableTester.encodeDecodeTest("WrappedKey(assymmetric wrap symmetric, with id and name)", wka, dwka, bdwka);
		Assert.assertArrayEquals(dwka.wrappingKeyIdentifier(), wrappingKeyID);
		
		Log.info(Log.FAC_TEST, "Completed testDecodeInputStream");
	}
	
	@Test
	public void testWrappedKeyObject() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testWrappedKeyObject");

		// don't use setUpBeforeClass, may not be handling slow initialization well
		setupTest(); 
		
		WrappedKey wks = WrappedKey.wrapKey(wrappedAESKey, null, aLabel, wrappingAESKey);
		WrappedKey wka = WrappedKey.wrapKey(wrappedAESKey, NISTObjectIdentifiers.id_aes128_CBC.toString(), 
										aLabel, wrappingKeyPair.getPublic());
		wka.setWrappingKeyIdentifier(wrappingKeyID);
		wka.setWrappingKeyName(wrappingKeyName);
		CCNHandle thandle = CCNHandle.open();
		CCNHandle thandle2 = CCNHandle.open();
		
		Flosser flosser = null;
		try {
			flosser = new Flosser();
			flosser.handleNamespace(storedKeyName);
			WrappedKeyObject wko = 
				new WrappedKeyObject(storedKeyName, wks, SaveType.RAW, thandle);
			wko.save();
			Assert.assertTrue(VersioningProfile.hasTerminalVersion(wko.getVersionedName()));
			// should update in another thread
			WrappedKeyObject wkoread = new WrappedKeyObject(storedKeyName, thandle2);
			Assert.assertTrue(wkoread.available());
			Assert.assertEquals(wkoread.getVersionedName(), wko.getVersionedName());
			Assert.assertEquals(wkoread.wrappedKey(), wko.wrappedKey());
			// DKS -- bug in interest handling, can't save wkoread and update wko
			wko.save(wka);
			Assert.assertTrue(VersioningProfile.isLaterVersionOf(wko.getVersionedName(), wkoread.getVersionedName()));
			wkoread.update();
			Assert.assertEquals(wkoread.getVersionedName(), wko.getVersionedName());
			Assert.assertEquals(wkoread.wrappedKey(), wko.wrappedKey());
			Assert.assertEquals(wko.wrappedKey(), wka);
		} finally {
			if (null != flosser) {
				Log.info(Log.FAC_TEST, "WrappedKeyTest: Stopping flosser.");
				flosser.stop();
				Log.info(Log.FAC_TEST, "WrappedKeyTest: flosser stopped.");
			}
			thandle.close();
			thandle2.close();
		}
		
		Log.info(Log.FAC_TEST, "Completed testWrappedKeyObject");
	}

}
