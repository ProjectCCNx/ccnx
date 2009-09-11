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

package org.ccnx.ccn.test.io.content;


import java.io.IOException;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Security;
import java.util.Random;
import java.util.logging.Level;

import javax.xml.stream.XMLStreamException;

import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jce.spec.ECParameterSpec;
import org.bouncycastle.jce.spec.ElGamalParameterSpec;
import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.PublicKeyObject;
import org.ccnx.ccn.profiles.VersionMissingException;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.test.Flosser;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;




public class PublicKeyObjectTestRepo {

	public static KeyPair pair1 = null;
	public static KeyPair pair2 = null;
	public static KeyPair egPair = null;
	public static KeyPair eccPair = null;
	public static KeyPair eciesPair = null;
	public static ContentName [][] storedKeyNames = new ContentName[2][3];
	public static ContentName namespace = null;
	
	static Level oldLevel;
	
	static Flosser flosser = null;
	public static CCNHandle library = null;
	
	@AfterClass
	public static void tearDownAfterClass() throws Exception {
		Log.setLevel(oldLevel);
	}

	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		library = CCNHandle.open();
		oldLevel = Log.getLevel();
		Log.setLevel(Level.FINEST);
		Security.addProvider(new BouncyCastleProvider());
		// generate key pair
		KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
		kpg.initialize(1024); // go for fast
		pair1 = kpg.generateKeyPair();
		pair2 = kpg.generateKeyPair();
		ElGamalParameterSpec egp = new ElGamalParameterSpec(
				new BigInteger(1, WrappedKeyTest.pbytes), new BigInteger(1, WrappedKeyTest.gbytes));
		KeyPairGenerator ekpg = KeyPairGenerator.getInstance("ElGamal");
		ekpg.initialize(egp); // go for fast
		egPair = ekpg.generateKeyPair();
		KeyPairGenerator eckpg = KeyPairGenerator.getInstance("EC", "BC");
		ECParameterSpec ecSpec = ECNamedCurveTable.getParameterSpec("P-384");
		eckpg.initialize(ecSpec);
		eccPair = eckpg.generateKeyPair();
		
		KeyPairGenerator g = KeyPairGenerator.getInstance("ECIES", "BC");
	    g.initialize(192);
	    eciesPair = g.generateKeyPair();
	     
