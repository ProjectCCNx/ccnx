/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008, 2009, 2011 Palo Alto Research Center, Inc.
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License version 2.1
 * as published by the Free Software Foundation. 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details. You should have received
 * a copy of the GNU Lesser General Public License along with this library;
 * if not, write to the Free Software Foundation, Inc., 51 Franklin Street,
 * Fifth Floor, Boston, MA 02110-1301 USA.
 */

package org.ccnx.ccn.impl.security.crypto;

import java.security.NoSuchAlgorithmException;

import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.ccnx.ccn.impl.support.Log;


/**
 * Implementation of a Merkle hash tree. 
 * 
 * Representation based on Knuth, Vol 1, section 2.3.4.5. We represent
 * trees as a special sublcass of extended binary
 * trees, where empty subtrees are only present in one end
 * of the tree.
 * 
 * Tree nodes are numbered starting with 1, which is the
 * root. 
 * 
 * Tree nodes are stored in an array, with node i stored at index
 * i-1 into the array.
 * 
 * Incomplete binary trees are represented as multi-level extended
 * binary trees -- lower-numbered leaves are represented in the
 * upper half of the tree, in a layer one closer to the root than
 * leaves in the complete subtree.
 * 
 * Total number of nodes in the tree = 2n + 1, where n is the number of leaves.
 * 
 * Taken in terms of node indices (where root == 1), the parent
 * of node k is node floor(k/2), and the children of node k are
 * nodes 2k and 2k+1. Leaves are numbered from node n+1 through 
 * 2n+1, where n is the number of leaves.
 * 
 * The sibling index of node k is (k xor 1).
 * 
 * Should we want to get fancy, we could have t-ary trees; the
 * construction above works for tree with internal nodes (non-leaves)
 * {1,2,...,n}.
 * 
 * The parent of node k is the node floor((k+t-2)/t) = ceil((k-1)/t).
 * The children of node k are:
 * t(k-1)+2, t(k-1)+3,..., tk+1
 * 
 * In the methods below, we refer to nodes as having a "nodeIndex" -- their
 * 1-based index into the node array as described above. Leaf nodes also have
 * a "leafIndex" -- their index into the set of n leaves. Convenience
 * methods are provided to convert between the two.
 * 
 * Store node digests internally as DEROctetStrings for more efficient
 * encoding. 
 */
public class MerkleTree {
	
	/**
	 * Node index of 1 (array index of 0).
	 */
	protected static final int ROOT_NODE = 1;
	
	protected DEROctetString [] _tree;
	protected int _numLeaves;
	protected String _digestAlgorithm;
	
	/**
	 * The OID prefix we use to represent Merkle trees. Derived from PARC-s sub-arc of Xerox's OID.
	 */
	protected static final String MERKLE_OID_PREFIX = "1.2.840.113550.11.1.2";
	
	/**
	 * Build a MerkleTree. This initializes the tree with content, builds the leaf
	 * and intermediate digests, and derives the root digest.
	 * @param digestAlgorithm the digest algorithm to use for computing leaf and
	 *   interior node digests of this tree
	 * @param contentBlocks the segmented leaf content to be hashed into this 
	 * Merkle hash tree. One block per leaf.
	 * @param isDigest are the content blocks raw content (false), or are they already digested
	 * 	  with digestAlgorithm? (default algorithm: CCNDigestHelper#DEFAULT_DIGEST_ALGORITHM)
	 * @param blockCount the number of those blocks to include (e.g. we may not
	 * 	have filled our contentBlocks buffers prior to building the tree). Must be
	 *  at least 2.
	 * @param baseBlockIndex the offset into the contentBlocks array at which to start.
	 * @param lastBlockLength the number of bytes of the last block to use
	 * @throws NoSuchAlgorithmException 
	 */
	public MerkleTree(String digestAlgorithm, 
					  byte contentBlocks[][], boolean isDigest, int blockCount, 
					  int baseBlockIndex, int lastBlockLength) throws NoSuchAlgorithmException {
		this(digestAlgorithm, blockCount);		
		initializeTree(contentBlocks, isDigest, baseBlockIndex, lastBlockLength);
	}
	
