/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008, 2009, 2012 Palo Alto Research Center, Inc.
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

import java.security.InvalidKeyException;
import java.security.Key;
import java.security.NoSuchAlgorithmException;
import java.security.SignatureException;
import java.util.logging.Level;

import org.bouncycastle.asn1.DEROctetString;
import org.ccnx.ccn.impl.support.DataUtils;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.ContentEncodingException;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Signature;
import org.ccnx.ccn.protocol.SignedInfo;


/**
 * Extends the basic MerkleTree for use in CCN.
 * It incorporates the CCN ContentName for an object at each node, so that
 * names are authenticated as well as content in a
 * way that intermediary CCN nodes can verify.
 * 
 * For each leaf node in the CCNMerkleTree, we compute its
 * digest in exactly the same way we would compute the digest of a ContentObject
 * node for signing on its own (incorporating the name, authentication metadata,
 * and content). We then combine all these leaf digests together into a MerkleTree,
 * and sign the root node.
 * 
 * To generate a leaf block digest, therefore, we need to know
 * - the content of the block
 * - the name for the block (which, for segmented content, includes the segmented
 * 	   number. If we're buffering content and building trees per buffer, the
 * 	   fragment numbers may carry across buffers (e.g. leaf 0 of this tree might
 *     be fragment 37 of the content as a whole)
 *     
 * - the authentication metadata. In the case of fragmented content, this is
 *     likely to be the same for all blocks. In the case of other content, the
 *     publisher is likely to be the same, but the timestamp and even maybe the
 *     type could be different -- i.e. you could use a CCNMerkleTree to amortize
 *     signature costs over any collection of data, not just a set of fragments.
 *     
 * So, we either need to hand in all the names, or have a function to call to get
 * the name for each block.
 * 
 * Note: There is no requirement that a CCNMerkleTree be built only from the segments
 *     of a single piece of content, although that is the most common use. One
 *     can build and verify a CCNMerkleTree built out of an arbitrary set of
 *     ContentObjects; this may be a useful way of limiting the number of
 *     signatures generated on constrained platforms. Eventually the CCNSegmenter
 *     will be extended to handle such collections of arbitrary objects.
 *     
 */
public class CCNMerkleTree extends MerkleTree {
	
	public static final String DEFAULT_MHT_ALGORITHM = "SHA256MHT";
	
	byte [] _rootSignature = null;
	ContentObject [] _segmentObjects = null;
	
	/**
	 * Build a CCNMerkleTree from a set of leaf ContentObjects. 
	 * @param contentObjects must be at least 2 blocks, or will throw IllegalArgumentException.
	 * @param signingKey key to sign the root with
	 * @throws NoSuchAlgorithmException if key or DEFAULT_DIGEST_ALGORITHM are unknown
	 * @throws InvalidKeyException if signingKey is invalid
	 * @throws SignatureException if we cannot sign
	 */
	public CCNMerkleTree(ContentObject [] contentObjects, 
						 Key signingKey) throws NoSuchAlgorithmException, InvalidKeyException, SignatureException {

		super(CCNDigestHelper.DEFAULT_DIGEST_ALGORITHM, ((null != contentObjects) ? contentObjects.length : 0));
		_segmentObjects = contentObjects;
		if (null == _segmentObjects) {
			throw new IllegalArgumentException("Contained objects cannot be null!");
		}
		
		// Compute leaves and tree
		// DKS TODO -- all we're essentially doing is running the constructor now.
		// Maybe make a static method to process blocks.
		initializeTree(contentObjects);
		_rootSignature = computeRootSignature(root(), signingKey);
		setSignatures();
		if (Log.isLoggable(Log.FAC_SIGNING, Level.FINE))
			Log.fine(Log.FAC_SIGNING, "CCNMerkleTree: built a tree of " + contentObjects.length + " objects.");
	}

	/**
	 * Returns the root signature on the tree.
	 * @return the root signature
	 */
	public byte [] rootSignature() { return _rootSignature; }
	
	/**
	 * Generate the name of segment leafIndex, where leafIndex is the leaf number in this
	 * tree. The overall index of leafIndex should be leafIndex + baseNameIndex().
	 * @param leafIndex the leaf whose blockName to generate
	 * @return the name
	 */
	public ContentName segmentName(int leafIndex) {
		if ((leafIndex < 0) || (leafIndex > _segmentObjects.length))
			throw new IllegalArgumentException("Index out of range!");			
		if ((leafIndex < _segmentObjects.length) && (null != _segmentObjects[leafIndex])) 
			return _segmentObjects[leafIndex].name();
		return null;
	}
	
	/**
	 * Return the SignedInfo for a given segment.
	 * @param leafIndex the index of the leaf whose SignedInfo we want
	 * @return the SignedInfo
	 */
	public SignedInfo segmentSignedInfo(int leafIndex) {
		if ((leafIndex < 0) || (leafIndex > _segmentObjects.length))
			throw new IllegalArgumentException("Index out of range!");	
		if (null != _segmentObjects[leafIndex]) {
			return _segmentObjects[leafIndex].signedInfo();
		}
		return null;
	}
	
