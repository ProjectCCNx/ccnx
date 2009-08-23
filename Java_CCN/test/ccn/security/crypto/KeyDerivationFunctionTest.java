package test.ccn.security.crypto;

import java.security.SecureRandom;

import javax.crypto.spec.SecretKeySpec;

import junit.framework.AssertionFailedError;

import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import com.parc.ccn.library.profiles.VersioningProfile;
import com.parc.ccn.security.crypto.ContentKeys;
import com.parc.ccn.security.crypto.KeyDerivationFunction;

public class KeyDerivationFunctionTest {

	static SecureRandom random = new SecureRandom();
	static PublisherPublicKeyDigest publisher = null;
	static ContentName testName = null;
	static ContentName testNameVersion1 = null;
	static ContentName testNameVersion2 = null;
	static String functionalLabel = "Key Function";

	static SecretKeySpec keySpec;
	static byte [] key = new byte[16];
	static ContentKeys keyandiv;
	static ContentKeys keyandivnolabel;
	
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		byte [] ppd = new byte[32];
		random.nextBytes(ppd);
		publisher = new PublisherPublicKeyDigest(ppd);
		testName = ContentName.fromNative("/parc/test/media/NathanAtTheBeach.m4v");
		testNameVersion1 = VersioningProfile.addVersion(testName);
		Thread.sleep(3); // make sure version is different
		testNameVersion2 = VersioningProfile.addVersion(testName);

		random.nextBytes(key);
		SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
		Assert.assertArrayEquals("raw bytes of key not the same as the encoded key!", key, keySpec.getEncoded());
		
		keyandiv = KeyDerivationFunction.DeriveKeysForObject(keySpec.getEncoded(), 
										functionalLabel, testName, publisher);
		keyandivnolabel = KeyDerivationFunction.DeriveKeysForObject(keySpec.getEncoded(), 
				null, testName, publisher);
	}

	@Test
	public void testKeysSameTwice() throws Exception {
		ContentKeys keyandiv2 = KeyDerivationFunction.DeriveKeysForObject(keySpec.getEncoded(), 
				functionalLabel, testName, publisher);
		Assert.assertEquals(keyandiv, keyandiv2);
	}
	
	@Test(expected=AssertionFailedError.class)
	public void testLabelMakesDifference() {
		Assert.assertEquals(keyandiv, keyandivnolabel);
	}
	
	@Test
	public void testNoLabelSameTwice() throws Exception {
		ContentKeys keyandivnolabel2 = KeyDerivationFunction.DeriveKeysForObject(keySpec.getEncoded(), 
				null, testName, publisher);
		Assert.assertEquals(keyandivnolabel, keyandivnolabel2);
	}
	
	@Test(expected=AssertionFailedError.class)
	public void testVersionMakesDifference() throws Exception {
		ContentKeys keyandivv1 = KeyDerivationFunction.DeriveKeysForObject(keySpec.getEncoded(), 
				null, testNameVersion1, publisher);
		ContentKeys keyandivv2 = KeyDerivationFunction.DeriveKeysForObject(keySpec.getEncoded(), 
				null, testNameVersion2, publisher);
		Assert.assertEquals(keyandivv1, keyandivv2);
	}
}