	/**
	 * Segment content and build a MerkleTree. This initializes the tree with content, builds the leaf
	 * and intermediate digests, and derives the root digest. Uses CCNDigestHelper#DEFAULT_DIGEST_ALGORITHM.
	 * @param content the content to segment into leaves and hash into this 
	 * Merkle hash tree. One blockWidth of content per leaf, except for the last leaf which may
	 * be shorter.
	 * @param offset offset into content at which to start processing data.
	 * @param length number of bytes of content to process
	 * @param blockWidth the length of leaf blocks to create
	 */
	public MerkleTree(byte [] content, int offset, int length, int blockWidth) {
		this(CCNDigestHelper.DEFAULT_DIGEST_ALGORITHM, blockCount(length, blockWidth));
		try {
			initializeTree(content, offset, length, blockWidth);
		} catch (NoSuchAlgorithmException e) {
			// DKS --big configuration problem
			Log.warning("Fatal Error: cannot find default algorithm " + CCNDigestHelper.DEFAULT_DIGEST_ALGORITHM);
			throw new RuntimeException("Error: can't find default algorithm " + CCNDigestHelper.DEFAULT_DIGEST_ALGORITHM + "!  " + e.toString());
		}
	}

	/**
	 * Segment content and build a MerkleTree. This initializes the tree with content, builds the leaf
	 * and intermediate digests, and derives the root digest. 
	 * @param digestAlgorithm the digest algorithm to use for computing leaf and
	 *   interior node digests of this tree
	 * @param content the content to segment into leaves and hash into this 
	 * Merkle hash tree. One blockWidth of content per leaf, except for the last leaf which may
	 * be shorter.
	 * @param offset offset into content at which to start processing data.
	 * @param length number of bytes of content to process
	 * @param blockWidth the length of leaf blocks to create
	 */
	public MerkleTree(String digestAlgorithm, byte [] content, int offset, int length,
					  int blockWidth) throws NoSuchAlgorithmException {
		this(digestAlgorithm, blockCount(length, blockWidth));
		initializeTree(content, offset, length, blockWidth);
	}

	/**
	 * Build a MerkleTree. This initializes the tree with content, builds the leaf
	 * and intermediate digests, and derives the root digest. Uses CCNDigestHelper#DEFAULT_DIGEST_ALGORITHM.
	 * @param contentBlocks the segmented leaf content to be hashed into this 
	 * Merkle hash tree. One block per leaf.
	 * @param isDigest are the content blocks raw content (false), or are they already digested
	 * 	  with digestAlgorithm? (default algorithm: CCNDigestHelper#DEFAULT_DIGEST_ALGORITHM)
	 * @param blockCount the number of those blocks to include (e.g. we may not
	 * 	have filled our contentBlocks buffers prior to building the tree). Must be
	 *  at least 2.
	 * @param baseBlockIndex the offset into the contentBlocks array at which to start.
	 * @param lastBlockLength the amount of the last block to use
	 * @throws NoSuchAlgorithmException 
	 */
	public MerkleTree(byte contentBlocks[][], boolean isDigest, 
					  int blockCount, int baseBlockIndex, int lastBlockLength) {
		this(CCNDigestHelper.DEFAULT_DIGEST_ALGORITHM, blockCount);		
		try {
			initializeTree(contentBlocks, isDigest, baseBlockIndex, lastBlockLength);
		} catch (NoSuchAlgorithmException e) {
			// DKS --big configuration problem
			Log.warning("Fatal Error: cannot find default algorithm " + CCNDigestHelper.DEFAULT_DIGEST_ALGORITHM);
			throw new RuntimeException("Error: can't find default algorithm " + CCNDigestHelper.DEFAULT_DIGEST_ALGORITHM + "!  " + e.toString());
		}
	}
	
	
	/**
	 * Subclass constructor.
	 * @param digestAlgorithm digest algorithm to use. If null, use CCNDigestHelper#DEFAULT_DIGEST_ALGORITHM.
	 * @param numLeaves the number of leaf nodes to reserve space for
	 */
	protected MerkleTree(String digestAlgorithm, int numLeaves) {
		if (numLeaves < 2) {
			throw new IllegalArgumentException("MerkleTrees must have 2 or more nodes!");
		}
		_digestAlgorithm = (null == digestAlgorithm) ? CCNDigestHelper.DEFAULT_DIGEST_ALGORITHM : digestAlgorithm;
		_numLeaves = numLeaves;
		_tree = new DEROctetString[nodeCount()];
		// Let calling constructor handle building the tree.
	}

