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

package org.ccnx.ccn.impl.security.crypto;

import java.io.IOException;
import java.io.InputStream;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateEncodingException;

import org.ccnx.ccn.impl.encoding.XMLEncodable;
import org.ccnx.ccn.impl.security.crypto.util.DigestHelper;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.ContentEncodingException;

/**
 * CCN-specific helper methods for working with digests, primarily to support Merkle trees.
 */
public class CCNDigestHelper extends DigestHelper {

	/**
	 * Current default algorithm is SHA-256. We expect it to move to SHA3 when that
	 * is standardized. We're doing our best to support variable algorithms in all but
	 * core network components (digest components in ContentNames, publisher IDs),
	 * whose digest algorithm is fixed for a given protocol version. 
	 */
	public static String DEFAULT_DIGEST_ALGORITHM = "SHA-256";
	public static int DEFAULT_DIGEST_LENGTH = 32;

	/**
	 * Make a CCNDigestHelper using the default digest algorithm (DEFAULT_DIGEST_ALGORITHM).
	 */
	public CCNDigestHelper() {
		super();
		if (!_md.getAlgorithm().equals(CCNDigestHelper.DEFAULT_DIGEST_ALGORITHM)) {
			Log.severe("Incorrect constructor in CCNDigestHelper -- picking up wrong digest algorithm!");
		}
	}

	/**
	 * Make a CCNDigestHelper that uses a specified algorithm.
	 * @param digestAlgorithm algorithm to use
	 * @throws NoSuchAlgorithmException if digestAlgorithm is unknown to any of our cryptography Providers
	 */
	public CCNDigestHelper(String digestAlgorithm) throws NoSuchAlgorithmException {
		super(digestAlgorithm);
	}

