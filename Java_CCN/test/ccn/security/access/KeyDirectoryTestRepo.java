package test.ccn.security.access;


import org.junit.Assert;
import org.junit.Test;
import org.junit.BeforeClass;

import java.security.Key;
import java.security.KeyPairGenerator;
import java.security.KeyPair;
import java.security.PublicKey;
import java.security.PrivateKey;
import java.util.ArrayList;
import java.util.Random;
import javax.crypto.KeyGenerator;
import java.lang.reflect.Method;

import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.security.WrappedKey;
import com.parc.ccn.data.security.WrappedKey.WrappedKeyObject;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.library.profiles.VersioningProfile;
import com.parc.ccn.security.access.KeyDirectory;
import com.parc.ccn.security.access.AccessControlManager;
import com.parc.ccn.security.crypto.CCNDigestHelper;

public class KeyDirectoryTestRepo {
	
	static Random rand = new Random();
	static KeyDirectory kd;
	static final String keyDirectoryBase = "/test/parc/directory-";
	static ContentName keyDirectoryName;
	static final String keyBase = "/test/parc/keys/";
	static String principalName = "pgolle-";
	static ContentName publicKeyName;
	static KeyPair kp;
	static Key secretKeyToWrap;
		
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		// randomize names to minimize stateful effects of ccnd/repo caches.
		keyDirectoryName = ContentName.fromNative(keyDirectoryBase + Integer.toString(rand.nextInt(10000)));
		principalName = principalName + Integer.toString(rand.nextInt(10000));
	}
	
	/*	Creates a new versioned KeyDirectory
	 * 
	 */
	@Test
	public void testKeyDirectoryCreation() throws Exception {
		ContentName versionDirectoryName = VersioningProfile.addVersion(keyDirectoryName);
		CCNLibrary library = CCNLibrary.open();
		AccessControlManager acm = new AccessControlManager(versionDirectoryName);
		kd = new KeyDirectory(acm, versionDirectoryName, library);
		// verify that the keyDirectory is created
		Assert.assertNotNull(kd);		
	}
	
	/*	Wraps a secret DES key in an RSA wrapping key
	 * 	and adds the wrapped key to the KeyDirectory
	 * 
	 */
	@Test
	public void testAddWrappedKey() throws Exception {
		// generate a secret key
		KeyGenerator kg = KeyGenerator.getInstance("DES");
		secretKeyToWrap = kg.generateKey();

		// generate a public key to wrap
		KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
		kpg.initialize(1024);
		kp = kpg.generateKeyPair();
		PublicKey publicKey = kp.getPublic();
		publicKeyName = ContentName.fromNative(keyBase + principalName);
		ContentName versionPublicKeyName = VersioningProfile.addVersion(publicKeyName);
		
		// add to the KeyDirectory the secret key wrapped in the public key
		kd.addWrappedKeyBlock(secretKeyToWrap, versionPublicKeyName, publicKey);			
	}
	
	
	/*	Creates a new (unversioned) KeyDirectory object with the same
	 * 	directory name as above. Check that the constructor retrieves the latest
	 * 	version of the KeyDirectory.
	 * 	The test then retrieves the wrapped key from the KeyDirectory by its KeyID,
	 * 	unwraps it, and checks that the secret key retrieved is as expected.
	 * 
	 */
	@Test
	public void retrieveWrappedKeyByKeyID() throws Exception {
		CCNLibrary library = CCNLibrary.open();
		AccessControlManager acm = new AccessControlManager(keyDirectoryName);
		// Use unversioned constructor so KeyDirectory returns the latest version
		kd = new KeyDirectory(acm, keyDirectoryName, library);
		kd.getNewData();		

		// we expect to get one wrapped key back
		ArrayList<byte[]> wkid = new ArrayList<byte[]>(kd.getWrappingKeyIDs());
		Assert.assertEquals(1, wkid.size());

		// unwrap the key and check that the unwrapped secret key is correct
		WrappedKeyObject wko = kd.getWrappedKeyForKeyID(wkid.get(0));
		WrappedKey wk = wko.wrappedKey();
		Key unwrappedSecretKey = wk.unwrapKey(kp.getPrivate());
		Assert.assertEquals(secretKeyToWrap, unwrappedSecretKey);
	}

	/*	Retrieves the wrapped key from the KD by principalName.
	 * 	As above, the key is unwrapped and we check that the result is as expected.
	 * 
	 */
	@Test
	public void retrieveWrappedKeyByPrincipalName() throws Exception {
		// unwrap the key and check that the unwrapped secret key is correct
		WrappedKeyObject wko = kd.getWrappedKeyForPrincipal(principalName);
		WrappedKey wk = wko.wrappedKey();
		Key unwrappedSecretKey = wk.unwrapKey(kp.getPrivate());
		Assert.assertEquals(secretKeyToWrap, unwrappedSecretKey);
	}
	
	/*	Adds the wrapping key to the AccessControlManager.
	 * 	When that's done, the test retrieves the wrapped key directly from the KD
	 * 	and checks that the result is as expected.
	 * 
	 */
	@Test
	public void retrieveUnwrappedKey() throws Exception {
		// We must first add the wrapping key to the access control manager
		AccessControlManager acm = new AccessControlManager(keyDirectoryName);
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
		
		// now ask KD to unwrap the key
		CCNLibrary library = CCNLibrary.open();
		kd = new KeyDirectory(acm, keyDirectoryName, library);		
		byte[] expectedKeyID = CCNDigestHelper.digest(secretKeyToWrap.getEncoded());
		Key unwrappedSecretKey = kd.getUnwrappedKey(expectedKeyID);
		Assert.assertEquals(secretKeyToWrap, unwrappedSecretKey);		
	}

	/*
	 * Adds a private key block to KD
	 */
	public void testAddPrivateKey() throws Exception {
		// generate a private key to wrap
		KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
		kpg.initialize(1024);
		kp = kpg.generateKeyPair();
		PrivateKey privateKey = kp.getPrivate();

		// generate a AES wrapping key
		KeyGenerator kg = KeyGenerator.getInstance("AES");
		Key privateKeyWrappingKey = kg.generateKey();
		
		// add private key block
		kd.addPrivateKeyBlock(privateKey, privateKeyWrappingKey);		
		kd.getNewData();
	}
	
	
}