	/**
	 * Method called by constructors to fill leaf nodes with digests and compute intermediate
	 * node values up the tree. Does its work by calling computeLeafValues(byte [][], boolean, int, int)
	 * and computeNodeValues().
	 * Separate this out to allow subclasses to initialize members before
	 * building tree.
	 * @throws NoSuchAlgorithmException if the digestAlgorithm specified for this tree is unknown
	 */
	protected void initializeTree(byte contentBlocks[][], boolean isDigest, int baseBlockIndex, int lastBlockLength) throws NoSuchAlgorithmException {
		if ((baseBlockIndex < 0) || (contentBlocks.length-baseBlockIndex < numLeaves()))
			throw new IllegalArgumentException("MerkleTree: cannot build tree from more blocks than given! Have " + (contentBlocks.length-baseBlockIndex) + " blocks, asked to use: " + (numLeaves()));
		
		computeLeafValues(contentBlocks, isDigest, baseBlockIndex, lastBlockLength);
		computeNodeValues();		
	}
	
	/**
	 * Method called by constructors to fill leaf nodes with digests and compute intermediate
	 * node values up the tree. Does its work by calling computeLeafValues(byte [], int, int, int)
	 * and computeNodeValues().
	 * Separate this out to allow subclasses to initialize members before
	 * building tree.
	 * @throws NoSuchAlgorithmException if the digestAlgorithm specified for this tree is unknown
	 */
	protected void initializeTree(byte [] content, int offset, int length, int blockWidth) throws NoSuchAlgorithmException {
		if ((offset < 0) || (length > content.length) || (blockCount(length, blockWidth) < numLeaves()))
			throw new IllegalArgumentException("MerkleTree: cannot build tree from more blocks than given! Have " + blockCount(length, blockWidth) + " blocks, asked to use: " + (numLeaves()));
		
		computeLeafValues(content, offset, length, blockWidth);
		computeNodeValues();		
	}
	
	/**
	 * Returns the digest algorithm used by this tree.
	 * @return the digest algorithm used
	 */
	public String digestAlgorithm() { return _digestAlgorithm; }
	
	/**
	 * Find the index of the parent of this node. 
	 * @param nodeIndex is a (1-based) node index whose parent we want to find.
	 * @return Returns 0 if this node has no parent (is the root), otherwise
	 * 	the parent's index
	 */
	public static int parent(int nodeIndex) { return nodeIndex/2; }
	
	/**
	 * Find the index of the left child of a given node.
	 * @param nodeIndex the (1-based) index of the node whose child we want to find
	 * @return the index of the left child, or size() if no left child.
	 */
	public int leftChild(int nodeIndex) { return 2*nodeIndex; }
	
	/**
	 * Find the index of the right child of a given node.
	 * @param nodeIndex the (1-based) index of the node whose child we want to find
	 * @return the index of the right child, or size() if no left child.
	 */
	public int rightChild(int nodeIndex) { return 2*nodeIndex + 1; }
	
	/**
	 * Find the index of this node's sibling.
	 * Everything always has a sibling, in this formulation of 
	 * (not-necessarily-complete binary trees). For root, returns 0.
	 * @param nodeIndex the (1-based) index of the node whose sibling we want to find
	 * @return the (1-based) index of the sibling, or 0 for if nodeIndex is the root.
	 */
	public static int sibling(int nodeIndex) {
		return nodeIndex^1; // Java has xor! who knew?
	}
	
	/**
	 * Check internal node index (not translated to leaves) to see if it
	 * is a left or right child. Internal nodes for a layer always start
	 * with an even index, as 1 is the root and the only layer with one
	 * member. Every other layer has an even number of nodes (except for
	 * possibly a dangling child at the end). So, left nodes have even
	 * indices, and right nodes have odd ones.
	 * @param nodeIndex node to check whether it is a right child
	 * @return true if it is a right child, false if a left child
	 */
	public static boolean isRight(int nodeIndex) { return (0 != (nodeIndex % 2)); }

