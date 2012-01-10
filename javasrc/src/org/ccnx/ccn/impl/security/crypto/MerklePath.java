/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008, 2009 Palo Alto Research Center, Inc.
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

import java.security.cert.CertificateEncodingException;
import java.util.Arrays;
import java.util.Enumeration;

import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.DERInteger;
import org.bouncycastle.asn1.DERObject;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.DigestInfo;
import org.ccnx.ccn.impl.security.crypto.util.CryptoUtil;
import org.ccnx.ccn.impl.security.crypto.util.OIDLookup;


/**
 * A representation of a path through a MerkleTree.
 */
public class MerklePath {
	
	int _leafNodeIndex;
	DEROctetString [] _path = null;
	
	// DKS TODO: implement lookup mechanism to get MHT
	// OID from component digest OID. Right now just pull
	// from CCNMerkleTree.
	
	/**
	 * Create a MerklePath for a given leaf
	 * @param leafNodeIndex the leaf index
	 * @param path the node digests necessary to verify that leaf
	 */
	public MerklePath(int leafNodeIndex, DEROctetString [] path) {
		_leafNodeIndex = leafNodeIndex;
		_path = path;
	}
	
	/**
	 * Decode a DER encoded MerklePath
	 * @param derEncodedPath the encoded path
	 * @throws CertificateEncodingException if there is a decoding error
	 */
	public MerklePath(byte [] derEncodedPath) throws CertificateEncodingException {
		DERObject decoded = CryptoUtil.decode(derEncodedPath);
		ASN1Sequence seq = (ASN1Sequence)decoded;
		DERInteger intVal = (DERInteger)seq.getObjectAt(0);
		_leafNodeIndex = intVal.getValue().intValue();
		ASN1Sequence seqOf = (ASN1Sequence)seq.getObjectAt(1);
		_path = new DEROctetString[seqOf.size()];
		Enumeration<?> en = seqOf.getObjects();
		int i=0;
		while (en.hasMoreElements()) {
			_path[i++] = (DEROctetString)en.nextElement();
		}
	}
	
	/**
	 * Compute the parent digest of the current node
	 * @param node the current node
	 * @param length the length of the path at this point
	 * @param pathDigest the previously computed digest along this path
	 * @return the parent digest
	 */
	protected byte [] computeParent(int node, int length, byte [] pathDigest) {
		byte [] parentDigest = null;
		if (MerkleTree.isRight(node)) {
			parentDigest = MerkleTree.computeNodeDigest(entry(length-1).getOctets(), pathDigest);
		} else {
			parentDigest = MerkleTree.computeNodeDigest(pathDigest, entry(length-1).getOctets());
		}	
		return parentDigest;
	}
	
	/**
	 * Take the content block for which this is the MerklePath,
	 * and compute the root digest for verification. The caller then needs
	 * to check whether it matches the root, and 
	 * the root is authentic (signed by a trusted key).
	 * @param nodeContent either the content of the block or its
	 * 	digest. If a subclass of MerkleTree overrides computeBlockDigest,
	 *  a caller must hand in the digest, as this uses the MerkleTree default.
	 * @param isDigest was this node already digested, or do we need to digest it
	 * @return the computed root digest
	 */
	public byte [] root(byte [] nodeContent, boolean isDigest) {
		if ((leafNodeIndex() < MerkleTree.ROOT_NODE) || (_path == null) ||
				(_path.length == 0) || (null == nodeContent)) {
			throw new IllegalArgumentException("MerklePath value illegal -- cannot verify!");
		}
		
		// subclasses must hand in the precomputed digest if they
		// override computeBlockDigest.
		byte [] leafDigest = (isDigest ? nodeContent :
								MerkleTree.computeBlockDigest(nodeContent));
		
		// Now, work our way up through the nodes in the path.
		int length = pathLength();
		
		int node = leafNodeIndex();
		byte [] pathDigest = leafDigest;
		
		// With the extended binary tree, this becomes simple -- all paths are
		// full, it's just that some are shorter than others...
		while (node != MerkleTree.ROOT_NODE) {
			pathDigest = computeParent(node, length, pathDigest);
			length--;
			node = MerkleTree.parent(node);
		}
		return pathDigest;
	}
	
	/**
	 * Get an in the path, where i is the index into the path array.
	 * @param i the entry we want
	 * @return the entry
	 */
	public DEROctetString entry(int i) {
		if ((i < 0) || (i >= _path.length))
			return null;
		return _path[i];
	}
	
	/**
	 * Return the leaf node this path is for
	 * @return the leaf node index
	 */
	public int leafNodeIndex() { return _leafNodeIndex; }
		
	public int pathLength() { 
		if ((null == _path) || (_path.length == 0))
			return 0;
		return _path.length;
	}
	
	/**
	 * DER-encode the path. Embed it in a DigestInfo
	 * with the appropriate algorithm identifier.
	 * @return the DER-encoded path
	 */
	public byte [] derEncodedPath() {
		
		/**
		 * Sequence of OCTET STRING
		 */
		DERSequence sequenceOf = new DERSequence(_path);
		/**
		 * Sequence of INTEGER, SEQUENCE OF OCTET STRING
		 */
		DERInteger intVal = new DERInteger(leafNodeIndex());
		ASN1Encodable [] pathStruct = new ASN1Encodable[]{intVal, sequenceOf};
		DERSequence encodablePath = new DERSequence(pathStruct);
		byte [] encodedPath = encodablePath.getDEREncoded();
		
		// Wrap it up in a DigestInfo
		return CCNDigestHelper.digestEncoder(CCNMerkleTree.DEFAULT_MHT_ALGORITHM, encodedPath);
	}
	
	/**
	 * Determine whether a given DigestInfo contains a MerklePath
	 * @param info the DigestInfo
	 * @return true if this is a MerklePath, false otherwise
	 */
	public static boolean isMerklePath(DigestInfo info) {
		AlgorithmIdentifier digestAlg = 
			new AlgorithmIdentifier(OIDLookup.getDigestOID(CCNMerkleTree.DEFAULT_MHT_ALGORITHM));
		return (info.getAlgorithmId().equals(digestAlg));
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + _leafNodeIndex;
		result = prime * result + Arrays.hashCode(_path);
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		MerklePath other = (MerklePath) obj;
		if (_leafNodeIndex != other._leafNodeIndex)
			return false;
		if (!Arrays.equals(_path, other._path))
			return false;
		return true;
	}
}
