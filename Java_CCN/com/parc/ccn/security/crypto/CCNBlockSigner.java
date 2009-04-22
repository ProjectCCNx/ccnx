package com.parc.ccn.security.crypto;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.SignatureException;

import com.parc.ccn.data.ContentObject;

/**
 * An unaggregating aggregated signer. Signs each block individually.
 * @author smetters
 *
 */
public class CCNBlockSigner implements CCNAggregatedSigner {
	
	public void signBlocks(
			ContentObject [] contentObjects, 
			PrivateKey signingKey) throws InvalidKeyException, SignatureException, 
											 NoSuchAlgorithmException, IOException {
			
		if (null == signingKey) {
			throw new InvalidKeyException("Key cannot be null!");
		}

		for (ContentObject co : contentObjects) {
			co.sign(signingKey);
		}
	}
}
