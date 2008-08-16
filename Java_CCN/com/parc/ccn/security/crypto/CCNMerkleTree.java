package com.parc.ccn.security.crypto;

import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.SignatureException;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.Library;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.security.ContentAuthenticator;
import com.parc.ccn.data.security.Signature;

/**
 * This class extends your basic Merkle tree to 
 * incorporate the block name at each node, so that
 * names are authenticated as well as content in a
 * way that intermediary CCN nodes can verify.
 * 
 * For each content node in the CCNMerkleTree, we compute its
 * digest in the same way we would compute the digest of a leaf
 * node for signing (incorporating the name, authentication metadata,
 * and content). We then combine all these together into a MerkleTree,
 * and sign the root node.
 * 
 * To generate a leaf block digest, therefore, we need to know
 * - the content of the block
 * - the name for the block (which, for fragmented content, includes the fragment
 * 	   number. If we're buffering content and building trees per buffer, the
 * 	   fragment numbers may carry across buffers (e.g. leaf 0 of this tree might
 *     be fragment 37 of the content as a whole)
 * - the authentication metadata. In the case of fragmented content, this is
 *     likely to be the same for all blocks. In the case of other content, the
 *     publisher is likely to be the same, but the timestamp and even maybe the
 *     type could be different -- i.e. you could use a CCNMerkleTree to amortize
 *     signature costs over any collection of data, not just a set of fragments.
 *     
 * So, we either need to hand in all the names, or a function to call to get
 * the name for each block.
 * @author smetters
 *
 */
public class CCNMerkleTree extends MerkleTree {
	
	public static final String DEFAULT_MHT_ALGORITHM = "SHA256MHT";
	
	ContentName _baseName = null;
	int _baseNameIndex;
	ContentAuthenticator _authenticator = null;
	ContentName [] _blockNames = null;
	
	byte [] _rootSignature = null;
	
	Signature [] _signatures = null;
	
	
	/**
	 * Constructor for a CCNMerkleTree, that takes a base
	 * name and adds a counter to the end of it to make the block
	 * names.
	 * @param baseName The base name for the content.
	 * @param baseNameIndex The index of the first block with respect to the actual content (e.g.
	 * 	the fragment number, for auto-generated names). This will be incorporated into the name
	 * 	for the block.
	 * @param publisher The publisher ID of the signer.
	 * @param timestamp
	 * @param contentBlocks
	 * @param isDigest
	 * @param blockCount the number of blocks of the contentBlocks array to use
	 * @param blockOffset the offset into the contentBlocks array at which to start
	 * @throws NoSuchAlgorithmException 
	 * @throws SignatureException 
	 * @throws InvalidKeyException 
	 */
	public CCNMerkleTree(
			ContentName baseName, 
			int baseNameIndex,
			ContentAuthenticator authenticator,
			byte[][] contentBlocks,
			boolean isDigest,
			int blockCount,
			int blockOffset,
			PrivateKey signingKey) throws InvalidKeyException, SignatureException, NoSuchAlgorithmException {
		// Allocate node array
		super(CCNDigestHelper.DEFAULT_DIGEST_ALGORITHM, blockCount);
		
		// Initialize fields we need for building tree.
		_baseName = baseName;
		_baseNameIndex = baseNameIndex;
		_authenticator = authenticator;

		// Computes leaves and tree.
		initializeTree(contentBlocks, isDigest, blockOffset);
		
		_rootSignature = computeRootSignature(root(), signingKey);
	}

	/**
	 * Constructor for a CCNMerkleTree, that takes a base
	 * name and adds a counter to the end of it to make the block
	 * names.
	 * @param baseName The base name for the content.
	 * @param baseIndex The index of the first block.
	 * @param publisher The publisher ID of the signer.
	 * @param timestamp
	 * @param contentBlocks
	 * @param isDigest
	 * @param blockCount
	 * @param baseBlockIndex
	 * @throws NoSuchAlgorithmException 
	 * @throws SignatureException 
	 * @throws InvalidKeyException 
	 */
	public CCNMerkleTree(
			ContentName [] nodeNames, 
			ContentAuthenticator authenticator,
			byte[][] contentBlocks,
			boolean isDigest,
			int blockCount,
			int baseBlockIndex, 
			PrivateKey signingKey) throws InvalidKeyException, SignatureException, NoSuchAlgorithmException {
		// Computes leaves and tree.
		super(CCNDigestHelper.DEFAULT_DIGEST_ALGORITHM, contentBlocks, isDigest, blockCount, baseBlockIndex);
		
		_blockNames = nodeNames;
		_authenticator = authenticator;
		_rootSignature = computeRootSignature(root(), signingKey);
	}

