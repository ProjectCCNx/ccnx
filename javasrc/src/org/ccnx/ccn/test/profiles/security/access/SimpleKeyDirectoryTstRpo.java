package org.ccnx.ccn.test.profiles.security.access;

import java.security.Key;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Comparator;
import java.util.Random;
import java.util.TreeSet;

import javax.crypto.KeyGenerator;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.impl.security.crypto.CCNDigestHelper;
import org.ccnx.ccn.impl.support.ByteArrayCompare;
import org.ccnx.ccn.io.content.WrappedKey;
import org.ccnx.ccn.io.content.WrappedKey.WrappedKeyObject;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.profiles.security.access.AccessControlManager;
import org.ccnx.ccn.profiles.security.access.AccessControlProfile;
import org.ccnx.ccn.profiles.security.access.KeyDirectory;
import org.ccnx.ccn.protocol.ContentName;
import org.junit.Assert;
import org.junit.Test;


public class SimpleKeyDirectoryTstRpo {	
	
	public static KeyDirectory kd;
	public static KeyPair groupMemberKeyPair;
	public static Key AESSecretKey;
	public static byte[] groupMemberPKID;
	
	@Test
	public void testKeyDirectoryCreation() throws Exception {
		ContentName cnDirectoryBase = ContentName.fromNative("/test");
		ContentName groupStore = AccessControlProfile.groupNamespaceName(cnDirectoryBase);
		ContentName userStore = ContentName.fromNative(cnDirectoryBase, "Users");		
		AccessControlManager acm = new AccessControlManager(cnDirectoryBase, groupStore, userStore);
		
		Random rand = new Random();
		String keyDirectoryBase = "/test/KeyDirectoryTestRepo-" + Integer.toString(rand.nextInt(10000));
		ContentName keyDirectoryName = ContentName.fromNative(keyDirectoryBase);
		ContentName versionedDirectoryName = VersioningProfile.addVersion(keyDirectoryName);

		CCNHandle handle = CCNHandle.open();
		
		// create key directory
		kd = new KeyDirectory(acm, versionedDirectoryName, handle);
		Assert.assertNotNull(kd);		
	}
	
	@Test
	public void testAddGroupPrivateKey() throws Exception {
		// generate a group private key
		KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
		kpg.initialize(1024);
		KeyPair groupKeyPair = kpg.generateKeyPair();
		PrivateKey groupPrivateKey = groupKeyPair.getPrivate();
	 
		// generate a symmetric (AES) wrapping key
		KeyGenerator kg = KeyGenerator.getInstance("AES");
		AESSecretKey = kg.generateKey();
		// add private key block
		kd.addPrivateKeyBlock(groupPrivateKey, AESSecretKey);
		kd.waitForData(); 
		Assert.assertTrue(kd.hasPrivateKeyBlock());
		
		// generate the public/private key pair of a group member
		KeyPairGenerator kpggm = KeyPairGenerator.getInstance("RSA");
		kpggm.initialize(1024);
		groupMemberKeyPair = kpggm.generateKeyPair();
		PublicKey publicKey = groupMemberKeyPair.getPublic();
		groupMemberPKID = CCNDigestHelper.digest(publicKey.getEncoded());
		ContentName groupMemberKeyName = ContentName.fromNative("/test/parc/keys/pgolle");
		ContentName vGroupMemberPublicKeyName = VersioningProfile.addVersion(groupMemberKeyName);
		
		// add to the KeyDirectory the secret key wrapped in the public key
		kd.addWrappedKeyBlock(AESSecretKey, vGroupMemberPublicKeyName, publicKey);
		kd.waitForData();
	}
	
	@Test
	public void testGetWrappedKeyForKeyID() throws Exception {
		// check the ID of the wrapping key
		TreeSet<byte[]> wkid = kd.getCopyOfWrappingKeyIDs();
		
		Assert.assertEquals(1, wkid.size());
		Comparator<byte[]> byteArrayComparator = new ByteArrayCompare();
		Assert.assertEquals(0, byteArrayComparator.compare(wkid.first(), groupMemberPKID));

		// check name
		ContentName wkName = kd.getWrappedKeyNameForKeyID(groupMemberPKID);
		Assert.assertNotNull(wkName);
		
		// unwrap the key and check that the unwrapped secret key is correct
		WrappedKeyObject wko = kd.getWrappedKeyForKeyID(groupMemberPKID);
		WrappedKey wk = wko.wrappedKey();
		Key unwrappedSecretKey = wk.unwrapKey(groupMemberKeyPair.getPrivate());
		Assert.assertEquals(AESSecretKey, unwrappedSecretKey);
		kd.stopEnumerating();
	}
	
}
