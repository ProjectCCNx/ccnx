package com.parc.ccn.security.crypto;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.SignatureException;

import com.parc.ccn.data.ContentObject;

/**
 * An aggregated signer takes a set of blocks and computes signatures
 * over them such that each block can be verified individually.
 * An example aggregated signer computes a Merkle hash tree over
 * the component blocks and then constructs signatures for each.
 * 
 * This could be a base abstract class or an interface; the former
 * would have a set of constructors or static factory methods that
 * made an object returning blocks. Instead, we try an interface
 * that has a set of bulk put methods which construct blocks, put
 * them to the network, and return an individual ContentObject.
 * 
 * 
 * @author smetters
 *
 */
public interface CCNAggregatedSigner {
	
	// public CCNAggregatedSigner(); // example constructor

	/**
	 * 	 
	 * Sign a set of unrelated content objects in one aggregated signature pass.
	 * Objects must have already been constructed and initialized. They must
	 * all indicate the same signer. 
	 * DKS TODO -- should we re-set the publisherID? Currently assume that it
	 *   was set to match the chinging key. Opens up the option to muck with
	 *    the insides of COs more than ideal.
	 *    TODO -- should the segmenter and these classes move into same package
	 *      with CO in order to have access to internal methods?
	 * @param segmenter
	 * @param contentObjects
	 * @param publisher used to select the private key to sign with.
	 * @return
	 * @throws InvalidKeyException
	 * @throws SignatureException
	 * @throws NoSuchAlgorithmException
	 * @throws IOException
	 */
	public void signBlocks(
			ContentObject [] contentObjects, 
			PrivateKey signingKey) throws InvalidKeyException, SignatureException, 
											 NoSuchAlgorithmException, IOException;
	
}
