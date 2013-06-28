/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008, 2009 Palo Alto Research Center, Inc.
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

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.math.BigInteger;
import java.net.URI;
import java.security.InvalidKeyException;
import java.security.KeyPair;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SignatureException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Random;
import java.util.SimpleTimeZone;
import java.util.Vector;

import javax.security.auth.x500.X500Principal;

import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.DERObjectIdentifier;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.asn1.x509.SubjectKeyIdentifier;
import org.bouncycastle.asn1.x509.X509Extensions;
import org.bouncycastle.asn1.x509.X509Name;
import org.bouncycastle.x509.X509V3CertificateGenerator;
import org.ccnx.ccn.impl.support.DataUtils;
import org.ccnx.ccn.impl.support.Log;


/**
 * Wrap BouncyCastle's X.509 certificate generator in a slightly more user-friendly way.
 */
public class MinimalCertificateGenerator {

	/**
     * A few useful OIDs that aren't in X509Extension, plus those that
     * are (because they're protected there).
     */
    public static final DERObjectIdentifier id_kp_serverAuth =  new DERObjectIdentifier("1.3.6.1.5.5.7.3.1");
    public static final DERObjectIdentifier id_kp_clientAuth = new DERObjectIdentifier("1.3.6.1.5.5.7.3.2");
    public static final DERObjectIdentifier id_kp_emailProtection = new DERObjectIdentifier("1.3.6.1.5.5.7.3.4");
    public static final DERObjectIdentifier id_kp_ipsec = new DERObjectIdentifier("1.3.6.1.5.5.8.2.2");

    /**
	 *  We can't just use null to get the default provider
	 *  and have any assurance of what it is, as a user
	 *  can change the default provider.
	 */
	public static final String SUN_PROVIDER = "SUN";
	
	/**
	 *  SHA is the official JCA name for SHA1
	 */
    protected static final String DEFAULT_DIGEST_ALGORITHM = "SHA";

	/**
	 * Cache a random number generator (non-secure, used for generating
	 * certificate serial numbers.)
	 */
	protected static Random cachedRandom = new Random();

	protected static SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyMMddHHmmss");
	protected static SimpleTimeZone TZ = new SimpleTimeZone(0, "Z");

	public static long MSEC_IN_YEAR = 1000 * 60 * 60 * 24 * 365;
	
	static {
		DATE_FORMAT.setTimeZone(TZ);
	}
	
	protected X509V3CertificateGenerator _generator = new X509V3CertificateGenerator();
	/**
	 * Cons up a list of EKUs and SubjectAltNames, then add them en masse just before signing.
	 */
	protected Vector<DERObjectIdentifier> _ekus = new Vector<DERObjectIdentifier>();
	protected ASN1EncodableVector _subjectAltNames = new ASN1EncodableVector();
	protected AuthorityKeyIdentifier _aki = null;

	/**
	 * Generates a X509 certificate for a specified user , 
	 * subject distinguished name and duration.
	 * @param userKeyPair the user key pair.
	 * @param subjectDN the distinguished name of the user.
	 * @param duration the duration of validity of the certificate.
	 * @return the X509 certificate.
	 * @throws CertificateEncodingException
	 * @throws InvalidKeyException
	 * @throws IllegalStateException
	 * @throws NoSuchAlgorithmException
	 * @throws SignatureException
	 */
	public static X509Certificate GenerateUserCertificate(PublicKey userPublicKey, String subjectDN, long duration, PrivateKey signingKey) throws CertificateEncodingException, InvalidKeyException, IllegalStateException, NoSuchAlgorithmException, SignatureException {
		MinimalCertificateGenerator mg = new MinimalCertificateGenerator(subjectDN, userPublicKey, duration, false, false);
		mg.setClientAuthenticationUsage();
		return mg.sign(null, signingKey);
	}
	
