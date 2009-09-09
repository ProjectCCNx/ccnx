package org.ccnx.ccn.test.profiles.access;


import org.junit.Assert;
import org.junit.Test;
import org.junit.BeforeClass;

import java.security.Key;
import java.security.KeyPairGenerator;
import java.security.KeyPair;
import java.security.PublicKey;
import java.security.PrivateKey;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.TreeSet;
import java.util.Random;

import javax.crypto.KeyGenerator;
import java.lang.reflect.Method;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.impl.security.crypto.CCNDigestHelper;
import org.ccnx.ccn.impl.support.ByteArrayCompare;
import org.ccnx.ccn.io.content.Link;
import org.ccnx.ccn.io.content.WrappedKey;
import org.ccnx.ccn.io.content.WrappedKey.WrappedKeyObject;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.profiles.access.AccessControlManager;
import org.ccnx.ccn.profiles.access.AccessControlProfile;
import org.ccnx.ccn.profiles.access.KeyDirectory;
import org.ccnx.ccn.profiles.access.Group;
import org.ccnx.ccn.protocol.ContentName;


public class KeyDirectoryTestRepo {
	
	static Random rand = new Random();
	static KeyDirectory kd;
	static final String directoryBase = "/test/parc";
	static final String keyDirectoryBase = "/test/parc/directory-";
	static ContentName keyDirectoryName;
	static final String keyBase = "/test/parc/keys/";
	static String principalName = "pgolle-";
	static ContentName publicKeyName;
	
	static PrivateKey wrappedPrivateKey; 
	static Key AESSecretKey;
	static KeyPair wrappingKeyPair;
	static byte[] wrappingPKID;
	static AccessControlManager acm;
	
