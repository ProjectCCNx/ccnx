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

import java.io.ByteArrayOutputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.ccnx.ccn.impl.security.crypto.CCNDigestHelper;
import org.ccnx.ccn.io.NullOutputStream;
import org.ccnx.ccn.io.content.ContentEncodingException;
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
	
	public static final double NanoToMilli = 1000000.0;
	
	public static CCNTestHelper testHelper = new CCNTestHelper(BenchmarkTest.class);

	public static ContentName testName;

	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		ContentName namespace = testHelper.getTestNamespace("benchmarkTest");
		testName = ContentName.fromNative(namespace, "BenchmarkObject");
		testName = VersioningProfile.addVersion(testName);
	}

	@Test
	public void testDigest() throws Exception {

		// Base case: short payload
		byte[] toWrite = ("this is sample segment content").getBytes();
		ContentName segment = SegmentationProfile.segmentName(testName, 0);
		ContentObject obj = ContentObject.buildContentObject(segment, toWrite, null, null, SegmentationProfile.getSegmentNumberNameComponent(0));
		long start = System.nanoTime();
		digest(obj);
		long dur = System.nanoTime() - start;
		System.out.println("Time to compute initial short digest " + dur/NanoToMilli + " ms.");
		start = System.nanoTime();
		digest(obj);
		dur = System.nanoTime() - start;
		System.out.println("Time to compute second short digest " + dur/NanoToMilli + " ms.");
		start = System.nanoTime();
		for (int i=0; i<NUM_DIGESTS; i++) {
			digest(obj);
		}
		dur = System.nanoTime() - start;
		System.out.println("Avg. time to compute short digest over " + NUM_DIGESTS + " trials = " + dur/NUM_DIGESTS/NanoToMilli + " ms.");
	
		// Longer payload case
		byte[] toWriteLong = new byte[1024];
		segment = SegmentationProfile.segmentName(testName, 1);
		obj = ContentObject.buildContentObject(segment, toWriteLong, null, null, SegmentationProfile.getSegmentNumberNameComponent(1));
		start = System.nanoTime();
		for (int j=0; j<NUM_DIGESTS; j++) {
			digest(obj);
		}
		dur = System.nanoTime() - start;
		System.out.println("Avg. time to compute long digest over " + NUM_DIGESTS + " trials = " + dur/NUM_DIGESTS/NanoToMilli + " ms.");

		// Reference case: running raw digest computation on encoded only
		ByteArrayOutputStream baos = new ByteArrayOutputStream(1500);
		obj.encode(baos);
		byte[] toDigest = baos.toByteArray();
		start = System.nanoTime();
		for (int k=0; k<NUM_DIGESTS; k++) {
			digestRaw(toDigest);
		}
		dur = System.nanoTime() - start;
		System.out.println("Avg. time to compute long digest without encoding over " + NUM_DIGESTS + " trials = " + dur/NUM_DIGESTS/NanoToMilli + " ms.");
			
	}
	
	// the ContentObject.digest() method saves the result to avoid computation on every request
	// we want the repeated computation for benchmarking
	public static byte[] digest(ContentObject obj) throws NoSuchAlgorithmException, ContentEncodingException {
		MessageDigest md = MessageDigest.getInstance(CCNDigestHelper.DEFAULT_DIGEST_ALGORITHM);
		DigestOutputStream dos = new DigestOutputStream(new NullOutputStream(), md);
		obj.encode(dos);
		return md.digest();
	}
	
	// For comparison, simply run the data through the digest directly, no encoding
	public static byte[] digestRaw(byte[] input) throws NoSuchAlgorithmException {
		MessageDigest md = MessageDigest.getInstance(CCNDigestHelper.DEFAULT_DIGEST_ALGORITHM);
		md.update(input);
		return md.digest();
	}
}
