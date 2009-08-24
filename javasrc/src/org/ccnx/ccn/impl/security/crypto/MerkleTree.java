package org.ccnx.ccn.impl.security.crypto;

import java.security.NoSuchAlgorithmException;

import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.ccnx.ccn.impl.support.Log;


/**
 * Modified to match Knuth, Vol 1 2.3.4.5. We represent
 * trees as a special sublcass of extended binary
 * trees, where empty subtrees are only present in one end
 * of the tree.
 * Tree nodes are numbered starting with 1, which is the
 * root. 
 * Tree nodes are stored in an array, with node i stored at index
 * i-1 into the array.
 * Incomplete binary trees are represented as multi-level extended
 * binary trees -- lower-numbered leaves are represented in the
 * upper half of the tree, in a layer one closer to the root than
 * leaves in the complete subtree.
 * 
 * Total number of nodes in the tree = 2n + 1, where n is the number of leaves.
 * 
 * Taken in terms of node indices (where root == 1), the parent
 * of node k is node floor(k/2), and hte children of node k are
 * nodes 2k and 2k+1. Leaves are numbered from node n+1 through 
 * 2n+1, where n is the number of leaves.
 * 
 * The sibling index of node k is (k xor 1).
 * 
 * Should we want to get fancy, we could have t-ary trees; the
 * construction above works for tree with internal nodes (non-leaves)
 * {1,2,...,n}.
 * The parent of node k is the node floor((k+t-2)/t) = ceil((k-1)/t).
 * The children of node k are:
 * t(k-1)+2, t(k-1)+3,..., tk+1
 * 
 * Store internally as DEROctetStrings for more efficient
 * encoding. 
 */
public class MerkleTree {
	
	/**
	 * Node index of 1 (array index of 0).
	 */
	protected static final int ROOT_NODE = 1;
	
	protected DEROctetString [] _tree;
	protected int _numLeaves;
	protected String _algorithm;
	
	protected static final String MERKLE_OID_PREFIX = "1.2.840.113550.11.1.2";
	
	/**
	 * Subclass constructor.
	 * @param algorithm
	 * @param contentBlocks
	 */
	protected MerkleTree(String algorithm, int numLeaves) {
		if (numLeaves < 2) {
			throw new IllegalArgumentException("MerkleTrees must have 2 or more nodes!");
		}
		_algorithm = (null == algorithm) ? CCNDigestHelper.DEFAULT_DIGEST_ALGORITHM : algorithm;
		_numLeaves = numLeaves;
		_tree = new DEROctetString[nodeCount()];
		// Let calling constructor handle building the tree.
	}
	
	/**
	 * @param contentBlocks the leaf content to be hashed into this 
	 * Merkle hash tree.
	 * @param blockCount the number of those blocks to include (e.g. we may not
	 * 	have filled our contentBlocks buffers prior to building the tree). Must be
	 *  at least 2.
	 * @param baseBlockIndex the offset into the contentBlocks array at which to start.
	 * @param isDigest are the content blocks raw content (false), or are they already digested
	 * 	  with our default algorithm (true)? (default algorithm: DigestHelper.DEFAULT_DIGEST_ALGORITHM)
	 * @throws NoSuchAlgorithmException 
	 */
	public MerkleTree(String algorithm, 
					  byte [][] contentBlocks, boolean isDigest, int blockCount, 
					  int baseBlockIndex, int lastBlockLength) throws NoSuchAlgorithmException {
		this(algorithm, blockCount);		
		initializeTree(contentBlocks, isDigest, baseBlockIndex, lastBlockLength);
	}
	
	public MerkleTree(byte [] content, int offset, int length, int blockWidth) {
		this(CCNDigestHelper.DEFAULT_DIGEST_ALGORITHM, (int)Math.ceil((1.0*length)/blockWidth));		
		try {
			initializeTree(content, offset, length, blockWidth);
		} catch (NoSuchAlgorithmException e) {
			// DKS --big configuration problem
			Log.logger().warning("Fatal Error: cannot find default algorithm " + CCNDigestHelper.DEFAULT_DIGEST_ALGORITHM);
			throw new RuntimeException("Error: can't find default algorithm " + CCNDigestHelper.DEFAULT_DIGEST_ALGORITHM + "!  " + e.toString());
		}
	}