	/**
	 * Check internal node index (not translated to leaves) to see if it
	 * is a left or right child. Internal nodes for a layer always start
	 * with an even index, as 1 is the root and the only layer with one
	 * member. Every other layer has an even number of nodes (except for
	 * possibly a dangling child at the end). So, left nodes have even
	 * indices, and right nodes have odd ones.
	 * @param nodeIndex node to check whether it is a left child
	 * @return true if it is a left child, false if a right child
	 */
	public static boolean isLeft(int nodeIndex) { return (0 == (nodeIndex % 2)); }
		
	/**
	 * Return the root digest
	 * @return the root digest
	 */
	public byte [] root() { 
		if ((null == _tree) || (_tree.length == 0))
			return new byte[0];
		return get(ROOT_NODE);
	} 
	
	/**
	 * Get the DEROctetString wrapped digest of the root node.
	 * @return a DEROctetString object containing the root node digest.
	 */
	public DEROctetString derRoot() { return derGet(ROOT_NODE); }
	
	/**
	 * Get the size of the tree, in nodes. (This is the number of nodes,
	 * not the number of leaves.)
	 * @return the tree size.
	 */
	public int size() { return _tree.length; }
	
	/**
	 * Returns the digest at the specified node.
	 * @param nodeIndex 1-based node index
	 * @return the digest for this node
	 */
	public byte [] get(int nodeIndex) { 
		DEROctetString dv = derGet(nodeIndex);
		if (null == dv)
			return null;
		return dv.getOctets(); 
	}
	
	/**
	 * Returns the digest at the specified node as a DEROctetString
	 * @param nodeIndex 1-based node index
	 * @return the digest for this node
	 */
	public DEROctetString derGet(int nodeIndex) { 
		if ((nodeIndex < ROOT_NODE) || (nodeIndex > size())) 
				return null;
		return _tree[nodeIndex-1]; 
	}

	/**
	 * Get the number of leaves in the tree.
	 * @return returns the number of leaves
	 */
	public int numLeaves() { return _numLeaves; }
	
	/**
	 * Calculate the number of nodes in a tree with a given number of leaves.
	 * @param numLeaves the number of leaves
	 * @return the number of nodes in the tree
	 */
	public static int nodeCount(int numLeaves) {
		// How many entries do we need? 2*numLeaves + 1
		return 2*numLeaves-1;
	}
	
	/**
	 * Calculates the number of nodes in this tree
	 * @return  the number of nodes
	 */
	public int nodeCount() { return nodeCount(numLeaves()); }

	/**
	 * Returns the node index of the first leaf.
	 * The node index of the first leaf is either size()-numleaves(), or
	 * nodeIndex = numLeaves.
	 * @return the first leaf's node index
	 */
	public int firstLeaf() { 
		return numLeaves();
	}
	
	/**
	 * Get the node index of a given leaf
	 * @param leafIndex the index of a leaf
	 * @return its node index
	 */
	public int leafNodeIndex(int leafIndex) { return firstLeaf() + leafIndex; }
	
	/**
	 * Retrieve the digest of a given leaf node. Returns null if there is
	 * no leaf leafIndex.
	 * @param leafIndex leaf index, starting at 0 for the first leaf.
	 * @return its digest
	 */
	public byte [] leaf(int leafIndex) {
		return get(leafNodeIndex(leafIndex));
	}
	