	/**
	 * Generates a X509 certificate for a specified user , 
	 * subject distinguished name and duration.
	 * @param userKeyPair the user key pair.
	 * @param subjectDN the distinguished name of the user.
	 * @param duration the duration of validity of the certificate.
	 * @return the X509 certificate.
	 * @throws CertificateEncodingException
	 * @throws InvalidKeyException
	 * @throws IllegalStateException
	 * @throws NoSuchAlgorithmException
	 * @throws SignatureException
	 * @throws IOException 
	 */
	public static X509Certificate GenerateUserCertificate(
			String subjectDN, PublicKey userPublicKey, 
			X509Certificate issuerCertificate, long duration, 
			PrivateKey signingKey) 
	         throws CertificateEncodingException, InvalidKeyException, IllegalStateException, NoSuchAlgorithmException, 
	         						SignatureException, IOException {
		MinimalCertificateGenerator mg = 
			new MinimalCertificateGenerator(subjectDN, userPublicKey, issuerCertificate, duration, false, null, false);
		mg.setClientAuthenticationUsage();
		return mg.sign(null, signingKey);
	}

	/**
	 * Helper method
	 */
	public static X509Certificate GenerateUserCertificate(KeyPair userKeyPair, String subjectDN, long duration) throws CertificateEncodingException, InvalidKeyException, IllegalStateException, NoSuchAlgorithmException, SignatureException {
		return GenerateUserCertificate(userKeyPair.getPublic(), subjectDN, duration, userKeyPair.getPrivate());
	}

	/**
	 * Generates an X509 certificate for a specified user key,
	 * subject distinguished name, email address and duration.
	 * @param userKeyPair the user key pair.
	 * @param subjectDN the distinguished name of the subject.
	 * @param emailAddress the email address.
	 * @param duration the validity duration of the certificate.
	 * @return the X509 certificate.
	 * @throws CertificateEncodingException
	 * @throws InvalidKeyException
	 * @throws IllegalStateException
	 * @throws NoSuchAlgorithmException
	 * @throws SignatureException
	 */
	public static X509Certificate GenerateUserCertificate(PublicKey userPublicKey, String subjectDN, String emailAddress, long duration, PrivateKey signingKey) throws CertificateEncodingException, InvalidKeyException, IllegalStateException, NoSuchAlgorithmException, SignatureException {
		MinimalCertificateGenerator mg = new MinimalCertificateGenerator(subjectDN, userPublicKey, duration, false, false);
		mg.setClientAuthenticationUsage();
		mg.setSecureEmailUsage(emailAddress);
		return mg.sign(null, signingKey);
	}
	
	/**
	 * Helper method
	 */
	public static X509Certificate GenerateUserCertificate(KeyPair userKeyPair, String subjectDN, String emailAddress, long duration) throws CertificateEncodingException, InvalidKeyException, IllegalStateException, NoSuchAlgorithmException, SignatureException {
		return GenerateUserCertificate(userKeyPair.getPublic(), subjectDN, emailAddress, duration, userKeyPair.getPrivate());
	}
	
	/**
	 * Certificate issued under an existing CA.
	 * @param subjectDN the distinguished name of the subject.
	 * @param subjectPublicKey the public key of the subject.
	 * @param issuerCertificate the certificate of the issuer.
	 * @param duration the validity duration of the certificate.
	 * @param isCA 
	 * @param allUsage if isCA is true, add "regular" KeyUsage flags, for dual-use cert
	 * @throws CertificateEncodingException
	 * @throws IOException
	 */
	public MinimalCertificateGenerator(String subjectDN, PublicKey subjectPublicKey,  
									   X509Certificate issuerCertificate, long duration, 
									   boolean isCA, Integer chainLength, boolean allUsage) throws CertificateEncodingException, IOException {

		this(subjectDN, subjectPublicKey, issuerCertificate.getSubjectX500Principal(), duration, isCA, chainLength, allUsage);
		// Pull the existing subject identifier out of the issuer cert. 
		byte [] subjectKeyID = issuerCertificate.getExtensionValue(X509Extensions.SubjectKeyIdentifier.toString());
		if (null == subjectKeyID) {
			subjectKeyID = CryptoUtil.generateKeyID(subjectPublicKey);

		} else {
			// content of extension is wrapped in a DEROctetString
			DEROctetString content = (DEROctetString)CryptoUtil.decode(subjectKeyID);
			byte [] encapsulatedOctetString = content.getOctets();
			DEROctetString octetStringKeyID = (DEROctetString)CryptoUtil.decode(encapsulatedOctetString);
			subjectKeyID = octetStringKeyID.getOctets();
		}
		_aki = new AuthorityKeyIdentifier(subjectKeyID);
	}

