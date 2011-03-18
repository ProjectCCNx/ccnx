/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008, 2009, 2010, 2011 Palo Alto Research Center, Inc.
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
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.Key;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Enumeration;

import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1InputStream;
import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.DEREncodable;
import org.bouncycastle.asn1.DERObject;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.DEROutputStream;
import org.bouncycastle.asn1.DERString;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.asn1.x509.X509Extensions;
import org.ccnx.ccn.impl.security.crypto.CCNDigestHelper;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.impl.support.Tuple;



/**
 * A collection of crypto-related utility methods largely related to BouncyCastle.
 * 
 */
public class CryptoUtil {
    
	/**
	 * Helper function to DER encode content.
	 * @param encodable content to encode
	 * @return encoded content
	 * @throws CertificateEncodingException if there is a problem encoding the content
	 */
	public static byte [] encode(DEREncodable encodable) throws CertificateEncodingException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		try {
			DEROutputStream dos = new DEROutputStream(baos);
			dos.writeObject(encodable);
			dos.close();
		} catch (IOException ex) {
			throw new CertificateEncodingException("Cannot encode: " + ex.toString());
		}
		return baos.toByteArray();
	}		
	
	/**
	 * Helper function to decode DER content.
	 * @param decodable content to decode
	 * @return generic DERObject, result of decoding
	 * @throws CertificateEncodingException if there is a problem decoding the content
	 */
	public static DERObject decode(byte [] decodable) throws CertificateEncodingException {
		DERObject dobj = null;
		try {
			ByteArrayInputStream bais = new ByteArrayInputStream(decodable);
			ASN1InputStream dis = new ASN1InputStream(bais);
			dobj = dis.readObject();
			dis.close();
		} catch (IOException ex) {
			StringBuffer sb = new StringBuffer();
			sb.append("decode error - length "+decodable.length);
			for(byte b : decodable)
				sb.append(" "+Integer.toHexString((int) b));
			Log.severe(sb.toString());
			for(StackTraceElement ste : ex.getStackTrace())
				Log.severe(ste.toString());
			throw new CertificateEncodingException("Cannot encode: " + ex.toString());
		}
		return dobj;
	}	

	/**
	 * Helper function to unpack public keys from DER encoding into Java PublicKey format
	 * @param spki a decoded SubjectPublicKeyInfo containing the desired public key
	 * @return the decoded PublicKey
	 * @throws CertificateEncodingException if there is a problem decoding the content
	 * @throws NoSuchAlgorithmException if the key algorithm is unknown
	 * @throws InvalidKeySpecException if the data in the SubjectPublicKeyInfo doesn't correctly represent a key
	 */
	public static PublicKey getPublicKey(SubjectPublicKeyInfo spki) 
				throws CertificateEncodingException, NoSuchAlgorithmException, 
								InvalidKeySpecException {
		// Reencode SubjectPublicKeyInfo, let java decode it.
		byte [] encodedKey = encode(spki);

		X509EncodedKeySpec keySpec = new X509EncodedKeySpec(encodedKey);
		String algorithmOID= 
				spki.getAlgorithmId().getObjectId().getId();
		String algorithm = OIDLookup.getCipherName(algorithmOID);
		if (algorithm == null) {
			throw new CertificateEncodingException("Unknown key algorithm!");
		}
		KeyFactory fact = KeyFactory.getInstance(algorithm);
		return fact.generatePublic(keySpec);
	}
	
	/**
	 * Helper function to decode and unpack a public key from DER encoding to a Java PublicKey
	 * @param derEncodedPublicKey DER encoding of public key in standard format (SubjectPublicKeyInfo)
	 * @return the decoded PublicKey
	 * @throws CertificateEncodingException if there is a problem decoding the content
	 * @throws NoSuchAlgorithmException if the key algorithm is unknown
	 * @throws InvalidKeySpecException if the data in the SubjectPublicKeyInfo doesn't correctly represent a key
	 */
	public static PublicKey getPublicKey(byte [] derEncodedPublicKey) throws CertificateEncodingException, 
										InvalidKeySpecException {

		X509EncodedKeySpec keySpec = new X509EncodedKeySpec(derEncodedPublicKey);
		// Problem is, we need the algorithm identifier inside
		// the key to decode it. So in essence we need to
		// decode it twice.
		DERObject genericObject = decode(derEncodedPublicKey);
		if (!(genericObject instanceof ASN1Sequence)) {
			throw new InvalidKeySpecException("This object is not a public key!");
		}
		
		// At this point it might also be a certificate, or
		// any number of things. 
		SubjectPublicKeyInfo keyInfo = 
			new SubjectPublicKeyInfo((ASN1Sequence)genericObject);
		String keyTypeOID = keyInfo.getAlgorithmId().getObjectId().toString();
		String keyType = OIDLookup.getCipherName(keyTypeOID);
		if (keyType == null) {
			Log.warning("Cannot find key type corresponding to OID: " + keyTypeOID);
			throw new InvalidKeySpecException("Unknown key type OID " + keyTypeOID + " in stored key.");
		}
		
		KeyFactory keyFactory = null;
		PublicKey key = null;
		try {
			keyFactory = KeyFactory.getInstance(keyType);
			key = keyFactory.generatePublic(keySpec);
		} catch (NoSuchAlgorithmException e) {
			Log.warning("Unknown key type " + keyType + " in stored key.");
			throw new InvalidKeySpecException("Unknown key type " + keyType + " in stored key.");
		}
		return key;
	}
	
	/**
	 * Helper method to decode a certificate.
	 * @param encodedCert DER encoded X.509 certificate
	 * @return the decoded X509Certificate
	 * @throws CertificateException if there is an error in decoding
	 */
	public static X509Certificate getCertificate(byte [] encodedCert) throws CertificateException {
		// Will make default provider's certificate if it has one.
		CertificateFactory cf = CertificateFactory.getInstance("X.509");
		return (X509Certificate)cf.generateCertificate(
			new ByteArrayInputStream(encodedCert));
	}
	
	private CryptoUtil() {
		super();
	}

	/**
	 * Generates a CertID -- the digest of the DER encoding
	 * of a java.security.cert.Certificate
	 * @param digestAlg the digest algorithm to use
	 * @param cert the certificate to digest
	 * @return the CertID
	 * @throws CertificateEncodingException if there is an error in the certificate encoding
	 */
	public static byte [] generateCertID(String digestAlg, Certificate cert)  throws CertificateEncodingException {
		byte [] id = null;
		try {
			byte [] encoding = cert.getEncoded();
			id = DigestHelper.digest(digestAlg, encoding);
		} catch (java.security.NoSuchAlgorithmException ex) {
			// DKS --big configuration problem
			throw new RuntimeException("Error: can't find " + digestAlg + "!  " + ex.toString());
		}
		return id;
	}
	
	/**
	 * Generates a CertID -- the digest of the DER encoding
	 * of a java.security.cert.Certificate
	 * @param cert the certificate
	 * @return the CertID
	 */	
	public static byte [] generateCertID(Certificate cert) throws CertificateEncodingException {
		return generateCertID(CCNDigestHelper.DEFAULT_DIGEST_ALGORITHM, cert);
	}

	/**
	 * Generates a KeyID -- the digest of the DER encoding
	 * of a SubjectPublicKeyInfo, or of a raw encoding of a 
	 * symmetric key.  Note that the former is slightly uncommon;
	 * but it is more general and complete than digesting the BIT STRING
	 * component of the SubjectPublicKeyInfo itself (and no standard dictates
	 * how you must generate a key ID).
	 * @param digestAlg the digest algorithm to use
	 * @param key the key to digest
	 * @return the KeyID
	 */
	public static byte [] generateKeyID(String digestAlg, Key key)  {
		
	    byte [] id = null;
	    try {
	        byte [] encoding = key.getEncoded();
	        id = DigestHelper.digest(digestAlg, encoding);
	    } catch (java.security.NoSuchAlgorithmException ex) {
	        // DKS --big configuration problem
	        throw new RuntimeException("Error: can't find " + digestAlg + "!  " + ex.toString());
	    }
	    return id;
	}

	/**
	 * Generates a KeyID -- the digest of the DER encoding
	 * of a SubjectPublicKeyInfo, or of a raw encoding of a 
	 * symmetric key.  Note that the former is slightly uncommon;
	 * but it is more general and complete than digesting the BIT STRING
	 * component of the SubjectPublicKeyInfo itself (and no standard dictates
	 * how you must generate a key ID).
	 * @param key the key to digest
	 * @return the KeyID
	 */
	public static byte [] generateKeyID(Key key) {
		return generateKeyID(CCNDigestHelper.DEFAULT_DIGEST_ALGORITHM, key);
	}

	/**
	 * Get the keyID from a CA certificate to use as the key ID in an AuthorityKeyIdentifier
	 * extension for certificates issued by that CA. This should come out of the SubjectKeyIdentifier
	 * extension of the certificate if present. If that extension is missing, this function
	 * will return null, and generateKeyID can be used to generate a new key ID.
	 * @param issuerCert the issuer certificate to extract the key ID from
	 * @return the key ID
	 * @throws IOException
	 * @throws CertificateEncodingException
	 */
	public static byte [] getKeyIDFromCertificate(X509Certificate issuerCert) 
		throws IOException, CertificateEncodingException {
		byte [] keyIDExtensionValue = issuerCert.getExtensionValue(X509Extensions.SubjectKeyIdentifier.toString());
		if (null == keyIDExtensionValue)
			return null;
		// extension should decode to an OCTET STRING containing the key id.
		DERObject decodedValue = decode(keyIDExtensionValue);
		if (!(decodedValue instanceof ASN1OctetString)) {
			throw new CertificateEncodingException("Cannot parse SubjectKeyIdentifier extension!");
		}
		// now decode the inner octet string to get key ID
		DERObject keyID = decode(((ASN1OctetString)decodedValue).getOctets());
		if (!(keyID instanceof ASN1OctetString)) {
			throw new CertificateEncodingException("Cannot parse SubjectKeyIdentifier extension!");
		}
		return ((ASN1OctetString)keyID).getOctets();
	}

	/**
	 * Helper method to pull SubjectAlternativeNames from a certificate. BouncyCastle has
	 * one of these, but it isn't included on all platforms. We get one by default from X509Certificate
	 * but it returns us a collection of ? and we can't ever know what the ? is because we might
	 * get a different impl class on different platforms. So we have to roll our own.
	 * 
	 * We filter the general names down to ones we can handle.
	 * @param certificate
	 * @return
	 * @throws IOException 
	 * @throws CertificateEncodingException 
	 */
    public static ArrayList<Tuple<Integer, String>> getSubjectAlternativeNames(X509Certificate certificate)
    		throws IOException, CertificateEncodingException {        

    	byte[] encodedExtension = certificate.getExtensionValue(X509Extensions.SubjectAlternativeName.getId());
    	
    	ArrayList<Tuple<Integer, String>> list = new ArrayList<Tuple<Integer, String>>();
    	
    	if (null == encodedExtension) {
    		return list;
    	}
    	
		// content of extension is wrapped in a DEROctetString
		DEROctetString content = (DEROctetString)CryptoUtil.decode(encodedExtension);
		byte [] encapsulatedOctetString = content.getOctets();
		
		ASN1InputStream aIn = new ASN1InputStream(encapsulatedOctetString);
		ASN1Encodable decodedObject = (ASN1Encodable)aIn.readObject();
		ASN1Sequence sequence = (ASN1Sequence)decodedObject.getDERObject();
    	
        Integer tag;
        GeneralName generalName;
        
        Enumeration<?> it = sequence.getObjects();
        while (it.hasMoreElements()) {
        	generalName = GeneralName.getInstance(it.nextElement());
        	tag = generalName.getTagNo();
        	
        	switch (tag) {
        	case GeneralName.dNSName:
            case GeneralName.rfc822Name:
            case GeneralName.uniformResourceIdentifier:
            	list.add(new Tuple<Integer,String>(tag, ((DERString)generalName.getName()).getString()));
            default:
            	// ignore other types
        	}
        }
        return list;
    }
    
    /**
     * Get the first DNS name in the subject alternative names.
     * @throws IOException 
     * @throws CertificateEncodingException 
     */
    public static String getSubjectAlternativeNameDNSName(X509Certificate certificate) throws IOException, CertificateEncodingException {
    	return findSubjectAlternativeName(GeneralName.dNSName, certificate);
    }
    
    /**
     * Get the first email address in the subject alternative names.
     * @throws IOException 
     * @throws CertificateEncodingException 
     */
    public static String getSubjectAlternativeNameEmailAddress(X509Certificate certificate) throws IOException, CertificateEncodingException {
    	return findSubjectAlternativeName(GeneralName.rfc822Name, certificate);
    }
    
    /**
     * Get the first DNS name in the subject alternative names.
     * @throws IOException 
     * @throws URISyntaxException 
     * @throws CertificateEncodingException 
     */
    public static URI getSubjectAlternativeNameURI(X509Certificate certificate) throws IOException, URISyntaxException, CertificateEncodingException {
    	String uriString = findSubjectAlternativeName(GeneralName.uniformResourceIdentifier, certificate);
    	
    	if (null == uriString) {
    		return null;
    	}
    	return new URI(uriString);
    }

    public static String findSubjectAlternativeName(int tag, X509Certificate certificate) throws IOException, CertificateEncodingException {
    	ArrayList<Tuple<Integer,String>> alternativeNames = getSubjectAlternativeNames(certificate);
    	
    	for (Tuple<Integer,String> name : alternativeNames) {
    		if (name.first() == tag) {
    			return name.second();
    		}
    	}
    	return null;
    }
}
