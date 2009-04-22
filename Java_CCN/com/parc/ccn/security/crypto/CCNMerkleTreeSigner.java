package com.parc.ccn.security.crypto;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.SignatureException;

import com.parc.ccn.data.ContentObject;

public class CCNMerkleTreeSigner implements CCNAggregatedSigner {
	
	public void signBlocks(
			ContentObject [] contentObjects, 
			PrivateKey signingKey) throws InvalidKeyException, SignatureException, 
											 NoSuchAlgorithmException, IOException {
		
		// Generate the signatures for these objects.
		CCNMerkleTree tree = 
			new CCNMerkleTree(contentObjects, signingKey);
		tree.setSignatures();
	}

}
