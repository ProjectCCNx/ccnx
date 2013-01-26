/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008-2013 Palo Alto Research Center, Inc.
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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.AlgorithmParameters;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.spec.InvalidParameterSpecException;
import java.util.Arrays;
import java.util.logging.Level;

import javax.crypto.Mac;

import org.bouncycastle.asn1.ASN1InputStream;
import org.bouncycastle.asn1.DEREncodable;
import org.bouncycastle.asn1.DERObjectIdentifier;
import org.bouncycastle.asn1.DERTags;
import org.bouncycastle.asn1.DERUnknownTag;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.ccnx.ccn.KeyManager;
import org.ccnx.ccn.config.PlatformConfiguration;
import org.ccnx.ccn.impl.security.crypto.SignatureLocks;
import org.ccnx.ccn.impl.security.crypto.gingerbreadfix.JDKDigestSignature;
import org.ccnx.ccn.impl.support.Log;

/** 
 * Helper class for generating signatures.
 */
public class SignatureHelper {
	
	/**
	 * Signs an array of bytes with a private signing key and specified digest algorithm. 
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
		if (null == toBeSigned) {
			Log.info("sign: null content to be signed!");
			throw new SignatureException("Cannot sign null content!");
		}
		if (null == signingKey) {
			Log.info("sign: Signing key cannot be null.");
			Log.info("Temporarily generating fake signature.");
			return DigestHelper.digest(digestAlgorithm, toBeSigned);
		}
		String sigAlgName =
			getSignatureAlgorithmName(((null == digestAlgorithm) || (digestAlgorithm.length() == 0)) ?
					DigestHelper.DEFAULT_DIGEST_ALGORITHM : digestAlgorithm,
					signingKey);
		if (null != sigAlgName && sigAlgName.toUpperCase().startsWith(CryptoConstants.HMAC)) {
			Mac mac = Mac.getInstance(sigAlgName, KeyManager.PROVIDER);
			mac.init(signingKey);
			return mac.doFinal(toBeSigned);
		}
		
		if (null == sigAlgName)
			throw new InvalidKeyException("Key algorithm: " + signingKey.getAlgorithm() + "not supported");
		
		// DKS TODO if we switch to SHA256, this fails.
		Signature sig = Signature.getInstance(sigAlgName);

		// Protect against GC on platforms that don't do JNI for crypto properly
		SignatureLocks.signingLock();
		try {
			sig.initSign((PrivateKey)signingKey);
			sig.update(toBeSigned);
			return sig.sign();
		} finally {
			SignatureLocks.signingUnock();
		}
	}
	
	/**
	 * Sign concatenation of the toBeSigneds.
	 * @param digestAlgorithm the digest algorithm. if null uses DEFAULT_DIGEST_ALGORITHM
	 * @param toBeSigneds the content to be signed.
	 * @param signingKey the signing key.
	 * @return the signature.
	 * @throws SignatureException
	 * @throws NoSuchAlgorithmException
	 * @throws InvalidKeyException
	 */
	public static byte [] sign(String digestAlgorithm,
							   byte[][] toBeSigneds,
							   Key signingKey) throws SignatureException,
							   	NoSuchAlgorithmException, InvalidKeyException {
		if (null == toBeSigneds) {
			Log.info("sign: null content to be signed!");
			throw new SignatureException("Cannot sign null content!");
		}
		
		if (null == signingKey) {
			Log.info("sign: Signing key cannot be null.");
			Log.info("Temporarily generating fake signature.");
			return DigestHelper.digest(digestAlgorithm, toBeSigneds);
		}
		String sigAlgName =
			getSignatureAlgorithmName(((null == digestAlgorithm) || (digestAlgorithm.length() == 0)) ?
					DigestHelper.DEFAULT_DIGEST_ALGORITHM : digestAlgorithm,
					signingKey);
		
		if (null != sigAlgName && sigAlgName.toUpperCase().startsWith(CryptoConstants.HMAC)) {
			Mac mac = Mac.getInstance(sigAlgName, KeyManager.PROVIDER);
			mac.init(signingKey);
			for (byte[] toBeSigned : toBeSigneds)
				mac.update(toBeSigned);
			return mac.doFinal();
		}
		
		if (null == sigAlgName)
			throw new InvalidKeyException("Key algorithm: " + signingKey.getAlgorithm() + "not supported");
		
		Signature sig = Signature.getInstance(sigAlgName);

		// Protect against GC on platforms that don't do JNI for crypto properly
		SignatureLocks.signingLock();
		try {
			sig.initSign((PrivateKey)signingKey);
			for (int i=0; i < toBeSigneds.length; ++i) {
				sig.update(toBeSigneds[i]);
			}
			return sig.sign();
		} finally {
			SignatureLocks.signingUnock();
		}
	}
	
