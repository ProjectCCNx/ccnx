/**
 * A CCNx library test.
 *
 * Copyright (C) 2010 Palo Alto Research Center, Inc.
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
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.util.Random;

import org.ccnx.ccn.KeyManager;
import org.ccnx.ccn.impl.security.crypto.CCNDigestHelper;
import org.ccnx.ccn.impl.security.crypto.util.SignatureHelper;
import org.ccnx.ccn.io.NullOutputStream;
import org.ccnx.ccn.profiles.SegmentationProfile;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.junit.BeforeClass;
import org.junit.Test;


/**
 * This is not a unit test designed to verify functionality.
 * Instead, this test times some operations for basic benchmarking.
 * @author jthornto
 *
 */
public class BenchmarkTest {

	public static final int NUM_DIGESTS = 1000;
	public static final int NUM_SIGNATURES = 1000;
	public static final int NUM_ITER = 1000;
	
	public static final double NanoToMilli = 1000000.0d;
	
	public static CCNTestHelper testHelper = new CCNTestHelper(BenchmarkTest.class);

	public static ContentName testName;
	public static byte[] shortPayload;
	public static byte[] longPayload;

	public abstract class Operation<T> {
		abstract void execute(T input) throws Exception;
		
		int size(T input) {
			if (input instanceof byte[]) {
				return ((byte[])input).length;
			} else if (input instanceof ContentObject) {
				return ((ContentObject)input).content().length;
			} else {
				throw new RuntimeException("Unsupported input type " + input.getClass());
			}
		}
	}
	
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		ContentName namespace = testHelper.getTestNamespace("benchmarkTest");
		testName = ContentName.fromNative(namespace, "BenchmarkObject");
		testName = VersioningProfile.addVersion(testName);
		shortPayload = ("this is sample segment content").getBytes();
		longPayload = new byte[1000];
		Random rnd = new Random();
		rnd.nextBytes(longPayload);
		System.out.println("Benchmark Test starting on " + System.getProperty("os.name"));
	}

	@SuppressWarnings("unchecked")
	public void runBenchmark(String desc, Operation op, Object input) throws Exception {
		long start = System.nanoTime();
		op.execute(input);
		long dur = System.nanoTime() - start;
		//System.out.println("Start " + start + " dur " + dur);
		System.out.println("Initial time to " + desc + " (payload " + op.size(input) + " bytes) = " + dur/NanoToMilli + " ms.");
		
		start = System.nanoTime();
		for (int i=0; i<NUM_ITER; i++) {
			op.execute(input);
		}
		dur = System.nanoTime() - start;
		System.out.println("Avg. to " + desc + " (" + NUM_ITER + " iterations) = " + dur/NUM_ITER/NanoToMilli + " ms.");		
	}
	@Test
	public void testDigest() throws Exception {
		System.out.println("==== Digest Benchmarks");
		Operation<byte[]> digest = new Operation<byte[]>() {
			void execute(byte[] input) throws Exception {
				MessageDigest md = MessageDigest.getInstance(CCNDigestHelper.DEFAULT_DIGEST_ALGORITHM);
				md.update(input);
				md.digest();
			}
		};
		System.out.println("--- Raw = digest only of byte[]");
		runBenchmark("raw digest short", digest, shortPayload);
		runBenchmark("raw digest long", digest, longPayload);
		ContentName segment = SegmentationProfile.segmentName(testName, 0);
		
		Operation<ContentObject> digestObj = new Operation<ContentObject>() {
			void execute(ContentObject input) throws Exception {
				MessageDigest md = MessageDigest.getInstance(CCNDigestHelper.DEFAULT_DIGEST_ALGORITHM);
				DigestOutputStream dos = new DigestOutputStream(new NullOutputStream(), md);
				input.encode(dos);
				md.digest();
			}
		};
		System.out.println("--- Object = digest of ContentObject");
		ContentObject shortObj = ContentObject.buildContentObject(segment, shortPayload, null, null, SegmentationProfile.getSegmentNumberNameComponent(0));
		ContentObject longObj = ContentObject.buildContentObject(segment, longPayload, null, null, SegmentationProfile.getSegmentNumberNameComponent(0));
		runBenchmark("obj digest short", digestObj, shortObj);
		runBenchmark("obj digest long", digestObj, longObj);
	}
		
	@Test
	public void testRawSigning() throws Exception {
		
		Operation<byte[]> sign = new Operation<byte[]>() {
			KeyManager keyManager = KeyManager.getDefaultKeyManager();
			PrivateKey signingKey = keyManager.getDefaultSigningKey();

			void execute(byte[] input) throws Exception {
				SignatureHelper.sign(CCNDigestHelper.DEFAULT_DIGEST_ALGORITHM, input, signingKey);
			}
		};
		System.out.println("==== PK Signing Benchmarks");
		runBenchmark("sign short", sign, shortPayload);
		runBenchmark("sign long", sign, longPayload);		
	}
}
