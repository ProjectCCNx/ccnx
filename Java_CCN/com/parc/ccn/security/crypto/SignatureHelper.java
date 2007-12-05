package com.parc.ccn.security.crypto;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.AlgorithmParameters;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.spec.InvalidParameterSpecException;

import javax.xml.crypto.Data;
import javax.xml.crypto.OctetStreamData;
import javax.xml.crypto.dsig.CanonicalizationMethod;
import javax.xml.crypto.dsig.TransformException;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMSignContext;
import javax.xml.crypto.dsig.spec.C14NMethodParameterSpec;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.stream.XMLStreamException;

import org.bouncycastle.asn1.ASN1InputStream;
import org.bouncycastle.asn1.DEREncodable;
import org.bouncycastle.asn1.DERObjectIdentifier;
import org.bouncycastle.asn1.DERTags;
import org.bouncycastle.asn1.DERUnknownTag;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

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
			return Digest.hash(hashAlgorithm, toBeSigned);
		}
		String sigAlgName =
			getSignatureAlgorithmName(((null == hashAlgorithm) || (hashAlgorithm.length() == 0)) ?
					Digest.DEFAULT_DIGEST : hashAlgorithm,
					signingKey);
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
	 */
	public static byte [] sign(String hashAlgorithm, 
							   XMLEncodable toBeSigned,
							   PrivateKey signingKey) throws SignatureException, InvalidKeyException, NoSuchAlgorithmException {
		
		if (null == toBeSigned) {
			Library.logger().info("Value to be signed and signing key must not be null.");
			return null;
		}
		// DKS TODO: figure out canonicalization
		//byte [] canonicalizedData = canonicalize(toBeSigned, signingKey);
		byte[] canonicalizedData = null;
		try {
			canonicalizedData = toBeSigned.encode();
		} catch (XMLStreamException e) {
			Library.logger().warning("Exception encoding toBeSigned: " + e.getMessage());
			Library.warningStackTrace(e);
		}
		return sign(hashAlgorithm, canonicalizedData, signingKey);
	}

	/**
	 * This is really annoying -- we need to pass in a key
	 * to instantiate the appropriate context to canonicalize.
	 * For some reason the Java XML signature API is focusing
	 * on canonicalization of signature info objects, rather
	 * than canonicalization of the things below them to
	 * actually be signed. 
	 * DKS TODO: look into the Apache API.
	 * @param toBeSigned
	 * @param signingKey
	 * @return
	 * @throws SignatureException
	 */
	public static byte [] canonicalize(XMLEncodable toBeSigned, PrivateKey signingKey) throws SignatureException {

		byte[] encoded;
		try {
			encoded = toBeSigned.encode();
		} catch (XMLStreamException e1) {
			Library.logger().warning("This should not happen: we cannot encode " + toBeSigned.getClass().getName() + " to be signed!");
			Library.warningStackTrace(e1);
			throw new SignatureException(e1);
		}
		
		ByteArrayInputStream bais = new ByteArrayInputStream(encoded);
		// Canonicalize XML document
        XMLSignatureFactory xmlSignatureFactory =
            XMLSignatureFactory.getInstance();
        
        DocumentBuilderFactory documentBuilderFactory =
            DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = null;
		try {
			builder = documentBuilderFactory.newDocumentBuilder();
		} catch (ParserConfigurationException e1) {
			handleException("This really should not happen: parser configuration exception -- error in DOM setup.", e1);
		}
        Document document = null;
		try {
			document = builder.parse(bais);
		} catch (SAXException e1) {
			handleException("This should not happen: we cannot parse mapping information to be signed!", e1);
		} catch (IOException e1) {
			handleException("This should not happen: we cannot read mapping information to be signed!", e1);
		}
    	Node node = document.getDocumentElement();
    	
    	bais.reset();
		OctetStreamData osd = new OctetStreamData(bais);
		
        DOMSignContext cryptoContext = new DOMSignContext(signingKey, node);

        String canonicalizationAlg = CanonicalizationMethod.EXCLUSIVE_WITH_COMMENTS;
        C14NMethodParameterSpec canonParams = null;
        Data out = null;
        String canonicalizedData = null;
        try {
			CanonicalizationMethod canonicalizationMethod =
			        xmlSignatureFactory.
			        newCanonicalizationMethod(
			        		canonicalizationAlg, canonParams);
			if (null == canonicalizationMethod) {
				Library.logger().warning("Cannot find canonicalization method: " + canonicalizationAlg);
				throw new TransformException("Cannot find canonicalization method: " + canonicalizationAlg);
			}
			out = canonicalizationMethod.transform(osd, cryptoContext);
			
			canonicalizedData = out.toString();
			
			Library.logger().info("Canonicalized data: " + canonicalizedData);
			
        } catch (NoSuchAlgorithmException e) {
        	handleException("This really should not happen: configuration error -- cannot find canonicalization algorithm.", e);
		} catch (InvalidAlgorithmParameterException e) {
			handleException("This really should not happen: configuration error -- cannot find canonicalization algorithm parameters.", e);
		} catch (TransformException e) {
			handleException("This should not happen: we cannot canonicalize mapping information to be signed!", e);
		}
		// Is there a problem with locale issues?
		// DKS TODO: may need to get closer to real XML sigs.
		return canonicalizedData.getBytes();
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

	public static void handleException(String message, 
			Exception e) throws SignatureException {
		Library.logger().warning(message);
		Library.warningStackTrace(e);
		throw new SignatureException(e);
	}

}
