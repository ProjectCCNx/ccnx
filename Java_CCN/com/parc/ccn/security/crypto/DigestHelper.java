package com.parc.ccn.security.crypto;

import java.security.MessageDigest;
import java.security.cert.CertificateEncodingException;
import java.util.Arrays;

import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.DERObject;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.DigestInfo;

import com.parc.ccn.Library;
import com.parc.ccn.security.crypto.certificates.CryptoUtil;
import com.parc.ccn.security.crypto.certificates.OIDLookup;

public class DigestHelper {

	public static String DEFAULT_DIGEST_ALGORITHM = "SHA256";
	// public static String DEFAULT_DIGEST_ALGORITHM = "SHA-1";
	
    public static byte[] digest(byte [] content) {
		return digest(DEFAULT_DIGEST_ALGORITHM, content);
	}
	
	public static byte [] digest(String digestAlgorithm, byte [] content) {
		byte [] hash = null;
		try {
	        MessageDigest md = MessageDigest.getInstance(digestAlgorithm);
	        hash = md.digest(content);
			return hash;
		} catch (java.security.NoSuchAlgorithmException ex) {
			// DKS --big configuration problem
			Library.logger().warning("Fatal Error: cannot find algorithm " + digestAlgorithm);
			throw new RuntimeException("Error: can't find " + digestAlgorithm + "!  " + ex.toString());
		}
	}

	/**
	 * Helper functions for building Merkle hash trees. Returns digest of
	 * two concatenated byte arrays.
	 * @param content1
	 * @param content2
	 * @return
	 */
	public static byte[] digest(byte [] content1, byte [] content2) {
		return digest(DEFAULT_DIGEST_ALGORITHM, content1, content2);
	}
	
	public static byte [] digest(String digestAlgorithm, byte [] content1, byte [] content2) {
		byte [] hash = null;
		try {
	        MessageDigest md = MessageDigest.getInstance(digestAlgorithm);
	        md.update(content1);
	        md.update(content2);
	        hash = md.digest();
			return hash;
		} catch (java.security.NoSuchAlgorithmException ex) {
			// DKS --big configuration problem
			Library.logger().warning("Fatal Error: cannot find algorithm " + digestAlgorithm);
			throw new RuntimeException("Error: can't find " + digestAlgorithm + "!  " + ex.toString());
		}
	}
	
	public static byte [] digest(String digestAlgorithm, byte [][] contents) {
		byte [] hash = null;
		try {
	        MessageDigest md = MessageDigest.getInstance(digestAlgorithm);
	        for (int i=0; i < contents.length; ++i) {
	        	md.update(contents[i]);
	        }
	        hash = md.digest();
			return hash;
		} catch (java.security.NoSuchAlgorithmException ex) {
			// DKS --big configuration problem
			Library.logger().warning("Fatal Error: cannot find algorithm " + digestAlgorithm);
			throw new RuntimeException("Error: can't find " + digestAlgorithm + "!  " + ex.toString());
		}
	}

	
	/**
	 * Digests some data and wraps it in a DigestInfo.
	 * @param digestAlgorithm
	 * @param content
	 * @return
	 * @throws CertificateEncodingException
	 */
	public static byte [] encodedDigest(String digestAlgorithm, byte [] content) throws CertificateEncodingException {
		byte [] digest = digest(digestAlgorithm, content);
		return digestEncoder(digestAlgorithm, digest);
	}
	
	public static byte [] encodedDigest(byte [] content) throws CertificateEncodingException {
		return encodedDigest(DEFAULT_DIGEST_ALGORITHM, content);
	}
	
	/**
	 * Takes an existing digest and wraps it in a DigestInfo.
	 * Do we need to wrap the byte [] in an OctetString?
	 * Or does BC do that?
	 */
	public static byte [] digestEncoder(String digestAlgorithm, byte [] theDigest) {
		AlgorithmIdentifier digestAlg = 
			new AlgorithmIdentifier(OIDLookup.getDigestOID(digestAlgorithm));
		DigestInfo info = new DigestInfo(digestAlg, theDigest);
		try {
			return CryptoUtil.encode(info);
		} catch (CertificateEncodingException e) {
			Library.logger().warning("Exception encoding digest as digest info using standard algorithms: " + e.getMessage());
			Library.warningStackTrace(e);
			// DKS TODO what to actually throw
			return new byte[0];
		}
	}
	
	/**
	 * Decode DigestInfo and determine whether this is
	 * a straight digest or a path.
	 * @param encodedDigest
	 * @return
	 * @throws CertificateEncodingException 
	 */
	public static byte [] digestToSign(byte [] encodedDigest) throws CertificateEncodingException {
		DigestInfo info = digestDecoder(encodedDigest);
		if (MerkleTree.isMerkleTree(info.getAlgorithmId())) {
			MerklePath mp = new MerklePath(info.getDigest());
			return mp.getRootAsEncodedDigest();
		} else {
			return info.getDigest();
		}
	}

	public static boolean verifyDigest(
			byte [] encodedDigest,
			byte [] content) throws CertificateEncodingException {
		DigestInfo info = digestDecoder(encodedDigest);
		if (MerkleTree.isMerkleTree(info.getAlgorithmId())) {
			MerklePath mp = new MerklePath(info.getDigest());
			return mp.verify(content);
		} else {
			byte [] digest = digest(OIDLookup.getDigestName(info.getAlgorithmId().getObjectId().getId()), content);
			return Arrays.equals(info.getDigest(), digest);
		}
	}
	
	public static DigestInfo digestDecoder(byte [] encodedDigest) throws CertificateEncodingException {
		DERObject di = CryptoUtil.decode(encodedDigest);
		DigestInfo info = new DigestInfo((ASN1Sequence)di);
		return info;
	}
}
