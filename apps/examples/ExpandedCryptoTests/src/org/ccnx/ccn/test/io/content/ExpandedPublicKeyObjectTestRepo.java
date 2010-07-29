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

package org.ccnx.ccn.test.io.content;


import java.io.IOException;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Security;
import java.util.logging.Level;

import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jce.spec.ECParameterSpec;
import org.bouncycastle.jce.spec.ElGamalParameterSpec;
import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.KeyManager;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.impl.CCNFlowControl.SaveType;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.PublicKeyObject;
import org.ccnx.ccn.profiles.VersionMissingException;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.test.CCNTestHelper;
import org.ccnx.ccn.test.Flosser;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Test reading and writing versioned, encoded PublicKeys to a repository.
 */
public class ExpandedPublicKeyObjectTestRepo {

	public static byte [] gbytes = new byte[]{(byte)0x0, (byte)0xc6, (byte)0x89, (byte)0xde, 
		   (byte)0x62, (byte)0x40, (byte)0x38, (byte)0x5f, 
		   (byte)0xc9, (byte)0x8c, (byte)0xf4, (byte)0x97, 
		   (byte)0xd8, (byte)0x4b, (byte)0x8e, (byte)0xe1, 
		   (byte)0xaf, (byte)0x77, (byte)0x4a, (byte)0xae, 
		   (byte)0x31, (byte)0xfa, (byte)0x23, (byte)0x1d, 
		   (byte)0x63, (byte)0x77, (byte)0xed, (byte)0xb4, 
		   (byte)0x78, (byte)0x84, (byte)0x8e, (byte)0xb5, 
		   (byte)0x5a, (byte)0xf5, (byte)0xf6, (byte)0xa8, 
		   (byte)0x29, (byte)0xaf, (byte)0xc7, (byte)0x8, 
		   (byte)0xba, (byte)0x20, (byte)0x73, (byte)0xc, 
		   (byte)0xe7, (byte)0xcd, (byte)0xc5, (byte)0x4d, 
		   (byte)0x73, (byte)0x20, (byte)0xa0, (byte)0xa9, 
		   (byte)0xbd, (byte)0x8a, (byte)0x4e, (byte)0x77, 
		   (byte)0x2c, (byte)0xc5, (byte)0x36, (byte)0x87, 
		   (byte)0xe4, (byte)0x62, (byte)0xb8, (byte)0x77, 
		   (byte)0xe9, (byte)0xbf, (byte)0xd3, (byte)0xac, 
		   (byte)0xb8, (byte)0xf0, (byte)0xde, (byte)0xd8, 
		   (byte)0x31, (byte)0x14, (byte)0x57, (byte)0x91, 
		   (byte)0xb1, (byte)0x35, (byte)0x4c, (byte)0x60, 
		   (byte)0xd4, (byte)0x57, (byte)0xf7, (byte)0x29, 
		   (byte)0x64, (byte)0x20, (byte)0x39, (byte)0xef, 
		   (byte)0xdc, (byte)0x41, (byte)0xe9, (byte)0xc, (byte)0xc1, 
		   (byte)0x6a, (byte)0xfc, (byte)0x52, (byte)0x86, (byte)0x75, 
		   (byte)0xfd, (byte)0x72, (byte)0xff, (byte)0xf0, (byte)0x23, 
		   (byte)0xba, (byte)0xba, (byte)0xb0, (byte)0xa6, (byte)0x91, 
		   (byte)0x8c, (byte)0xdc, (byte)0x8e, (byte)0x2b, (byte)0x8a, 
		   (byte)0x32, (byte)0x96, (byte)0x67, (byte)0xff, (byte)0x97, 
		   (byte)0x63, (byte)0xb6, (byte)0x7d, (byte)0x3e, (byte)0xea, 
		   (byte)0xd3, (byte)0x1b, (byte)0x62, (byte)0xf2, (byte)0x53, 
		   (byte)0xf2};
	public static byte [] pbytes = new byte[]{(byte)0x0, (byte)0xd8, (byte)0x2d, (byte)0xee, 
		(byte)0x67, (byte)0xd8, (byte)0x76, (byte)0x4a, (byte)0xd1, (byte)0x5a, (byte)0x3b, 
		(byte)0xbf, (byte)0xae, (byte)0x3f, (byte)0x39, (byte)0x16, (byte)0xbc, (byte)0x3b, 
		(byte)0xfe, (byte)0x23, (byte)0xf0, (byte)0x3, (byte)0x3, (byte)0x41, (byte)0xda, 
		(byte)0x70, (byte)0x9a, (byte)0x1c, (byte)0xeb, (byte)0xe2, (byte)0x5a, (byte)0x7f, 
		(byte)0x3, (byte)0x46, (byte)0x42, (byte)0x13, (byte)0x8a, (byte)0x37, (byte)0xc0, 
		(byte)0xfe, (byte)0xa1, (byte)0xd7, (byte)0xe6, (byte)0xa4, (byte)0x24, (byte)0x59, 
		(byte)0x2b, (byte)0x15, (byte)0xf9, (byte)0x86, (byte)0x25, (byte)0x35, (byte)0x4e, 
		(byte)0x85, (byte)0x88, (byte)0xaf, (byte)0x76, (byte)0xe6, (byte)0xbd, (byte)0xb9, 
		(byte)0x45, (byte)0xb4, (byte)0x0, (byte)0xe2, (byte)0x8a, (byte)0x3c, (byte)0xb6, 
		(byte)0x9f, (byte)0xa9, (byte)0xa8, (byte)0xbc, (byte)0x24, (byte)0xd2, (byte)0x1b, 
		(byte)0x40, (byte)0xdb, (byte)0xa4, (byte)0x88, (byte)0x10, (byte)0xa8, (byte)0xcd, 
		(byte)0xe7, (byte)0x4f, (byte)0x38, (byte)0xd8, (byte)0x9, (byte)0xa1, (byte)0x8a, 
		(byte)0xf7, (byte)0x5a, (byte)0xf2, (byte)0xd0, (byte)0xec, (byte)0x53, (byte)0x61, 
		(byte)0xbb, (byte)0xb2, (byte)0x4a, (byte)0x93, (byte)0x92, (byte)0x13, (byte)0x2c, 
		(byte)0x8f, (byte)0xed, (byte)0x9f, (byte)0xf1, (byte)0xe5, (byte)0x80, (byte)0x55, 
		(byte)0x10, (byte)0xe8, (byte)0x47, (byte)0xea, (byte)0xad, (byte)0x26, (byte)0x8c, 
		(byte)0x6e, (byte)0x2, (byte)0x8d, (byte)0x31, (byte)0xee, (byte)0xfd, (byte)0x28, 
		(byte)0xc5, (byte)0xd5, (byte)0x74, (byte)0xe9, (byte)0x7e, (byte)0x33};

/**
	 * Handle naming for the test
	 */
	static CCNTestHelper testHelper = new CCNTestHelper(ExpandedPublicKeyObjectTestRepo.class);

