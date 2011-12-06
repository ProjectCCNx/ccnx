/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008-2010 Palo Alto Research Center, Inc.
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

/**
 * Helper class for digest algorithms.
 * Includes static methods to compute the digest of an array of bytes with
 * DEFAULT_DIGEST_ALGORITHM ("SHA-1" by default). 
 * Includes methods for computing Merkle hash trees (hash computation of the 
 * concatenation of two or more arrays of bytes.)
 */
public class DigestHelper {

	public static String DEFAULT_DIGEST_ALGORITHM = "SHA-1";
	// public static String DEFAULT_DIGEST_ALGORITHM = "SHA-256"; // change length to 32
	public static int DEFAULT_DIGEST_LENGTH = 20;

	protected MessageDigest _md;

	/**
	 * Instantiates a MessageDigest of type DEFAULT_DIGEST_ALGORITHM.
	 */
	public DigestHelper() {
		try {
			_md = MessageDigest.getInstance(getDefaultDigest());
		} catch (java.security.NoSuchAlgorithmException ex) {
			// possible configuration problem
			Log.warning("Fatal Error: cannot find default algorithm " + getDefaultDigest());
			throw new RuntimeException("Error: can't find default algorithm " + getDefaultDigest() + "!  " + ex.toString());
		}
	}

	/**
	 * Instantiates a MessageDigest of type digestAlgorithm.
	 * @param digestAlgorithm the digest algorithm selected.
	 * @throws NoSuchAlgorithmException
	 */
	public DigestHelper(String digestAlgorithm) throws NoSuchAlgorithmException {
		_md = MessageDigest.getInstance((null == digestAlgorithm) ? getDefaultDigest() : digestAlgorithm);
	}

	/**
	 * This method is non-static so subclasses can override it.
	 * @return the default digest algorithm.
	 */
	public String getDefaultDigest() { return DEFAULT_DIGEST_ALGORITHM; }

	/**
	 * Updates the digest using the specified array of bytes, starting at the specified offset. 
	 * @param content the array of bytes.
	 * @param offset the offset.
	 * @param len the number of bytes to use, starting at offset.
	 */
	public void update(byte [] content, int offset, int len) {
		_md.update(content, offset, len);
	}

	public void update(byte [] content) {
		_md.update(content);
	}

	/**
	 * Completes the hash computation by performing final operations such as padding. 
	 * The digest is reset after this call is made. 
	 * @return the array of bytes for the resulting hash value.
	 */
	public byte [] digest() {
		return _md.digest();
	}

	/**
	 * Static method to hash an array of bytes with DEFAULT_DIGEST_ALGORITHM.
	 * @param content the array of bytes.
	 * @return the array of bytes for the resulting hash value.
	 */
	public static byte[] digest(byte [] content) {
		if (null == content) {
			throw new IllegalArgumentException("Content cannot be null!");
		}
		return digest(content, 0, content.length);
	}

	/**
	 * Static method to hash an array of bytes with a specified digest algorithm. 
	 * @param digestAlgorithm the digest algorithm.
	 * @param content the array of bytes.
	 * @return the array of bytes for the resulting hash value.
	 * @throws NoSuchAlgorithmException
	 */
	public static byte [] digest(String digestAlgorithm, byte [] content) throws NoSuchAlgorithmException {
		if (null == content) {
			throw new IllegalArgumentException("Content cannot be null!");
		}
		return digest(digestAlgorithm, content, 0, content.length);
	}

	/**
	 * Static method to hash an array of bytes with DEFAULT_DIGEST_ALGORITHM,
	 * starting at the specified offset.
	 * @param content the array of bytes.
	 * @param offset the offset.
	 * @param length the number of bytes to use, starting at offset.
	 * @return the array of bytes for the resulting hash value.
	 */
	public static byte [] digest(byte [] content, int offset, int length) {
		DigestHelper dh = new DigestHelper();
		dh.update(content, offset, length);
		return dh.digest();
	}

	/**
	 * Static method to hash an array of bytes with a specified digest algorithm,
	 * starting at the specified offset.
	 * @param digestAlgorithm the digest algorithm.
	 * @param content the array of bytes.
	 * @param offset the offset.
	 * @param length the number of bytes to user, starting at offset.
	 * @return the array of bytes for the resulting hash value.
	 * @throws NoSuchAlgorithmException
	 */
	public static byte [] digest(String digestAlgorithm, byte [] content, int offset, int length) throws NoSuchAlgorithmException {
		DigestHelper dh = new DigestHelper(digestAlgorithm);
		dh.update(content, offset, length);
		return dh.digest();
	}

	/**
	 * Helper function for building Merkle hash trees. Returns digest of
	 * two concatenated byte arrays. If either is null, simply includes
	 * the non-null array. The digest is computed with DEFAULT_DIGEST_ALGORITHM.
	 * @param content1 first array of bytes.
	 * @param content2 second array of bytes.
	 * @return the array of bytes for the resulting hash value.
	 */
	public static byte[] digest(byte [] content1, byte [] content2) {
		return digest(new byte [][]{content1, content2});
	}

