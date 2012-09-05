/*
 * A CCNx library test.
 *
 * Copyright (C) 2010-2012 Palo Alto Research Center, Inc.
 *
 * This work is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License version 2 as published by the
 * Free Software Foundation. 
 * This work is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License
 * for more details. You should have received a copy of the GNU General Public
 * License along with this program; if not, write to the
 * Free Software Foundation, Inc., 51 Franklin Street, Fifth Floor,
 * Boston, MA 02110-1301, USA.
 */

package org.ccnx.ccn.test;

import java.security.DigestOutputStream;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.Random;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.UserConfiguration;
import org.ccnx.ccn.impl.CCNNetworkManager;
import org.ccnx.ccn.impl.CCNFlowControl.SaveType;
import org.ccnx.ccn.impl.security.crypto.CCNDigestHelper;
import org.ccnx.ccn.impl.security.crypto.util.SignatureHelper;
import org.ccnx.ccn.impl.support.Tuple;
import org.ccnx.ccn.io.NoMatchingContentFoundException;
import org.ccnx.ccn.io.NullOutputStream;
import org.ccnx.ccn.io.content.CCNStringObject;
import org.ccnx.ccn.profiles.SegmentationProfile;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Interest;
import org.ccnx.ccn.protocol.Signature;
import org.junit.BeforeClass;
import org.junit.Test;


/**
 * This is not a unit test designed to verify functionality.
 * Instead, this test times some operations for basic benchmarking.
 * @author jthornto
 *
 */
public class BenchmarkTest {

	public static final int NUM_ITER = 1000;
	public static final int NUM_KEYGEN = 100; // Key generation is really slow
	
	public static final double NanoToMilli = 1000000.0d;
	public static final double NanoToSec = 1000000000000.0d;
	
	public static CCNTestHelper testHelper = new CCNTestHelper(BenchmarkTest.class);
	public static CCNHandle handle;

	public static ContentName testName;
	public static byte[] shortPayload;
	public static byte[] longPayload;
	public static byte[] veryLongPayload;
	public static byte [][] payloads;
	
	// Need to benchmark multiple key lengths
	public static final int [] keyLengths = new int[]{512, 1024, 2048};
	
	// Need to benchmark multiple digest algorithms. MD5 not used for signing,
	// but is used for non-security critical applications.
	public static final String [] digestAlgorithms = new String[]{"MD5", "SHA1", CCNDigestHelper.DEFAULT_DIGEST_ALGORITHM};

	public static final int LONG_LENGTH = 1000;
	public static final int VERY_LONG_LENGTH = 4096; // our actual packet length
	
	public static ContentObject [] contentObjects;
	public static ContentObject [] unsignedContentObjects;
	
	// Default algorithm, add ECC later
	public static KeyPair[] keyPairs = new KeyPair[keyLengths.length];
	
	public static NumberFormat format = DecimalFormat.getNumberInstance();
	
	static abstract class Operation<T extends Object,U extends Object> {
		abstract Object execute(T input, U parameter) throws Exception;
		
		int size(T input) {
			if (null == input) {
				return -1;
			} else if (input instanceof byte[]) {
				return ((byte[])input).length;
			} else if (input instanceof ContentObject) {
				return ((ContentObject)input).content().length;
			} else {
				throw new RuntimeException("Unsupported input type " + input.getClass());
			}
		}

		public void runBenchmark(String desc, T input, U parameter) throws Exception {
			runBenchmark(NUM_ITER, desc, input, parameter);
		}