	public static KeyPair pair1 = null;
	public static KeyPair pair2 = null;
	public static KeyPair egPair = null;
	public static KeyPair eccPair = null;
	public static KeyPair eciesPair = null;
	public static int NUM_ALGORITHMS = 3;
	public static ContentName [][] storedKeyNames = new ContentName[2][NUM_ALGORITHMS];
	public static ContentName namespace = null;
	
	static Level oldLevel;
	
	static Flosser flosser = null;
	public static CCNHandle handle = null;
	
	@AfterClass
	public static void tearDownAfterClass() throws Exception {
		Log.setDefaultLevel(oldLevel);
	}

	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		handle = CCNHandle.open();
		oldLevel = Log.getLevel();
		Log.setDefaultLevel(Level.FINEST);
		Security.addProvider(new BouncyCastleProvider());
		// generate key pair
		KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
		kpg.initialize(1024); // go for fast
		pair1 = kpg.generateKeyPair();
		pair2 = kpg.generateKeyPair();
		ElGamalParameterSpec egp = new ElGamalParameterSpec(
				new BigInteger(1, pbytes), new BigInteger(1, gbytes));
		KeyPairGenerator ekpg = KeyPairGenerator.getInstance("ElGamal", KeyManager.getDefaultProvider());
		ekpg.initialize(egp); // go for fast
		egPair = ekpg.generateKeyPair();
		KeyPairGenerator eckpg = KeyPairGenerator.getInstance("EC", KeyManager.getDefaultProvider());
		ECParameterSpec ecSpec = ECNamedCurveTable.getParameterSpec("P-384");
		eckpg.initialize(ecSpec);
		eccPair = eckpg.generateKeyPair();
		