	/**
	 * Self-signed certificate (which may or may not be a CA).
	 * @param subjectDN the distinguished name of the subject.
	 * @param subjectPublicKey the public key of the subject.
	 * @param duration the validity duration of the certificate.
	 * @param isCA add basic constraints
	 * @param allUsage if isCA is true, add "regular" KeyUsage flags, for dual-use cert
	 */
	public MinimalCertificateGenerator(String subjectDN, PublicKey subjectPublicKey,  
									   long duration, boolean isCA, boolean allUsage) {

		this(subjectDN, subjectPublicKey, new X500Principal(subjectDN), duration, isCA, null, allUsage);
		// This needs to match what we are using for a subject key identifier.
		_aki = new AuthorityKeyIdentifier(CryptoUtil.generateKeyID(subjectPublicKey));
	}

	/**
	 * Basic common path.
	 * @param subjectDN the distinguished name of the subject.
	 * @param subjectPublicKey the public key of the subject.
	 * @param issuerDN the distinguished name of the issuer.
	 * @param duration the validity duration of the certificate.
	 * @param isCA
	 * @param allUsage if isCA is true, add "regular" KeyUsage flags, for dual-use cert
	 */
	public MinimalCertificateGenerator(String subjectDN, PublicKey subjectPublicKey, 
									   X500Principal issuerDN, long duration, boolean isCA, 
									   Integer chainLength,
									   boolean allUsage) {
		
		_generator.setSubjectDN(new X509Name(subjectDN));
		_generator.setIssuerDN(issuerDN);
		_generator.setSerialNumber(new BigInteger(64, cachedRandom));
		_generator.setPublicKey(subjectPublicKey);
		
		Date startTime = new Date();
		Date stopTime = new Date(startTime.getTime() + duration);
		_generator.setNotBefore(startTime);
		_generator.setNotAfter(stopTime);

		// CA key usage
		final int caKeyUsage = KeyUsage.digitalSignature | KeyUsage.nonRepudiation | KeyUsage.keyCertSign | KeyUsage.cRLSign;
		// Non-CA key usage
		final int nonCAKeyUsage = KeyUsage.digitalSignature | KeyUsage.nonRepudiation | KeyUsage.keyEncipherment | KeyUsage.dataEncipherment | KeyUsage.keyAgreement;
		
		int ourUsage;
		if (isCA) {
			if (!allUsage) {
				ourUsage = caKeyUsage;
			} else {
				ourUsage = caKeyUsage | nonCAKeyUsage;
			}
		} else {
			ourUsage = nonCAKeyUsage;
		}
		_generator.addExtension(X509Extensions.KeyUsage, false, new KeyUsage(ourUsage));			
		
		BasicConstraints bc = 
			((isCA == false) || (null == chainLength)) ? new BasicConstraints(isCA) :
														 new BasicConstraints(chainLength.intValue());
		_generator.addExtension(X509Extensions.BasicConstraints, true, bc);

	    SubjectKeyIdentifier ski = new SubjectKeyIdentifier(CryptoUtil.generateKeyID(subjectPublicKey));
	    _generator.addExtension(X509Extensions.SubjectKeyIdentifier, false, ski);
	}
	
	/**
	 * Both adds the server authentication OID to the EKU
	 * extension, and adds the DNS name to the subject alt name
	 * extension (not marked critical). (Combines addServerAuthenticationEKU and
	 * addDNSNameSubjectAltName).
	 * @param serverDNSName the DNS name of the server.
	 */
	public void setServerAuthenticationUsage(String serverDNSName) {
		GeneralName name = new GeneralName(GeneralName.dNSName, serverDNSName);
		_subjectAltNames.add(name);
		_ekus.add(id_kp_serverAuth);
	}


	/**
	 * Adds client authentication as a usage for this
	 * certificate.
	 */
	public void setClientAuthenticationUsage() {
		_ekus.add(id_kp_clientAuth);
	}

