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
	
	public static byte [] sign(String digestAlgorithm,
							   byte [] toBeSigned,
							   PrivateKey signingKey) throws SignatureException, 
							   			NoSuchAlgorithmException, InvalidKeyException {
		if (null == toBeSigned) {
			Library.logger().info("sign: null content to be signed!");
			throw new SignatureException("Cannot sign null content!");
		}
		if (null == signingKey) {
			Library.logger().info("sign: Signing key cannot be null.");
			Library.logger().info("Temporarily generating fake signature.");
			return DigestHelper.digest(digestAlgorithm, toBeSigned);
		}
		String sigAlgName =
			getSignatureAlgorithmName(((null == digestAlgorithm) || (digestAlgorithm.length() == 0)) ?
					DigestHelper.DEFAULT_DIGEST_ALGORITHM : digestAlgorithm,
					signingKey);
		// DKS TODO if we switch to SHA256, this fails.
		Signature sig = Signature.getInstance(sigAlgName);

		sig.initSign(signingKey);
		sig.update(toBeSigned);
		return sig.sign();
	}
	
	/**
	 * Sign concatenation of the toBeSigneds.
	 * @param digestAlgorithm
	 * @param toBeSigneds
	 * @param signingKey
	 * @return
	 * @throws SignatureException
	 * @throws NoSuchAlgorithmException
	 * @throws InvalidKeyException
	 */
	public static byte [] sign(String digestAlgorithm,
							   byte [][] toBeSigneds,
							   PrivateKey signingKey) throws SignatureException,
							   	NoSuchAlgorithmException, InvalidKeyException {
		if (null == toBeSigneds) {
			Library.logger().info("sign: null content to be signed!");
			throw new SignatureException("Cannot sign null content!");
		}
		
		if (null == signingKey) {
			Library.logger().info("sign: Signing key cannot be null.");
			Library.logger().info("Temporarily generating fake signature.");
			return DigestHelper.digest(digestAlgorithm, toBeSigneds);
		}
		String sigAlgName =
			getSignatureAlgorithmName(((null == digestAlgorithm) || (digestAlgorithm.length() == 0)) ?
					DigestHelper.DEFAULT_DIGEST_ALGORITHM : digestAlgorithm,
					signingKey);

		Signature sig = Signature.getInstance(sigAlgName);

		sig.initSign(signingKey);
		for (int i=0; i < toBeSigneds.length; ++i) {
			sig.update(toBeSigneds[i]);
		}
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
	public static byte [] sign(String digestAlgorithm, 
							   XMLEncodable toBeSigned,
							   PrivateKey signingKey) throws SignatureException, InvalidKeyException, NoSuchAlgorithmException, XMLStreamException {
		
		if (null == toBeSigned) {
			Library.logger().info("Value to be signed must not be null.");
			throw new SignatureException("Cannot sign null content!");
		}
		return sign(digestAlgorithm, 
					toBeSigned.encode(), 
					signingKey);
	}
	
	public static byte [] sign(String digestAlgorithm,
							   XMLEncodable [] toBeSigneds,
							   byte [][] additionalToBeSigneds,
							   PrivateKey signingKey) throws SignatureException, InvalidKeyException, NoSuchAlgorithmException, XMLStreamException {
		
		if (null == toBeSigneds) {
			Library.logger().info("Value to be signed must not be null.");
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

	public static boolean verify(
			byte [][] data,
			byte [] signature,
			String digestAlgorithm,
			PublicKey verificationKey) throws SignatureException, 
						NoSuchAlgorithmException, InvalidKeyException {
		if (null == verificationKey) {
			Library.logger().info("verify: Verifying key cannot be null.");
			throw new IllegalArgumentException("verify: Verifying key cannot be null.");
		}

		String sigAlgName =
			getSignatureAlgorithmName(((null == digestAlgorithm) || (digestAlgorithm.length() == 0)) ?
					DigestHelper.DEFAULT_DIGEST_ALGORITHM : digestAlgorithm,
					verificationKey);
		
		Signature sig = Signature.getInstance(sigAlgName);

		sig.initVerify(verificationKey);
		if (null != data) {
			for (int i=0; i < data.length; ++i) {
				sig.update(data[i]);
			}
		}
		return sig.verify(signature);
	}
	
	public static boolean verify(byte [] data, byte [] signature, String digestAlgorithm,
										PublicKey verificationKey) 
					throws InvalidKeyException, SignatureException, NoSuchAlgorithmException {
		return verify(new byte[][]{data}, signature, digestAlgorithm, verificationKey);
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
			String digestAlgorithm,
			PublicKey verificationKey) throws SignatureException, InvalidKeyException, NoSuchAlgorithmException, XMLStreamException {

		if ((null == xmlData) || (null == signature)) {
			Library.logger().info("Value to be verified and signature must not be null.");
			throw new SignatureException("verify: Value to be verified and signature must not be null.");
		}
		return verify(
				xmlData.encode(), 
				signature,
				digestAlgorithm,
				verificationKey);
	}

	public static boolean verify(XMLEncodable [] xmlData,
								 byte [][] binaryData,
								 byte [] signature,
								 String digestAlgorithm,
								 PublicKey verificationKey) throws SignatureException, InvalidKeyException, NoSuchAlgorithmException, XMLStreamException {

		if ((null == xmlData) || (null == signature)) {
			Library.logger().info("Value to be verified and signature must not be null.");
			throw new SignatureException("verify: Value to be verified and signature must not be null.");
		}
		byte [][] encodedData = new byte [xmlData.length + ((null != binaryData) ? binaryData.length : 0)][];
		for (int i=0; i < xmlData.length; ++i) {
			encodedData[i] = xmlData[i].encode();
		} // DKS TODO -- switch to ostreams to handle binary end/begin tags
		if (null != binaryData) {
			for (int i=0,j=xmlData.length; j < encodedData.length; ++i,++j) {
				encodedData[j] = binaryData[i];
			}
		}
		return verify(
				encodedData, 
				signature,
				digestAlgorithm,
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

	public static String getSignatureAlgorithmName(
			String hashAlgorithm, PublicKey publicKey)
	{
		return getSignatureAlgorithmName(hashAlgorithm, publicKey.getAlgorithm());
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
		//Library.logger().info("getSignatureName: combining " +
		//			hashAlgorithm  + " and " + keyAlgorithm +
		//			" results in: " + signatureAlgorithm);
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
	//	Library.logger().info("getSignatureAlgorithmOID: combining " +
	//				hashAlgorithm  + " and " + keyAlgorithm +
	//				" results in: " + signatureAlgorithm);
		return signatureAlgorithm;
	}
}