		KeyPairGenerator g = KeyPairGenerator.getInstance("ECIES", KeyManager.getDefaultProvider());
	    g.initialize(192);
	    eciesPair = g.generateKeyPair();
	     
	    namespace = ContentName.fromNative(testHelper.getClassNamespace(), "Users");
	    for (int i=0; i < storedKeyNames.length; ++i) {
			storedKeyNames[i][0] = ContentName.fromNative(namespace, "testRSAUser-" + i, "KEY");
			storedKeyNames[i][1] = ContentName.fromNative(namespace, "testEGUser-" + i, "KEY");
			storedKeyNames[i][2] = ContentName.fromNative(namespace, "testECCUser-" + i, "KEY");		    
	    }
	}

	@Test
	public void testRawPublicKeyObject() throws Exception {
		
		try {
			testRawKeyReadWrite(storedKeyNames[0][0], pair1.getPublic(), pair2.getPublic());
			testRawKeyReadWrite(storedKeyNames[0][1], egPair.getPublic(), null);
			testRawKeyReadWrite(storedKeyNames[0][2], eccPair.getPublic(), eciesPair.getPublic());
		} finally {
			Log.info("PublicKeyObjectTestRepo: Stopping flosser.");
			flosser.stop();
			flosser = null;
			Log.info("PublicKeyObjectTestRepo: Flosser stopped.");
		}
	}

	@Test
	public void testRepoPublicKeyObject() throws Exception {

		testRepoKeyReadWrite(storedKeyNames[1][0], pair1.getPublic(), pair2.getPublic());
		testRepoKeyReadWrite(storedKeyNames[1][1], egPair.getPublic(), null);
		testRepoKeyReadWrite(storedKeyNames[1][2], eccPair.getPublic(), eciesPair.getPublic());
	}

	public void testRawKeyReadWrite(ContentName keyName, PublicKey key, PublicKey optional2ndKey) throws ConfigurationException, IOException, VersionMissingException {
		

		Log.info("Reading and writing raw key " + keyName + " key 1: " + key.getAlgorithm() + " key 2: " + ((null == optional2ndKey) ? "null" : optional2ndKey.getAlgorithm()));
		if (null == flosser) {
			flosser = new Flosser();
		} 
		PublicKeyObject pko = new PublicKeyObject(keyName, key, SaveType.RAW, handle);
		flosser.handleNamespace(keyName);
		pko.save();
		Log.info("Saved " + pko.getVersionedName() + ", now trying to read.");
		Assert.assertTrue(VersioningProfile.hasTerminalVersion(pko.getVersionedName()));
		// should update in another thread
		PublicKeyObject pkoread = new PublicKeyObject(keyName, null); // new handle
		Assert.assertTrue(pkoread.available());
		Assert.assertEquals(pkoread.getVersionedName(), pko.getVersionedName());
		if (!pkoread.publicKey().equals(pko.publicKey())) {
			Log.info("Mismatched public keys, chance provider doesn't implement equals()." );
			Assert.assertArrayEquals(pkoread.publicKey().getEncoded(), pko.publicKey().getEncoded());
		} else {
			Assert.assertEquals(pkoread.publicKey(), pko.publicKey());
		}
		if (null != optional2ndKey) {
			Log.info("Reading and writing second raw key " + keyName + " key 1: " + key.getAlgorithm() + " key 2: " + ((null == optional2ndKey) ? "null" : optional2ndKey.getAlgorithm()));
			pkoread.setupSave(SaveType.RAW);
			pkoread.save(optional2ndKey);
			Assert.assertTrue(VersioningProfile.isLaterVersionOf(pkoread.getVersionedName(), pko.getVersionedName()));
			pko.update();
			Assert.assertEquals(pkoread.getVersionedName(), pko.getVersionedName());
			if (!pkoread.publicKey().equals(pko.publicKey())) {
				Log.info("Mismatched public keys, chance provider doesn't implement equals()." );
				Assert.assertArrayEquals(pkoread.publicKey().getEncoded(), pko.publicKey().getEncoded());
			} else {
				Assert.assertEquals(pkoread.publicKey(), pko.publicKey());
			}
			if (!optional2ndKey.equals(pko.publicKey())) {
				Log.info("Mismatched public keys, chance provider doesn't implement equals()." );
				Assert.assertArrayEquals(optional2ndKey.getEncoded(), pko.publicKey().getEncoded());
			} else {
				Assert.assertEquals(optional2ndKey, pko.publicKey());
			}
		}
		Log.info("Finished reading and writing raw key " + keyName + " key 1: " + key.getAlgorithm() + " key 2: " + ((null == optional2ndKey) ? "null" : optional2ndKey.getAlgorithm()));

	}

	public void testRepoKeyReadWrite(ContentName keyName, PublicKey key, PublicKey optional2ndKey) throws ConfigurationException, IOException, VersionMissingException {

		Log.info("Reading and writing key to repo " + keyName + " key 1: " + key.getAlgorithm() + " key 2: " + ((null == optional2ndKey) ? "null" : optional2ndKey.getAlgorithm()));
		PublicKeyObject pko = new PublicKeyObject(keyName, key, SaveType.REPOSITORY, handle);
		pko.save();
		Assert.assertTrue(VersioningProfile.hasTerminalVersion(pko.getVersionedName()));
		Log.info("Saved " + pko.getVersionedName() + " to repo, now trying to read.");
		// should update in another thread

		PublicKeyObject pkoread = new PublicKeyObject(keyName, null); // new handle
		Assert.assertTrue(pkoread.available());
		Assert.assertEquals(pkoread.getVersionedName(), pko.getVersionedName());
		if (!pkoread.publicKey().equals(pko.publicKey())) {
			Log.info("Mismatched public keys, chance provider doesn't implement equals()." );
			Assert.assertArrayEquals(pkoread.publicKey().getEncoded(), pko.publicKey().getEncoded());
		} else {
			Assert.assertEquals(pkoread.publicKey(), pko.publicKey());
		}
		if (null != optional2ndKey) {
			Log.info("Reading and writing second key to repo " + keyName + " key 1: " + key.getAlgorithm() + " key 2: " + ((null == optional2ndKey) ? "null" : optional2ndKey.getAlgorithm()));
			pkoread.setupSave(SaveType.REPOSITORY);
			pkoread.save(optional2ndKey);
			Assert.assertTrue(VersioningProfile.isLaterVersionOf(pkoread.getVersionedName(), pko.getVersionedName()));
			pko.update();
			Assert.assertEquals(pkoread.getVersionedName(), pko.getVersionedName());
			if (!pkoread.publicKey().equals(pko.publicKey())) {
				Log.info("Mismatched public keys, chance provider doesn't implement equals()." );
				Assert.assertArrayEquals(pkoread.publicKey().getEncoded(), pko.publicKey().getEncoded());
			} else {
				Assert.assertEquals(pkoread.publicKey(), pko.publicKey());
			}
			if (!optional2ndKey.equals(pko.publicKey())) {
				Log.info("Mismatched public keys, chance provider doesn't implement equals()." );
				Assert.assertArrayEquals(optional2ndKey.getEncoded(), pko.publicKey().getEncoded());
			} else {
				Assert.assertEquals(optional2ndKey, pko.publicKey());
			}
		}
		Log.info("Finished reading and writing key to repo " + keyName + " key 1: " + key.getAlgorithm() + " key 2: " + ((null == optional2ndKey) ? "null" : optional2ndKey.getAlgorithm()));
	}
}
