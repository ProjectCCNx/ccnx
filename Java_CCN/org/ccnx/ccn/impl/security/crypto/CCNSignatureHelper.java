package org.ccnx.ccn.impl.security.crypto;

import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SignatureException;

import javax.xml.stream.XMLStreamException;

import org.ccnx.ccn.impl.encoding.XMLEncodable;
import org.ccnx.ccn.impl.support.Library;

import com.parc.ccn.security.crypto.util.SignatureHelper;

/** 
 * Helper class for generating signatures.
 * @author smetters
 *
 */
public class CCNSignatureHelper extends SignatureHelper {
	
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
	 * Overrides to get correct default digest.
	 */

	public static byte [] sign(String digestAlgorithm,
			byte [] toBeSigned,
			PrivateKey signingKey) throws SignatureException, 
			NoSuchAlgorithmException, InvalidKeyException {
		return SignatureHelper.sign(((null == digestAlgorithm) || (digestAlgorithm.length() == 0)) ?
				CCNDigestHelper.DEFAULT_DIGEST_ALGORITHM : digestAlgorithm, toBeSigned, signingKey);
	}

	public static byte [] sign(String digestAlgorithm,
			   byte [][] toBeSigneds,
			   PrivateKey signingKey) throws SignatureException,
			   	NoSuchAlgorithmException, InvalidKeyException {
		return SignatureHelper.sign(((null == digestAlgorithm) || (digestAlgorithm.length() == 0)) ?
					CCNDigestHelper.DEFAULT_DIGEST_ALGORITHM : digestAlgorithm, toBeSigneds, signingKey);
	}

	public static boolean verify(
			byte [][] data,
			byte [] signature,
			String digestAlgorithm,
			PublicKey verificationKey) throws SignatureException, 
						NoSuchAlgorithmException, InvalidKeyException {
		return SignatureHelper.verify(data, signature,
				((null == digestAlgorithm) || (digestAlgorithm.length() == 0)) ?
						CCNDigestHelper.DEFAULT_DIGEST_ALGORITHM : digestAlgorithm, verificationKey);
	}
	
	public static boolean verify(byte [] data, byte [] signature, String digestAlgorithm,
			PublicKey verificationKey) 
	throws InvalidKeyException, SignatureException, NoSuchAlgorithmException {
		return verify(new byte[][]{data}, signature, digestAlgorithm, verificationKey);
	}
}
