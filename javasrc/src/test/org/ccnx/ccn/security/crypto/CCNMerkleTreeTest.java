/*
 * A CCNx library test.
 *
 * Copyright (C) 2008-2012 Palo Alto Research Center, Inc.
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

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.util.Random;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.ccnx.ccn.impl.security.crypto.CCNDigestHelper;
import org.ccnx.ccn.impl.security.crypto.CCNMerkleTree;
import org.ccnx.ccn.impl.support.DataUtils;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.profiles.SegmentationProfile;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.KeyLocator;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;
import org.ccnx.ccn.protocol.Signature;
import org.ccnx.ccn.protocol.SignedInfo;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Test CCN-specific Merkle tree functionality.
 */
public class CCNMerkleTreeTest {

	protected static Random _rand = new Random(); // don't need SecureRandom
	
	protected static KeyPair _pair = null;
	static ContentName keyname = new ContentName("test","keys","treeKey");
	static ContentName baseName = new ContentName("test","data","treeTest");

	static KeyPair pair = null;
	static PublisherPublicKeyDigest publisher = null;
	static KeyLocator keyLoc = null;
	
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		try {
			Security.addProvider(new BouncyCastleProvider());
			
			// generate key pair
			KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
			kpg.initialize(512); // go for fast
			pair = kpg.generateKeyPair();
			publisher = new PublisherPublicKeyDigest(pair.getPublic());
			keyLoc = new KeyLocator(pair.getPublic());
		} catch (Exception e) {
			System.out.println("Exception in test setup: " + e.getMessage());
			e.printStackTrace();
			throw e;
		}
	}

	
	@Test
	public void testMerkleTree() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testMerkleTree");

		int [] sizes = new int[]{128,256,512,4096};
		
		try {
			testTree(0, sizes[0], false);
			Assert.fail("CCNMerkleTree should throw an exception for tree sizes < 2.");
		} catch (IllegalArgumentException e) {
			// ok
		}
		
		try {
			testTree(1, sizes[0], false);
			Assert.fail("CCNMerkleTree should throw an exception for tree sizes < 2.");
		} catch (IllegalArgumentException e) {
			// ok
		}

		System.out.println("Testing small trees, fixed block widths.");
		for (int i=2; i < 515; ++i) {
			testTree(i,sizes[i%sizes.length],false);
		}
		
		System.out.println("Testing large trees, fixed block widths.");
		int [] nodecounts = new int[]{1000,1001,1025,1098,1536,1575,2053,5147,8900,9998};
//		int [] nodecounts = new int[]{1000,1001,1025,1098,1536,1575,2053,5147,8900,9998,9999,10000};
		for (int i=0; i < nodecounts.length; ++i) {
			testTreeWrapper(nodecounts[i],sizes[i%sizes.length],false);
		}
		
		Log.info(Log.FAC_TEST, "Completed testMerkleTree");
	}
	
	@Test
	public void testMerkleTreeBuf() {
		Log.info(Log.FAC_TEST, "Starting testMerkleTreeBuf");

		int [] sizes = new int[]{128,256,512,4096};
		System.out.println("Testing small trees, random block widths.");
		for (int i=10; i < 515; ++i) {
			testTreeWrapper(i,sizes[i%sizes.length], true);
		}
		
		Log.info(Log.FAC_TEST, "Completed testMerkleTreeBuf");
	}

	public static void testTreeWrapper(int testNodeCount, int blockWidth, boolean randomWidths) {
		try {
			testTree(testNodeCount, blockWidth, randomWidths);
		} catch (Exception e) {
			System.out.println("Building tree of " + testNodeCount + " nodes, random widths? " + randomWidths + ". Caught a " + e.getClass().getName() + " exception: " + e.getMessage());
			e.printStackTrace();
			Assert.fail("Exception " + e.getClass().getName() + " in testTreeWrapper.");
		}
	}

	public static ContentObject [] makeContent(ContentName rootName, int numNodes, int maxLength, boolean randLengths) {
		
		ContentObject [] cos = new ContentObject[numNodes];
		byte [][] bufs = new byte[numNodes][];
		
		SignedInfo si = new SignedInfo(publisher, keyLoc);
		for (int i=0; i < numNodes; ++i) {
			int blockLen = 0;
			if (randLengths) {
				while (blockLen == 0) {
					blockLen = Math.abs(_rand.nextInt(maxLength));
				}
			} else {
				blockLen = maxLength;
			}
			bufs[i] = new byte[blockLen];
			_rand.nextBytes(bufs[i]);
			
			cos[i] = 
				new ContentObject(
						SegmentationProfile.segmentName(rootName, (i + SegmentationProfile.baseSegment())),
					si, bufs[i], (Signature)null);
		}
		
		return cos;
	}
	
	
	public static void testTree(int nodeCount, int blockWidth, boolean randomWidths) throws Exception {
		int version = _rand.nextInt(1000);
		ContentName theName = new ContentName(baseName, "testDocBuffer.txt");
		theName = VersioningProfile.addVersion(theName, version);
		
		try {
			ContentObject [] cos = makeContent(theName, nodeCount, blockWidth, randomWidths);
			
			// TODO DKS Need to do offset versions with different ranges of fragments
			// Generate a merkle tree. Verify each path for the content.
			CCNMerkleTree tree = new CCNMerkleTree(cos,
													pair.getPrivate());
			tree.setSignatures();
		
			System.out.println("Constructed tree of numleaves: " + 
										tree.numLeaves() + " max pathlength: " + tree.maxDepth());
		
			ContentObject block;
			for (int i=0; i < tree.numLeaves()-1; ++i) {
				block = cos[i];
				boolean result = block.verify(pair.getPublic());
				if (!result) {
					System.out.println("Block name: " + tree.segmentName(i) + " num "  + i + " verified? " + result + ", content: " + DataUtils.printBytes(block.digest()));
					byte [] digest = CCNDigestHelper.digest(block.encode());
					byte [] tbsdigest = 
						CCNDigestHelper.digest(block.prepareContent());
					System.out.println("Raw content digest: " + DataUtils.printBytes(CCNDigestHelper.digest(block.content())) +
							" object content digest:  " + DataUtils.printBytes(CCNDigestHelper.digest(block.content())));
					System.out.println("Block: " + block.name() + " timestamp: " + block.signedInfo().getTimestamp() + " encoded digest: " + DataUtils.printBytes(digest) + " tbs content: " + DataUtils.printBytes(tbsdigest));
				} else if (i % 100 == 0) {
					System.out.println("Block name: " + tree.segmentName(i) + " num "  + i + " verified? " + result + ", content: " + DataUtils.printBytes(block.digest()));					
				}
				Assert.assertTrue("Path " + i + " failed to verify.", result);
			}
			tree = null;
		} catch (Exception e) {
			System.out.println("Exception in testTree: " + e.getClass().getName() + ": " + e.getMessage());
			e.printStackTrace();
			throw(e); // must re-throw rather than assert fail; one test actually expects an exception
		}
	}
}
