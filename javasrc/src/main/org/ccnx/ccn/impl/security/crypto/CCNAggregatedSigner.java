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

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.NoSuchAlgorithmException;
import java.security.SignatureException;

import org.ccnx.ccn.protocol.ContentObject;


/**
 * An aggregated signer takes a set of blocks and computes signatures
 * over them such that each block can be verified individually.
 * An example aggregated signer computes a Merkle hash tree over
 * the component blocks and then constructs signatures for each.
 * 
 * Signing can be a computationally expensive operation; aggregated
 * signing mitigates this.
 * 
 * This could be a base abstract class or an interface; the former
 * would have a set of constructors or static factory methods that
 * made an object returning blocks. Instead, we try an interface
 * that has a set of bulk put methods which construct blocks, put
 * them to the network, and return an individual ContentObject.
 */
public interface CCNAggregatedSigner {
	
	// public CCNAggregatedSigner(); // example constructor

	/**
	 * 	 
	 * Sign a set of unrelated content objects in one aggregated signature pass.
	 * Objects must have already been constructed and initialized. They must
	 * all indicate the same signer. 
	 * Open questions:
	 * 	- should we re-set the publisherID? Currently assume that it
	 *   	was set to match the signing key when the blocks were
	 *   	built. This opens up the option to muck with
	 *    	the insides of COs more than ideal.
	 * @param contentObjects the set of objects to sign
	 * @param signingKey the key to sign with
	 * @throws InvalidKeyException if there is a problem with the signing key
	 * @throws SignatureException if we have an error in signature generation
	 * @throws NoSuchAlgorithmException if we do not recognize the default digest algorithm, or the signature
	 * 	algorithm associated with the key, or an internal algorithm used by the aggregating
	 *  signer
	 * @throws IOException
	 */
	public void signBlocks(
			ContentObject [] contentObjects, 
			Key signingKey) throws InvalidKeyException, SignatureException, 
											 NoSuchAlgorithmException, IOException;
	
}
