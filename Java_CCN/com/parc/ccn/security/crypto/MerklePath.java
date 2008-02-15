package com.parc.ccn.security.crypto;

import java.security.cert.CertificateEncodingException;
import java.util.Arrays;
import java.util.Enumeration;

import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.DERInteger;
import org.bouncycastle.asn1.DERObject;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.DERSequence;

import com.parc.ccn.security.crypto.certificates.CryptoUtil;

public class MerklePath {
	
	int _leafNum;
	DEROctetString [] _path = null;
	// DKS TODO: implement lookup mechanism to get MHT
	// OID from component digest OID. Right now just pull
	// from CCNMerkleTree.
	
	public MerklePath(int leafNum, DEROctetString [] path) {
		_leafNum = leafNum;
		_path = path;
	}
	
	public MerklePath(byte [] derEncodedPath) throws CertificateEncodingException {
		DERObject decoded = CryptoUtil.decode(derEncodedPath);
		ASN1Sequence seq = (ASN1Sequence)decoded;
		DERInteger intVal = (DERInteger)seq.getObjectAt(0);
		_leafNum = intVal.getValue().intValue();
		ASN1Sequence seqOf = (ASN1Sequence)seq.getObjectAt(1);
		_path = new DEROctetString[seqOf.size()];
		Enumeration<?> en = seqOf.getObjects();
		int i=0;
		while (en.hasMoreElements()) {
			_path[i] = (DEROctetString)en.nextElement();
		}
	}
	
	/**
	 * Take the content block for which this is the MerklePath,
	 * and verify that it matches. The caller then needs
	 * to check whether the root is authentic.
	 * @return
	 */
	public boolean verify(byte [] nodeContent) {
		if ((_leafNum < 0) || (_path == null) ||
				(_path.length == 0) || (null == nodeContent)) {
			throw new IllegalArgumentException("MerklePath value illegal -- cannot verify!");
		}
		byte [] leafDigest = 
			MerkleTree.computeBlockDigest(nodeContent);
		
		byte [] node = leafDigest;
		int nodeIndex = _leafNum;
		// Each item in the path is the sibling of the
		// current value in the tree.
		// The exception is if the node is the last child
		// in the tree with no sibling...
		if (MerkleTree.isLeft(_leafNum) && 
				(MerkleTree.computePathLength(_leafNum) > 
				  _path.length)) {
			// This is a naked left child, no sibling.
			// Climb one more level.
			node = MerkleTree.computeNodeDigest(leafDigest);
			nodeIndex = MerkleTree.parent(nodeIndex);
		}
			
		for (int i=_path.length; i > 0; i--) {
			// node points to the digest of the current node,
			// nodeIndex is its index
			// _path[i] is its sibling
			if (MerkleTree.isLeft(nodeIndex)) {
				node = MerkleTree.computeNodeDigest(node, _path[i].getOctets());
			} else {
				node = MerkleTree.computeNodeDigest(_path[i].getOctets(), node);
			}
			nodeIndex = MerkleTree.parent(nodeIndex);	
		}
		
		// Now we should be up to the root. Compare.
		return Arrays.equals(node, _path[0].getOctets());
	}
	
	public DEROctetString root() { 
		if ((null == _path) || (_path.length == 0))
			return null;
		return _path[0];
	}
	
	public int pathLength() { 
		if ((null == _path) || (_path.length == 0))
			return 0;
		return _path.length;
	}

	/**
	 * DER-encode the path. Embed it in a DigestInfo
	 * with the appropriate algorithm identifier.
	 */
	public byte [] derEncodedPath() {
		
		/**
		 * Sequence of OCTET STRING
		 */
		DERSequence sequenceOf = new DERSequence(_path);
		/**
		 * Sequence of INTEGER, SEQUENCE OF OCTET STRING
		 */
		DERInteger intVal = new DERInteger(_leafNum);
		ASN1Encodable [] pathStruct = new ASN1Encodable[]{intVal, sequenceOf};
		DERSequence encodablePath = new DERSequence(pathStruct);
		byte [] encodedPath = encodablePath.getDEREncoded();
		
		// Wrap it up in a DigestInfo
		return DigestHelper.digestEncoder(CCNMerkleTree.DEFAULT_MHT_ALGORITHM, encodedPath);
	}

	public byte[] getRootAsEncodedDigest() {
		// Take root and wrap it up as an encoded DigestInfo
		return DigestHelper.digestEncoder(
				DigestHelper.DEFAULT_DIGEST_ALGORITHM, 
				root().getOctets());
	}
}
