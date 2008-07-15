package com.parc.ccn.security.crypto;

import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;


public class MerkleTree {
	
	protected static final int ROOT_NODE = 0;
	
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
		_tree = new DEROctetString[nodeCount()];
		// Let calling constructor handle building the tree.
	}
	
	/**
	 * @param contentBlocks the leaf content to be hashed into this 
	 * Merkle hash tree.
	 * @param blockCount the number of those blocks to include (e.g. we may not
	 * 	have filled our contentBlocks buffers prior to building the tree).
	 * @param baseBlockIndex the offset into the contentBlocks array at which to start.
	 * @param isDigest are the content blocks raw content (false), or are they already digested
	 * 	  with our default algorithm (true)? (default algorithm: DigestHelper.DEFAULT_DIGEST_ALGORITHM)
	 */
	public MerkleTree(String algorithm, 
					  byte [][] contentBlocks, boolean isDigest, int blockCount, int blockOffset) {
		this(algorithm, blockCount);
		
		initializeTree(contentBlocks, isDigest, blockOffset);
	}
	
	public MerkleTree(byte [][] contentBlocks, boolean isDigest, int blockCount, int blockOffset) {
		this(DigestHelper.DEFAULT_DIGEST_ALGORITHM, contentBlocks, isDigest, blockCount, blockOffset);
	}
	
	/**
	 * Separate this out to allow subclasses to initialize members before
	 * building tree.
	 * @return
	 */
	protected void initializeTree(byte [][] contentBlocks, boolean isDigest, int blockOffset) {
		if ((blockOffset < 0) || (contentBlocks.length-blockOffset < numLeaves()))
			throw new IllegalArgumentException("MerkleTree: cannot build tree from more blocks than given! Have " + (contentBlocks.length-blockOffset) + " blocks, asked to use: " + (numLeaves()));
		
		computeLeafValues(contentBlocks, isDigest, blockOffset);
		computeNodeValues();		
	}
	
	
	public String algorithm() { return _algorithm; }
	
	/**
	 * Returns -1 if this node has no parent (is the root).
	 * @param i
	 * @return
	 */
	public static int parent(int i) { 
		if (i == ROOT_NODE) {
			return -1;
		}
		return (int)Math.floor((i-1)/2.0); 
	}
	
	/**
	 * Will return size() if no left child.
	 * @param i
	 * @return
	 */
	public int leftChild(int i) { return 2*i + 1; }
	
	/**
	 * Will return size() if no right child.
	 * @param i
	 * @return
	 */
	public int rightChild(int i) { return 2*i + 2; }
	
	/**
	 * Will return size() if requested to return sibling
	 * of last node of an odd-sized tree. For root, returns -1.
	 * @param i
	 * @return
	 * 0
	 * 1    2
	 * 3 4  5 6
	 */
	public static int sibling(int i) {
		if (isLeft(i))
			return i+1;
		return i-1;
	}
	
	/**
	 * Check internal node index (not translated to leaves) to see if it
	 * is a left or right child. Internal nodes for a layer always start
	 * with an odd index, as 0 is the root and the only layer with one
	 * member. Every other layer has an even number of nodes (except for
	 * possibly a dangling child at the end). So, left nodes have odd
	 * indices, and right nodes have even ones.
	 * @param i
	 * @return
	 */
	public static boolean isRight(int i) { return (0 == (i % 2)); }
	public static boolean isLeft(int i) { return (0 != (i % 2)); }
	
	/**
	 * Check a leaf node index to see if it is a left or right branch leaf.
	 * In this case, we are considering indices only within the leaf layer
	 * of the tree. The first (left) leaf has index 0, and left leaves have
	 * even indices, and right leaves have odd ones.
	 * @return
	 */
	public static boolean isLeftLeaf(int i) { return (0 == (i % 2)); }
	public static boolean isRightLeaf(int i) { return (0 != (i % 2)); }
	
	public byte [] root() { 
		if ((null == _tree) || (_tree.length == 0))
			return new byte[0];
		return _tree[ROOT_NODE].getOctets(); } 
	public DEROctetString derRoot() { return _tree[ROOT_NODE]; }
	public int size() { return _tree.length; }
	
	/**
	 * Returns null if there is no node i.
	 * @param i
	 * @return
	 */
	public byte [] get(int i) { 
		DEROctetString dv = derGet(i);
		if (null == dv)
			return null;
		return dv.getOctets(); 
	}
	
	/**
	 * Returns null if there is no node i.
	 * @param i
	 * @return
	 */
	public DEROctetString derGet(int i) { 
		if ((i < 0) || (i >= size())) 
				return null;
		return _tree[i]; 
	}

	public int numLeaves() { return _numLeaves; }
	
	public static int nodeCount(int numLeaves) {
		// How many entries do we need? 
		// 2^(pathLength-1) - 1 
		// gets us the number of nodes up to the level below ours.
		// We then add in the number of leaves.
		if (0 == numLeaves) return 0;
		
		int nodeCount = (int)(Math.pow(2.0,maxDepth(numLeaves)))-1;
		nodeCount += numLeaves;
		
		return nodeCount;
	}
	
	public int nodeCount() { return nodeCount(numLeaves()); }

	public int firstLeaf() { return size() - numLeaves(); }
	
	public int leafNodeIndex(int leafIndex) { return firstLeaf() + leafIndex; }
	
	public static int leafIndex(int leafNodeIndex) { 
		int baseLog = (int)Math.floor(log2(leafNodeIndex + 1));
		int baseLeaf = 1 << baseLog;
		return (leafNodeIndex + 1 - baseLeaf);
	}	
		
	/**
	 * Retrieve just the leaf nodes. Returns null if there is
	 * no leaf i.
	 * @param i leaf index, starting at 0 for the first leaf.
	 * @return
	 */
	public byte [] leaf(int leafIndex) {
		if ((leafIndex < 0) || (leafIndex > _numLeaves))
			return null;
		return _tree[firstLeaf() + leafIndex].getOctets(); 
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
		
		// Start at the leaf, pushing siblings.
		int leafNode = leafNodeIndex(leafNum);
		// We want to push nodes of the path onto the path structure
		// in reverse order. We'd then like to turn them into bare
		// arrays for efficiency. Java's stacks, though, turn them
		// into arrays in the wrong order. So, let's make too big of
		// a path and shrink it at the end.
		DEROctetString [] resultStack = new DEROctetString[maxDepth(leafNode)];
		
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
		index++;
		
		if (index > 0) {
			DEROctetString [] results = new DEROctetString[resultStack.length - index];
			// Can't use 1.6 array operations...
			System.arraycopy(resultStack, index, results, 0, results.length);
			resultStack = results;
		}
		return new MerklePath(leafNode, resultStack);
	}
	
	/**
	 * What is the maximum path length to a node with this leaf node index?
	 */
	public static int maxPathLength(int leafNodeIndex) {
		int baseLog = (int)Math.floor(MerkleTree.log2(leafNodeIndex + 1));
		return baseLog+1;
	}
			
	/**
	 * What is the maximum path length of a Merkle tree with
	 * a given number of leaves. If the tree isn't balanced,
	 * many nodes may have shorter paths.
	 * @param numLeaves
	 * @return
	 */
	public static int maxDepth(int numLeaves) {
		// We can't assume a balanced binary tree; we may be fixed
		// in our block sizes. 
		// diff = 2^ceil(log2(numleaves)) - numleaves
		// is the number of missing leaves between this tree
		// and a balanced binary tree. If that difference
		// is 0, pathlen is log2(numleaves) (if we don't include the root).
		// Nodes with indices between 0 and 2^floor(log2(numleaves))-1
		// are in a complete subtree, and have pathlengths of 
		// epathlen = ceil(log2(numleaves)) as in a balanced tree.
		// nodes = 8
		// diff = 1, pathlen = epathlen - 1
		// diff = 2, pathlen = epathlen - 1
		// diff = 3, pathlen = epathlen - 2
		// nodes = 16
		// diff = 1, pathlen = epathlen - 1
		// diff = 2, pathlen = epathlen - 1
		// diff = 3, pathlen = epathlen - 2
		// diff = 4, pathlen = epathlen - 1
		// diff = 5, pathlen = epathlen - 2
		// diff = 6, pathlen = epathlen - 2
		// diff = 7, pathlen = epathlen - 3
		// nodes = 32
		// d = 8, p = e - 1
		// d = 9, p = e - 2
		// d = 10, p = e - 2
		// d = 11, p = e - 3
		// d = 12, p = e - 2
		// d = 13, p = e - 3
		// d = 14, p = e - 3
		// d = 15, p = e - 4
		// 
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
	 * @param isDigest if the content is already digested (must use the default digest algorithm, for
	 * 	now; we can change that if there is  a need).
	 */
	protected void computeLeafValues(byte [][] contentBlocks, boolean isDigest, int blockOffset) {
		// Hash the leaves
		for (int i=0; i < numLeaves(); ++i) {
			_tree[leafNodeIndex(i)] = 
				new DEROctetString(
						(isDigest ? contentBlocks[i+blockOffset] : computeBlockDigest(i, blockOffset, contentBlocks)));
		}
	}
	
	protected void computeNodeValues() {
		// Climb the tree
		int firstNode = firstLeaf(); // DKS TODO Next coming out with null node values
		int endNode = size()-1;
		int nextLevelStart = parent(firstNode);
		int nextLevelEnd = firstNode - 1;
		
		while (parent(firstNode) >= 0) {
			for (int i = parent(firstNode); i <= parent(endNode); ++i) {
				// leftChild(i) and rightChild(i) will each return size() if there is no
				// such child, in which case the digest will ignore that child.
				byte [] nodeDigest = DigestHelper.digest(_algorithm, get(leftChild(i)), get(rightChild(i))); 
				_tree[i] = (null != nodeDigest) ? new DEROctetString(nodeDigest) : null;
			}
			firstNode = nextLevelStart;
			endNode = nextLevelEnd;
			nextLevelEnd = firstNode - 1;
			nextLevelStart = parent(firstNode);
		}
	}
	
	/**
	 * Function for validating paths. Given a digest, it returns what node in
	 * the tree has that digest. If no node has that digest, returns size(). 
	 * If argument is null, returns -1. Slow.
	 */
	public int getNodeIndex(DEROctetString node) {
		if (null == node)
			return -1;
		for (int i=0; i < size(); ++i) {
			if (node.equals(derGet(i)))
				return i;
		}
		return size();
	}
	
	public byte[] getRootAsEncodedDigest() {
		// Take root and wrap it up as an encoded DigestInfo
		return DigestHelper.digestEncoder(
				algorithm(), 
				root());
	}

	/**
	 * Separate this out so that it can be overridden.
	 * @param leafIndex
	 * @param contentBlocks
	 * @return
	 */
	protected byte [] computeBlockDigest(int leafIndex, int blockOffset, byte [][] contentBlocks) {
		return computeBlockDigest(_algorithm, contentBlocks[leafIndex+blockOffset]);
	}
	
	public static byte [] computeBlockDigest(String algorithm, byte [] block) {
		return DigestHelper.digest(algorithm, block);		
	}

	public static byte [] computeBlockDigest(byte [] block) {
		return computeBlockDigest(DigestHelper.DEFAULT_DIGEST_ALGORITHM, block);		
	}
	
	/**
	 * Compute an intermediate node. If this is a last left child (right is null),
	 * simply hash left alone.
	 */
	public static byte [] computeNodeDigest(String algorithm, byte [] left, byte [] right) {
		return DigestHelper.digest(algorithm, left, right);
	}
	
	public static byte [] computeNodeDigest(byte [] left, byte [] right) {
		return computeNodeDigest(DigestHelper.DEFAULT_DIGEST_ALGORITHM, left, right);
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
}
