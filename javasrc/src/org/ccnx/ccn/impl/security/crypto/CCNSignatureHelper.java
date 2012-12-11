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

import java.security.InvalidKeyException;
import java.security.Key;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.SignatureException;

import org.ccnx.ccn.impl.encoding.XMLEncodable;
import org.ccnx.ccn.impl.security.crypto.util.SignatureHelper;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.ContentEncodingException;


/** 
 * Helper class for generating signatures, supporting CCN-specific operations.
 */
public class CCNSignatureHelper extends SignatureHelper {
	
	/**
	 * Helper method that encodes and then signs an XMLEncodable object.
	 * @param digestAlgorithm the digest algorithm to use for the signature
	 * @param toBeSigned the object to encode and sign
	 * @param signingKey the private key to sign with
	 * @return the signature
	 * @throws SignatureException if the content is null, or there is an error generating the signature
	 * @throws NoSuchAlgorithmException if the digestAlgorithm is not recognized
	 * @throws InvalidKeyException if the signingKey is not valid
	 * @throws ContentEncodingException  if the object cannot be encoded
	 */
	public static byte [] sign(String digestAlgorithm, 
							   XMLEncodable toBeSigned,
							   PrivateKey signingKey) 
			throws SignatureException, InvalidKeyException, NoSuchAlgorithmException, ContentEncodingException {
		
		if (null == toBeSigned) {
			Log.info("Value to be signed must not be null.");
			throw new SignatureException("Cannot sign null content!");
		}
		return sign(digestAlgorithm, 
					toBeSigned.encode(), 
					signingKey);
	}
	
