package org.ccnx.ccn.impl.security.crypto;

import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.SignatureException;

import javax.xml.stream.XMLStreamException;

import org.bouncycastle.asn1.DEROctetString;
import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.config.SystemConfiguration.DEBUGGING_FLAGS;
import org.ccnx.ccn.impl.support.DataUtils;
import org.ccnx.ccn.impl.support.Library;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Signature;
import org.ccnx.ccn.protocol.SignedInfo;


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
	
	byte [] _rootSignature = null;
	ContentObject [] _blockObjects = null;
	
	/**
	 * Build a CCNMerkleTree. 
	 * @param contentObjects must be at least 2 blocks, or will throw IllegalArgumentException.
	 * @param signingKey
	 * @throws NoSuchAlgorithmException
	 * @throws InvalidKeyException
	 * @throws SignatureException
	 */
	public CCNMerkleTree(ContentObject [] contentObjects, 
			PrivateKey signingKey) throws NoSuchAlgorithmException, InvalidKeyException, SignatureException {

		super(CCNDigestHelper.DEFAULT_DIGEST_ALGORITHM, ((null != contentObjects) ? contentObjects.length : 0));
		_blockObjects = contentObjects;
		if (null == _blockObjects) {
			throw new IllegalArgumentException("Contained objects cannot be null!");
		}
		
		// Compute leaves and tree
		// DKS TODO -- all we're essentially doing is running the constructor now.
		// Maybe make a static method to process blocks.
		initializeTree(contentObjects);
		_rootSignature = computeRootSignature(root(), signingKey);
		setSignatures();
		Library.logger().info("CCNMerkleTree: built a tree of " + contentObjects.length + " objects.");
	}

	public byte [] rootSignature() { return _rootSignature; }
	
	/**
	 * The name of block leafIndex, where leafIndex is the leaf number in this
	 * tree. The overall index of leafIndex should be leafIndex + baseNameIndex().
	 * @param leafIndex
	 * @return
	 */
	public ContentName blockName(int leafIndex) {
		if ((leafIndex < 0) || (leafIndex > _blockObjects.length))
			throw new IllegalArgumentException("Index out of range!");
				
		if ((null != _blockObjects) && (leafIndex < _blockObjects.length) && (null != _blockObjects[leafIndex])) 
			return _blockObjects[leafIndex].name();
		return null;
	}
	
	public SignedInfo blockSignedInfo(int leafIndex) {
		if ((leafIndex < 0) || (leafIndex > _blockObjects.length))
			throw new IllegalArgumentException("Index out of range!");
		
		if ((null != _blockObjects) && (null != _blockObjects[leafIndex])) {
			return _blockObjects[leafIndex].signedInfo();
		}
		return null;
	}
	
	public Signature blockSignature(int leafIndex) {
		if ((leafIndex < 0) || (leafIndex > _blockObjects.length))
			throw new IllegalArgumentException("Index out of range!");
		
		if ((null != _blockObjects) && (null != _blockObjects[leafIndex])) {
			if (null == _blockObjects[leafIndex].signature()) {
				_blockObjects[leafIndex].setSignature(computeSignature(leafIndex));
			}
			return _blockObjects[leafIndex].signature();
		}
		
		return null;
	}
	
	/**
	 * Sets the signatures of all the blockObjects.
	 * TODO DKS refactor this class to remove unused stuff.
	 */
	public void setSignatures() {
		for (int i=0; i < numLeaves(); ++i) {
			blockSignature(i); // DKS TODO refactor, sets signature as a side effect
		}
	}
			
	protected void initializeTree(ContentObject [] contentObjects) throws NoSuchAlgorithmException {
		if (contentObjects.length < numLeaves())
			throw new IllegalArgumentException("MerkleTree: cannot build tree from more blocks than given! Have " + contentObjects.length + " blocks, asked to use: " + (numLeaves()));
		
		computeLeafValues(contentObjects);
		computeNodeValues();		
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
	
	protected void computeLeafValues(ContentObject [] contentObjects) throws NoSuchAlgorithmException {
		// Hash the leaves
		for (int i=0; i < numLeaves(); ++i) {
			// DKS -- need to make sure content() doesn't clone
			_tree[leafNodeIndex(i)-1] = 
				new DEROctetString(computeBlockDigest(i, contentObjects[i].content(), 
													  0, contentObjects[i].contentLength()));
		}
	}

	/**
	 * We need to incorporate the name of the content block
	 * and the signedInfo into the leaf digest of the tree.
	 * Essentially, we want the leaf digest to be the same thing
	 * we would use for signing a stand-alone leaf.
	 * @param leafIndex
	 * @param contentBlocks
	 * @return
	 * @throws  
	 */
	@Override
	protected byte [] computeBlockDigest(int leafIndex, byte [] content, int offset, int length) {

		// Computing the leaf digest.
		//new XMLEncodable[]{name, signedInfo}, new byte[][]{content},
		
		byte[] blockDigest = null;
		try {
			blockDigest = CCNDigestHelper.digest(
									ContentObject.prepareContent(blockName(leafIndex), 
																 blockSignedInfo(leafIndex),
																 content, offset, length));
			if (SystemConfiguration.checkDebugFlag(DEBUGGING_FLAGS.DEBUG_SIGNATURES)) {
				Library.logger().info("offset: " + offset + " block length: " + length + " blockDigest " + 
						DataUtils.printBytes(blockDigest) + " content digest: " + 
						DataUtils.printBytes(CCNDigestHelper.digest(content, offset, length)));
			}
		} catch (XMLStreamException e) {
			Library.logger().info("Exception in computeBlockDigest, leaf: " + leafIndex + " out of " + numLeaves() + " type: " + e.getClass().getName() + ": " + e.getMessage());
			// DKS todo -- what to throw?
		} 

		return blockDigest;
	}
}