	/**
	 * Both adds the secure email OID to the EKU
	 * extension, and adds the email address to the subject alt name
	 * extension (not marked critical). (Combines addSecureEmailEKU and addEmailSubjectAltName).
	 * @param subjectEmailAddress the email address of the subject.
	 */
	public void setSecureEmailUsage(String subjectEmailAddress) {
		GeneralName name = new GeneralName(GeneralName.rfc822Name, subjectEmailAddress);
		_subjectAltNames.add(name);
		_ekus.add(id_kp_emailProtection);
	}

	/**
	 * Adds ip address to subjectAltName and IPSec usage to EKU
	 * @param ipAddress string form of the IP address. Assumed to be in either
	 * IPv4 form, "n.n.n.n", with 0<=n<256, orIPv6 form, 
	 * "n.n.n.n.n.n.n.n", where the n's are the HEXADECIMAL form of the
	 * 16-bit address components.
	 **/
	public void setIPSecUsage(String ipAddress) {
		GeneralName name = new GeneralName(GeneralName.iPAddress, ipAddress);
		_subjectAltNames.add(name);
		_ekus.add(id_kp_ipsec);
	}
	
	public void setExtendedKeyUsage(String usageOID) {
		DERObjectIdentifier oid = new DERObjectIdentifier(usageOID);
		_ekus.add(oid);
	}
	
	public void addSubjectAltName(URI subjectURI) {
		GeneralName name = new GeneralName(GeneralName.uniformResourceIdentifier, subjectURI.toString());
		_subjectAltNames.add(name);
	}
	
	/**
	 * Add additional AuthorityKeyIdentifier information. We've already set
	 * key ID.
	 */
	public void addAuthorityName(URI authorityName) {
		if (null == _aki) {
			_aki = new AuthorityKeyIdentifier(null, null, null);
		}
		_aki.setIssuerName(authorityName);
	}

	/**
	 * Generate an X509 certificate, based on the current issuer and subject using the default provider.
	 * Use the old form of the BC certificate generation call for compatibility with older versions
	 * of BouncyCastle; suppress the deprecation warning on newer platforms.
	 * @param digestAlgorithm the digest algorithm.
	 * @param signingKey the signing key.
	 * @return the X509 certificate.
	 * @throws CertificateEncodingException
	 * @throws InvalidKeyException
	 * @throws IllegalStateException
	 * @throws NoSuchAlgorithmException
	 * @throws SignatureException
	 */
	@SuppressWarnings("deprecation")
	public X509Certificate sign(String digestAlgorithm, PrivateKey signingKey) throws CertificateEncodingException, InvalidKeyException, IllegalStateException, NoSuchAlgorithmException, SignatureException {
		/**
		 * Finalize extensions.
		 */
		addExtendedKeyUsageExtension();
		addSubjectAltNamesExtension();
		addAuthorityKeyIdentifierExtension();
		
		if (null == digestAlgorithm) 
			digestAlgorithm = DEFAULT_DIGEST_ALGORITHM;
		
		String signatureAlgorithm = OIDLookup.getSignatureAlgorithm(digestAlgorithm, signingKey.getAlgorithm());
		if (null == signatureAlgorithm) {
			Log.warning("Cannot find signature algorithm for digest " + digestAlgorithm + " and key " + signingKey.getAlgorithm() + ".");
		}
		_generator.setSignatureAlgorithm(signatureAlgorithm);
		
		// Move back to the older form to allow compatibility with BC 1.34
		//return _generator.generate(signingKey);
		return _generator.generateX509Certificate(signingKey);
	}
	
	/**
	 * Adds an extended key usage extension to the certificate.
	 */
	protected void addExtendedKeyUsageExtension() {
		if (_ekus.isEmpty())
			return;
		ExtendedKeyUsage eku = new ExtendedKeyUsage(_ekus);
		_generator.addExtension(X509Extensions.ExtendedKeyUsage, false, eku);
	}

	/**
	 * Adds an authority key identifier extension to the certificate.
	 */
	protected void addAuthorityKeyIdentifierExtension() {
		if (null == _aki)
			return;
		_generator.addExtension(X509Extensions.AuthorityKeyIdentifier, false, _aki);
	}