	public MerkleTree(String algorithm, byte [] content, int offset, int length,
					  int blockWidth) throws NoSuchAlgorithmException {
		this(algorithm, blockCount(length, blockWidth));
		initializeTree(content, offset, length, blockWidth);
	}

	public MerkleTree(byte [][] contentBlocks, boolean isDigest, 
					  int blockCount, int baseBlockIndex, int lastBlockLength) {
		this(CCNDigestHelper.DEFAULT_DIGEST_ALGORITHM, blockCount);		
		try {
			initializeTree(contentBlocks, isDigest, baseBlockIndex, lastBlockLength);
		} catch (NoSuchAlgorithmException e) {
			// DKS --big configuration problem
			Log.logger().warning("Fatal Error: cannot find default algorithm " + CCNDigestHelper.DEFAULT_DIGEST_ALGORITHM);
			throw new RuntimeException("Error: can't find default algorithm " + CCNDigestHelper.DEFAULT_DIGEST_ALGORITHM + "!  " + e.toString());
		}
	}
	
	/**
	 * Separate this out to allow subclasses to initialize members before
	 * building tree.
	 * @return
	 * @throws NoSuchAlgorithmException 
	 */
	protected void initializeTree(byte [][] contentBlocks, boolean isDigest, int baseBlockIndex, int lastBlockLength) throws NoSuchAlgorithmException {
		if ((baseBlockIndex < 0) || (contentBlocks.length-baseBlockIndex < numLeaves()))
			throw new IllegalArgumentException("MerkleTree: cannot build tree from more blocks than given! Have " + (contentBlocks.length-baseBlockIndex) + " blocks, asked to use: " + (numLeaves()));
		
		computeLeafValues(contentBlocks, isDigest, baseBlockIndex, lastBlockLength);
		computeNodeValues();		
	}
	
	/**
	 * Separate this out to allow subclasses to initialize members before
	 * building tree.
	 * @return
	 * @throws NoSuchAlgorithmException 
	 */
	protected void initializeTree(byte [] content, int offset, int length, int blockWidth) throws NoSuchAlgorithmException {
		if ((offset < 0) || (length > content.length) || (blockCount(length, blockWidth) < numLeaves()))
			throw new IllegalArgumentException("MerkleTree: cannot build tree from more blocks than given! Have " + blockCount(length, blockWidth) + " blocks, asked to use: " + (numLeaves()));
		
		computeLeafValues(content, offset, length, blockWidth);
		computeNodeValues();		
	}
	
	public String algorithm() { return _algorithm; }
	
	/**
	 * Returns 0 if this node has no parent (is the root).
	 * @param i is a (1-based) node index.
	 * @return
	 */
	public static int parent(int nodeIndex) { 
		return (int)Math.floor(nodeIndex/2.0); 
	}
	
	/**
	 * Will return size() if no left child.
	 * @param nodeIndex
	 * @return
	 */
	public int leftChild(int nodeIndex) { return 2*nodeIndex; }
	
	/**
	 * Will return size() if no right child.
	 * @param nodeIndex
	 * @return
	 */
	public int rightChild(int nodeIndex) { return 2*nodeIndex + 1; }
	
	/**
	 * Everything always has a sibling, in this formulation of 
	 * (not-necessarily-complete binary trees). For root, returns 0.
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
	 * @param nodeIndex
	 * @return
	 */
	public static boolean isRight(int nodeIndex) { return (0 != (nodeIndex % 2)); }
	public static boolean isLeft(int nodeIndex) { return (0 == (nodeIndex % 2)); }
		
	public byte [] root() { 
		if ((null == _tree) || (_tree.length == 0))
			return new byte[0];
		return get(ROOT_NODE);
	} 
	
	public DEROctetString derRoot() { return derGet(ROOT_NODE); }
	public int size() { return _tree.length; }
	
	/**
	 * Returns null if there is no node nodeIndex.
	 * @param nodeIndex 1-based node index
	 * @return
	 */
	public byte [] get(int nodeIndex) { 
		DEROctetString dv = derGet(nodeIndex);
		if (null == dv)
			return null;
		return dv.getOctets(); 
	}
	
	/**
	 * Returns null if there is no node nodeIndex.
	 * @param nodeIndex
	 * @return
	 */
	public DEROctetString derGet(int nodeIndex) { 
		if ((nodeIndex < ROOT_NODE) || (nodeIndex > size())) 
				return null;
		return _tree[nodeIndex-1]; 
	}

