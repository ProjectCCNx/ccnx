/*
 * A CCNx library test.
 *
 * Copyright (C) 2008, 2009, 2011, 2012 Palo Alto Research Center, Inc.
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
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Security;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.impl.CCNFlowControl.SaveType;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.CCNOutputStream;
import org.ccnx.ccn.io.content.PublicKeyObject;
import org.ccnx.ccn.profiles.SegmentationProfile;
import org.ccnx.ccn.profiles.VersionMissingException;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.test.CCNTestHelper;
import org.ccnx.ccn.test.Flosser;
import org.ccnx.ccn.test.TestUtils;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Test reading and writing versioned, encoded PublicKeys to a repository. We have
 * separated out reading and writing El Gamal and ECC public keys, because BouncyCastle
 * doesn't support all algorithms out of the box on certain platforms. See
 * apps/extras/ExpandedCryptoTests for the full tests.
 */
public class PublicKeyObjectTestRepo {

	/**
	 * Handle naming for the test
	 */
	static CCNTestHelper testHelper = new CCNTestHelper(PublicKeyObjectTestRepo.class);

	public static KeyPair pair1 = null;
	public static KeyPair pair2 = null;
	public static KeyPair dsaPair = null;
	public static KeyPair dhPair = null;
	public static int NUM_ALGORITHMS = 3;
	public static ContentName [][] storedKeyNames = new ContentName[2][NUM_ALGORITHMS];
	public static ContentName namespace = null;

	static Flosser flosser = null;
	public static CCNHandle handle = null;

	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		handle = CCNHandle.open();
		Security.addProvider(new BouncyCastleProvider());
		// generate key pair
		KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
		kpg.initialize(1024); // go for fast
		pair1 = kpg.generateKeyPair();
		pair2 = kpg.generateKeyPair();

		KeyPairGenerator keyGen = KeyPairGenerator.getInstance("DSA");
        keyGen.initialize(1024);
        dsaPair = keyGen.genKeyPair();

