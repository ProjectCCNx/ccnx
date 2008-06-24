package com.parc.ccn.security.crypto;

import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.SignatureException;
import java.sql.Timestamp;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.Library;
import com.parc.ccn.data.CompleteName;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.security.ContentAuthenticator;
import com.parc.ccn.data.security.KeyLocator;
import com.parc.ccn.data.security.PublisherKeyID;
import com.parc.ccn.data.security.ContentAuthenticator.ContentType;

/**
 * This class extends your basic Merkle tree to 
 * incorporate the block name at each node, so that
 * names are authenticated as well as content in a
 * way that intermediary CCN nodes can verify.
 * @author smetters
 *
 */
public class CCNMerkleTree extends MerkleTree {
	
	public static final String DEFAULT_MHT_ALGORITHM = "SHA256MHT";
	
	ContentName _rootName = null;
	ContentAuthenticator _rootAuthenticator = null;
	ContentName [] _blockNames = null;
	byte [] _rootSignature = null;
	ContentAuthenticator [] _blockAuthenticators = null;
	int _baseBlockIndex = 0;
	
	public CCNMerkleTree(
			ContentName name, 
			PublisherKeyID publisher,
			Timestamp timestamp,
			byte[][] contentBlocks,
			boolean isDigest,
			int blockCount,
			int baseBlockIndex,
			KeyLocator locator,
			PrivateKey signingKey) throws InvalidKeyException, SignatureException {
		// Computes leaves and tree.
		super(DigestHelper.DEFAULT_DIGEST_ALGORITHM, contentBlocks, isDigest, blockCount);
		
		_rootName = name;
		
		_baseBlockIndex = baseBlockIndex;
		_blockNames = new ContentName[numLeaves()];
		_blockAuthenticators = new ContentAuthenticator[numLeaves()];
		
		// DKS TODO root() here is throwing an exception
		_rootAuthenticator = new ContentAuthenticator(publisher, null, timestamp, 
													  ContentType.FRAGMENT, locator, root(), true);
		_rootSignature = ContentObject.sign(_rootName, _rootName.count(),
											_rootAuthenticator, signingKey);
	}

	public ContentName getBlockName(int i) {
		if ((i < 0) || (i > _blockNames.length))
			throw new IllegalArgumentException("Index out of range!");
		return _blockNames[i];
	}
	
	public ContentAuthenticator getBlockAuthenticator(int i) {
		if ((i < 0) || (i > _blockAuthenticators.length))
			throw new IllegalArgumentException("Index out of range: " + i);
		return _blockAuthenticators[i];
	}

	ContentAuthenticator rootAuthenticator() {
		return _rootAuthenticator;
	}
	
	byte [] rootSignature() {
		return _rootSignature;
	}

	/**
     * The content authenticators for a set of fragments consist of:
     * The authenticator of the header block, which contains both
     * the root hash of the Merkle tree and the hash of the content.
     * This is a normal authenticator, signing the header block 
     * as content.
     * Then for each fragment, the authenticator contains the signature
     * on the root hash as the signature, and the set of hashes necessary
     * to verify the Merkle structure as the content hash.
     * 
     * The signature on the root node is actually a signature
     * on a content authenticator containing the root hash as its
     * content item. That way we also sign the publisher ID, type,
     * etc.
     * 
     * @throws SignatureException 
     * @throws NoSuchAlgorithmException 
     * @throws InvalidKeyException 
     */
    public CompleteName
    	getBlockCompleteName(int i) throws SignatureException, InvalidKeyException, NoSuchAlgorithmException {
    	
		if ((i < 0) || (i > _blockNames.length))
			throw new IllegalArgumentException("Index i out of range: " + i);
 
		if (null == _blockAuthenticators[i]) {
			_blockAuthenticators[i]=
				new ContentAuthenticator(
						rootAuthenticator().publisherKeyID(), 
						rootAuthenticator().nameComponentCount(),
						rootAuthenticator().timestamp(), 
						ContentType.FRAGMENT, 
						rootAuthenticator().keyLocator(),
						derPath(i).derEncodedPath(),
						true);
		}
		
		return new CompleteName(getBlockName(i), getBlockAuthenticator(i), rootSignature());
    }


	/**
	 * We need to incorporate the name of the content block
	 * into the tree. We also need to incorporate the hash
	 * of the content block itself into the name to make it
	 * unique.
	 * DKS TODO aren't incorporating the fragment identifier into
	 * the name.
	 * @param i
	 * @param contentBlocks
	 * @return
	 * @throws  
	 */
	protected byte [] computeBlockDigest(int i, byte [][] contentBlocks) {
		
		// First we need to construct dummy content authenticator.
		// Eventually need to optimize this across blocks; start
		// for now doing it the straightforward way.
		byte [] contentDigest = super.computeBlockDigest(i, contentBlocks);
	
		CompleteName uniqueName = null;
		try {
			uniqueName = CompleteName.generateAuthenticatedName(
					_rootName,
					rootAuthenticator().publisherKeyID(),
					rootAuthenticator().timestamp(),
					ContentAuthenticator.ContentType.FRAGMENT,
					rootAuthenticator().keyLocator(),
					contentDigest,
					true,
					null);
		} catch (InvalidKeyException e1) {
			Library.handleException("Unexpected exception: InvalidKeyException when no signature performed.", e1);
			return null;
		} catch (SignatureException e1) {
			Library.handleException("Unexpected exception: Signature when no signature performed.", e1);
			return null;
		}
		
		_blockNames[i] = uniqueName.name();
		byte[] blockDigest = null;
		try {
			blockDigest = super.computeNodeDigest(_blockNames[i].encode(), contentBlocks[i]);
		} catch (XMLStreamException e) {
			Library.handleException("This should not happen: exception encoding XML content we created!",e);
		}
		return blockDigest;
	}

}
