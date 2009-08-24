package org.ccnx.ccn.impl.security.crypto.util;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateEncodingException;

import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.DERObject;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.DigestInfo;
import org.ccnx.ccn.impl.support.Log;



public class DigestHelper {

	public static String DEFAULT_DIGEST_ALGORITHM = "SHA-1";
	// public static String DEFAULT_DIGEST_ALGORITHM = "SHA-256"; // change length to 64
	public static int DEFAULT_DIGEST_LENGTH = 32;
	
	protected MessageDigest _md;
	
	public DigestHelper() {
		try {
			_md = MessageDigest.getInstance(getDefaultDigest());
		} catch (java.security.NoSuchAlgorithmException ex) {
			// DKS --big configuration problem
			Log.logger().warning("Fatal Error: cannot find default algorithm " + getDefaultDigest());
			throw new RuntimeException("Error: can't find default algorithm " + getDefaultDigest() + "!  " + ex.toString());
		}
	}
	
	public DigestHelper(String digestAlgorithm) throws NoSuchAlgorithmException {
		_md = MessageDigest.getInstance(digestAlgorithm);
	}
	
	/**
	 * Has to be non-static to allow subclasses to override.
	 * @return
	 */
	public String getDefaultDigest() { return DEFAULT_DIGEST_ALGORITHM; }
	
	public void update(byte [] content, int offset, int len) {
		_md.update(content, offset, len);
	}
	
	public byte [] digest() {
		return _md.digest();
	}
	
    public static byte[] digest(byte [] content) {
		if (null == content) {
			throw new IllegalArgumentException("Content cannot be null!");
		}
		return digest(content, 0, content.length);
	}
	
	public static byte [] digest(String digestAlgorithm, byte [] content) throws NoSuchAlgorithmException {
		if (null == content) {
			throw new IllegalArgumentException("Content cannot be null!");
		}
		return digest(digestAlgorithm, content, 0, content.length);
	}
	
	public static byte [] digest(byte [] content, int offset, int length) {
		DigestHelper dh = new DigestHelper();
		dh.update(content, offset, length);
		return dh.digest();
	}
	
	public static byte [] digest(String digestAlgorithm, byte [] content, int offset, int length) throws NoSuchAlgorithmException {
		DigestHelper dh = new DigestHelper(digestAlgorithm);
		dh.update(content, offset, length);
		return dh.digest();
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
		return digest(new byte [][]{content1, content2});
	}
	
	/**
	 * Helper functions for building Merkle hash trees. Returns digest of
	 * two concatenated byte arrays. If either is null, simply includes
	 * the non-null array.
	 * @param content1
	 * @param content2
	 * @return
	 * @throws NoSuchAlgorithmException 
	 */
	public static byte [] digest(String digestAlgorithm, byte [] content1, byte [] content2) throws NoSuchAlgorithmException {
		return digest(digestAlgorithm, new byte [][]{content1, content2});
	}
	
	public static byte [] digest(byte [][] contents) {
		DigestHelper dh = new DigestHelper();
		for (int i=0; i < contents.length; ++i) {
			dh.update(contents[i], 0, contents[i].length);
		}
		return dh.digest();
	}	
	
	public static byte [] digest(String digestAlgorithm, byte [][] contents) throws NoSuchAlgorithmException {
		DigestHelper dh = new DigestHelper(digestAlgorithm);
		for (int i=0; i < contents.length; ++i) {
			dh.update(contents[i], 0, contents[i].length);
		}
		return dh.digest();
	}
	
	/**
	 * Digests some data and wraps it in a DigestInfo.
	 * @param digestAlgorithm
	 * @param content
	 * @return
	 * @throws CertificateEncodingException
	 * @throws NoSuchAlgorithmException 
	 */
	public static byte [] encodedDigest(String digestAlgorithm, byte [] content) throws CertificateEncodingException, NoSuchAlgorithmException {
		byte [] digest = digest(digestAlgorithm, content);
		return digestEncoder(digestAlgorithm, digest);
	}
	
	public static byte [] encodedDigest(byte [] content) throws CertificateEncodingException {
		byte [] digest = digest(content);
		return digestEncoder(DEFAULT_DIGEST_ALGORITHM, digest);
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
			Log.logger().warning("Exception encoding digest as digest info using standard algorithms: " + e.getMessage());
			Log.warningStackTrace(e);
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
