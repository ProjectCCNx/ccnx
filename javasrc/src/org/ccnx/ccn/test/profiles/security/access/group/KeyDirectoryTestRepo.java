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

package org.ccnx.ccn.test.profiles.security.access.group;


import java.io.IOException;
import java.security.Key;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Random;
import java.util.TreeSet;

import javax.crypto.KeyGenerator;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.KeyManager;
import org.ccnx.ccn.impl.security.crypto.CCNDigestHelper;
import org.ccnx.ccn.impl.support.ByteArrayCompare;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.Link;
import org.ccnx.ccn.io.content.WrappedKey;
import org.ccnx.ccn.io.content.WrappedKey.WrappedKeyObject;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.profiles.security.access.group.Group;
import org.ccnx.ccn.profiles.security.access.group.GroupAccessControlManager;
import org.ccnx.ccn.profiles.security.access.group.GroupAccessControlProfile;
import org.ccnx.ccn.profiles.security.access.group.PrincipalKeyDirectory;
import org.ccnx.ccn.protocol.ContentName;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;


public class KeyDirectoryTestRepo {
	
	static Random rand = new Random();
	static final String directoryBase = "/test";
	static final String keyDirectoryBase = "/test/KeyDirectoryTestRepo-";
	static ContentName keyDirectoryName;
	static ContentName userStore;
	static String principalName = "pgolle-";
	static ContentName publicKeyName;
	static ContentName versionedDirectoryName;
	
	static PrivateKey wrappedPrivateKey; 
	static Key AESSecretKey;
	static KeyPair wrappingKeyPair;
	static byte[] wrappingPKID;
	static GroupAccessControlManager acm;
	
	class TestPrincipalKeyDirectory extends PrincipalKeyDirectory {
		public TestPrincipalKeyDirectory() throws IOException {
			super(acm, versionedDirectoryName, handle);
		}
		
		/*	
		 * Retrieve the wrapped key from the KD by principalName.
		 * As above, the key is unwrapped and we check that the result is as expected.
		 * 
		 */
		public void testGetWrappedKeyForPrincipal() throws Exception {		
			// unwrap the key and check that the unwrapped secret key is correct
			WrappedKeyObject wko = kd.getWrappedKeyForPrincipal(principalName);
			Assert.assertNotNull(wko);
			WrappedKey wk = wko.wrappedKey();
			Key unwrappedSecretKey = wk.unwrapKey(wrappingKeyPair.getPrivate());
			Assert.assertEquals(AESSecretKey, unwrappedSecretKey);
		}
	}
	static TestPrincipalKeyDirectory kd;
	
	static int testCount = 0;
	
	static CCNHandle handle;

	
	@AfterClass
	public static void tearDownAfterClass() throws Exception {
		kd.stopEnumerating();
		KeyManager.closeDefaultKeyManager();
	}
	
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		// randomize names to minimize stateful effects of ccnd/repo caches.
		keyDirectoryName = ContentName.fromNative(keyDirectoryBase + Integer.toString(rand.nextInt(10000)));
		principalName = principalName + Integer.toString(rand.nextInt(10000));
		handle = CCNHandle.getHandle();
		