	/**
	 * Same digest preparation algorithm as ContentObject.
	 * @throws ContentEncodingException 
	 * @throws NoSuchAlgorithmException 
	 */
	public static byte [] digestLeaf(
			String digestAlgorithm,
			XMLEncodable [] toBeSigneds,
			byte [][] additionalToBeSigneds) throws ContentEncodingException, NoSuchAlgorithmException {

		if (null == toBeSigneds) {
			Log.info("Value to be signed must not be null.");
			throw new ContentEncodingException("Unexpected null content in digestLeaf!");
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
		return DigestHelper.digest(((null == digestAlgorithm) || (digestAlgorithm.length() == 0)) ?
				CCNDigestHelper.DEFAULT_DIGEST_ALGORITHM : digestAlgorithm, 
				encodedData);
	}

	@Override
	public String getDefaultDigest() { return DEFAULT_DIGEST_ALGORITHM; }

	/**
	 * Static digest helper.
	 * @param content content to digest
	 * @return digest of content using DEFAULT_DIGEST_ALGORITHM
	 */
	public static byte[] digest(byte [] content) {
		if (null == content) {
			throw new IllegalArgumentException("Content cannot be null!");
		}
		return digest(content, 0, content.length);
	}

	/**
	 * Static digest helper.
	 * @param digestAlgorithm digest algorithm to use
	 * @param content content to digest
	 * @return digest of content using specified algorithm
	 * @throws NoSuchAlgorithmException if the algorithm is unknown to any of our providers
	 */
	public static byte [] digest(String digestAlgorithm, byte [] content) throws NoSuchAlgorithmException {
		if (null == content) {
			throw new IllegalArgumentException("Content cannot be null!");
		}
		return digest(digestAlgorithm, content, 0, content.length);
	}

	/**
	 * Static digest helper.
	 * @param content content to digest
	 * @param offset offset into content at which to start digesting, in bytes
	 * @param length number of bytes of content to digest
	 * @return digest of content using DEFAULT_DIGEST_ALGORITHM
	 */
	public static byte [] digest(byte [] content, int offset, int length) {
		CCNDigestHelper dh = new CCNDigestHelper();
		dh.update(content, offset, length);
		return dh.digest();
	}

	/**
	 * Static digest helper.
	 * @param digestAlgorithm digest algorithm to use
	 * @param content content to digest
	 * @param offset offset into content at which to start digesting, in bytes
	 * @param length number of bytes of content to digest
	 * @return digest of content using specified algorithm
	 * @throws NoSuchAlgorithmException if the algorithm is unknown to any of our providers
	 */
	public static byte [] digest(String digestAlgorithm, byte [] content, int offset, int length) throws NoSuchAlgorithmException {
		CCNDigestHelper dh = new CCNDigestHelper(digestAlgorithm);
		dh.update(content, offset, length);
		return dh.digest();
	}

	/**
	 * Static digest helper; returns the digest of the concatenation of two byte arrays.
	 * If either is null, simply includes the non-null array in the digest. 
	 * @param content1 first content array to digest
	 * @param content2 second content array to digest
	 * @return digest of content using DEFAULT_DIGEST_ALGORITHM
	 */
	public static byte[] digest(byte [] content1, byte [] content2) {
		return digest(new byte [][]{content1, content2});
	}

	/**
	 * Static digest helper; returns the digest of the concatenation of two byte arrays.
	 * If either is null, simply includes the non-null array in the digest. 
	 * @param digestAlgorithm digest algorithm to use
	 * @param content1 first content array to digest
	 * @param content2 second content array to digest
	 * @return digest of concatenated content using specified algorithm
	 * @throws NoSuchAlgorithmException if the algorithm is unknown to any of our providers
	 */
	public static byte [] digest(String digestAlgorithm, byte [] content1, byte [] content2) throws NoSuchAlgorithmException {
		return digest(digestAlgorithm, new byte [][]{content1, content2});
	}

	/**
	 * Static digest helper; returns the digest of the concatenation of any number of component
	 * byte arrays. Null arrays are skipped
	 * @param contents the arrays of content to digest
	 * @return digest of concatenated content using DEFAULT_DIGEST_ALGORITHM
	 */
	public static byte [] digest(byte contents[][]) {
		CCNDigestHelper dh = new CCNDigestHelper();
		for (int i=0; i < contents.length; ++i) {
			if (null != contents[i])
				dh.update(contents[i], 0, contents[i].length);
		}
		return dh.digest();
	}	

	/**
	 * Static digest helper; returns the digest of the concatenation of any number of component
	 * byte arrays. Null arrays are skipped
	 * @param digestAlgorithm digest algorithm to use
	 * @param contents the arrays of content to digest
	 * @return digest of concatenated content using specified algorithm
	 * @throws NoSuchAlgorithmException if the algorithm is unknown to any of our providers
	 */
	public static byte [] digest(String digestAlgorithm, byte contents[][]) throws NoSuchAlgorithmException {
		CCNDigestHelper dh = new CCNDigestHelper(digestAlgorithm);
		for (int i=0; i < contents.length; ++i) {
			if (null != contents[i])
				dh.update(contents[i], 0, contents[i].length);
		}
		return dh.digest();
	}


	public static byte [] digest(String digestAlgorithm, InputStream input) throws NoSuchAlgorithmException, IOException {
		// Don't need data, so don't bother with digest input stream.
		CCNDigestHelper dh = new CCNDigestHelper(digestAlgorithm);
		byte [] buffer = new byte[1024];
		int read = 0;
		while (read >= 0) {
			read = input.read(buffer);
			if (read > 0) {
				dh.update(buffer, 0, read);
			}
		}
		return dh.digest();
	}

	public static byte [] digest(InputStream input) throws IOException {
		try {
			byte [] digest = digest(DEFAULT_DIGEST_ALGORITHM, input);
			return digest;
		} catch (java.security.NoSuchAlgorithmException ex) {
			// possible configuration problem
			Log.warning("Fatal Error: cannot find default algorithm " + DEFAULT_DIGEST_ALGORITHM);
			throw new RuntimeException("Error: can't find default algorithm " + DEFAULT_DIGEST_ALGORITHM + "!  " + ex.toString());
		}
	}

	/**
	 * Digests some data and wraps it in an encoded PKCS#1 DigestInfo, which contains a specification
	 * of the digestAlgorithm (as an Object Identifier, or OID wrapped in an AlgorithmIdentifier,
	 * which for a digest algorithm typically has null parameters), and the digest itself, all encoded in DER.
	 * @param digestAlgorithm the algorithm to use to digest (as a Java String algorithm name)
	 * @param content the content to digest
	 * @return a DER-encoded DigestInfo containing the digested content and the OID for digestAlgorithm
	 * @throws CertificateEncodingException if there is an error in encoding
	 * @throws NoSuchAlgorithmException if none of our providers recognize digestAlgorithm, or know its OID
	 */
	public static byte [] encodedDigest(String digestAlgorithm, byte [] content) throws CertificateEncodingException, NoSuchAlgorithmException {
		byte [] digest = digest(digestAlgorithm, content);
		return digestEncoder(digestAlgorithm, digest);
	}

	/**
	 * Digests some data and wraps it in an encoded PKCS#1 DigestInfo, which contains a specification
	 * of the digestAlgorithm (as an Object Identifier, or OID wrapped in an AlgorithmIdentifier,
	 * which for a digest algorithm typically has null parameters), and the digest itself, all encoded in DER.
	 * This digests content with the DEFAULT_DIGEST_ALGORITHM.
	 * @param content the content to digest
	 * @return a DER-encoded DigestInfo containing the content digested with DEFAULT_DIGEST_ALGORITHM
	 * 		and the OID for DEFAULT_DIGEST_ALGORITHM
	 * @throws CertificateEncodingException if there is an error in encoding
	 */
	public static byte [] encodedDigest(byte [] content) throws CertificateEncodingException {
		byte [] digest = digest(content);
		return digestEncoder(CCNDigestHelper.DEFAULT_DIGEST_ALGORITHM, digest);
	}
}