        // Generate a 576-bit DH key pair
        keyGen = KeyPairGenerator.getInstance("DH");
        keyGen.initialize(576);
        dhPair = keyGen.genKeyPair();
	    namespace = new ContentName(testHelper.getClassNamespace(), "Users");
	    for (int i=0; i < storedKeyNames.length; ++i) {
			storedKeyNames[i][0] = new ContentName(namespace, "testRSAUser-" + i, "KEY");
			storedKeyNames[i][1] = new ContentName(namespace, "testDSAUser-" + i, "KEY");
			storedKeyNames[i][2] = new ContentName(namespace, "testDHUser-" + i, "KEY");
	    }
	}

	@AfterClass
	public static void cleanupAfterClass() {
		handle.close();
	}

	@Test
	public void testRawPublicKeyObject() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testRawPublicKeyObject");

		try {
			testRawKeyReadWrite(storedKeyNames[0][0], pair1.getPublic(), pair2.getPublic());
			testRawKeyReadWrite(storedKeyNames[0][1], dsaPair.getPublic(), null);
			testRawKeyReadWrite(storedKeyNames[0][2], dhPair.getPublic(), null);
		} finally {
			Log.info(Log.FAC_TEST, "PublicKeyObjectTestRepo: Stopping flosser.");
			flosser.stop();
			flosser = null;
			Log.info(Log.FAC_TEST, "PublicKeyObjectTestRepo: Flosser stopped.");
		}

		Log.info(Log.FAC_TEST, "Completed testRawPublicKeyObject");
	}

	@Test
	public void testRepoPublicKeyObject() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testRepoPublicKeyObject");

		testRepoKeyReadWrite(storedKeyNames[1][0], pair1.getPublic(), pair2.getPublic());
		testRepoKeyReadWrite(storedKeyNames[1][1], dsaPair.getPublic(), null);
		testRepoKeyReadWrite(storedKeyNames[1][2], dhPair.getPublic(), null);

		Log.info(Log.FAC_TEST, "Completed testRepoPublicKeyObject");
	}

	@Test
	public void testUnversionedPublicKeyObject() throws Exception {
		// we might want to use a PKO to read an object written without a version.
		ContentName unversionedName = new ContentName(testHelper.getTestNamespace("testUnversionedPublicKeyObject"), "unversionedKey");
		if (null == flosser) {
			flosser = new Flosser();
		}
		flosser.handleNamespace(unversionedName);

		CCNOutputStream writeStream = new CCNOutputStream(unversionedName, handle);
		writeStream.write(pair1.getPublic().getEncoded());
		writeStream.close();
		Log.info(Log.FAC_TEST, "Saved unversioned key to name {0}, now trying to read.", unversionedName);

		CCNHandle otherHandle = CCNHandle.open();
		ContentObject firstSegment = SegmentationProfile.getSegment(unversionedName, null, null,
									SystemConfiguration.getDefaultTimeout(), null, otherHandle);
		if (null == firstSegment) {
			Log.warning(Log.FAC_TEST, "Cannot retrieve segment of stream {0}", unversionedName);
			Assert.fail("Cannot retrieve first segment: " + unversionedName);
		}

		PublicKeyObject testObject = new PublicKeyObject(firstSegment, CCNHandle.open());
		Log.info(Log.FAC_TEST, "testObject available? " + testObject.available());
		otherHandle.close();
		testObject.close();

		Log.info(Log.FAC_TEST, "Completed testRepoPublicKeyObject");
	}

	public void testRawKeyReadWrite(ContentName keyName, PublicKey key, PublicKey optional2ndKey) throws ConfigurationException, IOException, VersionMissingException {


		Log.info(Log.FAC_TEST, "Reading and writing raw key " + keyName + " key 1: " + key.getAlgorithm() + " key 2: " + ((null == optional2ndKey) ? "null" : optional2ndKey.getAlgorithm()));
		if (null == flosser) {
			flosser = new Flosser();
		}
		flosser.handleNamespace(keyName);
		PublicKeyObject pko = new PublicKeyObject(keyName, key, SaveType.RAW, handle);
		pko.save();

		Log.info(Log.FAC_TEST, "Saved " + pko.getVersionedName() + ", now trying to read.");
		Assert.assertTrue(VersioningProfile.hasTerminalVersion(pko.getVersionedName()));
		// should update in another thread
		PublicKeyObject pkoread = new PublicKeyObject(keyName, null); // new handle
		Assert.assertTrue(pkoread.available());
		Assert.assertEquals(pkoread.getVersionedName(), pko.getVersionedName());
		Assert.assertTrue(pkoread.equalsKey(pko));
		if (null != optional2ndKey) {
			pkoread.setupSave(SaveType.RAW);
			Log.info(Log.FAC_TEST, "Reading and writing second raw key " + keyName + " key 1: " + key.getAlgorithm() + " key 2: " + ((null == optional2ndKey) ? "null" : optional2ndKey.getAlgorithm()));
			pkoread.save(optional2ndKey);
			Assert.assertTrue(VersioningProfile.isLaterVersionOf(pkoread.getVersionedName(), pko.getVersionedName()));
			pko.update();
			Assert.assertEquals(pkoread.getVersionedName(), pko.getVersionedName());
			Assert.assertTrue(pkoread.equalsKey(pko));
			Assert.assertTrue(pko.equalsKey(optional2ndKey));
		}
		Log.info(Log.FAC_TEST, "Finished reading and writing raw key " + keyName + " key 1: " + key.getAlgorithm() + " key 2: " + ((null == optional2ndKey) ? "null" : optional2ndKey.getAlgorithm()));

	}

	public void testRepoKeyReadWrite(ContentName keyName, PublicKey key, PublicKey optional2ndKey) throws ConfigurationException, IOException, VersionMissingException {

		Log.info(Log.FAC_TEST, "Reading and writing key to repo " + keyName + " key 1: " + key.getAlgorithm() + " key 2: " + ((null == optional2ndKey) ? "null" : optional2ndKey.getAlgorithm()));
		PublicKeyObject pko = new PublicKeyObject(keyName, key, SaveType.REPOSITORY, handle);
		pko.save();
		TestUtils.checkObject(handle, pko);
		Assert.assertTrue(VersioningProfile.hasTerminalVersion(pko.getVersionedName()));
		Log.info(Log.FAC_TEST, "Saved " + pko.getVersionedName() + " to repo, now trying to read.");
		// should update in another thread

		PublicKeyObject pkoread = new PublicKeyObject(keyName, null); // new handle
		Assert.assertTrue(pkoread.available());
		Assert.assertEquals(pkoread.getVersionedName(), pko.getVersionedName());
		Assert.assertTrue(pkoread.equalsKey(pko));

		if (null != optional2ndKey) {
			pkoread.setupSave(SaveType.REPOSITORY);
			Log.info(Log.FAC_TEST, "Reading and writing second key to repo " + keyName + " key 1: " + key.getAlgorithm() + " key 2: " + ((null == optional2ndKey) ? "null" : optional2ndKey.getAlgorithm()));
			pkoread.save(optional2ndKey);
			Assert.assertTrue(VersioningProfile.isLaterVersionOf(pkoread.getVersionedName(), pko.getVersionedName()));
			pko.update();
			Assert.assertEquals(pkoread.getVersionedName(), pko.getVersionedName());
			Assert.assertTrue(pkoread.equalsKey(pko));
			Assert.assertTrue(pko.equalsKey(optional2ndKey));
		}
		Log.info(Log.FAC_TEST, "Finished reading and writing key to repo " + keyName + " key 1: " + key.getAlgorithm() + " key 2: " + ((null == optional2ndKey) ? "null" : optional2ndKey.getAlgorithm()));
	}
}
