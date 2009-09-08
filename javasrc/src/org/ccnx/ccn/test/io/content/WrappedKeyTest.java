package org.ccnx.ccn.test.io.content;

import static org.junit.Assert.fail;

import java.math.BigInteger;
import java.security.Key;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Security;

import javax.crypto.spec.SecretKeySpec;

import org.bouncycastle.asn1.nist.NISTObjectIdentifiers;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jce.spec.ElGamalParameterSpec;
import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.impl.security.crypto.jce.CCNCryptoProvider;
import org.ccnx.ccn.io.content.WrappedKey;
import org.ccnx.ccn.io.content.WrappedKey.WrappedKeyObject;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.PublisherID;
import org.ccnx.ccn.test.Flosser;
import org.ccnx.ccn.test.impl.encoding.XMLEncodableTester;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;



public class WrappedKeyTest {

	public static KeyPair wrappingKeyPair = null;
	public static KeyPair wrappedKeyPair = null;
	public static KeyPair wrappingEGKeyPair = null;
	public static KeyPair wrappedEGKeyPair = null;
	public static SecretKeySpec wrappingAESKey = null;
	public static SecretKeySpec wrappedAESKey = null;
	public static String aLabel = "FileEncryptionKeys";
	public static byte [] wrappingKeyID = null;
	public static ContentName wrappingKeyName = null;
	public static ContentName storedKeyName = null;
	public static byte [] dummyWrappedKey = new byte[64];
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
	
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		Security.addProvider(new BouncyCastleProvider());
		Security.addProvider(new CCNCryptoProvider());
		
		SecureRandom sr = new SecureRandom();
		sr.nextBytes(dummyWrappedKey);
		
		// generate key pair
		KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
		kpg.initialize(1024); // go for fast
		wrappingKeyPair = kpg.generateKeyPair();
		wrappedKeyPair = kpg.generateKeyPair();
		wrappingKeyID = PublisherID.generatePublicKeyDigest(wrappingKeyPair.getPublic());
		wrappingKeyName = VersioningProfile.addVersion(ContentName.fromNative("/parc/Users/briggs/KEY"));
		
		ElGamalParameterSpec egp = new ElGamalParameterSpec(new BigInteger(1, pbytes), new BigInteger(1, gbytes));
		KeyPairGenerator ekpg = KeyPairGenerator.getInstance("ElGamal");
		ekpg.initialize(egp); // go for fast
		wrappingEGKeyPair = ekpg.generateKeyPair();
		wrappedEGKeyPair = ekpg.generateKeyPair();
		
		byte [] key = new byte[16];
		sr.nextBytes(key);
		wrappingAESKey = new SecretKeySpec(key, "AES");
		sr.nextBytes(key);
		wrappedAESKey = new SecretKeySpec(key, "AES");
		
