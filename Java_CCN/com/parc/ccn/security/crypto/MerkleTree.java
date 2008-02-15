package com.parc.ccn.security.crypto;

import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;


public class MerkleTree {
	
	/**
	 * Represent hashes as a binary tree.
	 * _tree[0] = root
	 * _tree[1], _tree[2] = 1st level
	 * node i's children: _tree[2i+1],_tree[2i+2]
	 * i's parent: floor(_tree[i-1/2])
	 * the leaf nodes are the last n nodes
	 * Store internally as DEROctetStrings for more efficient
	 * encoding. 
	 */
	protected DEROctetString [] _tree;
	protected int _numLeaves;
	protected int _pathLength;
	protected String _algorithm;
	
	protected static final String MERKLE_OID_PREFIX = "1.2.840.113550.11.1.2";
	
	/**
	 * Subclass constructor.
	 * @param algorithm
	 * @param contentBlocks
	 */
	protected MerkleTree(String algorithm, int numLeaves) {
		_algorithm = (null == algorithm) ? DigestHelper.DEFAULT_DIGEST_ALGORITHM : algorithm;
		_numLeaves = numLeaves;
		_pathLength = computePathLength(numLeaves);
		_tree = new DEROctetString[nodeCount()];
		// Let calling constructor handle building the tree.
	}
	
	/**
	 * @param contentBlocks the leaf content to be hashed into this 
	 * Merkle hash tree.
	 */
	public MerkleTree(String algorithm, 
					  byte [][] contentBlocks) {
		this(algorithm, contentBlocks.length);
		
		computeLeafValues(contentBlocks);
		computeNodeValues();
	}
	
	public MerkleTree(byte [][] contentBlocks) {
		this(DigestHelper.DEFAULT_DIGEST_ALGORITHM, contentBlocks);
	}
	
	public static int parent(int i) { 
		if (i == 0) {
			return -1;
		}
		return (int)Math.floor((i-1)/2.0); 
	}
	
	public int leftChild(int i) { return 2*i + 1; }
	/**
	 * Will return size() if no right child.
	 * @param i
	 * @return
	 */
	public int rightChild(int i) { return 2*i + 2; }
	
	/**
	 * Will return size() if requested to return sibling
	 * of last node of an odd-sized tree.
	 * @param i
	 * @return
	 */
	public int sibling(int i) {
		if (0 == (i % 2))
			return i+1;
		return i-1;
	}
	public static boolean isRight(int i) { return (0 != (i % 2)); }
	public static boolean isLeft(int i) { return (0 == (i % 2)); }
	
	public byte [] root() { return _tree[0].getOctets(); } 
	public DEROctetString derRoot() { return _tree[0]; }
	public byte [] get(int i) { return _tree[i].getOctets(); }
	public DEROctetString derGet(int i) { return _tree[i]; }
	public int size() { return _tree.length; }
	
	public int numLeaves() { return _numLeaves; }
	public int pathLength() { return _pathLength; }
	
	public int firstLeaf() { return size() - numLeaves(); }
	public int leafIndex(int i) { return firstLeaf() + i; }
	
	/**
	 * Retrieve just the leaf nodes.
	 * @param i leaf index, starting at 0 for the first leaf.
	 * @return
	 */
	public byte [] leaf(int i) {
		if ((i < 0) || (i > _numLeaves))
			throw new IllegalArgumentException(i + " is not a valid leaf value for a tree with " + numLeaves() + " leaves.");
		return _tree[firstLeaf() + i].getOctets(); 
	}
	
	/**
	 * There are a variety of traversal algorithms for 
	 * computing/reading Merkle hash trees.
	 * 
	 * Here, item 0 is the root, item n-1 is the sibling
	 * of the leaf itself. Whether nodeIndex is odd or
	 * not determines whether it is the left or right
	 * sibling (if nodeIndex odd, it is the right sibling,
	 * even, the left). The
	 * leaves in between are one each for each level of the
	 * tree. There are pathLength() items (the leaf itself
	 * is not represented). We can detect this when a) leafNum
	 * is odd, leafNum > 1 and computePathLength(leafNum) is 
	 * one greater than the length of the presented path.
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
	 * @param leafNum
	 * @return
	 */
	public byte [][] path(int leafNum) {
		byte [][] result = null;
		
		int sibling = sibling(leafNum);
		if (sibling == size()) {
			// we've hit the end, this node has no siblings
			result = new byte[pathLength()-1][];
		} else {
			result = new byte[pathLength()][];
			result[pathLength()-1] = get(sibling);
		}
		
		int index = parent(leafNum);
		while (index > 0) {
			result[index] = get(sibling(index));
			index = parent(index);
		}
		result[index] = get(index); // root
		return result;
	}
	