	/**
	 * Verifies the signature on the concatenation of a set of individual
	 * data items, given the verification key and digest algorithm. 
	 * @param data the data; which are expected to have been concatenated before 
	 * 	signing. Any null arrays are skipped.
	 * @param signature the signature.
	 * @param digestAlgorithm the digest algorithm. if null uses DEFAULT_DIGEST_ALGORITHM
	 * @param verificationKey the public or symmetric (secret) verification key.
	 * @return the correctness of the signature as a boolean.
	 * @throws SignatureException
	 * @throws NoSuchAlgorithmException
	 * @throws InvalidKeyException
	 */
	public static boolean verify(
			final byte[][] data,
			final byte [] signature,
			String digestAlgorithm,
			final Key verificationKey) throws SignatureException, 
						NoSuchAlgorithmException, InvalidKeyException {
		if (null == verificationKey) {
			Log.info("verify: Verifying key cannot be null.");
			throw new IllegalArgumentException("verify: Verifying key cannot be null.");
		}

		String sigAlgName =
			getSignatureAlgorithmName(((null == digestAlgorithm) || (digestAlgorithm.length() == 0)) ?
					DigestHelper.DEFAULT_DIGEST_ALGORITHM : digestAlgorithm,
					verificationKey);
		
		if (null != sigAlgName && sigAlgName.toUpperCase().startsWith(CryptoConstants.HMAC)) {
			Mac mac = Mac.getInstance(sigAlgName, KeyManager.PROVIDER);
			mac.init(verificationKey);
			for (byte[] b : data) {
				mac.update(b);
			}
			byte[] check = mac.doFinal();
			return Arrays.equals(check, signature);
		}
		
		if (PlatformConfiguration.workaroundGingerbreadBug) {
			// this clause is only used when running on Android Gingerbread. It is
			// necessary to work around a bug in the Gingerbread version of
			// Bouncycastle
			return new JDKDigestSignature.SHA256WithRSAEncryption() {
				boolean verify() throws InvalidKeyException, SignatureException {
					SignatureLocks.signingLock();
					try {
						engineInitVerify((PublicKey)verificationKey);
						if (null != data) {
							for (int i=0; i < data.length; ++i) {
								if (data[i] != null)
									engineUpdate(data[i], 0, data[i].length);
							}
						}
						return engineVerify(signature);
					} finally {
						SignatureLocks.signingUnock();
					}
				}
			}.verify();
		} else {
			
			if (null == sigAlgName)
				throw new InvalidKeyException("Key algorithm: " + verificationKey.getAlgorithm() + "not supported");
			Signature sig = Signature.getInstance(sigAlgName);

			// Protect against GC on platforms that don't do JNI for crypto properly
			SignatureLocks.signingLock();
			try {
				sig.initVerify((PublicKey)verificationKey);
				if (null != data) {
					for (int i=0; i < data.length; ++i) {
						if (data[i] != null)
							sig.update(data[i]);
					}
				}
				return sig.verify(signature);
			} finally {
				SignatureLocks.signingUnock();
			}
		}
	}
	