	public byte [] rootSignature() { return _rootSignature; }
	
	/**
	 * The name of block leafIndex, where leafIndex is the leaf number in this
	 * tree. The overall index of leafIndex should be leafIndex + baseNameIndex().
	 * @param leafIndex
	 * @return
	 */
	public ContentName blockName(int leafIndex) {
		if (null == _blockNames) {
			_blockNames = new ContentName[numLeaves()];
		}
		
		if ((leafIndex < 0) || (leafIndex > _blockNames.length))
			throw new IllegalArgumentException("Index out of range!");
		
		if (null == _blockNames[leafIndex]) {
			_blockNames[leafIndex] = computeName(leafIndex);
		}
		return _blockNames[leafIndex];
	}
	
	protected ContentName computeName(int leafIndex) {
		return new ContentName(baseName(), Integer.toString(baseNameIndex() + leafIndex));
	}
	
	public int baseNameIndex() { return _baseNameIndex; }
	public ContentName baseName() { return _baseName; }
	
	public ContentAuthenticator blockAuthenticator(int i) {
		// Eventually allow for separate authenticators
		return _authenticator;
	}
	
	public Signature blockSignature(int leafIndex) {
		if (null == _signatures) {
			_signatures = new Signature[numLeaves()];
		}
		
		if ((leafIndex < 0) || (leafIndex > _signatures.length))
			throw new IllegalArgumentException("Index out of range!");
		
		if (null == _signatures[leafIndex]) {
			_signatures[leafIndex] = computeSignature(leafIndex);
		}
		return _signatures[leafIndex];
	}
	
	/**
	 * Helper function
	 * @param i
	 * @return
	 */
	public ContentObject block(int leafIndex, byte [] blockContent) {
		return new ContentObject(blockName(leafIndex), blockAuthenticator(leafIndex), blockContent, blockSignature(leafIndex));
	}
		
	protected Signature computeSignature(int leafIndex) {
		MerklePath path = path(leafIndex);
		return new Signature(path.derEncodedPath(), rootSignature());		
	}
	
	protected static byte [] computeRootSignature(byte [] root, PrivateKey signingKey) throws InvalidKeyException, SignatureException, NoSuchAlgorithmException {
		// Given the root of the authentication tree, compute a signature over it
		// Right now, this will digest again. It's actually quite hard to get at the raw
		// signature guts for various platforms to avoid re-digesting; too dependent on
		// the sig alg used.
		return CCNSignatureHelper.sign(null, root, signingKey);
	}
	
	/**
	 * We need to incorporate the name of the content block
	 * and the authenticator into the leaf digest of the tree.
	 * Essentially, we want the leaf digest to be the same thing
	 * we would use for signing a stand-alone leaf.
	 * @param leafIndex
	 * @param contentBlocks
	 * @return
	 * @throws  
	 */
	protected byte [] computeBlockDigest(int leafIndex, int blockOffset, byte [][] contentBlocks) {

		// Computing the leaf digest.
		//new XMLEncodable[]{name, authenticator}, new byte[][]{content},
		
		byte[] blockDigest = null;
		try {
			blockDigest = CCNDigestHelper.digest(
									CCNDigestHelper.DEFAULT_DIGEST_ALGORITHM, 
									ContentObject.prepareContent(blockName(leafIndex), blockAuthenticator(leafIndex),
																	contentBlocks[leafIndex + blockOffset]));
		} catch (XMLStreamException e) {
			Library.logger().info("Exception in computeBlockDigest, leaf: " + leafIndex + " out of " + numLeaves() + " type: " + e.getClass().getName() + ": " + e.getMessage());
			// DKS todo -- what to throw?
		}

		return blockDigest;
	}

}