	public MerklePath derPath(int leafNum) {
		DEROctetString [] result = 
			new DEROctetString[pathLength()];
		
		int sibling = sibling(leafNum);
		result[pathLength()-1] = derGet(sibling);
		int index = parent(leafNum);
		while (index > 0) {
			result[index] = derGet(sibling(index));
			index = parent(index);
		}
		result[index] = derGet(index); // root
		return new MerklePath(leafNum, result);
	}
		
	protected void computeNodes(byte [][] contentBlocks) {
		// Hash the leaves
		for (int i=0; i < numLeaves(); ++i) {
			_tree[leafIndex(i)] = 
				new DEROctetString(DigestHelper.digest(_algorithm, contentBlocks[i]));
		}
	}
	
	protected int nodeCount() {
		// How many entries do we need? 
		// 2^(pathLength) - 1 (if even # nodes), - 2 (if odd)		
		int nodeCount = (int)(Math.pow(2.0,_pathLength));
		if (0 == (_numLeaves % 2)) 
			nodeCount -= 1;
		else
			nodeCount -= 2;
		
		return nodeCount;
	}
	
	public static int computePathLength(int numLeaves) {
		int pathLength = -1;
		if (numLeaves > 1)
			pathLength = (int)Math.ceil(Math.log(numLeaves)/Math.log(2)) + 1;
		else
			pathLength = 2;
		return pathLength;
	}
	
	protected void computeLeafValues(byte [][] contentBlocks) {
		// Hash the leaves
		for (int i=0; i < numLeaves(); ++i) {
			_tree[leafIndex(i)] = 
				new DEROctetString(
						computeBlockDigest(i, contentBlocks));
		}
	}
	
	protected void computeNodeValues() {
		// Climb the tree
		int firstNode = firstLeaf(); 
		int endNode = size();
		int nextLevelStart = parent(firstNode);
		int nextLevelEnd = firstNode - 1;
		
		while (parent(firstNode) > 0) {
			for (int i = parent(firstNode); i < parent(endNode); ++i) {
				if (rightChild(i) < endNode)
					_tree[i] = 
						new DEROctetString(DigestHelper.digest(_algorithm, get(leftChild(i)), get(rightChild(i))));
				else
					// last leaf
					_tree[i] = 
						new DEROctetString(DigestHelper.digest(_algorithm, get(leftChild(i))));
			}
			firstNode = nextLevelStart;
			endNode = nextLevelEnd;
			nextLevelEnd = firstNode - 1;
			nextLevelStart = parent(firstNode);
		}
	}
	
	/**
	 * Separate this out so that it can be overridden.
	 * @param i
	 * @param contentBlocks
	 * @return
	 */
	protected byte [] computeBlockDigest(int i, byte [][] contentBlocks) {
		return computeBlockDigest(_algorithm, contentBlocks[i]);
	}
	
	public static byte [] computeBlockDigest(String algorithm, byte [] block) {
		return DigestHelper.digest(algorithm, block);		
	}

	public static byte [] computeBlockDigest(byte [] block) {
		return computeBlockDigest(DigestHelper.DEFAULT_DIGEST_ALGORITHM, block);		
	}
	
	/**
	 * Compute an intermediate node.
	 */
	public static byte [] computeNodeDigest(String algorithm, byte [] left, byte [] right) {
		return DigestHelper.digest(algorithm, left, right);
	}
	
	public static byte [] computeNodeDigest(byte [] left, byte [] right) {
		return computeNodeDigest(DigestHelper.DEFAULT_DIGEST_ALGORITHM, left, right);
	}
	
	/**
	 * Compute an intermediate note for a last left child.
	 */
	public static byte [] computeNodeDigest(String algorithm, byte [] left) {
		return DigestHelper.digest(algorithm, left);		
	}

	public static byte [] computeNodeDigest(byte [] left) {
		return computeNodeDigest(DigestHelper.DEFAULT_DIGEST_ALGORITHM, left);		
	}

	public static boolean isMerkleTree(AlgorithmIdentifier algorithmId) {
		// Use a hack -- all MHT OIDs use same prefix.
		String strAlg = algorithmId.toString();
		if (strAlg.startsWith(MERKLE_OID_PREFIX))
			return true;
		return false;
	}
}
