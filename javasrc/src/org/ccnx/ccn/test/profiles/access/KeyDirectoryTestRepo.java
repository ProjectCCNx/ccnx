package org.ccnx.ccn.test.profiles.access;


import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.impl.security.crypto.CCNDigestHelper;
import org.ccnx.ccn.impl.support.ByteArrayCompare;
import org.ccnx.ccn.io.content.WrappedKey;
import org.ccnx.ccn.io.content.WrappedKey.WrappedKeyObject;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.profiles.access.AccessControlManager;
import org.ccnx.ccn.profiles.access.KeyDirectory;
import org.ccnx.ccn.protocol.ContentName;
import org.junit.Assert;
import org.junit.Test;
import org.junit.BeforeClass;

import java.security.Key;
import java.security.KeyPairGenerator;
import java.security.KeyPair;
import java.security.PublicKey;
import java.security.PrivateKey;
import java.util.Comparator;
import java.util.TreeSet;
import java.util.Random;
import javax.crypto.KeyGenerator;
import java.lang.reflect.Method;



public class KeyDirectoryTestRepo {
	
	static Random rand = new Random();
	static KeyDirectory kd;
	static final String keyDirectoryBase = "/test/parc/directory-";
	static ContentName keyDirectoryName;
	static final String keyBase = "/test/parc/keys/";
	static String principalName = "pgolle-";
	static ContentName publicKeyName;
	static KeyPair kp;
	static byte[] wrappingPKID;
	static Key AESSecretKey;
	static AccessControlManager acm;
		
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
		CCNHandle library = CCNHandle.open();
		acm = new AccessControlManager(versionDirectoryName);
		kd = new KeyDirectory(acm, versionDirectoryName, library);
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
		kp = kpg.generateKeyPair();
		PrivateKey privateKey = kp.getPrivate();

		// generate a AES wrapping key
		KeyGenerator kg = KeyGenerator.getInstance("AES");
		AESSecretKey = kg.generateKey();
		
		// add private key block
		kd.addPrivateKeyBlock(privateKey, AESSecretKey);		
//		kd.getNewData();	
//		Assert.assertTrue(kd.hasPrivateKeyBlock());
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
		kp = kpg.generateKeyPair();
		PublicKey publicKey = kp.getPublic();
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
		PrivateKey privKey = kp.getPrivate();
		byte[] publicKeyIdentifier = CCNDigestHelper.digest(kp.getPublic().getEncoded());
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
		kd.getNewData();		

		// check the ID of the wrapping key
		try{
			kd.getKeyIDLock().readLock().lock();
			TreeSet<byte[]> wkid = kd.getWrappingKeyIDs();
			Assert.assertEquals(1, wkid.size());
			Comparator<byte[]> byteArrayComparator = new ByteArrayCompare();
			Assert.assertEquals(0, byteArrayComparator.compare(wkid.first(), wrappingPKID));
		}finally{
			kd.getKeyIDLock().readLock().unlock();
		}

		// unwrap the key and check that the unwrapped secret key is correct
		WrappedKeyObject wko = kd.getWrappedKeyForKeyID(wrappingPKID);
		WrappedKey wk = wko.wrappedKey();
		Key unwrappedSecretKey = wk.unwrapKey(kp.getPrivate());
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
		WrappedKey wk = wko.wrappedKey();
		Key unwrappedSecretKey = wk.unwrapKey(kp.getPrivate());
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

	
	@Test
	public void testGetPrivateKey() throws Exception {
		PrivateKey kdPrivKey = kd.getPrivateKey();
		
	}
	
	public void testAddSupersededByBlock() throws Exception {
		
	}
	
	
}