	/**
	 * Generate a MerklePath for a given leaf, to use in verifying that
	 * leaf.
	 * 
	 * There are a variety of traversal algorithms for 
	 * computing/reading Merkle hash trees.
	 * 
	 * We need to represent the leaves so that the user
	 * a) knows what order they come in, and b) also knows
	 * which is the leaf being represented. The cheapest
	 * way to do that is to represent the leaves in order,
	 * and also start out with an indication of whether
	 * this leaf is the left or right of the last pair.
	 * To make this most general and easy to use, we
	 * will represent this path as
	 *  
	 * MerklePath ::= SEQUENCE {
	 * 	nodeIndex INTEGER, 
	 *  nodes NodeList 
	 * }
	 *  
	 * NodeList ::= SEQUENCE OF OCTET STRING
	 *  
	 * the nodeIndex here is the index of the leaf node in
	 * the tree as a whole (not just among the leaves), and
	 * the nodes list contains neither the digest of the
	 * leaf itself nor the root of the tree.
	 * 
	 * We could probably save a few bytes by not encoding this
	 * as DER, and simply packing in the bytes to represent this
	 * data -- this encoding offers a fair amount of ease of parsing
	 * and clarity, at the cost of probably 5 + 2*pathLength bytes of overhead,
	 * or 20 bytes in typical paths. At some point this may
	 * seem too much, and we will move to a more compact encoding.
	 *  
	 * @param leafNum the leaf index of the leaf
	 * @return the MerklePath for verifying that leaf
	 * @see MerklePath
	 */
	public MerklePath path(int leafNum) {
		
		// Start at the leaf, pushing siblings. We know we always have
		// a complete path to the leaf.
		int leafNode = leafNodeIndex(leafNum);
		// We want to push nodes of the path onto the path structure
		// in reverse order. We'd then like to turn them into bare
		// arrays for efficiency. Java's stacks, though, turn them
		// into arrays in the wrong order. So, make an array. With
		// the extended binary tree, all paths are complete for their
		// region of the tree.
		DEROctetString [] resultStack = new DEROctetString[maxPathLength(leafNode)];
		
		// Start at the leaf, pushing siblings.
		int node = leafNode;
		int index = resultStack.length-1;
		
		while (node != ROOT_NODE) {
			
			int siblingIdx = sibling(node);
			// returns null if siblingIdx is too large, or if
			// there is no child at that index (empty subtree)
			DEROctetString sibling = derGet(siblingIdx);
			
			if (null != sibling) {
				resultStack[index--] = sibling;
			}
			
			node = parent(node);
		}
		return new MerklePath(leafNode, resultStack);
	}
	
	/**
	 * What is the maximum path length to a node with this node index,
	 * including its sibling but not including the root?
	 * @param nodeIndex the node to find the path length for
	 * @return the maximum path length
	 */
	public static int maxPathLength(int nodeIndex) {
		int baseLog = (int)Math.floor(MerkleTree.log2(nodeIndex));
		return baseLog;
	}
			
	/**
	 * What is the maximum depth of a Merkle tree with
	 * a given number of leaves. If the tree isn't balanced,
	 * many nodes may have shorter paths than maxDepth.
	 * @param numLeaves the number of leaves in the tree.
	 * @return the maximum depth of the tree
	 */
	public static int maxDepth(int numLeaves) {

		if (numLeaves == 0)
			return 0;
		if (numLeaves == 1)
			return 1;
		int pathLength = (int)Math.ceil(log2(numLeaves));
		//Library.info("numLeaves: " + numLeaves + " log2(nl+1) " + log2(numLeaves+1));
		return pathLength; 
	}
	
	/**
	 * Get the maximum depth of this MerkleTree.
	 * @return the depth
	 */
	public int maxDepth() { return maxDepth(numLeaves()); }
	
	/**
	 * Compute the raw digest of the leaf content blocks, and format them appropriately.
	 * @param contentBlocks the leaf content, one leaf per array
	 * @param isDigest have these been digested already, or do we need to digest
	 * 	them using computeBlockDigest(int, byte [][], int, int)?
	 * @param baseBlockIndex first block in the array to use
	 * @param lastBlockLength number of bytes of the last block to use; N/A if isDigest is true
	 * @throws NoSuchAlgorithmException if digestAlgorithm is unknown
	 */
	protected void computeLeafValues(byte contentBlocks[][], boolean isDigest, int baseBlockIndex, int lastBlockLength) throws NoSuchAlgorithmException {
		// Hash the leaves
		for (int i=0; i < numLeaves(); ++i) {
			_tree[leafNodeIndex(i)-1] = 
				new DEROctetString(
						(isDigest ? contentBlocks[i+baseBlockIndex] : 
									computeBlockDigest(i, contentBlocks, baseBlockIndex, lastBlockLength)));
		}
	}
	