	public int numLeaves() { return _numLeaves; }
	
	public static int nodeCount(int numLeaves) {
		// How many entries do we need? 2*numLeaves + 1
		return 2*numLeaves-1;
	}
	
	public int nodeCount() { return nodeCount(numLeaves()); }

	public int firstLeaf() { // node index of the 
		// first leaf is either size()-numleaves(), or
		// nodeIndex = numLeaves
		return numLeaves();
	}
	
	public int leafNodeIndex(int leafIndex) { return firstLeaf() + leafIndex; }
	
	/**
	 * Retrieve just the leaf nodes. Returns null if there is
	 * no leaf leafIndex.
	 * @param leafIndex leaf index, starting at 0 for the first leaf.
	 * @return
	 */
	public byte [] leaf(int leafIndex) {
		return get(leafNodeIndex(leafIndex));
	}
	
	/**
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
	 *  nodes NodeList }
	 *  
	 *  NodeList ::= SEQUENCE OF OCTET STRING
	 *  
	 *  the nodeIndex here is the index of the leaf node in
	 *  the tree as a whole (not just among the leaves), and
	 *  the nodes list contains neither the digest of the
	 *  leaf itself nor the root of the tree.
	 *  
	 * @param leafNum
	 * @return
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
	 * including its sibling but not the root?
	 */
	public static int maxPathLength(int nodeIndex) {
		int baseLog = (int)Math.floor(MerkleTree.log2(nodeIndex));
		return baseLog;
	}
			
	/**
	 * What is the maximum path length of a Merkle tree with
	 * a given number of leaves. If the tree isn't balanced,
	 * many nodes may have shorter paths.
	 * @param numLeaves
	 * @return
	 */
	public static int maxDepth(int numLeaves) {

		if (numLeaves == 0)
			return 0;
		if (numLeaves == 1)
			return 1;
		int pathLength = (int)Math.ceil(log2(numLeaves));
		//Library.logger().info("numLeaves: " + numLeaves + " log2(nl+1) " + log2(numLeaves+1));
		return pathLength; 
	}
	
	public int maxDepth() { return maxDepth(numLeaves()); }
	
	/**
	 * Compute the raw digest of the content blocks, and format them appropriately.
	 * @param contentBlocks
	 * @param baseBlockIndex first block in the array to use
	 * @param lastBlockLength number of bytes of the last block to use; N/A if isDigest is true
	 * @param isDigest if the content is already digested (must use the default digest algorithm, for
	 * 	now; we can change that if there is  a need).
	 * @throws NoSuchAlgorithmException 
	 */
	protected void computeLeafValues(byte [][] contentBlocks, boolean isDigest, int baseBlockIndex, int lastBlockLength) throws NoSuchAlgorithmException {
		// Hash the leaves
		for (int i=0; i < numLeaves(); ++i) {
			_tree[leafNodeIndex(i)-1] = 
				new DEROctetString(
						(isDigest ? contentBlocks[i+baseBlockIndex] : 
									computeBlockDigest(i, contentBlocks, baseBlockIndex, lastBlockLength)));
		}
	}
	