		storedKeyName = ContentName.fromNative("/test/content/File1.txt/_access_/NK");
	}

	@Test
	public void testWrapUnwrapKey() {
		// for each wrap case, wrap, unwrap, and make sure it matches.
		try {
			// Wrap secret in secret
			WrappedKey wks = WrappedKey.wrapKey(wrappedAESKey, null, aLabel, wrappingAESKey);
			Key unwrappedKey = wks.unwrapKey(wrappingAESKey);
			Assert.assertArrayEquals(wrappedAESKey.getEncoded(), unwrappedKey.getEncoded());
			// wrap secret in public			
			WrappedKey wksp = WrappedKey.wrapKey(wrappedAESKey, null, aLabel, wrappingKeyPair.getPublic());
			unwrappedKey = wksp.unwrapKey(wrappingKeyPair.getPrivate());
			Assert.assertArrayEquals(wrappedAESKey.getEncoded(), unwrappedKey.getEncoded());
			// wrap private in public
			WrappedKey wkpp = WrappedKey.wrapKey(wrappingKeyPair.getPrivate(), null, aLabel, wrappingKeyPair.getPublic());
			unwrappedKey = wkpp.unwrapKey(wrappingKeyPair.getPrivate());
			Assert.assertArrayEquals(wrappingKeyPair.getPrivate().getEncoded(), unwrappedKey.getEncoded());
			// wrap private in secret
			System.out.println("wpk length " + wrappingKeyPair.getPrivate().getEncoded().length);
			WrappedKey wkp = WrappedKey.wrapKey(wrappingKeyPair.getPrivate(), null, aLabel, wrappingAESKey);
			unwrappedKey = wkp.unwrapKey(wrappingAESKey);
			Assert.assertArrayEquals(wrappingKeyPair.getPrivate().getEncoded(), unwrappedKey.getEncoded());
			// ditto for el gamal
			/*
			 * ElGamal encryption requires unlimited strength crypto. This used to be installed
			 * by default on OSX, but not anymore, and not on Ubuntu or Windows.
			 * 
			wksp = WrappedKey.wrapKey(wrappedAESKey, null, aLabel, wrappingEGKeyPair.getPublic());
			unwrappedKey = wksp.unwrapKey(wrappingEGKeyPair.getPrivate());
			Assert.assertEquals(new BigInteger(1, wrappedAESKey.getEncoded()), new BigInteger(1, unwrappedKey.getEncoded()));
			wkpp = WrappedKey.wrapKey(wrappingEGKeyPair.getPrivate(), null, aLabel, wrappingKeyPair.getPublic());
			unwrappedKey = wkpp.unwrapKey(wrappingKeyPair.getPrivate());
			Assert.assertArrayEquals(wrappingEGKeyPair.getPrivate().getEncoded(), unwrappedKey.getEncoded());
			*/
		} catch (Exception e) {
			e.printStackTrace();
			fail("Exception in wrapUnwrapKey: " + e.getClass().getName() + ":  " + e.getMessage());
		} 
		
	}

	@Test
	public void testWrappedKeyByteArrayStringStringStringByteArrayByteArray() {
		WrappedKey wka = null;
		try {
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
		} catch (Exception e) {
			fail("Exception in wrapKey: " + e.getClass().getName() + ":  " + e.getMessage());
		}
		wka.setWrappingKeyIdentifier(wrappingKeyID);
	}
	
	@Test
	public void testDecodeInputStream() {
		WrappedKey wk = new WrappedKey(wrappingKeyID, dummyWrappedKey);
		WrappedKey dwk = new WrappedKey();
		WrappedKey bdwk = new WrappedKey();
		XMLEncodableTester.encodeDecodeTest("WrappedKey(dummy)", wk, dwk, bdwk);

		WrappedKey wks = null;
		try {
			wks = WrappedKey.wrapKey(wrappedAESKey, null, aLabel, wrappingAESKey);
		} catch (Exception e) {
			fail("Exception in wrapKey: " + e.getClass().getName() + ":  " + e.getMessage());
		}
		WrappedKey dwks = new WrappedKey();
		WrappedKey bdwks = new WrappedKey();
		XMLEncodableTester.encodeDecodeTest("WrappedKey(symmetric, real)", wks, dwks, bdwks);

		WrappedKey wka = null;
		try {
			wka = WrappedKey.wrapKey(wrappedAESKey, NISTObjectIdentifiers.id_aes128_CBC.toString(), 
										aLabel, wrappingKeyPair.getPublic());
		} catch (Exception e) {
			fail("Exception in wrapKey: " + e.getClass().getName() + ":  " + e.getMessage());
		}
		wka.setWrappingKeyIdentifier(wrappingKeyID);
		wka.setWrappingKeyName(wrappingKeyName);
		WrappedKey dwka = new WrappedKey();
		WrappedKey bdwka = new WrappedKey();
		XMLEncodableTester.encodeDecodeTest("WrappedKey(assymmetric wrap symmetric, with id and name)", wka, dwka, bdwka);
		Assert.assertArrayEquals(dwka.wrappingKeyIdentifier(), wrappingKeyID);
	}
	
	@Test
	public void testWrappedKeyObject() {
		
		WrappedKey wks = null;
		try {
			wks = WrappedKey.wrapKey(wrappedAESKey, null, aLabel, wrappingAESKey);
		} catch (Exception e) {
			fail("Exception in wrapKey: " + e.getClass().getName() + ":  " + e.getMessage());
		}
		WrappedKey wka = null;
		try {
			wka = WrappedKey.wrapKey(wrappedAESKey, NISTObjectIdentifiers.id_aes128_CBC.toString(), 
										aLabel, wrappingKeyPair.getPublic());
		} catch (Exception e) {
			fail("Exception in wrapKey: " + e.getClass().getName() + ":  " + e.getMessage());
		}
		wka.setWrappingKeyIdentifier(wrappingKeyID);
		wka.setWrappingKeyName(wrappingKeyName);
		
		Flosser flosser = null;
		try {
			CCNHandle library = CCNHandle.open();
			flosser = new Flosser();
			flosser.handleNamespace(storedKeyName);
			WrappedKeyObject wko = new WrappedKeyObject(storedKeyName, wks, library);
			wko.save();
			Assert.assertTrue(VersioningProfile.hasTerminalVersion(wko.getVersionedName()));
			// should update in another thread
			WrappedKeyObject wkoread = new WrappedKeyObject(storedKeyName, CCNHandle.open()); // new library
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
		} catch (Exception e) {
			fail("Exception in wrapKeyObject testing: " + e.getClass().getName() + ":  " + e.getMessage());
			
		} finally {
			if (null != flosser)
				flosser.stop();
		}
	}

}