	/**
	 * Compute the raw digest of the leaf content blocks, and format them appropriately.
	 * uses computeBlockDigest(int, byte[], int, int) to compute the leaf digest.
	 * @param content the content to segment into leaves and hash into this 
	 * Merkle hash tree. One blockWidth of content per leaf, except for the last leaf which may
	 * be shorter.
	 * @param offset offset into content at which to start processing data.
	 * @param length number of bytes of content to process
	 * @param blockWidth the length of leaf blocks to create
	 * @throws NoSuchAlgorithmException if digestAlgorithm is unknown
	 */
	protected void computeLeafValues(byte [] content, int offset, int length, int blockWidth) throws NoSuchAlgorithmException {
		// Hash the leaves
		for (int i=0; i < numLeaves(); ++i) {
			_tree[leafNodeIndex(i)-1] = 
				new DEROctetString(
						(computeBlockDigest(i, content, offset + (blockWidth*i), 
											((i < numLeaves()-1) ? blockWidth : (length - (blockWidth*i))))));
		}
	}

	/**
	 * Compute the intermediate node values by digesting the concatenation of the
	 * left and right children (or the left child alone if there is no right child).
	 * @throws NoSuchAlgorithmException if digestAlgorithm is unknown
	 */
	protected void computeNodeValues() throws NoSuchAlgorithmException {
		// Climb the tree
		int firstNode = firstLeaf()-1;
		for (int i=firstNode; i >= ROOT_NODE; --i) {
			byte [] nodeDigest = CCNDigestHelper.digest(digestAlgorithm(), get(leftChild(i)), get(rightChild(i)));
			_tree[i-1] = new DEROctetString(nodeDigest);
		}
	}
	
	/**
	 * Function for validating paths. Given a digest, it returns what node in
	 * the tree has that digest. If no node has that digest, returns 0. 
	 * If argument is null, returns -1. Slow.
	 * @param node the node digest to validate
	 * @return the nodeIndex of the node with that digest
	 */
	public int getNodeIndex(DEROctetString node) {
		if (null == node)
			return -1;
		for (int i=1; i <= size(); ++i) {
			if (node.equals(derGet(i)))
				return i;
		}
		return 0;
	}
	
	/**
	 * Get the root node as an encoded PKCS#1 DigestInfo.
	 * @return the encoded DigestInfo
	 */
	public byte[] getRootAsEncodedDigest() {
		// Take root and wrap it up as an encoded DigestInfo
		return CCNDigestHelper.digestEncoder(
				digestAlgorithm(), 
				root());
	}

	/**
	 * Compute the digest of a leaf node.
	 * Separate this out so that it can be overridden.
	 * @param leafIndex The index of the leaf we are computing the digest of.
	 * @param contentBlocks The array of content blocks containing the leaf content.
	 * @param baseBlockIndex The first content block in the array containing leaf content (if rolling buffers).
	 * 					  numLeaves() blocks contain leaf content, so the last block used is blockOffset+numLeaves().
	 * @param lastBlockLength the number of bytes of the last block to use, can be smaller than
	 *    the number available
	 * @return the digest for this leaf
	 * @throws NoSuchAlgorithmException 
	 */
	protected byte [] computeBlockDigest(int leafIndex, byte contentBlocks[][], int baseBlockIndex, int lastBlockLength) throws NoSuchAlgorithmException {
		if ((leafIndex + baseBlockIndex) > contentBlocks.length) 
			throw new IllegalArgumentException("Cannot ask for a leaf beyond the number of available blocks!");
		// Are we on the last block?
		if ((leafIndex + baseBlockIndex) == (baseBlockIndex + numLeaves() - 1))
			computeBlockDigest(leafIndex, contentBlocks[leafIndex+baseBlockIndex], 0, lastBlockLength);
		return computeBlockDigest(_digestAlgorithm, contentBlocks[leafIndex+baseBlockIndex]);
	}
	
	/**
	 * Compute the digest of a leaf node.
	 * Separate this out so that it can be overridden.
	 * @param leafIndex The index of the leaf we are computing the digest of.
	 * @param content the content to segment into leaves and hash into this 
	 * Merkle hash tree.
	 * @param offset offset into content at which this leaf starts
	 * @param length number of bytes of content in this leaf
	 * @return the digest for this leaf
	 * @throws NoSuchAlgorithmException if digestAlgorithm is unknown
	 */
	protected byte [] computeBlockDigest(int leafIndex, byte [] content, int offset, int length) throws NoSuchAlgorithmException {
		return CCNDigestHelper.digest(_digestAlgorithm, content, offset, length);		
	}
	