		ContentName cnDirectoryBase = ContentName.fromNative(directoryBase);
		ContentName groupStore = GroupAccessControlProfile.groupNamespaceName(cnDirectoryBase);
		userStore = new ContentName(cnDirectoryBase, "Users");
		acm = new GroupAccessControlManager(cnDirectoryBase, groupStore, userStore);
		versionedDirectoryName = VersioningProfile.addVersion(keyDirectoryName);
	}
	
	/**
	 * Ensures that the tests run in the correct order.
	 * @throws Exception
	 */
	@Test
	public void testInOrder() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testInOrder");

		testKeyDirectoryCreation();
		testAddPrivateKey();
		testGetUnwrappedKeyGroupMember();
		testAddWrappedKey();
		addWrappingKeyToACM();
		testGetWrappedKeyForKeyID();
		kd.testGetWrappedKeyForPrincipal();
		testGetUnwrappedKey();
		testGetPrivateKey();
		testGetUnwrappedKeySuperseded();
		testAddPreviousKeyBlock();
		
		Log.info(Log.FAC_TEST, "Completed testInOrder");
	}
	
	/*	
	 * Create a new versioned KeyDirectory
	 */
	public void testKeyDirectoryCreation() throws Exception {

		kd = new TestPrincipalKeyDirectory();
		// verify that the keyDirectory is created
		Assert.assertNotNull(kd);		
	}
	
	public void testAddPrivateKey() throws Exception {
		// generate a private key to wrap
		KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
		kpg.initialize(1024);
		wrappingKeyPair = kpg.generateKeyPair();
		wrappedPrivateKey = wrappingKeyPair.getPrivate();
	 
		// generate a AES wrapping key
		KeyGenerator kg = KeyGenerator.getInstance("AES");
		AESSecretKey = kg.generateKey();
		// add private key block
		kd.addPrivateKeyBlock(wrappedPrivateKey, AESSecretKey);
		kd.waitForChildren(); // this was the first add; need to wait till we have any data, hopefully NE responder will fast path
		Assert.assertTrue(kd.hasPrivateKeyBlock());
	}
	
	/*
	 * Unwrap the private key via membership in a group
	 */
	public void testGetUnwrappedKeyGroupMember() throws Exception {
		ContentName myIdentity = new ContentName(userStore, "pgolle");
		acm.publishMyIdentity(myIdentity, null);		
				
		// add myself to a newly created group				
		String randomGroupName = "testGroup" + rand.nextInt(10000);
		ArrayList<Link> newMembers = new ArrayList<Link>();
		newMembers.add(new Link(myIdentity));
		Group myGroup = acm.groupManager().createGroup(randomGroupName, newMembers, 0);
		Assert.assertTrue(acm.groupManager().haveKnownGroupMemberships());

		Thread.sleep(5000); // FIXME: this delay is necessary for the repo-write to complete
		// it should not be needed, as the data should be available locally.

		PrincipalKeyDirectory pkd = myGroup.privateKeyDirectory(acm);
		pkd.waitForChildren();
		Assert.assertTrue(pkd.hasPrivateKeyBlock());
		
		// add to the KeyDirectory the secret key wrapped in the public key
		ContentName versionDirectoryName2 = VersioningProfile.addVersion(
				ContentName.fromNative(keyDirectoryBase + Integer.toString(rand.nextInt(10000)) ));
		PrincipalKeyDirectory kd2 = new PrincipalKeyDirectory(acm, versionDirectoryName2, handle);
		PublicKey groupPublicKey = myGroup.publicKey();
		ContentName groupPublicKeyName = myGroup.publicKeyName();
		kd2.addWrappedKeyBlock(AESSecretKey, groupPublicKeyName, groupPublicKey);		
		
		// retrieve the secret key
		byte[] expectedKeyID = CCNDigestHelper.digest(AESSecretKey.getEncoded());
		kd2.waitForChildren();
		Thread.sleep(10000);
		Key unwrappedSecretKey = kd2.getUnwrappedKey(expectedKeyID);
		Assert.assertEquals(AESSecretKey, unwrappedSecretKey);
		kd2.stopEnumerating();
	}

	
	/*	
	 * Wraps the AES key in an RSA wrapping key
	 * and adds the wrapped key to the KeyDirectory
	 */
	public void testAddWrappedKey() throws Exception {
		// generate a public key to wrap
		KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
		kpg.initialize(1024);
		wrappingKeyPair = kpg.generateKeyPair();
		PublicKey publicKey = wrappingKeyPair.getPublic();
		wrappingPKID = CCNDigestHelper.digest(publicKey.getEncoded());
		publicKeyName = new ContentName(userStore, principalName);
		ContentName versionPublicKeyName = VersioningProfile.addVersion(publicKeyName);
		
		// add to the KeyDirectory the secret key wrapped in the public key
		kd.addWrappedKeyBlock(AESSecretKey, versionPublicKeyName, publicKey);	
	}
	
	/*
	 * Adds the wrapping key to the AccessControlManager.
	 */
	public void addWrappingKeyToACM() throws Exception {
		PrivateKey privKey = wrappingKeyPair.getPrivate();
		byte[] publicKeyIdentifier = CCNDigestHelper.digest(wrappingKeyPair.getPublic().getEncoded());
		handle.keyManager().getSecureKeyCache().addMySigningKey(publicKeyIdentifier, privKey);
	}
	
	
	/*	
	 * Create a new (unversioned) KeyDirectory object with the same	
	 * directory name as above. Check that the constructor retrieves the latest
	 * version of the KeyDirectory.
	 * Retrieve the wrapped key from the KeyDirectory by its KeyID,
	 * unwrap it, and check that the secret key retrieved is as expected.
	 * 
	 */
	public void testGetWrappedKeyForKeyID() throws Exception {
		CCNHandle handle = CCNHandle.open();
		// Use unversioned constructor so KeyDirectory returns the latest version
		PrincipalKeyDirectory uvkd = new PrincipalKeyDirectory(acm, keyDirectoryName, handle);
		while (!uvkd.hasChildren() || uvkd.getCopyOfWrappingKeyIDs().size() == 0) {
			uvkd.waitForNewChildren();
		}
		// check the ID of the wrapping key
		TreeSet<byte[]> wkid = uvkd.getCopyOfWrappingKeyIDs();
		
		Assert.assertEquals(1, wkid.size());
		Comparator<byte[]> byteArrayComparator = new ByteArrayCompare();
		Assert.assertEquals(0, byteArrayComparator.compare(wkid.first(), wrappingPKID));

		// check name
		ContentName wkName = uvkd.getWrappedKeyNameForKeyID(wrappingPKID);
		Assert.assertNotNull(wkName);
		
		// unwrap the key and check that the unwrapped secret key is correct
		WrappedKeyObject wko = uvkd.getWrappedKeyForKeyID(wrappingPKID);
		WrappedKey wk = wko.wrappedKey();
		Key unwrappedSecretKey = wk.unwrapKey(wrappingKeyPair.getPrivate());
		Assert.assertEquals(AESSecretKey, unwrappedSecretKey);
		uvkd.stopEnumerating();
	}

	/*
	 * 	Retrieve the wrapped key directly from the KD
	 * 	and check that the result is as expected.
	 * 
	 */
	public void testGetUnwrappedKey() throws Exception {
		byte[] expectedKeyID = CCNDigestHelper.digest(AESSecretKey.getEncoded());
		Key unwrappedSecretKey = kd.getUnwrappedKey(expectedKeyID);
		Assert.assertEquals(AESSecretKey, unwrappedSecretKey);		
	}

	/*
	 * Retrieve the private key from KD and check the result
	 */
	public void testGetPrivateKey() throws Exception {
		Assert.assertTrue(kd.hasPrivateKeyBlock());
		Key privKey = kd.getPrivateKey();
		Assert.assertEquals(wrappedPrivateKey, privKey);
	}
	
	/*
	 * Create an "old" key directory, which is superseded by the kd created above
	 * Check that the unwrappedKey for the old superseded KD can be obtained.
	 */
	public void testGetUnwrappedKeySuperseded() throws Exception {
		// create a superseded key directory
		ContentName supersededKeyDirectoryName = ContentName.fromNative(keyDirectoryBase + rand.nextInt(10000) + "/superseded");
		ContentName versionSupersededKeyDirectoryName = VersioningProfile.addVersion(supersededKeyDirectoryName);
		CCNHandle handle = CCNHandle.open();
		PrincipalKeyDirectory skd = new PrincipalKeyDirectory(acm, versionSupersededKeyDirectoryName, handle);

		// generate a AES wrapping key
		KeyGenerator kg = KeyGenerator.getInstance("AES");
		Key supersededAESSecretKey = kg.generateKey();
		byte[] expectedKeyID = CCNDigestHelper.digest(supersededAESSecretKey.getEncoded());
				
		// add a superseded block
		ContentName supersedingKeyName = keyDirectoryName;
		skd.addSupersededByBlock(supersededAESSecretKey, supersedingKeyName, null, AESSecretKey);
		while (!skd.hasChildren() || !skd.hasSupersededBlock()) 
			skd.waitForNewChildren();
		Assert.assertTrue(skd.hasSupersededBlock());
		Assert.assertNotNull(skd.getSupersededBlockName());

		// get unwrapped key for superseded KD
		Key unwrappedSecretKey = skd.getUnwrappedKey(expectedKeyID);
		Assert.assertEquals(supersededAESSecretKey, unwrappedSecretKey);
		skd.stopEnumerating();
	}

	
	/*
	 * Add a previous key block
	 */
	public void testAddPreviousKeyBlock() throws Exception {
		Assert.assertTrue(! kd.hasPreviousKeyBlock());
		// generate a new AES wrapping key
		KeyGenerator kg = KeyGenerator.getInstance("AES");
		Key newAESSecretKey = kg.generateKey();

		ContentName supersedingKeyName = ContentName.fromNative(keyDirectoryBase + "previous");
		kd.addPreviousKeyBlock(AESSecretKey, supersedingKeyName, newAESSecretKey);
		kd.waitForNewChildren();
		Assert.assertTrue(kd.hasPreviousKeyBlock());
	}	
}
