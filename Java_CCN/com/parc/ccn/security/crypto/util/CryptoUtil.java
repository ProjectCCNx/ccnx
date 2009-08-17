package com.parc.ccn.security.crypto.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
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

import org.bouncycastle.asn1.ASN1InputStream;
import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.DEREncodable;
import org.bouncycastle.asn1.DERObject;
import org.bouncycastle.asn1.DEROutputStream;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.asn1.x509.X509Extensions;

import com.parc.ccn.security.crypto.CCNDigestHelper;


/**
 * @author D.K. Smetters
 *
 * Collection of crypto utility functions specific to bouncy castle.
 */
public class CryptoUtil {
    
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
	
	public static DERObject decode(byte [] decodable) throws CertificateEncodingException {
		DERObject dobj = null;
		try {
			ByteArrayInputStream bais = new ByteArrayInputStream(decodable);
			ASN1InputStream dis = new ASN1InputStream(bais);
			dobj = dis.readObject();
			dis.close();
		} catch (IOException ex) {
			throw new CertificateEncodingException("Cannot encode: " + ex.toString());
		}
		return dobj;
	}	

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
	
	public static PublicKey getPublicKey(byte [] derEncodedPublicKey) throws 
			InvalidKeySpecException, 
			CertificateEncodingException, NoSuchAlgorithmException {
		
		// Problem is, we need the algorithm identifier inside
		// the key to decode it. So in essence we need to
		// decode it twice.
		DERObject genericObject = decode(derEncodedPublicKey);
		if (!(genericObject instanceof ASN1Sequence)) {
			throw new InvalidKeySpecException("This object is not a public key!");
		}
		
		// At this point it might also be a certificate, or
		// any number of things. 
		SubjectPublicKeyInfo spki = 
			new SubjectPublicKeyInfo((ASN1Sequence)genericObject);
		
		X509EncodedKeySpec keySpec = new X509EncodedKeySpec(derEncodedPublicKey);
		String algorithmOID= 
			spki.getAlgorithmId().getObjectId().getId();
		String algorithm = OIDLookup.getCipherName(algorithmOID);
		if (algorithm == null) {
			throw new NoSuchAlgorithmException("Unknown key algorithm: " + algorithmOID);
		}
		KeyFactory fact = KeyFactory.getInstance(algorithm);
		return fact.generatePublic(keySpec);
	}
	
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
	
	public static byte [] generateCertID(Certificate cert) throws CertificateEncodingException {
		return generateCertID(CCNDigestHelper.DEFAULT_DIGEST_ALGORITHM, cert);
	}

	/**
	 * Generates a KeyID -- the digest of the DER encoding
	 * of a SubjectPublicKeyInfo, or of a raw encoding of a 
	 * symmetric key. 
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

	public static byte [] generateKeyID(Key key) {
		return generateKeyID(CCNDigestHelper.DEFAULT_DIGEST_ALGORITHM, key);
	}

	public static String getKeyIDString(String digestAlg, Key key)  {
		byte[] keyID = generateKeyID(digestAlg, key);
		BigInteger big = new BigInteger(1,keyID);
		return big.toString(16);
	}

	public static String getKeyIDString(Key key) {
		return getKeyIDString(CCNDigestHelper.DEFAULT_DIGEST_ALGORITHM,key);
	}
	
	/**
	 * Get the keyID from a CA certificate to use as the key id in an AuthorityKeyIdentifier
	 * extension for certificates issued by that CA. This should come out of the SubjectKeyIdentifier
	 * extension of the certificate if present. If that extension is missing, this function
	 * will return null, and generateKeyID can be used to generate a new key id.
	 **/
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
}