	/**
	 * Compute the digest of a leaf node.
	 * @param digestAlgorithm the digest algorithm to use
	 * @param content the content of this leaf
	 * @return the digest for this leaf
	 * @throws NoSuchAlgorithmException if digestAlgorithm is unknown
	 */
	public static byte [] computeBlockDigest(String digestAlgorithm, byte [] content) throws NoSuchAlgorithmException {
		return CCNDigestHelper.digest(digestAlgorithm, content);		
	}

	/**
	 * Compute the digest of a leaf node.
	 * @param digestAlgorithm the digest algorithm to use
	 * @param content the content to segment into leaves and hash into this 
	 * Merkle hash tree.
	 * @param offset offset into content at which this leaf starts
	 * @param length number of bytes of content in this leaf
	 * @return the digest for this leaf
	 * @throws NoSuchAlgorithmException if digestAlgorithm is unknown
	 */
	public static byte [] computeBlockDigest(String digestAlgorithm, byte [] content, int offset, int length) throws NoSuchAlgorithmException {
		return CCNDigestHelper.digest(digestAlgorithm, content, offset, length);		
	}

	/**
	 * Compute the digest of a block using CCNDigestHelper#DEFAULT_DIGEST_ALGORITHM.
	 * DKS TODO - check -- was being by MerklePath to compute digest for root without
	 * properly recovering OID from encoded path.
	 * @param block block to digest
	 * @return block digest
	 */
	public static byte [] computeBlockDigest(byte [] block) {
		try {
			return computeBlockDigest(CCNDigestHelper.DEFAULT_DIGEST_ALGORITHM, block);
		} catch (NoSuchAlgorithmException e) {
			// DKS --big configuration problem
			Log.warning("Fatal Error: cannot find default algorithm " + CCNDigestHelper.DEFAULT_DIGEST_ALGORITHM);
			throw new RuntimeException("Error: can't find default algorithm " + CCNDigestHelper.DEFAULT_DIGEST_ALGORITHM + "!  " + e.toString());
		}		
	}
	
	/**
	 * Compute the digest for an intermediate node. If this is a last left child (right is null),
	 * simply hash left alone.
	 * @throws NoSuchAlgorithmException 
	 */
	public static byte [] computeNodeDigest(String algorithm, byte [] left, byte [] right) throws NoSuchAlgorithmException {
		return CCNDigestHelper.digest(algorithm, left, right);
	}
	
	/**
	 * Compute the digest for an intermediate node with two children.
	 * @param left left child
	 * @param right right child
	 * @return parent digest
	 */
	public static byte [] computeNodeDigest(byte [] left, byte [] right) {
		try {
			return computeNodeDigest(CCNDigestHelper.DEFAULT_DIGEST_ALGORITHM, left, right);
		} catch (NoSuchAlgorithmException e) {
			// DKS --big configuration problem
			Log.warning("Fatal Error: cannot find default algorithm " + CCNDigestHelper.DEFAULT_DIGEST_ALGORITHM);
			throw new RuntimeException("Error: can't find default algorithm " + CCNDigestHelper.DEFAULT_DIGEST_ALGORITHM + "!  " + e.toString());
		}		
	}
	
	/**
	 * Does this algorithm identifier indicate a Merkle tree?
	 * @param algorithmId the algorithm identifier
	 * @return true if its a merkle tree, false otherwise
	 */
	public static boolean isMerkleTree(AlgorithmIdentifier algorithmId) {
		// Use a hack -- all MHT OIDs use same prefix.
		String strAlg = algorithmId.toString();
		if (strAlg.startsWith(MERKLE_OID_PREFIX))
			return true;
		return false;
	}
	
	/**
	 * Helper method
	 * @param arg
	 * @return log base 2 of arg
	 */
	public static double log2(int arg) {
		return Math.log(arg)/Math.log(2);
	}
	
	/**
	 * The number of blocks of blockWidth (bytes) necessary to hold length (bytes)
	 * @param length the buffer length
	 * @param blockWidth the segment with
	 * @return the number of blocks
	 */
	public static int blockCount(int length, int blockWidth) {
		if (0 == length)
			return 0;
		return (length + blockWidth - 1) / blockWidth;
	//	return (int)Math.ceil((1.0*length)/blockWidth);
	}
}