		public void runBenchmark(int count, String desc, T input, U parameter) throws Exception {
			long start = System.nanoTime();
			execute(input, parameter);
			long dur = System.nanoTime() - start;
			//System.out.println("Start " + start + " dur " + dur);
			int size = size(input);
			System.out.println("Initial time to " + desc + (size >= 0 ? " (payload " + size(input) + " bytes)" : "") +
					" = " + dur/NanoToMilli + " ms.");
			
			start = System.nanoTime();
			for (int i=0; i<count; i++) {
				execute(input, parameter);
			}
			dur = System.nanoTime() - start;
			System.out.println("Avg. to " + desc + " (" + count + " iterations) = " + 
					dur/count/NanoToMilli + " ms. (" + 
					format.format(NanoToSec/dur) + " operations/sec)");		
		}
	}
	
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		ContentName namespace = testHelper.getTestNamespace("benchmarkTest");
		testName = new ContentName(namespace, "BenchmarkObject");
		testName = VersioningProfile.addVersion(testName);
		shortPayload = ("this is sample segment content").getBytes();
		longPayload = new byte[LONG_LENGTH];
		Random rnd = new Random();
		rnd.nextBytes(longPayload);
		veryLongPayload = new byte[VERY_LONG_LENGTH];
		rnd.nextBytes(veryLongPayload);
		payloads = new byte [][]{shortPayload, longPayload, veryLongPayload};
		
		contentObjects = new ContentObject[payloads.length];
		unsignedContentObjects = new ContentObject[payloads.length];
		// Should have content objects signed by multiple key types...
		ContentName segmentName = SegmentationProfile.segmentName(testName, 0);
		for (int i=0; i < payloads.length; ++i) {
			contentObjects[i] = ContentObject.buildContentObject(segmentName, payloads[i], null, null, SegmentationProfile.getSegmentNumberNameComponent(0));
			unsignedContentObjects[i] = new ContentObject(contentObjects[i].name(), contentObjects[i].signedInfo(), contentObjects[i].content(), (Signature) null);
		}
		
		final KeyPairGenerator kpg = KeyPairGenerator.getInstance(UserConfiguration.defaultKeyAlgorithm());
		for (int i=0; i < keyLengths.length; ++i) {
			kpg.initialize(keyLengths[i]);
			keyPairs[i] = kpg.generateKeyPair();
		}
		format.setMaximumFractionDigits(3);
		
