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
import com.parc.ccn.data.security.ContentAuthenticator;
import com.parc.ccn.data.security.KeyLocator;
import com.parc.ccn.data.security.PublisherID;
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
	
	ContentName _rootName = null;
	PublisherID _publisherID = null;
	Timestamp _timestamp = null;
	KeyLocator _locator = null;
	ContentName [] _blockNames = null;
	ContentAuthenticator _rootAuthenticator = null;
	PrivateKey _signingKey = null; // don't like keeping this here,
		// but simplifies things... leave it for now.
	ContentAuthenticator [] _blockAuthenticators = null;
	
	public CCNMerkleTree(
			ContentName name, 
			PublisherID publisher,
			Timestamp timestamp,
			byte[][] contentBlocks,
			KeyLocator locator,
			PrivateKey signingKey) {
		
		super(Digest.DEFAULT_DIGEST, contentBlocks);
		
		_rootName = name;
		_publisherID = publisher;
		_timestamp = timestamp;
		_locator = locator;
		
		_blockNames = new ContentName[numLeaves()];
		_blockAuthenticators = new ContentAuthenticator[numLeaves()];
		
		computeLeafValues(contentBlocks);
		computeNodeValues();
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

	/**
   	 * Need to sign the root node, along with the other supporting
	 * data.
   	 * @throws NoSuchAlgorithmException 
   	 * @throws SignatureException 
   	 * @throws InvalidKeyException 
	 **/ 
	ContentAuthenticator rootAuthenticator() throws InvalidKeyException, SignatureException, NoSuchAlgorithmException {
		
		if (null == _rootAuthenticator) {
			synchronized(this) {
				// DKS TODO -- need to indicate how much of final name we are signing.
				// Add a component count to the content authenticator.
				_rootAuthenticator = new ContentAuthenticator(
						_rootName, null, _publisherID, _timestamp, 
						ContentType.FRAGMENT, root(), true, 
						_locator, _signingKey);
			}
		}
		return _rootAuthenticator;
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
						_publisherID, 
						rootAuthenticator().nameComponentCount(),
						_timestamp, 
						ContentType.FRAGMENT, 
						derPath(i).derEncodedPath(),
						true,
						_locator, 
						rootAuthenticator().signature());
		}
		
		return new CompleteName(getBlockName(i), getBlockAuthenticator(i));
    }


	/**
	 * We need to incorporate the name of the content block
	 * into the tree. We also need to incorporate the hash
	 * of the content block itself into the name to make it
	 * unique.
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
	
		CompleteName uniqueName;
		try {
			uniqueName = ContentAuthenticator.generateAuthenticatedName(
					_rootName,
					_publisherID,
					_timestamp,
					ContentAuthenticator.ContentType.FRAGMENT,
					contentDigest,
					true,
					_locator,
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
			blockDigest = super.computeNodeDigest(_blockNames[i].canonicalizeAndEncode(), contentBlocks[i]);
		} catch (XMLStreamException e) {
			Library.handleException("This should not happen: exception encoding XML content we created!",e);
		}
		return blockDigest;
	}

}
