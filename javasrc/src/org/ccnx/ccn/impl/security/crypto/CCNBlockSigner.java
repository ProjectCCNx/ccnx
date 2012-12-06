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
 * An unaggregating aggregated signer. Signs each block individually
 * using org.ccnx.ccn.protocol.ContentObject#sign(PrivateKey).
 */
public class CCNBlockSigner implements CCNAggregatedSigner {
	
	public void signBlocks(
			ContentObject [] contentObjects, 
			Key signingKey) throws InvalidKeyException, SignatureException, 
											 NoSuchAlgorithmException, IOException {
			
		if (null == signingKey) {
			throw new InvalidKeyException("Key cannot be null!");
		}

		for (ContentObject co : contentObjects) {
			co.sign(signingKey);
		}
	}
}