	    namespace = ContentName.fromNative("/parc/Users");
	    for (int i=0; i < storedKeyNames.length; ++i) {
		    int randomTrial = new Random().nextInt(10000);
			storedKeyNames[i][0] = ContentName.fromNative(namespace, "testRSAUser-" + Integer.toString(randomTrial), "KEY");
			storedKeyNames[i][1] = ContentName.fromNative(namespace, "testEGUser-" + Integer.toString(randomTrial), "KEY");
			storedKeyNames[i][2] = ContentName.fromNative(namespace, "testECCUser-" + Integer.toString(randomTrial), "KEY");		    
	    }
	}

	@Test
	public void testRawPublicKeyObject() throws Exception {
		
		try {
			testRawKeyReadWrite(storedKeyNames[0][0], pair1.getPublic(), pair2.getPublic());
			testRawKeyReadWrite(storedKeyNames[0][1], egPair.getPublic(), null);
			testRawKeyReadWrite(storedKeyNames[0][2], eccPair.getPublic(), eciesPair.getPublic());
		} finally {
			System.out.println("Stopping flosser.");
			flosser.stop();
			flosser = null;
		}
	}

	@Test
	public void testRepoPublicKeyObject() throws Exception {

		testRepoKeyReadWrite(storedKeyNames[1][0], pair1.getPublic(), pair2.getPublic());
		testRepoKeyReadWrite(storedKeyNames[1][1], egPair.getPublic(), null);
		testRepoKeyReadWrite(storedKeyNames[1][2], eccPair.getPublic(), eciesPair.getPublic());
	}

	public void testRawKeyReadWrite(ContentName keyName, PublicKey key, PublicKey optional2ndKey) throws ConfigurationException, IOException, XMLStreamException, VersionMissingException {
		

		System.out.println("Reading and writing key " + keyName + " key 1: " + key.getAlgorithm() + " key 2: " + ((null == optional2ndKey) ? "null" : optional2ndKey.getAlgorithm()));
		if (null == flosser) {
			flosser = new Flosser();
		} 
		flosser.handleNamespace(keyName);
		PublicKeyObject pko = new PublicKeyObject(keyName, key, library);
		pko.save();
		Assert.assertTrue(VersioningProfile.hasTerminalVersion(pko.getVersionedName()));
		// should update in another thread
		PublicKeyObject pkoread = new PublicKeyObject(keyName, null); // new library
		Assert.assertTrue(pkoread.available());
		Assert.assertEquals(pkoread.getVersionedName(), pko.getVersionedName());
		if (!pkoread.publicKey().equals(pko.publicKey())) {
			Log.info("Mismatched public keys, chance provider doesn't implement equals()." );
			Assert.assertArrayEquals(pkoread.publicKey().getEncoded(), pko.publicKey().getEncoded());
		} else {
			Assert.assertEquals(pkoread.publicKey(), pko.publicKey());
		}
		if (null != optional2ndKey) {
			// if we save on pkoread and attempt to update on pko, the interests don't
			// get delivered and we end up on a wait for put drain, even though there
			// is a perfectly good matching interest coming from the flosser -- it somehow
			// only makes it to the object that doesn't have data.
			pkoread.save(optional2ndKey);
			Assert.assertTrue(VersioningProfile.isLaterVersionOf(pkoread.getVersionedName(), pko.getVersionedName()));
			pko.update();
			Assert.assertEquals(pkoread.getVersionedName(), pko.getVersionedName());
			Assert.assertEquals(pkoread.publicKey(), pko.publicKey());
			Assert.assertEquals(pko.publicKey(), optional2ndKey);
		}
	}

	public void testRepoKeyReadWrite(ContentName keyName, PublicKey key, PublicKey optional2ndKey) throws ConfigurationException, IOException, XMLStreamException, VersionMissingException {
		

		System.out.println("Reading and writing key " + keyName + " key 1: " + key.getAlgorithm() + " key 2: " + ((null == optional2ndKey) ? "null" : optional2ndKey.getAlgorithm()));
		PublicKeyObject pko = new PublicKeyObject(keyName, key, library);
		pko.saveToRepository();
		Assert.assertTrue(VersioningProfile.hasTerminalVersion(pko.getVersionedName()));
		Log.info("Saved " + pko.getVersionedName() + " to repo, now trying to read.");
		// should update in another thread

		PublicKeyObject pkoread = new PublicKeyObject(keyName, null); // new library
		Assert.assertTrue(pkoread.available());
		Assert.assertEquals(pkoread.getVersionedName(), pko.getVersionedName());
		if (!pkoread.publicKey().equals(pko.publicKey())) {
			Log.info("Mismatched public keys, chance provider doesn't implement equals()." );
			Assert.assertArrayEquals(pkoread.publicKey().getEncoded(), pko.publicKey().getEncoded());
		} else {
			Assert.assertEquals(pkoread.publicKey(), pko.publicKey());
		}
		if (null != optional2ndKey) {
			// if we save on pkoread and attempt to update on pko, the interests don't
			// get delivered and we end up on a wait for put drain, even though there
			// is a perfectly good matching interest coming from the flosser -- it somehow
			// only makes it to the object that doesn't have data.
			// TODO DKS FIX
			pkoread.saveToRepository(optional2ndKey);
			Assert.assertTrue(VersioningProfile.isLaterVersionOf(pkoread.getVersionedName(), pko.getVersionedName()));
			pko.update();
			Assert.assertEquals(pkoread.getVersionedName(), pko.getVersionedName());
			Assert.assertEquals(pkoread.publicKey(), pko.publicKey());
			Assert.assertEquals(pko.publicKey(), optional2ndKey);
		}
	}
}