	/**
	 * Helper method that encodes, concatenates and then signs a set of
	 * XMLEncodable objects and auxiliary data
	 * @param digestAlgorithm the digest algorithm to use for the signature
	 * @param toBeSigneds the objects to encode, concatenate and sign
	 * @param additionalToBeSigneds additional data to be concatenated with the
	 * 	encoded toBeSigneds prior to signing
	 * @param signingKey the private key to sign with
	 * @return the signature
	 * @throws SignatureException if the content is null, or there is an error generating the signature
	 * @throws NoSuchAlgorithmException if the digestAlgorithm is not recognized
	 * @throws InvalidKeyException if the signingKey is not valid
	 * @throws ContentEncodingException  if the object cannot be encoded
	 */
	public static byte [] sign(String digestAlgorithm,
							   XMLEncodable [] toBeSigneds,
							   byte additionalToBeSigneds[][],
							   PrivateKey signingKey) 
			throws SignatureException, InvalidKeyException, NoSuchAlgorithmException, ContentEncodingException {
		
		if (null == toBeSigneds) {
			Log.info("Value to be signed must not be null.");
			throw new SignatureException("Cannot sign null content!");
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
		return sign(digestAlgorithm, 
					encodedData,
					signingKey);
	}

	/**
	 * Helper method that encodes and then verifies a signature on an XMLEncodable object.
	 * @param xmlData the object to encode and verify
	 * @param signature the signature
	 * @param digestAlgorithm the digest algorithm used for the signature
	 * @param verificationKey the public key to verify with
	 * @return true if valid, false otherwise
	 * @throws SignatureException if the content is null, or there is an error generating the signature
	 * @throws NoSuchAlgorithmException if the digestAlgorithm is not recognized
	 * @throws InvalidKeyException if the signingKey is not valid
	 * @throws ContentEncodingException  if the object cannot be encoded
	 */
	public static boolean verify(
			XMLEncodable xmlData,
			byte [] signature,
			String digestAlgorithm,
			Key verificationKey) 
			throws SignatureException, InvalidKeyException, NoSuchAlgorithmException, ContentEncodingException {

		if ((null == xmlData) || (null == signature)) {
			Log.info("Value to be verified and signature must not be null.");
			throw new SignatureException("verify: Value to be verified and signature must not be null.");
		}
		return verify(
				xmlData.encode(), 
				signature,
				digestAlgorithm,
				verificationKey);
	}

	/**
	 * Helper method that encodes, concatenates and then verifies a signature on a
	 * set of XMLEncodable objects and auxiliary data.
	 * @param xmlData the objects to encode and verify
	 * @param auxiliaryData
	 * @param signature the signature
	 * @param digestAlgorithm the digest algorithm used for the signature
	 * @param verificationKey the public key to verify with
	 * @return true if valid, false otherwise
	 * @throws SignatureException if the content is null, or there is an error generating the signature
	 * @throws NoSuchAlgorithmException if the digestAlgorithm is not recognized
	 * @throws InvalidKeyException if the signingKey is not valid
	 * @throws ContentEncodingException  if the object cannot be encoded
	 */
	public static boolean verify(XMLEncodable [] xmlData,
								 byte auxiliaryData[][],
								 byte [] signature,
								 String digestAlgorithm,
								 Key verificationKey) 
			throws SignatureException, InvalidKeyException, NoSuchAlgorithmException, ContentEncodingException {

		if ((null == xmlData) || (null == signature)) {
			Log.info("Value to be verified and signature must not be null.");
			throw new SignatureException("verify: Value to be verified and signature must not be null.");
		}
		byte [][] encodedData = new byte [xmlData.length + ((null != auxiliaryData) ? auxiliaryData.length : 0)][];
		for (int i=0; i < xmlData.length; ++i) {
			encodedData[i] = xmlData[i].encode();
		} // DKS TODO -- switch to ostreams to handle binary end/begin tags
		if (null != auxiliaryData) {
			for (int i=0,j=xmlData.length; j < encodedData.length; ++i,++j) {
				encodedData[j] = auxiliaryData[i];
			}
		}
		return verify(
				encodedData, 
				signature,
				digestAlgorithm,
				verificationKey);
	}
	
	/**
	 * Signs an array of bytes with a private signing key and specified digest algorithm. 
	 * Overrides SignatureHelper to get correct default digest.
	 * @param digestAlgorithm the digest algorithm. if null uses DEFAULT_DIGEST_ALGORITHM
	 * @param toBeSigned the array of bytes to be signed.
	 * @param signingKey the signing key.
	 * @return the signature.
	 * @throws SignatureException
	 * @throws NoSuchAlgorithmException
	 * @throws InvalidKeyException
	 */
	public static byte [] sign(String digestAlgorithm,
			byte [] toBeSigned,
			Key signingKey) throws SignatureException, 
			NoSuchAlgorithmException, InvalidKeyException {
		return SignatureHelper.sign(((null == digestAlgorithm) || (digestAlgorithm.length() == 0)) ?
				CCNDigestHelper.DEFAULT_DIGEST_ALGORITHM : digestAlgorithm, toBeSigned, signingKey);
	}

	/**
	 * Sign concatenation of the toBeSigneds.
	 * Overrides SignatureHelper to get correct default digest.
	 * @param digestAlgorithm the digest algorithm. if null uses DEFAULT_DIGEST_ALGORITHM
	 * @param toBeSigneds the content to be signed.
	 * @param signingKey the signing key.
	 * @return the signature.
	 * @throws SignatureException
	 * @throws NoSuchAlgorithmException
	 * @throws InvalidKeyException
	 */
	public static byte [] sign(String digestAlgorithm,
			   byte toBeSigneds[][],
			   PrivateKey signingKey) throws SignatureException,
			   	NoSuchAlgorithmException, InvalidKeyException {
		return SignatureHelper.sign(((null == digestAlgorithm) || (digestAlgorithm.length() == 0)) ?
					CCNDigestHelper.DEFAULT_DIGEST_ALGORITHM : digestAlgorithm, toBeSigneds, signingKey);
	}

	/**
	 * Verifies the signature on the concatenation of a set of individual
	 * data items, given the verification key and digest algorithm. 
	 * Overrides SignatureHelper to get correct default digest.
	 * @param data the data; which are expected to have been concatenated before 
	 * 	signing. Any null arrays are skipped.
	 * @param signature the signature.
	 * @param digestAlgorithm the digest algorithm. if null uses DEFAULT_DIGEST_ALGORITHM
	 * @param verificationKey the public verification key.
	 * @return the correctness of the signature as a boolean.
	 * @throws SignatureException
	 * @throws NoSuchAlgorithmException
	 * @throws InvalidKeyException
	 */
	public static boolean verify(
			byte data[][],
			byte [] signature,
			String digestAlgorithm,
			Key verificationKey) throws SignatureException, 
						NoSuchAlgorithmException, InvalidKeyException {
		return SignatureHelper.verify(data, signature,
				((null == digestAlgorithm) || (digestAlgorithm.length() == 0)) ?
						CCNDigestHelper.DEFAULT_DIGEST_ALGORITHM : digestAlgorithm, verificationKey);
	}
	
	/**
	 * Verify a standalone signature.
	 * Overrides SignatureHelper to get correct default digest.
	 * @param data the data whose signature we want to verify
	 * @param signature the signature itself
	 * @param digestAlgorithm the digest algorithm used to generate the signature. 
	 * 		if null uses DEFAULT_DIGEST_ALGORITHM
	 * @param verificationKey the key to verify the signature with
	 * @return true if signature valid, false otherwise
	 * @throws InvalidKeyException
	 * @throws SignatureException
	 * @throws NoSuchAlgorithmException
	 */
	public static boolean verify(byte [] data, byte [] signature, String digestAlgorithm,
			Key verificationKey) 
	throws InvalidKeyException, SignatureException, NoSuchAlgorithmException {
		return verify(new byte[][]{data}, signature, digestAlgorithm, verificationKey);
	}
}