	static CCNHandle handle;
		
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		// randomize names to minimize stateful effects of ccnd/repo caches.
		keyDirectoryName = ContentName.fromNative(keyDirectoryBase + Integer.toString(rand.nextInt(10000)));
		principalName = principalName + Integer.toString(rand.nextInt(10000));
	}
	
	/*	
	 * Create a new versioned KeyDirectory
	 */
	@Test
	public void testKeyDirectoryCreation() throws Exception {
		ContentName versionDirectoryName = VersioningProfile.addVersion(keyDirectoryName);
		handle = CCNHandle.open();
		
		ContentName cnDirectoryBase = ContentName.fromNative(directoryBase);
		ContentName groupStore = AccessControlProfile.groupNamespaceName(cnDirectoryBase);
		ContentName userStore = ContentName.fromNative(cnDirectoryBase, "Users");		

		acm = new AccessControlManager(cnDirectoryBase, groupStore, userStore);
		kd = new KeyDirectory(acm, versionDirectoryName, handle);
		// verify that the keyDirectory is created
		Assert.assertNotNull(kd);		
	}
	
	/*
	 * Add a private key block to KD
	 */
	@Test
	public void testAddPrivateKey() throws Exception {
		Assert.assertFalse(kd.hasPrivateKeyBlock());
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
		Assert.assertTrue(kd.hasPrivateKeyBlock());
	}
	
	/*
	 * Unwrap the private key via membership in a group
	 */
	@Test
	public void testGetUnwrappedKeyGroupMember() throws Exception {
		ContentName myIdentity = ContentName.fromNative("/test/parc/Users/pgolle");
		acm.publishMyIdentity(myIdentity, null);		
				
		// add myself to a newly created group				
		String randomGroupName = "testGroup" + rand.nextInt(10000);
		ArrayList<Link> newMembers = new ArrayList<Link>();
		newMembers.add(new Link(myIdentity));
		Group myGroup = acm.groupManager().createGroup(randomGroupName, newMembers);
		Assert.assertTrue(acm.groupManager().haveKnownGroupMemberships());
		Assert.assertTrue(myGroup.privateKeyDirectory(acm).hasPrivateKeyBlock());
		
		// add to the KeyDirectory the secret key wrapped in the public key
		ContentName versionDirectoryName2 = VersioningProfile.addVersion(
				ContentName.fromNative(keyDirectoryBase + Integer.toString(rand.nextInt(10000)) ));
		KeyDirectory kd2 = new KeyDirectory(acm, versionDirectoryName2, handle);
		PublicKey groupPublicKey = myGroup.publicKey();
		ContentName groupPublicKeyName = myGroup.publicKeyName();
		kd2.addWrappedKeyBlock(AESSecretKey, groupPublicKeyName, groupPublicKey);		
		
		// retrieve the secret key
		byte[] expectedKeyID = CCNDigestHelper.digest(AESSecretKey.getEncoded());
		Key unwrappedSecretKey = kd2.getUnwrappedKey(expectedKeyID);
		Assert.assertEquals(AESSecretKey, unwrappedSecretKey);
	}

	
	/*	
	 * Wraps the AES key in an RSA wrapping key
	 * and adds the wrapped key to the KeyDirectory
	 */
	@Test
	public void testAddWrappedKey() throws Exception {
		// generate a public key to wrap
		KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
		kpg.initialize(1024);
		wrappingKeyPair = kpg.generateKeyPair();
		PublicKey publicKey = wrappingKeyPair.getPublic();
		wrappingPKID = CCNDigestHelper.digest(publicKey.getEncoded());
		publicKeyName = ContentName.fromNative(keyBase + principalName);
		ContentName versionPublicKeyName = VersioningProfile.addVersion(publicKeyName);
		
		// add to the KeyDirectory the secret key wrapped in the public key
		kd.addWrappedKeyBlock(AESSecretKey, versionPublicKeyName, publicKey);			
	}
	
	/*
	 * Adds the wrapping key to the AccessControlManager.
	 */
	@Test
	public void addWrappingKeyToACM() throws Exception {
		PrivateKey privKey = wrappingKeyPair.getPrivate();
		byte[] publicKeyIdentifier = CCNDigestHelper.digest(wrappingKeyPair.getPublic().getEncoded());
		String methodName = "addMyPrivateKey";
		Class<?>[] parameterTypes = new Class[2];
		parameterTypes[0] = publicKeyIdentifier.getClass();
		parameterTypes[1] = PrivateKey.class;
		Method m = acm.getClass().getDeclaredMethod(methodName, parameterTypes);
		m.setAccessible(true);
		Object[] args = new Object[2];
		args[0] = publicKeyIdentifier;
		args[1] = privKey;
		m.invoke(acm, args);
	}
	
	/*	
	 * Create a new (unversioned) KeyDirectory object with the same	
	 * directory name as above. Check that the constructor retrieves the latest
	 * version of the KeyDirectory.
	 * Retrieve the wrapped key from the KeyDirectory by its KeyID,
	 * unwrap it, and check that the secret key retrieved is as expected.
	 * 
	 */
	@Test
	public void testGetWrappedKeyForKeyID() throws Exception {
		CCNHandle library = CCNHandle.open();
		// Use unversioned constructor so KeyDirectory returns the latest version
		kd = new KeyDirectory(acm, keyDirectoryName, library);

		// check the ID of the wrapping key
		TreeSet<byte[]> wkid = kd.getCopyOfWrappingKeyIDs();
		
		Assert.assertEquals(1, wkid.size());
		Comparator<byte[]> byteArrayComparator = new ByteArrayCompare();
		Assert.assertEquals(0, byteArrayComparator.compare(wkid.first(), wrappingPKID));

		// check name
		ContentName wkName = kd.getWrappedKeyNameForKeyID(wrappingPKID);
		Assert.assertNotNull(wkName);
		
		// unwrap the key and check that the unwrapped secret key is correct
		WrappedKeyObject wko = kd.getWrappedKeyForKeyID(wrappingPKID);
		WrappedKey wk = wko.wrappedKey();
		Key unwrappedSecretKey = wk.unwrapKey(wrappingKeyPair.getPrivate());
		Assert.assertEquals(AESSecretKey, unwrappedSecretKey);
	}

	/*	
	 * Retrieve the wrapped key from the KD by principalName.
	 * As above, the key is unwrapped and we check that the result is as expected.
	 * 
	 */
	@Test
	public void testGetWrappedKeyForPrincipal() throws Exception {		
		// unwrap the key and check that the unwrapped secret key is correct
		WrappedKeyObject wko = kd.getWrappedKeyForPrincipal(principalName);
		Assert.assertNotNull(wko);
		WrappedKey wk = wko.wrappedKey();
		Key unwrappedSecretKey = wk.unwrapKey(wrappingKeyPair.getPrivate());
		Assert.assertEquals(AESSecretKey, unwrappedSecretKey);
	}
	
	/*
	 * 	Retrieve the wrapped key directly from the KD
	 * 	and check that the result is as expected.
	 * 
	 */
	@Test
	public void testGetUnwrappedKey() throws Exception {
		byte[] expectedKeyID = CCNDigestHelper.digest(AESSecretKey.getEncoded());
		Key unwrappedSecretKey = kd.getUnwrappedKey(expectedKeyID);
		Assert.assertEquals(AESSecretKey, unwrappedSecretKey);		
	}

	/*
	 * Retrieve the private key from KD and check the result
	 */
	@Test
	public void testGetPrivateKey() throws Exception {
		Assert.assertTrue(kd.hasPrivateKeyBlock());
		PrivateKey privKey = kd.getPrivateKey();
		Assert.assertEquals(wrappedPrivateKey, privKey);
	}
	
	/*
	 * Create an "old" key directory, which is superseded by the kd created above
	 * Check that the unwrappedKey for the old superseded KD can be obtained.
	 */
	@Test
	public void testGetUnwrappedKeySuperseded() throws Exception {
		// create a superseded key directory
		ContentName supersededKeyDirectoryName = ContentName.fromNative(keyDirectoryBase + rand.nextInt(10000) + "/superseded");
		ContentName versionSupersededKeyDirectoryName = VersioningProfile.addVersion(supersededKeyDirectoryName);
		CCNHandle library = CCNHandle.open();
		KeyDirectory skd = new KeyDirectory(acm, versionSupersededKeyDirectoryName, library);
		Assert.assertTrue(! skd.hasSupersededBlock());

		// generate a AES wrapping key
		KeyGenerator kg = KeyGenerator.getInstance("AES");
		Key supersededAESSecretKey = kg.generateKey();
		byte[] expectedKeyID = CCNDigestHelper.digest(supersededAESSecretKey.getEncoded());
				
		// add a superseded block
		ContentName supersedingKeyName = keyDirectoryName;
		skd.addSupersededByBlock(supersededAESSecretKey, supersedingKeyName, AESSecretKey);
		Assert.assertTrue(skd.hasSupersededBlock());
		Assert.assertNotNull(skd.getSupersededBlockName());

		// get unwrapped key for superseded KD
		Key unwrappedSecretKey = skd.getUnwrappedKey(expectedKeyID);
		Assert.assertEquals(supersededAESSecretKey, unwrappedSecretKey);
	}

	
	/*
	 * Add a previous key block
	 */
	@Test
	public void testAddPreviousKeyBlock() throws Exception {
		Assert.assertTrue(! kd.hasPreviousKeyBlock());
		// generate a new AES wrapping key
		KeyGenerator kg = KeyGenerator.getInstance("AES");
		Key newAESSecretKey = kg.generateKey();

		ContentName supersedingKeyName = ContentName.fromNative(keyDirectoryBase + "previous");
		kd.addPreviousKeyBlock(AESSecretKey, supersedingKeyName, newAESSecretKey);
		Assert.assertTrue(kd.hasPreviousKeyBlock());
	}
	
	
}
