package test.ccn.security.keys;


import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.util.Random;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import test.ccn.data.util.Flosser;

import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.security.KeyLocator;
import com.parc.ccn.data.security.PublisherPublicKeyDigest;
import com.parc.ccn.data.security.SignedInfo;
import com.parc.ccn.library.CCNFlowControl;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.security.crypto.CCNDigestHelper;
import com.parc.ccn.security.keys.KeyManager;
import com.parc.ccn.security.keys.KeyRepository;

public class KeyManagerTest {

	protected static Random _rand = new Random(); // don't need SecureRandom
	
	protected static final int KEY_COUNT = 5;
	protected static final int DATA_COUNT_PER_KEY = 3;
	protected static KeyPair [] pairs = new KeyPair[KEY_COUNT];
	static ContentName testprefix = ContentName.fromNative(new String[]{"test","pubidtest"});
	static ContentName keyprefix = ContentName.fromNative(testprefix,"keys");
	static ContentName dataprefix = ContentName.fromNative(testprefix,"data");

	static PublisherPublicKeyDigest [] publishers = new PublisherPublicKeyDigest[KEY_COUNT];
	static KeyLocator [] keyLocs = new KeyLocator[KEY_COUNT];
	
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		try {
			Security.addProvider(new BouncyCastleProvider());
			
			// generate key pair
			KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
			kpg.initialize(512); // go for fast
			for (int i=0; i < KEY_COUNT; ++i) {
				pairs[i] = kpg.generateKeyPair();
				publishers[i] = new PublisherPublicKeyDigest(pairs[i].getPublic());
				keyLocs[i] = new KeyLocator(new ContentName(keyprefix, publishers[i].digest()));
			}
		} catch (Exception e) {
			System.out.println("Exception in test setup: " + e.getMessage());
			e.printStackTrace();
			throw e;
		}
	}

	@Test
	public void testWriteContent() {
		try {
			// insert your preferred way of writing to the repo here
			// I'm actually about to add a bunch of lower-level write stuff
			// for the access control, but that's not in place now.
			Flosser flosser = new Flosser(testprefix);
			CCNFlowControl fc = new CCNFlowControl(testprefix, CCNLibrary.open());
			
			KeyRepository kr = KeyManager.getDefaultKeyManager().keyRepository();
			for (int i=0; i < KEY_COUNT; ++i) {
				kr.publishKey(keyLocs[i].name().name(), pairs[i].getPublic(), publishers[i], pairs[i].getPrivate());
			}

			Random rand = new Random();
			for (int i=0; i < DATA_COUNT_PER_KEY; ++i) {
				byte [] buf = new byte[1024];
				rand.nextBytes(buf);
				byte [] digest = CCNDigestHelper.digest(buf);
				// make the names strings if it's clearer, this allows you to pull the name
				// and compare it to the actual content digest 
				ContentName dataName = new ContentName(dataprefix, digest);
				for (int j=0; j < KEY_COUNT; ++j) {
					SignedInfo si = new SignedInfo(publishers[j], keyLocs[j]);
					ContentObject co = new ContentObject(dataName, si, buf, pairs[j].getPrivate());
					System.out.println("Key " + j + ": " + publishers[j] + " signed content " + i + ": " + dataName);
					fc.put(co);
				}
			}
			flosser.stop();
		} catch (Exception e) {
			System.out.println("Exception in testWriteContent: " + e.getClass().getName() + ": " + e.getMessage());
			e.printStackTrace();
			Assert.fail("Exception in testWriteContent: " + e.getClass().getName() + ": " + e.getMessage());
		}
	}

}
