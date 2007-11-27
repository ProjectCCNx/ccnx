package com.parc.ccn.security.crypto;

import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.DERInteger;
import org.bouncycastle.asn1.DERObjectIdentifier;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.DERSequence;


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
	
	/**
	 * @param contentBlocks the leaf content to be hashed into this 
	 * Merkle hash tree.
	 */
	public MerkleTree(String algorithm, byte [][] contentBlocks) {
		_numLeaves = contentBlocks.length;
		_pathLength = (int)Math.ceil(Math.log(_numLeaves)/Math.log(2));
		
		// How many entries do we need? The number of
		// leaves plus that number minus 1.
		_tree = new DEROctetString[2*_numLeaves - 1];
		
		// Hash the leaves
		for (int i=0; i < numLeaves(); ++i) {
			_tree[leafIndex(i)] = new DEROctetString(Digest.hash(algorithm, contentBlocks[i]));
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
				_tree[parent] = new DEROctetString(Digest.hash(_tree[i].getOctets(), _tree[i+1].getOctets()));
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
			return -1;
		}
		return (int)Math.floor((i-1)/2.0); 
	}
	
	public int leftChild(int i) { return 2*i + 1; }
	public int rightChild(int i) { return 2*i + 2; }
	public int sibling(int i) {
		if (0 == (i % 2))
			return i+1;
		return i-1;
	}
	public boolean isRight(int i) { return (0 != (i % 2)); }
	public boolean isLeft(int i) { return (0 == (i % 2)); }
	
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
	 * Here, item 0 is the root, items n-1 and
	 * n-2 are the leaf itself and its sibling. The
	 * leaves in between are one each for each level of the
	 * tree. There are pathLength()+1 items (one extra for
	 * the leaf itself -- i.e. two at the terminal level).
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
	 *  We can derive whether this node is the last entry
	 *  or the next to last by whether or not nodeIndex
	 *  is odd; we can also use nodeIndex to determine
	 *  what block this path represents in the full object.
	 * @param leafNum
	 * @return
	 */
	public byte [][] path(int leafNum) {
		byte [][] result = new byte[pathLength()+1][];
		
		int sibling = sibling(leafNum);
		if (isLeft(leafNum)) {
			result[pathLength()-1] = get(leafNum);
			result[pathLength()] = get(sibling);
		} else {
			result[pathLength()-1] = get(sibling);
			result[pathLength()] = get(leafNum);
		}
		int index = parent(leafNum);
		while (index >= 0) {
			result[index] = get(sibling(index));
			index = parent(index);
		}
		return result;
	}
	
	protected DEROctetString [] derPath(int leafNum) {
		DEROctetString [] result = new DEROctetString[pathLength()+1];
		
		int sibling = sibling(leafNum);
		if (isLeft(leafNum)) {
			result[pathLength()-1] = derGet(leafNum);
			result[pathLength()] = derGet(sibling);
		} else {
			result[pathLength()-1] = derGet(sibling);
			result[pathLength()] = derGet(leafNum);
		}
		int index = parent(leafNum);
		while (index >= 0) {
			result[index] = derGet(sibling(index));
			index = parent(index);
		}
		return result;
	}
	
	/**
	 * DER-encode the path.
	 * TODO: DKS need decoder
	 */
	public byte [] derEncodedPath(int leafNum) {
		DEROctetString[] path = derPath(leafNum);
		
		/**
		 * Sequence of OCTET STRING
		 */
		DERSequence sequenceOf = new DERSequence(path);
		/**
		 * Sequence of INTEGER, SEQUENCE OF OCTET STRING
		 */
		DERInteger intVal = new DERInteger(leafNum);
		ASN1Encodable [] pathStruct = new ASN1Encodable[]{intVal, sequenceOf};
		DERSequence encodablePath = new DERSequence(pathStruct);
		byte [] encodedPath = encodablePath.getDEREncoded();
		return encodedPath;
	}
}
