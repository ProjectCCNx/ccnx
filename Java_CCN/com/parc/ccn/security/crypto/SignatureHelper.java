package com.parc.ccn.security.crypto;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.AlgorithmParameters;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.spec.InvalidParameterSpecException;

import javax.xml.stream.XMLStreamException;

import org.bouncycastle.asn1.ASN1InputStream;
import org.bouncycastle.asn1.DEREncodable;
import org.bouncycastle.asn1.DERObjectIdentifier;
import org.bouncycastle.asn1.DERTags;
import org.bouncycastle.asn1.DERUnknownTag;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;

import com.parc.ccn.Library;
import com.parc.ccn.data.util.XMLEncodable;
import com.parc.ccn.security.crypto.certificates.BCX509CertificateGenerator;
import com.parc.ccn.security.crypto.certificates.OIDLookup;

/** 
 * Helper class for generating signatures.
 * @author smetters
 *
 */
public class SignatureHelper {
	
	public static byte [] sign(String hashAlgorithm,
							   byte [] toBeSigned,
							   PrivateKey signingKey) throws SignatureException, 
							   			NoSuchAlgorithmException, InvalidKeyException {
		if (null == signingKey) {
			Library.logger().info("sign: Signing key cannot be null.");
			Library.logger().info("Temporarily generating fake signature.");
			return DigestHelper.digest(hashAlgorithm, toBeSigned);
		}
		String sigAlgName =
			getSignatureAlgorithmName(((null == hashAlgorithm) || (hashAlgorithm.length() == 0)) ?
					DigestHelper.DEFAULT_DIGEST_ALGORITHM : hashAlgorithm,
					signingKey);
		// DKS TODO if we switch to SHA256, this fails.
		Signature sig = Signature.getInstance(sigAlgName);

		sig.initSign(signingKey);
		sig.update(toBeSigned);
		return sig.sign();
	}
	
	/**
	 * Encodes and canonicalizes an XML object before
	 * signing it.
	 * @throws SignatureException 
	 * @throws NoSuchAlgorithmException 
	 * @throws InvalidKeyException 
	 * @throws XMLStreamException 
	 */
	public static byte [] sign(String hashAlgorithm, 
							   XMLEncodable toBeSigned,
							   PrivateKey signingKey) throws SignatureException, InvalidKeyException, NoSuchAlgorithmException, XMLStreamException {
		
		if (null == toBeSigned) {
			Library.logger().info("Value to be signed must not be null.");
			return null;
		}
		return sign(hashAlgorithm, 
					toBeSigned.canonicalizeAndEncode(), 
					signingKey);
	}


	public static boolean verify(
			byte [] data,
			byte [] signature,
			PublicKey verificationKey) throws SignatureException, 
						NoSuchAlgorithmException, InvalidKeyException {
		if (null == verificationKey) {
			Library.logger().info("verify: Verifying key cannot be null.");
			throw new IllegalArgumentException("verify: Verifying key cannot be null.");
		}

		Signature sig = Signature.getInstance(verificationKey.getAlgorithm());

		sig.initVerify(verificationKey);
		sig.update(data);
		return sig.verify(signature);
	}

	/**
	 * Encodes and canonicalizes an XML object before
	 * signing it.
	 * @throws SignatureException 
	 * @throws NoSuchAlgorithmException 
	 * @throws InvalidKeyException 
	 * @throws XMLStreamException 
	 */
	public static boolean verify(
			XMLEncodable xmlData,
			byte [] signature,
			PublicKey verificationKey) throws SignatureException, InvalidKeyException, NoSuchAlgorithmException, XMLStreamException {

		if ((null == xmlData) || (null == signature)) {
			Library.logger().info("Value to be verified and signature must not be null.");
			throw new SignatureException("verify: Value to be verified and signature must not be null.");
		}
		return verify(
				xmlData.canonicalizeAndEncode(), 
				signature,
				verificationKey);
	}

	/**
	 * gets an AlgorithmIdentifier incorporating a given digest and
	 * encryption algorithm, and containing any necessary prarameters for
	 * the signing key
	 *
	 * @param hashAlgorithm the JCA standard name of the digest algorithm
	 * (e.g. "SHA1")
	 * @param signingKey the private key that will be used to compute the
	 * signature
	 *
	 * @throws NoSuchAlgorithmException if the algorithm identifier can't
	 * be formed
	 */
	public static AlgorithmIdentifier getSignatureAlgorithm(
			String hashAlgorithm, PrivateKey signingKey)
	throws NoSuchAlgorithmException, InvalidParameterSpecException, 
	InvalidAlgorithmParameterException
	{
		if (BCX509CertificateGenerator.debug) {
			System.out.println(
					"X509CertificateGenerator: getSignatureAlgorithm, hash: " +
					hashAlgorithm + " key alg: " + signingKey.getAlgorithm());
		}
		String signatureAlgorithmOID = getSignatureAlgorithmOID(
				hashAlgorithm, signingKey.getAlgorithm());
	
		if (signatureAlgorithmOID == null) {
			if (BCX509CertificateGenerator.debug) {
				System.out.println("Error: got no signature algorithm!");
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
	 * gets the JCA string name of a signature algorithm, to be used with
	 * a Signature object
	 *
	 * @param hashAlgorithm the JCA standard name of the digest algorithm
	 * (e.g. "SHA1")
	 * @param signingKey the private key that will be used to compute the
	 * signature
	 *
	 * @returns the JCA string alias for the signature algorithm
	 */
	public static String getSignatureAlgorithmName(
			String hashAlgorithm, PrivateKey signingKey)
	{
		return getSignatureAlgorithmName(hashAlgorithm, signingKey.getAlgorithm());
	}

	/**
	 * gets the JCA string name of a signature algorithm, to be used with
	 * a Signature object
	 *
	 * @param hashAlgorithm the JCA standard name of the digest algorithm
	 * (e.g. "SHA1")
	 * @param signingKey the private key that will be used to compute the
	 * signature
	 *
	 * @returns the JCA string alias for the signature algorithm
	 */
	public static String getSignatureAlgorithmName(
			String hashAlgorithm, String keyAlgorithm)
	{
		String signatureAlgorithm = OIDLookup.getSignatureAlgorithm(hashAlgorithm,
				keyAlgorithm);
		Library.logger().info("getSignatureName: combining " +
					hashAlgorithm  + " and " + keyAlgorithm +
					" results in: " + signatureAlgorithm);
		return signatureAlgorithm;
	}

	/**
	 * gets the OID of a signature algorithm, to be used with
	 * a Signature object
	 *
	 * @param hashAlgorithm the JCA standard name of the digest algorithm
	 * (e.g. "SHA1")
	 * @param signingKey the private key that will be used to compute the
	 * signature
	 *
	 * @returns the JCA string alias for the signature algorithm
	 */
	public static String getSignatureAlgorithmOID(
			String hashAlgorithm, String keyAlgorithm)
	{
		String signatureAlgorithm = OIDLookup.getSignatureAlgorithmOID(hashAlgorithm,
				keyAlgorithm);
		Library.logger().info("getSignatureAlgorithmOID: combining " +
					hashAlgorithm  + " and " + keyAlgorithm +
					" results in: " + signatureAlgorithm);
		return signatureAlgorithm;
	}
}