	/**
	 * Set the signature for a particular segment.
	 * @param leafIndex the leaf segment to set the signature for
	 * @return the Signature
	 */
	public Signature segmentSignature(int leafIndex) {
		if ((leafIndex < 0) || (leafIndex > _segmentObjects.length))
			throw new IllegalArgumentException("Index out of range!");
		
		if (null != _segmentObjects[leafIndex]) {
			if (null == _segmentObjects[leafIndex].signature()) {
				_segmentObjects[leafIndex].setSignature(computeSignature(leafIndex));
			}
			return _segmentObjects[leafIndex].signature();
		}
		
		return null;
	}
	
	/**
	 * Sets the signatures of all the contained ContentObjects.
	 */
	public void setSignatures() {
		for (int i=0; i < numLeaves(); ++i) {
			segmentSignature(i); // DKS TODO refactor, sets signature as a side effect
		}
	}
			
	/**
	 * A version of initializeTree to go with the CCNMerkleTree(ContentObject []) constructor.
	 * @param contentObjects objects to build into the tree
	 * @throws NoSuchAlgorithmException if the default digest algorithm unknown
	 */
	protected void initializeTree(ContentObject [] contentObjects) throws NoSuchAlgorithmException {
		if (contentObjects.length < numLeaves())
			throw new IllegalArgumentException("MerkleTree: cannot build tree from more blocks than given! Have " + contentObjects.length + " blocks, asked to use: " + (numLeaves()));
		
		computeLeafValues(contentObjects);
		computeNodeValues();		
	}

	/**
	 * Construct the Signature for a given leaf. This is composed of the rootSignature(),
	 * which is the same for all nodes, and the DER encoded MerklePath for this leaf as the
	 * witness.
	 * @param leafIndex the leaf to compute the signature for
	 * @return the signature
	 */
	protected Signature computeSignature(int leafIndex) {
		MerklePath path = path(leafIndex);
		return new Signature(path.derEncodedPath(), rootSignature());		
	}
	
	/**
	 * Compute the signature on the root node. It's already a digest, so in
	 * theory we could just wrap it up in some PKCS#1 padding, encrypt it
	 * with our private key, and voila! A signature. But there are basically
	 * no crypto software packages that provide signature primitives that take
	 * already-digested data and just do the padding and encryption, and so we'd
	 * be asking anyone attempting to implement CCN MHT signing (including ourselves)
	 * to re-implement a very complicated wheel, across a number of signature algorithms.
	 * We might also want to sign with a key that does not support the digest algorithm
	 * we used to compute the root (for example, DSA).
	 * So take the computationally very slightly more expensive, but vastly simpler
	 * (implementation-wise) approach of taking our digest and signing it with
	 * a standard signing API -- which means digesting it one more time for the
	 * signature. So we sign (digest + encrypt) the root digest. 
	 * 
	 * @param root the root digest to sign
	 * @param signingKey the key to sign with
	 * @return the bytes of the signature
	 * @throws InvalidKeyException
	 * @throws SignatureException
	 * @throws NoSuchAlgorithmException
	 */
	protected static byte [] computeRootSignature(byte [] root, Key signingKey) throws InvalidKeyException, SignatureException, NoSuchAlgorithmException {
		// Given the root of the authentication tree, compute a signature over it
		// Right now, this will digest again. It's actually quite hard to get at the raw
		// signature guts for various platforms to avoid re-digesting; too dependent on
		// the sig alg used.
		return CCNSignatureHelper.sign(null, root, signingKey);
	}
	
	/**
	 * Compute the leaf values of the ContentObjects in this tree
	 * @param contentObjects the content
	 * @throws NoSuchAlgorithmException if the digestAlgorithm unknown
	 */
	protected void computeLeafValues(ContentObject [] contentObjects) throws NoSuchAlgorithmException {
		// Hash the leaves
		for (int i=0; i < numLeaves(); ++i) {
			// DKS -- need to make sure content() doesn't clone
			try {
				ContentObject co = contentObjects[i];
				byte [] blockDigest = CCNDigestHelper.digest(co.prepareContent()); 
				_tree[leafNodeIndex(i)-1] = new DEROctetString(blockDigest);
				
				if (Log.isLoggable(Log.FAC_SIGNING, Level.FINER)) {
					Log.finer(Log.FAC_SIGNING, "offset: " + 0 + " block length: " + co.contentLength() + " blockDigest " + 
							DataUtils.printBytes(blockDigest) + " content digest: " + 
							DataUtils.printBytes(CCNDigestHelper.digest(co.content(), 0, co.contentLength())));
				}

			} catch (ContentEncodingException e) {
				Log.info("Exception in computeBlockDigest, leaf: " + i + " out of " + numLeaves() + " type: " + e.getClass().getName() + ": " + e.getMessage());
				e.printStackTrace();
				// DKS todo -- what to throw?
			}
		}
	}
}