	/**
	 * Helper function for building Merkle hash trees. Returns digest of
	 * two concatenated byte arrays. If either is null, simply includes
	 * the non-null array. The digest is computed with the specified digest algorithm.
	 * @param digestAlgorithm the digest algorithm.
	 * @param content1 first array of bytes.
	 * @param content2 second array of bytes.
	 * @return the array of bytes for the resulting hash value.
	 * @throws NoSuchAlgorithmException 
	 */
	public static byte [] digest(String digestAlgorithm, byte [] content1, byte [] content2) throws NoSuchAlgorithmException {
		return digest(digestAlgorithm, new byte [][]{content1, content2});
	}

	/**
	 * Helper function for building Merkle hash trees. 
	 * Returns the digest of an array of byte arrays.
	 * The digest is computed with DEFAULT_DIGEST_ALGORITHM.
	 * @param contents the array of byte arrays.
	 * @return the array of bytes for the resulting hash value.
	 */
	public static byte [] digest(byte[][] contents) {
		DigestHelper dh = new DigestHelper();
		for (int i=0; i < contents.length; ++i) {
			dh.update(contents[i], 0, contents[i].length);
		}
		return dh.digest();
	}	

	/**
	 * Helper function for building Merkle hash trees. 
	 * Returns the digest of an array of byte arrays.
	 * The digest is computed with the specified digest algorithm.
	 * @param digestAlgorithm the digest algorithm.
	 * @param contents the array of byte arrays.
	 * @return the array of bytes of the resulting hash value.
	 * @throws NoSuchAlgorithmException
	 */
	public static byte [] digest(String digestAlgorithm, byte[][] contents) throws NoSuchAlgorithmException {
		DigestHelper dh = new DigestHelper(digestAlgorithm);
		for (int i=0; i < contents.length; ++i) {
			dh.update(contents[i], 0, contents[i].length);
		}
		return dh.digest();
	}

	/**
	 * Digests some array of bytes with the specified digest algorithm and wraps it in a DigestInfo.
	 * @param digestAlgorithm the digest algorithm.
	 * @param content the array of bytes.
	 * @return the array of bytes of the resulting DigestInfo.
	 * @throws CertificateEncodingException
	 * @throws NoSuchAlgorithmException 
	 */
	public static byte [] encodedDigest(String digestAlgorithm, byte [] content) throws CertificateEncodingException, NoSuchAlgorithmException {
		byte [] digest = digest(digestAlgorithm, content);
		return digestEncoder(digestAlgorithm, digest);
	}

	/**
	 * Digests some array of bytes with DEFAULT_DIGEST_ALGORITHM and wraps it in a DigestInfo.
	 * @param content the array of bytes.
	 * @return the array of bytes of the resulting DigestInfo.
	 * @throws CertificateEncodingException
	 * @throws NoSuchAlgorithmException 
	 */
	public static byte [] encodedDigest(byte [] content) throws CertificateEncodingException {
		byte [] digest = digest(content);
		return digestEncoder(DEFAULT_DIGEST_ALGORITHM, digest);
	}

	/**
	 * Takes a specified digest and wraps it in a DigestInfo for the specified digest algorithm.
	 * @param digestAlgorithm the digest algorithm.
	 * @param theDigest the digest.
	 * @return the array of bytes of the resulting DigestInfo.
	 */
	public static byte [] digestEncoder(String digestAlgorithm, byte [] theDigest) {
		AlgorithmIdentifier digestAlg = 
			new AlgorithmIdentifier(OIDLookup.getDigestOID(digestAlgorithm));
		DigestInfo info = new DigestInfo(digestAlg, theDigest);
		try {
			return CryptoUtil.encode(info);
		} catch (CertificateEncodingException e) {
			Log.warning("Exception encoding digest as digest info using standard algorithms: " + e.getMessage());
			Log.warningStackTrace(e);
			// DKS TODO what to actually throw
			return new byte[0];
		}
	}

	/**
	 * Returns the DigestInfo corresponding to a specified array of bytes.
	 * @param encodedDigest the array of bytes.
	 * @return the corresponding DigestInfo.
	 * @throws CertificateEncodingException
	 */
	public static DigestInfo digestDecoder(byte [] encodedDigest) throws CertificateEncodingException {
		DERObject di = CryptoUtil.decode(encodedDigest);
		DigestInfo info = new DigestInfo((ASN1Sequence)di);
		return info;
	}

	/**
	 * Returns an array of bytes as a String.
	 * @param binaryObject the array of bytes.
	 * @param radix the radix.
	 * @return the corresponding String.
	 */
	public static String printBytes(byte [] binaryObject, int radix) {
		BigInteger bi = new BigInteger(binaryObject);
		return bi.toString(radix);
	}

	/**
	 * Returns a String as an array of bytes.
	 * @param encodedString the String.
	 * @param radix the radix.
	 * @return the corresponding array of bytes.
	 */
	public static byte [] scanBytes(String encodedString, int radix) {
		BigInteger bi = new BigInteger(encodedString, radix);
		return bi.toByteArray();
	}
}
