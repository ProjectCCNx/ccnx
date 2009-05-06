package test.ccn.security.crypto;

import static org.junit.Assert.fail;

import java.security.SecureRandom;

import javax.crypto.spec.SecretKeySpec;

import org.bouncycastle.util.Arrays;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.security.PublisherPublicKeyDigest;
import com.parc.ccn.data.util.DataUtils;
import com.parc.ccn.library.profiles.VersioningProfile;
import com.parc.ccn.security.crypto.CCNCipherFactory;
import com.parc.ccn.security.crypto.KeyDerivationFunction;

public class KeyDerivationFunctionTest {

	static SecureRandom random = new SecureRandom();
	static PublisherPublicKeyDigest publisher = null;
	static ContentName testName = null;
	static ContentName testNameVersion1 = null;
	static ContentName testNameVersion2 = null;
	static String functionalLabel = "Key Function";
	
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		byte [] ppd = new byte[32];
		random.nextBytes(ppd);
		publisher = new PublisherPublicKeyDigest(ppd);
		testName = ContentName.fromNative("/parc/test/media/NathanAtTheBeach.m4v");
		testNameVersion1 = VersioningProfile.versionName(testName);
		testNameVersion2 = VersioningProfile.versionName(testName);
	}

	@Test
	public void testDeriveKey() {
		byte [] key = new byte[16];
		random.nextBytes(key);
		SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
		Assert.assertArrayEquals("raw bytes of key not the same as the encoded key!", key, keySpec.getEncoded());
		
		try {
			byte [][] keyandiv = KeyDerivationFunction.DeriveKeyForObject(keySpec.getEncoded(), 
											functionalLabel, testName, publisher);
			byte [][] keyandiv2 = KeyDerivationFunction.DeriveKeyForObject(keySpec.getEncoded(), 
					functionalLabel, testName, publisher);
			Assert.assertArrayEquals(keyandiv[0], keyandiv2[0]);
			Assert.assertArrayEquals(keyandiv[1], keyandiv2[1]);
			Assert.assertEquals(keyandiv[0].length, CCNCipherFactory.DEFAULT_AES_KEY_LENGTH);
			Assert.assertEquals(keyandiv[1].length, CCNCipherFactory.IV_MASTER_LENGTH);
			byte [][] keyandivnolabel = KeyDerivationFunction.DeriveKeyForObject(keySpec.getEncoded(), 
					null, testName, publisher);
			System.out.println("Key for content: " + testName + ": " + DataUtils.printBytes(keyandiv[0]) + 
								" iv: " + DataUtils.printBytes(keyandiv[1]));
			Assert.assertFalse(Arrays.areEqual(keyandiv[0], keyandivnolabel[0]));
			Assert.assertFalse(Arrays.areEqual(keyandiv[1], keyandivnolabel[1]));
			byte [][] keyandivnolabel2 = KeyDerivationFunction.DeriveKeyForObject(keySpec.getEncoded(), 
					null, testName, publisher);
			Assert.assertArrayEquals(keyandivnolabel[0], keyandivnolabel2[0]);
			Assert.assertArrayEquals(keyandivnolabel[1], keyandivnolabel2[1]);
			
			byte [][] keyandivv1 = KeyDerivationFunction.DeriveKeyForObject(keySpec.getEncoded(), 
					null, testNameVersion1, publisher);
			byte [][] keyandivv2 = KeyDerivationFunction.DeriveKeyForObject(keySpec.getEncoded(), 
					null, testNameVersion2, publisher);
			Assert.assertFalse(Arrays.areEqual(keyandivv1[0], keyandivv2[0]));
			Assert.assertFalse(Arrays.areEqual(keyandivv1[1], keyandivv2[1]));
			byte [][] keyandivv22 = KeyDerivationFunction.DeriveKeyForObject(keySpec.getEncoded(), 
					null, testNameVersion2, publisher);
			Assert.assertArrayEquals(keyandivv2[0], keyandivv22[0]);
			Assert.assertArrayEquals(keyandivv2[1], keyandivv22[1]);
		} catch (Exception e) {
			e.printStackTrace();
			fail("Exception in testDeriveKey: " + e.getClass().getName() + ":  " + e.getMessage());
		} 
	}

}
