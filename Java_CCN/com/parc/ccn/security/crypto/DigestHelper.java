package com.parc.ccn.security.crypto;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateEncodingException;

import javax.xml.stream.XMLStreamException;

import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.DERObject;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.DigestInfo;

import com.parc.ccn.Library;
import com.parc.ccn.data.util.XMLEncodable;
import com.parc.ccn.security.crypto.certificates.CryptoUtil;
import com.parc.ccn.security.crypto.certificates.OIDLookup;

public class DigestHelper {

	public static String DEFAULT_DIGEST_ALGORITHM = "SHA-256";
	// public static String DEFAULT_DIGEST_ALGORITHM = "SHA-1";
	public static int DEFAULT_DIGEST_LENGTH = 32;
	
	protected MessageDigest _md;
	
	public DigestHelper() {
		try {
			_md = MessageDigest.getInstance(DEFAULT_DIGEST_ALGORITHM);
		} catch (java.security.NoSuchAlgorithmException ex) {
			// DKS --big configuration problem
			Library.logger().warning("Fatal Error: cannot find algorithm " + DEFAULT_DIGEST_ALGORITHM);
			throw new RuntimeException("Error: can't find " + DEFAULT_DIGEST_ALGORITHM + "!  " + ex.toString());
		}
	}
	
	public DigestHelper(String digestAlgorithm) throws NoSuchAlgorithmException {
		_md = MessageDigest.getInstance(digestAlgorithm);
	}
	
	public void update(byte [] content, int offset, int len) {
		_md.update(content);
	}
	
	public byte [] digest() {
		return _md.digest();
	}
	
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
	 * two concatenated byte arrays. If either is null, simply includes
	 * the non-null array.
	 * @param content1
	 * @param content2
	 * @return
	 */
	public static byte[] digest(byte [] content1, byte [] content2) {
		return digest(DEFAULT_DIGEST_ALGORITHM, content1, content2);
	}
	
	/**
	 * Helper functions for building Merkle hash trees. Returns digest of
	 * two concatenated byte arrays. If either is null, simply includes
	 * the non-null array.
	 * @param content1
	 * @param content2
	 * @return
	 */
	public static byte [] digest(String digestAlgorithm, byte [] content1, byte [] content2) {
		byte [] hash = null;
		try {
	        MessageDigest md = MessageDigest.getInstance(digestAlgorithm);
	        if ((null == content1) && (null == content2)) 
	        	return null;
	        if (null != content1)
	        	md.update(content1);
	        if (null != content2)
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
	 * Same digest preparation algorithm as ContentObject.
	 * @throws XMLStreamException 
	 */
	public static byte [] digestLeaf(
			String digestAlgorithm,
			XMLEncodable [] toBeSigneds,
			byte [][] additionalToBeSigneds) throws XMLStreamException {
		
		if (null == toBeSigneds) {
			Library.logger().info("Value to be signed must not be null.");
			throw new XMLStreamException("Unexpected null content in digestLeaf!");
		}
		byte [][] encodedData = new byte [toBeSigneds.length + ((null != additionalToBeSigneds) ? additionalToBeSigneds.length : 0)][];
		for (int i=0; i < toBeSigneds.length; ++i) {
			encodedData[i] = toBeSigneds[i].encode();
		}
		if (null != additionalToBeSigneds) {
			for (int i=0,j=toBeSigneds.length; j < encodedData.length; ++i,++j) {
				encodedData[j] = additionalToBeSigneds[i];
			}
		}
		return DigestHelper.digest(digestAlgorithm, 
									encodedData);
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
	
	public static DigestInfo digestDecoder(byte [] encodedDigest) throws CertificateEncodingException {
		DERObject di = CryptoUtil.decode(encodedDigest);
		DigestInfo info = new DigestInfo((ASN1Sequence)di);
		return info;
	}
	
	public static String printBytes(byte [] binaryObject, int radix) {
		BigInteger bi = new BigInteger(1,binaryObject);
		return bi.toString(radix);
	}
	
	public static byte [] scanBytes(String encodedString, int radix) {
		BigInteger bi = new BigInteger(encodedString, radix);
		return bi.toByteArray();
	}
}
