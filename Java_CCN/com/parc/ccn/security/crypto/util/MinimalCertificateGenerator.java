/**
 * 
 */
package com.parc.ccn.security.crypto.util;

import java.io.IOException;
import java.math.BigInteger;
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
import java.util.Random;
import java.util.SimpleTimeZone;
import java.util.Vector;

import javax.security.auth.x500.X500Principal;

import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.DERObjectIdentifier;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.x509.AuthorityKeyIdentifier;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.asn1.x509.SubjectKeyIdentifier;
import org.bouncycastle.asn1.x509.X509Extensions;
import org.bouncycastle.asn1.x509.X509Name;
import org.bouncycastle.x509.X509V3CertificateGenerator;
import org.ccnx.ccn.Library;


/**
 * @author smetters
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
	 *  Can't just use null to get the default provider
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

	public static X509Certificate GenerateUserCertificate(KeyPair userKeyPair, String subjectDN, long duration) throws CertificateEncodingException, InvalidKeyException, IllegalStateException, NoSuchAlgorithmException, SignatureException {
		MinimalCertificateGenerator mg = new MinimalCertificateGenerator(subjectDN, userKeyPair.getPublic(), duration, false);
		mg.setClientAuthenticationUsage();
		return mg.sign(null, userKeyPair.getPrivate());
	}
	
	public static X509Certificate GenerateUserCertificate(KeyPair userKeyPair, String subjectDN, String emailAddress, long duration) throws CertificateEncodingException, InvalidKeyException, IllegalStateException, NoSuchAlgorithmException, SignatureException {
		MinimalCertificateGenerator mg = new MinimalCertificateGenerator(subjectDN, userKeyPair.getPublic(), duration, false);
		mg.setClientAuthenticationUsage();
		mg.setSecureEmailUsage(emailAddress);
		return mg.sign(null, userKeyPair.getPrivate());
	}

	/**
	 * Certificate issued under an existing CA.
	 * @param subjectDN
	 * @param subjectPublicKey
	 * @param issuerCertificate
	 * @param duration
	 * @param isCA
	 * @throws CertificateEncodingException
	 * @throws IOException
	 */
	public MinimalCertificateGenerator(String subjectDN, PublicKey subjectPublicKey,  
									   X509Certificate issuerCertificate, long duration, boolean isCA) throws CertificateEncodingException, IOException {

		this(subjectDN, subjectPublicKey, issuerCertificate.getSubjectX500Principal(), duration, isCA);
		AuthorityKeyIdentifier aki = 
			new AuthorityKeyIdentifier(CryptoUtil.generateKeyID(subjectPublicKey));
		_generator.addExtension(X509Extensions.AuthorityKeyIdentifier, false, aki);
	}

	/**
	 * Self-signed certificate (which may or may not be a CA).
	 * @param subjectDN
	 * @param keyPair
	 * @param duration
	 * @param isCA
	 * @throws CertificateEncodingException
	 * @throws IOException
	 */
	public MinimalCertificateGenerator(String subjectDN, PublicKey subjectPublicKey,  
									   long duration, boolean isCA) {

		this(subjectDN, subjectPublicKey, new X500Principal(subjectDN), duration, isCA);
		AuthorityKeyIdentifier aki = 
			new AuthorityKeyIdentifier(CryptoUtil.generateKeyID(subjectPublicKey));
		_generator.addExtension(X509Extensions.AuthorityKeyIdentifier, false, aki);
	}

	/**
	 * Basic common path.
	 * @param subjectDN
	 * @param subjectPublicKey
	 * @param issuerDN
	 * @param duration
	 * @param isCA
	 */
	public MinimalCertificateGenerator(String subjectDN, PublicKey subjectPublicKey, 
									   X500Principal issuerDN, long duration, boolean isCA) {
		
		_generator.setSubjectDN(new X509Name(subjectDN));
		_generator.setIssuerDN(issuerDN);
		_generator.setSerialNumber(new BigInteger(64, cachedRandom));
		_generator.setPublicKey(subjectPublicKey);
		
		Date startTime = new Date();
		Date stopTime = new Date(startTime.getTime() + duration);
		_generator.setNotBefore(startTime);
		_generator.setNotAfter(stopTime);

		// CA key usage
		final KeyUsage caKeyUsage = new KeyUsage(KeyUsage.digitalSignature | KeyUsage.nonRepudiation | KeyUsage.keyCertSign | KeyUsage.cRLSign);
		// Non-CA key usage
		final KeyUsage nonCAKeyUsage = new KeyUsage(KeyUsage.digitalSignature | KeyUsage.nonRepudiation | KeyUsage.keyEncipherment | KeyUsage.dataEncipherment | KeyUsage.keyAgreement);
		if (isCA) {
			_generator.addExtension(X509Extensions.KeyUsage, false, caKeyUsage);
		} else {
			_generator.addExtension(X509Extensions.KeyUsage, false, nonCAKeyUsage);			
		}
		
		BasicConstraints bc = new BasicConstraints(isCA);
		_generator.addExtension(X509Extensions.BasicConstraints, true, bc);

	    SubjectKeyIdentifier ski = new SubjectKeyIdentifier(CryptoUtil.generateKeyID(subjectPublicKey));
	    _generator.addExtension(X509Extensions.SubjectKeyIdentifier, false, ski);
	}
	
	/**
	 * Both adds the server authentication OID to the EKU
	 * extension, and adds the DNS name to the subject alt name
	 * extension (not marked critical). (Combines addServerAuthenticationEKU and
	 * addDNSNameSubjectAltName).
	 * @throws java.security.cert.CertificateEncodingException if the DNS name
	 * is not a DNS name
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
	 * @throws java.security.cert.CertificateEncodingException if the email address
	 * is not an email address
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
	
	public X509Certificate sign(String digestAlgorithm, PrivateKey signingKey) throws CertificateEncodingException, InvalidKeyException, IllegalStateException, NoSuchAlgorithmException, SignatureException {
		
		/**
		 * Finalize extensions.
		 */
		addExtendedKeyUsageExtension();
		addSubjectAltNamesExtension();
		
		if (null == digestAlgorithm) 
			digestAlgorithm = DEFAULT_DIGEST_ALGORITHM;
		
		String signatureAlgorithm = OIDLookup.getSignatureAlgorithm(digestAlgorithm, signingKey.getAlgorithm());
		if (null == signatureAlgorithm) {
			Library.logger().warning("Cannot find signature algorithm for digest " + digestAlgorithm + " and key " + signingKey.getAlgorithm() + ".");
		}
		_generator.setSignatureAlgorithm(signatureAlgorithm);
		
		return _generator.generate(signingKey);
	}
	
	protected void addExtendedKeyUsageExtension() {
		if (_ekus.isEmpty())
			return;
		ExtendedKeyUsage eku = new ExtendedKeyUsage(_ekus);
		_generator.addExtension(X509Extensions.ExtendedKeyUsage, false, eku);
	}

	protected void addSubjectAltNamesExtension() {
		if (_subjectAltNames.size() == 0)
			return;
		GeneralNames genNames = new GeneralNames(new DERSequence(_subjectAltNames));
		_generator.addExtension(X509Extensions.SubjectAlternativeName, false, genNames);
	}
}
