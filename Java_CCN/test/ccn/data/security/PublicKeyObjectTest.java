package test.ccn.data.security;


import static org.junit.Assert.fail;

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
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import test.ccn.data.util.Flosser;

import com.parc.ccn.Library;
import com.parc.ccn.config.ConfigurationException;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.security.PublicKeyObject;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.library.profiles.VersioningProfile;

public class PublicKeyObjectTest {

	public static KeyPair pair1 = null;
	public static KeyPair pair2 = null;
	public static KeyPair egPair = null;
	public static KeyPair eccPair = null;
	public static KeyPair eciesPair = null;
	public static ContentName storedKeyName = null;
	public static ContentName storedKeyName2 = null;
	public static ContentName storedKeyName3 = null;
	public static ContentName namespace = null;
	
	static Level oldLevel;
	
	static Flosser flosser = null;
	public static CCNLibrary library = null;
	
	@AfterClass
	public static void tearDownAfterClass() throws Exception {
		Library.logger().setLevel(oldLevel);
		if (flosser != null) {
			flosser.stop();
			flosser = null;
		}
	}

	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		library = CCNLibrary.open();
		oldLevel = Library.logger().getLevel();
		Library.logger().setLevel(Level.FINE);
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
	    int randomTrial = new Random().nextInt(10000);
		storedKeyName = ContentName.fromNative(namespace, "testRSAUser-" + Integer.toString(randomTrial), "KEY");
		storedKeyName2 = ContentName.fromNative(namespace, "testEGUser-" + Integer.toString(randomTrial), "KEY");
		storedKeyName3 = ContentName.fromNative(namespace, "testECCUser-" + Integer.toString(randomTrial), "KEY");
	}

	@Test
	public void testRawPublicKeyObject() {
		
		try {
			testRawKeyReadWrite(storedKeyName, pair1.getPublic(), pair2.getPublic());
			testRawKeyReadWrite(storedKeyName2, egPair.getPublic(), null);
			testRawKeyReadWrite(storedKeyName3, eccPair.getPublic(), eciesPair.getPublic());
		} catch (Exception e) {
			e.printStackTrace();
			fail("Exception in publicKeyObject testing: " + e.getClass().getName() + ":  " + e.getMessage());
		} finally {
			flosser.stop();
			flosser = null;
		}
	}

	@Test
	public void testRepoPublicKeyObject() {
		
		try {
			testRepoKeyReadWrite(storedKeyName, pair1.getPublic(), pair2.getPublic());
			testRepoKeyReadWrite(storedKeyName2, egPair.getPublic(), null);
			testRepoKeyReadWrite(storedKeyName3, eccPair.getPublic(), eciesPair.getPublic());
		} catch (Exception e) {
			e.printStackTrace();
			fail("Exception in publicKeyObject testing: " + e.getClass().getName() + ":  " + e.getMessage());
		} finally {
		}
	}

	public void testRawKeyReadWrite(ContentName keyName, PublicKey key, PublicKey optional2ndKey) throws ConfigurationException, IOException, XMLStreamException {
		

		System.out.println("Reading and writing key " + keyName + " key 1: " + key.getAlgorithm() + " key 2: " + ((null == optional2ndKey) ? "null" : optional2ndKey.getAlgorithm()));
		if (null == flosser) {
			flosser = new Flosser();
		} 
		flosser.handleNamespace(keyName);
		PublicKeyObject pko = new PublicKeyObject(keyName, key, library);
		pko.save();
		Assert.assertTrue(VersioningProfile.isVersioned(pko.getName()));
		// should update in another thread
		PublicKeyObject pkoread = new PublicKeyObject(keyName, null); // new library
		Assert.assertTrue(pkoread.ready());
		Assert.assertEquals(pkoread.getName(), pko.getName());
		if (!pkoread.publicKey().equals(pko.publicKey())) {
			Library.logger().info("Mismatched public keys, chance provider doesn't implement equals()." );
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
			//pkoread.save(optional2ndKey);
			//Assert.assertTrue(VersioningProfile.isLaterVersionOf(pkoread.getName(), pko.getName()));
			//pko.update();
			pko.save(optional2ndKey);
			Assert.assertTrue(VersioningProfile.isLaterVersionOf(pko.getName(), pkoread.getName()));
			pkoread.update();
			Assert.assertEquals(pkoread.getName(), pko.getName());
			Assert.assertEquals(pkoread.publicKey(), pko.publicKey());
			Assert.assertEquals(pko.publicKey(), optional2ndKey);
		}
	}

	public void testRepoKeyReadWrite(ContentName keyName, PublicKey key, PublicKey optional2ndKey) throws ConfigurationException, IOException, XMLStreamException {
		

		System.out.println("Reading and writing key " + keyName + " key 1: " + key.getAlgorithm() + " key 2: " + ((null == optional2ndKey) ? "null" : optional2ndKey.getAlgorithm()));
		PublicKeyObject pko = new PublicKeyObject(keyName, key, library);
		pko.saveToRepository();
		Assert.assertTrue(VersioningProfile.isVersioned(pko.getName()));
		// should update in another thread
		PublicKeyObject pkoread = new PublicKeyObject(keyName, null); // new library
		Assert.assertTrue(pkoread.ready());
		Assert.assertEquals(pkoread.getName(), pko.getName());
		if (!pkoread.publicKey().equals(pko.publicKey())) {
			Library.logger().info("Mismatched public keys, chance provider doesn't implement equals()." );
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
			//pkoread.saveToRepository(optional2ndKey);
			//Assert.assertTrue(VersioningProfile.isLaterVersionOf(pkoread.getName(), pko.getName()));
			//pko.update();
			pko.saveToRepository(optional2ndKey);
			Assert.assertTrue(VersioningProfile.isLaterVersionOf(pko.getName(), pkoread.getName()));
			pkoread.update();
			Assert.assertEquals(pkoread.getName(), pko.getName());
			Assert.assertEquals(pkoread.publicKey(), pko.publicKey());
			Assert.assertEquals(pko.publicKey(), optional2ndKey);
		}
	}
}