	/**
	 * Verify a standalone signature.
	 * @param data the data whose signature we want to verify
	 * @param signature the signature itself
	 * @param digestAlgorithm the digest algorithm used to generate the signature,
	 * 		if null uses DEFAULT_DIGEST_ALGORITHM
	 * @param verificationKey the public key to verify the signature with
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

	/**
	 * Gets an AlgorithmIdentifier incorporating a given digest and
	 * encryption algorithm, and containing any necessary parameters for
	 * the signing key.
	 * 
	 * @param hashAlgorithm the JCA standard name of the digest algorithm
	 * (e.g. "SHA1")
	 * @param signingKey the private key that will be used to compute the
	 * signature
	 * @return the algorithm identifier.
	 * @throws NoSuchAlgorithmException if the algorithm identifier can't
	 * be formed
	 * @throws InvalidParameterSpecException
	 * @throws InvalidAlgorithmParameterException
	 */
	public static AlgorithmIdentifier getSignatureAlgorithm(
			String hashAlgorithm, Key signingKey)
	throws NoSuchAlgorithmException, InvalidParameterSpecException, 
	InvalidAlgorithmParameterException
	{
		String signatureAlgorithmOID = getSignatureAlgorithmOID(
				hashAlgorithm, signingKey.getAlgorithm());
	
		if (signatureAlgorithmOID == null) {
			if (Log.isLoggable(Level.WARNING)) {
				Log.warning("Error: got no signature algorithm!");
			}
			throw new NoSuchAlgorithmException(
					"Cannot determine OID for hash algorithm "+ hashAlgorithm + " and encryption alg " + signingKey.getAlgorithm());
		}
	
		AlgorithmIdentifier thisSignatureAlgorithm = null;
		try {
	
			DEREncodable paramData = null;
			AlgorithmParameters params = OIDLookup.getParametersFromKey(signingKey);
	
			if (params == null) {
				paramData = new DERUnknownTag(DERTags.NULL, new byte [0]);
			} else {
				ByteArrayInputStream bais = new ByteArrayInputStream(params.getEncoded());
				ASN1InputStream dis = new ASN1InputStream(bais);
				paramData = dis.readObject();
			}
	
	
			// Now we need the OID and the parameters. This is not the most
			// efficient way in the world to do this, but it should work.
			thisSignatureAlgorithm =
				new AlgorithmIdentifier(new DERObjectIdentifier(signatureAlgorithmOID),
						paramData);
		} catch (IOException ex) {
			System.out.println("This should not happen: getSignatureAlgorithm -- " );
			System.out.println("    IOException thrown when decoding a key");
			ex.getMessage();
			ex.printStackTrace();
			throw new InvalidParameterSpecException(ex.getMessage());
		} 
		return thisSignatureAlgorithm;
	}

	/**
	 * Gets the JCA string name of a signature algorithm, to be used with
	 * a Signature object.
	 *
	 * @param hashAlgorithm the JCA standard name of the digest algorithm
	 * (e.g. "SHA1").
	 * @param signingKey the key that will be used to compute the
	 * signature.
	 *
	 * @returns the JCA string alias for the signature algorithm.
	 */
	public static String getSignatureAlgorithmName(
			String hashAlgorithm, Key signingKey)
	{
		return getSignatureAlgorithmName(hashAlgorithm, signingKey.getAlgorithm());
	}

	/**
	 * Gets the JCA string name of a signature algorithm, to be used with
	 * a Signature object.
	 * @param hashAlgorithm the JCA standard name of the digest algorithm
	 * (e.g. "SHA1").
	 * @param keyAlgorithm the key algorithm.
	 * @return the JCA string alias for the signature algorithm.
	 */
	public static String getSignatureAlgorithmName(
			String hashAlgorithm, String keyAlgorithm)
	{
		String signatureAlgorithm = OIDLookup.getSignatureAlgorithm(hashAlgorithm,
				keyAlgorithm);
		//Log.info("getSignatureAlgorithmName: combining " +
		//			hashAlgorithm  + " and " + keyAlgorithm +
		//			" results in: " + signatureAlgorithm);
		return signatureAlgorithm;
	}

	/**
	 * Gets the OID of a signature algorithm, to be used with
	 * a Signature object.
	 * @param hashAlgorithm the JCA standard name of the digest algorithm
	 * (e.g. "SHA1").
	 * @param keyAlgorithm the key algorithm.
	 * @return the JCA string alias for the signature algorithm.
	 */
	public static String getSignatureAlgorithmOID(
			String hashAlgorithm, String keyAlgorithm)
	{
		String signatureAlgorithm = OIDLookup.getSignatureAlgorithmOID(hashAlgorithm,
				keyAlgorithm);
	//	Log.info("getSignatureAlgorithmOID: combining " +
	//				hashAlgorithm  + " and " + keyAlgorithm +
	//				" results in: " + signatureAlgorithm);
		return signatureAlgorithm;
	}
}