	/**
	 * Compute the raw digest of the content blocks, and format them appropriately.
	 * @param contentBlocks
	 * @param baseBlockIndex first block in the array to use
	 * @param lastBlockLength number of bytes of the last block to use; N/A if isDigest is true
	 * @param isDigest if the content is already digested (must use the default digest algorithm, for
	 * 	now; we can change that if there is  a need).
	 * @throws NoSuchAlgorithmException 
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

	protected void computeNodeValues() throws NoSuchAlgorithmException {
		// Climb the tree
		int firstNode = firstLeaf()-1;
		for (int i=firstNode; i >= ROOT_NODE; --i) {
			byte [] nodeDigest = CCNDigestHelper.digest(algorithm(), get(leftChild(i)), get(rightChild(i)));
			_tree[i-1] = new DEROctetString(nodeDigest);
		}
	}
	
	/**
	 * Function for validating paths. Given a digest, it returns what node in
	 * the tree has that digest. If no node has that digest, returns 0. 
	 * If argument is null, returns -1. Slow.
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
	
	public byte[] getRootAsEncodedDigest() {
		// Take root and wrap it up as an encoded DigestInfo
		return CCNDigestHelper.digestEncoder(
				algorithm(), 
				root());
	}

	/**
	 * Separate this out so that it can be overridden.
	 * @param leafIndex The number of the leaf we are computing.
	 * @param contentBlocks The array of content blocks containing the leaf content.
	 * @param baseBlockIndex The first content block in the array containing leaf content (if rolling buffers).
	 * 					  numLeaves() blocks contain leaf content, so the last block used is blockOffset+numLeaves().
	 * @param lastBlockLength the number of bytes of the last block to use, can be smaller than
	 *    the number available
	 * @return
	 * @throws NoSuchAlgorithmException 
	 */
	protected byte [] computeBlockDigest(int leafIndex, byte [][] contentBlocks, int baseBlockIndex, int lastBlockLength) throws NoSuchAlgorithmException {
		if ((leafIndex + baseBlockIndex) > contentBlocks.length) 
			throw new IllegalArgumentException("Cannot ask for a leaf beyond the number of available blocks!");
		// Are we on the last block?
		if ((leafIndex + baseBlockIndex) == (baseBlockIndex + numLeaves() - 1))
			computeBlockDigest(leafIndex, contentBlocks[leafIndex+baseBlockIndex], 0, lastBlockLength);
		return computeBlockDigest(_algorithm, contentBlocks[leafIndex+baseBlockIndex]);
	}
	
	protected byte [] computeBlockDigest(int leafIndex, byte [] block, int offset, int length) throws NoSuchAlgorithmException {
		return CCNDigestHelper.digest(_algorithm, block, offset, length);		
	}
	
	public static byte [] computeBlockDigest(String algorithm, byte [] block) throws NoSuchAlgorithmException {
		return CCNDigestHelper.digest(algorithm, block);		
	}

	public static byte [] computeBlockDigest(String algorithm, byte [] block, int offset, int length) throws NoSuchAlgorithmException {
		return CCNDigestHelper.digest(algorithm, block, offset, length);		
	}

	/**
	 * DKS TODO - used by MerklePath to compute digest for root without
	 * properly recovering OID from encoded path.
	 * @param block
	 * @return
	 */
	public static byte [] computeBlockDigest(byte [] block) {
		try {
			return computeBlockDigest(CCNDigestHelper.DEFAULT_DIGEST_ALGORITHM, block);
		} catch (NoSuchAlgorithmException e) {
			// DKS --big configuration problem
			Log.logger().warning("Fatal Error: cannot find default algorithm " + CCNDigestHelper.DEFAULT_DIGEST_ALGORITHM);
			throw new RuntimeException("Error: can't find default algorithm " + CCNDigestHelper.DEFAULT_DIGEST_ALGORITHM + "!  " + e.toString());
		}		
	}
	
	/**
	 * Compute an intermediate node. If this is a last left child (right is null),
	 * simply hash left alone.
	 * @throws NoSuchAlgorithmException 
	 */
	public static byte [] computeNodeDigest(String algorithm, byte [] left, byte [] right) throws NoSuchAlgorithmException {
		return CCNDigestHelper.digest(algorithm, left, right);
	}
	
	public static byte [] computeNodeDigest(byte [] left, byte [] right) {
		try {
			return computeNodeDigest(CCNDigestHelper.DEFAULT_DIGEST_ALGORITHM, left, right);
		} catch (NoSuchAlgorithmException e) {
			// DKS --big configuration problem
			Log.logger().warning("Fatal Error: cannot find default algorithm " + CCNDigestHelper.DEFAULT_DIGEST_ALGORITHM);
			throw new RuntimeException("Error: can't find default algorithm " + CCNDigestHelper.DEFAULT_DIGEST_ALGORITHM + "!  " + e.toString());
		}		
	}
	
	public static boolean isMerkleTree(AlgorithmIdentifier algorithmId) {
		// Use a hack -- all MHT OIDs use same prefix.
		String strAlg = algorithmId.toString();
		if (strAlg.startsWith(MERKLE_OID_PREFIX))
			return true;
		return false;
	}
	
	public static double log2(int arg) {
		return Math.log(arg)/Math.log(2);
	}
	
	public static int blockCount(int length, int blockWidth) {
		if (0 == length)
			return 0;
		return (length + blockWidth - 1) / blockWidth;
	//	return (int)Math.ceil((1.0*length)/blockWidth);
	}
}
