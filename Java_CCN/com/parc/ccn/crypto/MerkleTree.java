package com.parc.ccn.crypto;


public class MerkleTree {
	
	/**
	 * Represent hashes as a binary tree.
	 * _tree[0] = root
	 * _tree[1], _tree[2] = 1st level
	 * node i's children: _tree[2i+1],_tree[2i+2]
	 * i's parent: floor(_tree[i-1/2])
	 * the leaf nodes are the last n nodes 
	 */
	protected byte [][]_tree;
	protected int _numLeaves;
	
	/**
	 * @param contentBlocks the leaf content to be hashed into this 
	 * Merkle hash tree.
	 */
	public MerkleTree(String algorithm, byte [][] contentBlocks) {
		_numLeaves = contentBlocks.length;
		
		// How many entries do we need? The number of
		// leaves plus that number minus 1.
		_tree = new byte[2*_numLeaves - 1][];
		
		// Hash the leaves
		for (int i=0; i < numLeaves(); ++i) {
			_tree[leafIndex(i)] = Digest.hash(algorithm, contentBlocks[i]);
		}
		
		// Climb the tree
		int firstNode = firstLeaf(); 
		int endNode = size();
		int nextLevelStart = parent(firstNode);
		int nextLevelEnd = firstNode - 1;
		int parent;
		
		while (firstNode > 0) {
			for (int i = firstNode; i < endNode; i += 2) {
				parent = parent(firstNode);
				_tree[parent] = Digest.hash(_tree[i], _tree[i+1]);
			}
			firstNode = nextLevelStart;
			endNode = nextLevelEnd;
			nextLevelEnd = firstNode - 1;
			nextLevelStart = parent(firstNode);
		}
	}
	
	public MerkleTree(byte [][] contentBlocks) {
		this(Digest.DEFAULT_DIGEST, contentBlocks);
	}
	
	public int parent(int i) { 
		if (i == 0) {
			return 0;
		}
		return (int)Math.floor((i-1)/2.0); 
	}
	
	public int leftChild(int i) { return 2*i + 1; }
	public int rightChild(int i) { return 2*i + 2; }
	
	public byte [] root() { return _tree[0]; } 
	public byte [] get(int i) { return _tree[i]; }
	public int size() { return _tree.length; }
	
	public int numLeaves() { return _numLeaves; }
	
	public int firstLeaf() { return size() - numLeaves() + 1; }
	public int leafIndex(int i) { return firstLeaf() + i; }
	
	/**
	 * Retrieve just the leaf nodes.
	 * @param i leaf index, starting at 0 for the first leaf.
	 * @return
	 */
	public byte [] leaf(int i) {
		if ((i < 0) || (i > _numLeaves))
			throw new IllegalArgumentException(i + " is not a valid leaf value for a tree with " + numLeaves() + " leaves.");
		return _tree[firstLeaf() + i]; 
	}
	
	public byte [][] path(int leafNum) {
		throw new UnsupportedOperationException("Implement me!");		
	}
	
	/**
	 * DER-encode the path.
	 */
	public byte [] derEncodedPath(int leafNum) {
		throw new UnsupportedOperationException("Implement me!");
	}
}