	/**
	 * Adds an subject alternative name extension to the certificate.
	 */
	protected void addSubjectAltNamesExtension() {
		if (_subjectAltNames.size() == 0)
			return;
		GeneralNames genNames = new GeneralNames(new DERSequence(_subjectAltNames));
		_generator.addExtension(X509Extensions.SubjectAlternativeName, false, genNames);
	}
	
	/**
	 * Open up the ability to add additional extensions that aren't 
	 * EKU or SubjectAltName (which we manage).
	 */
	 public void addExtension(
		        String oid,
		        boolean critical,
		        byte [] value) {
		 if (null == oid)
			 throw new IllegalArgumentException("OID cannot be null!");
		 
		 DERObjectIdentifier derOID = new DERObjectIdentifier(oid);
		 if ((derOID.equals(X509Extensions.ExtendedKeyUsage)) || (derOID.equals(X509Extensions.SubjectAlternativeName)) ||
				 (derOID.equals(X509Extensions.AuthorityKeyIdentifier))) {
			 throw new IllegalArgumentException("Cannot use addExtension to set ExtendedKeyUsage or SubjectAlternativeName or AuthorityKeyIdentifier!");
		 }
		 _generator.addExtension(derOID, critical, value);
	 }
	 
	 /**
	  * Writes file of certificates in the form expected by SSL_CTX_load_verify_locations()
	  * and (if in the right order) SSL_CTX_use_certificate_chain_file
	  * Quoting from the OpenSSL documentation:
	  * If CAfile is not NULL, it points to a file of CA certificates in PEM format. 
	  * The file can contain several CA certificates identified by
	  * -----BEGIN CERTIFICATE-----
	  * ... (CA certificate in base64 encoding) ...
	  * -----END CERTIFICATE-----
	  * sequences. Before, between, and after the certificates text is allowed 
	  * which can be used e.g. for descriptions of the certificates.
	  * 
	  * From documentation: SSL_CTX_use_certificate_chain_file loads a 
	  * certificate chain from file into ctx. The certificates must be in 
	  * PEM format and must be sorted starting with the subject's 
	  * certificate (actual client or server certificate), followed by 
	  * intermediate CA certificates if applicable, and ending at the 
	  * highest level (root) CA. There is no corresponding function 
	  * working on a single SSL object.
	  * This method assumes the caller already has ordered the chain.
	  * @param userDirectory
	  * @param userCertificate if not null, the first cert to write in the chain
	  * @param chain a set of certificates to write after any user certificate. Written
	  *   in order given, can be used to write an ordered chain or a set of roots where
	  *   order doesn't matter.
	  * @param chainOffset the index into chain to start writing
	  * @param chainCount the number of certs to output.
	  * @throws CertificateEncodingException 
	  * @throws FileNotFoundException 
	  */
	 public static void writeCertificateChain(File targetFile,
			 X509Certificate userCertificate,
			 List<X509Certificate> chain, int chainOffset, int chainCount) throws CertificateEncodingException, FileNotFoundException {
		targetFile.getParentFile().mkdirs();
		PrintWriter writer = new PrintWriter(targetFile.getAbsolutePath());
		if (null != userCertificate) {
			writePEMCertificate(writer, userCertificate);
		}
		for (int i=chainOffset; i < chainOffset + chainCount; ++i) {
			writePEMCertificate(writer, chain.get(i));
		}
		writer.close();
	 }

	 
	 public static final String BEGIN_CERTIFICATE = "-----BEGIN CERTIFICATE-----";
	 public static final String END_CERTIFICATE = "-----END CERTIFICATE-----";
	 public static final int CERTIFICATE_WRAP_LENGTH = 40; // TODO what should this be?
	 
	 public static void writePEMCertificate(PrintWriter writer, X509Certificate certificate) throws CertificateEncodingException {
		 writer.println(BEGIN_CERTIFICATE);
		 // TODO print or println?
		 writer.println(DataUtils.base64Encode(certificate.getEncoded(), CERTIFICATE_WRAP_LENGTH));
		 writer.println(END_CERTIFICATE);
		 writer.println();
	 }
}

