package org.ccnx.ccn.test.impl.security.keys;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

import org.ccnx.ccn.KeyManager;
import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.impl.CCNFlowControl.SaveType;
import org.ccnx.ccn.impl.security.keys.SecureKeyCache;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.CCNStringObject;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;
import org.ccnx.ccn.test.CCNTestBase;
import org.ccnx.ccn.test.CCNTestHelper;
import org.ccnx.ccn.test.Flosser;
import org.junit.Assert;
import org.junit.Test;

public class SymmetricKeyTest extends CCNTestBase {
	
	static Flosser flosser = null;
	
	static CCNTestHelper testHelper = new CCNTestHelper(SymmetricKeyTest.class);

	@Test
	public void testSymmetricKeys() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testSymmetricKeys");
		
		flosser = new Flosser();
		KeyGenerator kg = KeyGenerator.getInstance("HMAC-SHA256", KeyManager.PROVIDER);
		SecretKey sk = kg.generateKey();
		SecureKeyCache skc = putHandle.getNetworkManager().getKeyManager().getSecureKeyCache();
		PublisherPublicKeyDigest publisher = new PublisherPublicKeyDigest(sk);
		skc.addSecretKey(null, publisher.digest(), sk);
		ContentName name = testHelper.getTestChildName("testSetLocator", "testString");
		CCNStringObject testString1 = new CCNStringObject(name, "A test!", 
									SaveType.RAW, publisher, null, putHandle);
		flosser.handleNamespace(name);
		testString1.save();
		CCNStringObject testString2 = new CCNStringObject(name, publisher,getHandle);
		testString2.waitForData(SystemConfiguration.EXTRA_LONG_TIMEOUT);
		Assert.assertEquals(testString2.string(), "A test!");
		testString1.close();
		testString2.close();
		flosser.stopMonitoringNamespaces();
	}
}