		handle = CCNHandle.open();
		System.out.println("Benchmark Test starting on " + System.getProperty("os.name"));
	}

	@Test
	public void testDigest() throws Exception {
		System.out.println("==== Digests");
		Operation<byte[],String> digest = new Operation<byte[],String>() {
			Object execute(byte[] input, String algorithm) throws Exception {
				MessageDigest md = MessageDigest.getInstance(algorithm);
				md.update(input);
				return md.digest();
			}
		};
		for (int i=0; i < digestAlgorithms.length; ++i) {
			System.out.println("--- Raw = digest only of byte[] using " + digestAlgorithms[i]);
			for (int j=0; j<payloads.length; ++j) {
				digest.runBenchmark("raw digest (" + payloads[j].length + " bytes)", 
						payloads[j], digestAlgorithms[i]);
			}
			System.out.println("");
		}

		
		Operation<ContentObject, String> digestObj = new Operation<ContentObject, String>() {
			Object execute(ContentObject input, String algorithm) throws Exception {
				MessageDigest md = MessageDigest.getInstance(algorithm);
				DigestOutputStream dos = new DigestOutputStream(new NullOutputStream(), md);
				input.encode(dos);
				return md.digest();
			}
		};

		for (int i=0; i < digestAlgorithms.length; ++i) {
			System.out.println("--- Raw = digest of contentObject using " + digestAlgorithms[i]);
			for (int j=0; j<contentObjects.length; ++j) {
				digestObj.runBenchmark("ContentObject digest (content " + contentObjects[j].contentLength() + " bytes) ", 
						contentObjects[j], digestAlgorithms[i]);
			}
			System.out.println("");
		}
	}
		
	@Test
	public void testEncode() throws Exception {
		System.out.println("==== Encoding");
		
		Operation<ContentObject, String> encodeObj = new Operation<ContentObject, String>() {
			Object execute(ContentObject input, String codec) throws Exception {
				return input.encode(codec);
			}
		};

		for (int j=0; j<contentObjects.length; ++j) {
			encodeObj.runBenchmark("ContentObject encode (content " + contentObjects[j].contentLength() + " bytes) ", 
					contentObjects[j], null);
		}
		System.out.println("");

		Operation<ContentObject, Object> prepareObj = new Operation<ContentObject, Object>() {
			Object execute(ContentObject input, Object ignored) throws Exception {
				return ContentObject.prepareContent(input.name(), input.signedInfo(), input.content());
			}
		};

		System.out.println("Prepare content: perform the encoding steps necessary for signing:");
		for (int j=0; j<contentObjects.length; ++j) {
			prepareObj.runBenchmark("ContentObject prepareDigest (content " + contentObjects[j].contentLength() + " bytes) ", 
					contentObjects[j], null);
		}
		System.out.println("");

	}

	@Test
	public void testRawSigning() throws Exception {
		
		// We need to be able to benchmark signing using multiple algorithms;
		// probably should make this depend on digest algorithm as well. 
		// For right now, just parameterize on key size.
		// Add ECC once we can test for its presence
		Operation<byte[], Tuple<String,PrivateKey>> sign = new Operation<byte[], Tuple<String,PrivateKey>>() {

			Object execute(byte[] input, Tuple<String,PrivateKey> signingParams) throws Exception {
				return SignatureHelper.sign(signingParams.first(), input, signingParams.second());
			}
		};
		
		// Need to handle multiple length of key, and at least SHA1 and SHA256
		for (int i = 1; i < digestAlgorithms.length; ++i) {
			// skip MD-5
			System.out.println("==== PK Signing: Digest: " + digestAlgorithms[i]);
			for (int j=0; j < keyPairs.length; ++j) {
				System.out.println("======= " + keyLengths[j] + "-bit " + keyPairs[j].getPublic().getAlgorithm() + " Key with " + digestAlgorithms[i] + ":");
				for (int k=0; k < payloads.length; ++k) {
					sign.runBenchmark("sign " + payloads[k].length + " bytes ",
							payloads[k], new Tuple<String,PrivateKey>(digestAlgorithms[i],keyPairs[j].getPrivate()));
				}
				System.out.println("");
			}
		}
		System.out.println("");
		
		Operation<Tuple<byte[],byte[]>, Tuple<String,PublicKey>> verify = 
			new Operation<Tuple<byte[],byte[]>, Tuple<String,PublicKey>>() {
			
			Object execute(Tuple<byte[],byte[]> input, Tuple<String,PublicKey> verifyParams) throws Exception {
				return SignatureHelper.verify(input.first(), input.second(), 
						verifyParams.first(), verifyParams.second());
			}
			
			int size(Tuple<byte[], byte[]> input) {
				return input.first().length;
			}
		};

		for (int i = 1; i < digestAlgorithms.length; ++i) {
			// skip MD-5
			System.out.println("==== PK Verifying: Digest: " + digestAlgorithms[i]);
			for (int j=0; j < keyPairs.length; ++j) {
				System.out.println("======= " + keyLengths[j] + "-bit " + keyPairs[j].getPublic().getAlgorithm() + " Key with " + digestAlgorithms[i] + ":");
				for (int k=0; k < payloads.length; ++k) {
					byte [] signature = (byte[])sign.execute(payloads[k], new Tuple<String,PrivateKey>(digestAlgorithms[i],keyPairs[j].getPrivate()));
					verify.runBenchmark("verify " + payloads[k].length + " bytes ",
							new Tuple<byte [], byte[]>(payloads[k],signature), 
							new Tuple<String,PublicKey>(digestAlgorithms[i],keyPairs[j].getPublic()));
				}
				System.out.println("");
			}
		}
		System.out.println("");
		
	}
	
	@Test
	public void testObjectSigning() throws Exception {
		
		// We need to be able to benchmark signing using multiple algorithms;
		// probably should make this depend on digest algorithm as well. 
		// For right now, just parameterize on key size.
		// Add ECC once we can test for its presence
		Operation<ContentObject, Tuple<String,PrivateKey>> objSign = new Operation<ContentObject, Tuple<String,PrivateKey>>() {

			Object execute(ContentObject input, Tuple<String,PrivateKey> signingParams) throws Exception {
				input.setSignature(null); // avoid warning
				input.sign(signingParams.first(), signingParams.second());
				return null;
			}
		};
		
		Operation<ContentObject, PublicKey> objVerify = 
			new Operation<ContentObject, PublicKey>() {
			
			Object execute(ContentObject input, PublicKey publicKey) throws Exception {
				return input.verify(publicKey);
			}
			
			int size(ContentObject input) {
				return input.contentLength();
			}
		};

		// Need to handle multiple length of key, and at least SHA1 and SHA256
		for (int i = 1; i < digestAlgorithms.length; ++i) {
			// skip MD-5
			System.out.println("==== PK Object Signing/Verifying: Digest: " + digestAlgorithms[i]);
			for (int j=0; j < keyPairs.length; ++j) {
				System.out.println("======= " + keyLengths[j] + "-bit " + keyPairs[j].getPublic().getAlgorithm() + " Key:");
				for (int k=0; k < unsignedContentObjects.length; ++k) {
					objSign.runBenchmark("sign " + unsignedContentObjects[k].contentLength() + " bytes ",
							unsignedContentObjects[k], 
							new Tuple<String,PrivateKey>(digestAlgorithms[i],keyPairs[j].getPrivate()));
					objVerify.runBenchmark("verify " + unsignedContentObjects[k].contentLength() + " bytes ", 
							unsignedContentObjects[k], keyPairs[j].getPublic());
				}
				System.out.println("");
			}
		}
		System.out.println("");
	}

	@Test
	public void testKeyGen() throws Exception {
		Operation<Object, Tuple<KeyPairGenerator, Integer>> genpair = new Operation<Object, Tuple<KeyPairGenerator, Integer>>() {
			Object execute(Object input, Tuple<KeyPairGenerator,Integer> keyGenParams) throws Exception {
				KeyPairGenerator kpg = keyGenParams.first();
				kpg.initialize(keyGenParams.second());
				KeyPair userKeyPair = kpg.generateKeyPair();
				return userKeyPair;
			}		
		};	
		
		// Do ECC as well, once we can test for presence of ECC.
		final KeyPairGenerator kpg = KeyPairGenerator.getInstance(UserConfiguration.defaultKeyAlgorithm());
		
		for (int i = 0; i < keyLengths.length; ++i) {
			System.out.println("==== Key Generation: " + keyLengths[i] + "-bit " + UserConfiguration.defaultKeyAlgorithm() + " key.");
			genpair.runBenchmark(NUM_KEYGEN, "generate keypair", null, new Tuple<KeyPairGenerator, Integer>(kpg, keyLengths[i]));
		}
	}
	
	@Test
	public void testCcndRetrieve() throws Exception {
		// Floss some content into ccnd
		ContentName dataPrefix = testHelper.getTestNamespace("TestCcndRetrieve");

		Flosser floss = new Flosser(dataPrefix);
		CCNStringObject so = new CCNStringObject(dataPrefix, "This is the value", SaveType.RAW, handle);
		so.save();
		ContentName name = so.getVersionedName();
		so.close();
		floss.stop();
		
		// Now that content is in local ccnd, we can benchmark retrieval of one content item
		Operation<Interest, Object> getcontent = new Operation<Interest, Object>() {
			Object execute(Interest interest, Object ignored) throws Exception {
				// Note as of this writing, interest refresh was PERIOD*2 with no constant in net mgr
				// We will use PERIOD for now, as we want to be sure to avoid refreshes and this should be fast.
				ContentObject result = handle.get(interest, CCNNetworkManager.PERIOD);
				// Make sure to throw exception if we get nothing back so this doesn't just 
				// look like a long successful run.
				if (null == result) {
					throw new NoMatchingContentFoundException("timeout on get for " + interest.name());
				}
				return null;
			}
			
			int size(Interest interest) {
				return -1;
			}
		};
		Interest interest = new Interest(name);
		System.out.println("==== Single data retrieval from ccnd: " + name);
		getcontent.runBenchmark("retrieve data", interest, null);
	}
}
