/*
 * A CCNx library test.
 *
 * Copyright (C) 2008, 2009, 2011 Palo Alto Research Center, Inc.
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

package org.ccnx.ccn.test.security.crypto;


import java.util.Arrays;
import java.util.Random;

import org.bouncycastle.asn1.x509.DigestInfo;
import org.ccnx.ccn.impl.security.crypto.CCNDigestHelper;
import org.ccnx.ccn.impl.security.crypto.MerklePath;
import org.ccnx.ccn.impl.security.crypto.MerkleTree;
import org.ccnx.ccn.impl.support.Log;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Test the low-level Merkle hash tree implementation.
 */
public class MerkleTreeTest {

	protected static Random _rand = new Random(); // don't need SecureRandom
	
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
	}

	@Before
	public void setUp() throws Exception {
	}
	
	@Test
	public void testMerkleTree() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testMerkleTree");

		int [] sizes = new int[]{128,256,512,4096};
		
		try {
			testTree(0, sizes[0], false);
			Assert.fail("MerkleTree should throw an exception for tree sizes < 2.");
		} catch (IllegalArgumentException e) {
			// ok
		}
		
		try {
			testTree(1, sizes[0], false);
			Assert.fail("MerkleTree should throw an exception for tree sizes < 2.");
		} catch (IllegalArgumentException e) {
			// ok
		}
		
		System.out.println("Testing small trees.");
		for (int i=2; i < 515; ++i) {
			testTree(i,sizes[i%sizes.length],false);
		}
		
		System.out.println("Testing large trees.");
		int [] nodecounts = new int[]{1000,1001,1025,1098,1536,1575,2053,5147,8900,9998,9999,10000};
		for (int i=0; i < nodecounts.length; ++i) {
			testTree(nodecounts[i],sizes[i%sizes.length],false);
		}
		
		Log.info(Log.FAC_TEST, "Completed testMerkleTree");
	}
	
	public static void testTree(int numLeaves, int nodeLength, boolean digest) throws Exception {
		try {
			byte [][] data = makeContent(numLeaves, nodeLength, digest);
			testTree(data, numLeaves, digest);
		} catch (Exception e) {
			System.out.println("Building tree of " + numLeaves + " Nodes. Caught a " + e.getClass().getName() + " exception: " + e.getMessage());
			throw e;
		}
	}
	
	public static byte [][] makeContent(int numNodes, int nodeLength, boolean digest) {
		
		byte [][] bufs = new byte[numNodes][];
		byte [] tmpbuf = null;
		
		if (digest)
			tmpbuf = new byte[nodeLength];
		
		int blocklen = (digest ? CCNDigestHelper.DEFAULT_DIGEST_LENGTH  : nodeLength);
		
		for (int i=0; i < numNodes; ++i) {
			bufs[i] = new byte[blocklen];
			
			if (digest) {
				_rand.nextBytes(tmpbuf);
				bufs[i] = CCNDigestHelper.digest(tmpbuf);
			} else {
				_rand.nextBytes(bufs[i]);
			}
		}
		return bufs;
	}
	
	public static void testTree(byte [][] content, int count, boolean digest) {
		// Generate a merkle tree. Verify each path for the content.
		MerkleTree tree = new MerkleTree(content, digest, count, 0, 
										((count-1) >= 0) && ((count-1) < content.length) ? content[count-1].length : 0);
				
		MerklePath [] paths = new MerklePath[count];
		for (int i=0; i < count; ++i) {
			paths[i] = tree.path(i);
			//checkPath(tree, paths[i]);
			byte [] root = paths[i].root(content[i],digest);
			boolean result = Arrays.equals(root, tree.root());
			if (!result) {
				System.out.println("Constructed tree of " + count + " blocks (of " + content.length + "), numleaves: " + 
						tree.numLeaves() + " max pathlength: " + tree.maxDepth());
				System.out.println("Path " + i + " verified for leaf " + paths[i].leafNodeIndex() + "? " + result);
			}
			Assert.assertTrue("Path " + i + " failed to verify.", result);
			
			try {
				byte [] encodedPath = paths[i].derEncodedPath();
				DigestInfo info = CCNDigestHelper.digestDecoder(encodedPath);
				MerklePath decoded = new MerklePath(info.getDigest());
				if (!decoded.equals(paths[i])) {
					System.out.println("Path " + i + " failed to encode and decode.");
					Assert.fail("Path " + i + " failed to encode and decode.");
				}
			} catch (Exception e) {
				System.out.println("Exception encoding path " + i + " :" + e.getClass().getName() + ": " + e.getMessage());
				e.printStackTrace();
				Assert.fail("Exception encoding path " + i + " :" + e.getClass().getName() + ": " + e.getMessage());
			}
		}
	}
	
	protected static void checkPath(MerkleTree tree, MerklePath path) {
		
		// Check path against the tree, and see if it contains the hashes it should.
		System.out.println("Checking path for nodeID: " + path.leafNodeIndex() + " path length: " + path.pathLength() + " num components: " + path.pathLength());
		StringBuffer buf = new StringBuffer("Path nodes: ");
		
		for (int i=0; i < path.pathLength(); ++i) {
			buf.append(tree.getNodeIndex(path.entry(i)));
			buf.append(" ");
		}
		System.out.println(buf.toString());
	}

}
