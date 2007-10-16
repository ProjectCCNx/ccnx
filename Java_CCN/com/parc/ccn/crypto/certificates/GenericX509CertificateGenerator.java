package com.parc.ccn.crypto.certificates;

// Note: This code uses both codec.x509.X509Certificate and java.security.cert.X509Certificate.
// Force the import to be the java version.
import java.math.BigInteger;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidParameterSpecException;
import java.util.Date;

/**
 * Title:        CA
 * Description:  Basic Certification Authority classes
 * Copyright:    Copyright (c) 2007
 * Company:      PARC
 * @author Paul Stewart
 * @version 1.0
 */

/**
 * This is an interface used as a generic in order smooth-over the
 * transition from the old-and-busted Codec-based implementation over
 * to the new BouncyCastle hotness.  As interfaces go, this should be
 * straightforward, and anyone who doesn't care which specific 
 * implementation is in use should be able to refer to this class
 * instead of the DianaX509CertificateGenerator or 
 * BCX509CertificateGenerator.
 */

public abstract class GenericX509CertificateGenerator {
    /**
     * A few useful OIDs that aren't in X509Extension, plus those that
     * are (because they're protected there).
     */
    public static final String id_ce_keyUsage = "2.5.29.15";
    public static final String id_ce_extendedKeyUsage = "2.5.29.37";
    public static final String id_ce_authorityKeyIdentifier = "2.5.29.35";
    public static final String id_ce_subjectKeyIdentifier = "2.5.29.14";
    public static final String id_ce_subjectAltName = "2.5.29.17";
    public static final String id_ce_basicConstraints = "2.5.29.19";
    public static final String id_ce_crlDistributionPoints = "2.5.29.31";
    public static final String id_kp_serverAuth = "1.3.6.1.5.5.7.3.1";
    public static final String id_kp_clientAuth = "1.3.6.1.5.5.7.3.2";
    public static final String id_kp_timeStamping = "1.3.6.1.5.5.7.3.8";
    public static final String id_kp_codeSigning = "1.3.6.1.5.5.7.3.3";
    public static final String id_kp_emailProtection = "1.3.6.1.5.5.7.3.4";
    public static final String id_kp_ipsec = "1.3.6.1.5.5.8.2.2";
    public static final String id_kp_registrationAgent = "1.2.840.113550.11.2.2.1";
    
    public static final int NUM_KEY_USAGE_BITS = 9;
    public static final int DigitalSignatureKeyUsageBit 	= 0;
    public static final int NonRepudiationKeyUsageBit 	= 1;
    public static final int KeyEnciphermentKeyUsageBit 	= 2;
    public static final int DataEnciphermentKeyUsageBit = 3;
    public static final int KeyAgreementKeyUsageBit 		= 4;
    public static final int KeyCertSignKeyUsageBit 			= 5;
    public static final int CRLSignKeyUsageBit 				= 6;
    public static final int EncipherOnlyKeyUsageBit 		= 7;
    public static final int DecipherOnlyKeyUsageBit 		= 8;
    
    public static final Integer RFC822AlternativeNameType = new Integer(1);
    public static final Integer DNSNAMEAlternativeNameType = new Integer(2);
    public static final Integer IPADDRESSAlternativeNameType = new Integer(3);

    // SHA is the official JCA name for SHA1
    protected static final String DEFAULT_HASH = "SHA";
    protected static final String KEY_ID_DIGEST_ALG = "SHA-1";


    /**
     * digests a byte array.
     */
    public static byte [] Digest(String digestAlg, byte [] data)
            throws java.security.NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance(digestAlg);
        return md.digest(data);
    }

    /**
     * Generates a KeyID -- the SHA-1 digest of the DER encoding
     * of a SubjectPublicKeyInfo. Note that this should not be used to
     * generate the key identifier in an AuthorityKeyIdentifier extension,
     * unless the issuer has no SubjectKeyIdentifier extension in it.
     * The keyIdentifier fields for AKI extensions should be taken from
     * the certificate of the issuer. Use getKeyIDFromCertificate to
     * do this. W
     */
    public static byte [] generateKeyID(String digestAlg, PublicKey key)  {
    	
        byte [] id = null;
        try {
            byte [] encoding = key.getEncoded();
            id = Digest(digestAlg, encoding);
        } catch (java.security.NoSuchAlgorithmException ex) {
            // DKS --big configuration problem
            throw new RuntimeException("Error: can't find " + digestAlg + "!  " + ex.toString());
        }
        return id;
    }
    
    public static byte [] generateKeyID(PublicKey key) {
    	return generateKeyID(KEY_ID_DIGEST_ALG, key);
    }

    public static String getKeyIDString(String digestAlg, PublicKey key)  {
    	byte[] keyID = generateKeyID(digestAlg, key);
    	BigInteger big = new BigInteger(1,keyID);
    	return big.toString(16);
    }
    
    public static String getKeyIDString(PublicKey key) {
    	return getKeyIDString(KEY_ID_DIGEST_ALG,key);
    }

    /**
     * Sign this certificate, using the provided private key. Encodes the
     * TBSCertificate given the current state of the certificate fields.
     *
     * @param hashAlgorithm the hash algorithm to be used. If null, SHA-1 is used.
     * Expects standard Java string identifiers for algorithms.
     * @param privateKey the private key used for signing. The actual signature algorithm
     * taken from the key.
     * @return Returns a signed java.security.cert.X509Certificate.
     *
     * @throws java.security.cert.CertificateEncodingException
     * @throws java.security.NoSuchAlgorithmException
     * @throws java.security.InvalidKeyEception
     */
    public abstract X509Certificate sign(String hashAlgorithm, PrivateKey signingKey)
	throws CertificateEncodingException,
                   NoSuchAlgorithmException,
                   InvalidKeyException,
                   InvalidAlgorithmParameterException,
		   InvalidParameterSpecException;

    /**
     * does this certificate contain an extension of this type (OID)
     * Use our getExtension, as codec's is broken (resolves down to an ill-chosen
     * equals()).
     */
    public abstract boolean hasExtension(String oid);

    /**
     * Remove the extension with the given OID, if present. Expensive.
     * Usually when we change the value of an extension, we get the current
     * extension object and alter its value, rather than remove & replace.
     * This function returns the old extension object, or null if none was present.
     */
    /**** public X509Extension removeExtension(String oid); */


    /**
     * Adds an Extended Key Usage extension, with the
     * given set of purposes. If the certificate already has an EKU extension,
     * its contents are replaced by this list of purposes. If purposes is
     * null or of zero length, no extension is added (but any old one is
     * deleted).
     *
     * @param critical mark this extension critical.
     * @param purposes the OIDs of the desired key usages
     *
     * @throws java.security.cert.CertificateEncodingException if one of the
     * purposes is not really an OID
     */
    public abstract void addEKUExtension(boolean critical, String [] purposes)
        throws java.security.cert.CertificateEncodingException;

    /**
     * Adds an additional key usage to an Extended Key
     * Usage extension. If the certificate has no EKU extension, one is
     * added. Duplicates are automatically filtered.
     * DKS -- add ability to set critical? EKU is rarely critical these
     * days, but who knows...
     * Requires that getExtension returns the actual extension in the
     * cert. If it doesn't, we have to copy and replace (but if it doesn't,
     * we can't replace...?)
     *
     * @param purpose the OID of the desired key usage
     *
     * @throws java.security.cert.CertificateEncodingException if the OID
     * is not really an OID
     */
    public abstract void addExtendedKeyUsage(String purpose)
	throws java.security.cert.CertificateEncodingException;

    /**
     * Removes a key usage from the Extended Key Usage
     * extension.  If the certificate does not have an EKU extension, or the
     * usage is not present therein, the function returns false (nothing to
     * remove). If it actually removed the usage, the function returns true.
     * DEPRECATED: doesn't currently work. Remove the extension and start over.
     * @param purpose the OID of the desired key usage to remove
     */
    /* public boolean removeExtendedKeyUsage(String purpose); */

    /**
     * Tests whether this certificate is enabled for a
     * given Extended Key Usage.
     *
     * @param purpose the OID of the desired usage to test
     */
    public abstract boolean hasExtendedKeyUsage(String purpose)
	throws java.security.cert.CertificateEncodingException;

    /**
     * Both adds the server authentication OID to the EKU
     * extension, and adds the DNS name to the subject alt name
     * extension (not marked critical). (Combines addServerAuthenticationEKU and
     * addDNSNameSubjectAltName).
     * @throws java.security.cert.CertificateEncodingException if the DNS name
     * is not a DNS name
     */
    public abstract void setServerAuthenticationUsage(String serverDNSName)
	throws java.security.cert.CertificateEncodingException;

    /**
     * Adds server authentication as a usage for this
     * certificate. Should also add DNS name to subjectAltName, to do both
     * use setServerAuthenticationUsage.
     */
    public abstract void addServerAuthenticationEKU();

    /**
     * Adds client authentication as a usage for this
     * certificate.
     */
    public abstract void addClientAuthenticationEKU();

    /**
     * Both adds the secure email OID to the EKU
     * extension, and adds the email address to the subject alt name
     * extension (not marked critical). (Combines addSecureEmailEKU and addEmailSubjectAltName).
     * @throws java.security.cert.CertificateEncodingException if the email address
     * is not an email address
     */
    public abstract void setSecureEmailUsage(String subjectEmailAddress)
	throws java.security.cert.CertificateEncodingException;

    /**
     * Adds secure email as a usage for this
     * certificate. Should also add email address to subjectAltName;
     * done as a whole with setSecureEmailUsage.
     */
    public abstract void addSecureEmailEKU();
    
    public abstract void addSubjectAlternativeName(boolean critical, Integer type, String value) throws CertificateEncodingException;
    
    /**
     * Adds email address to subjectAltName
     **/
    public abstract void addEmailSubjectAltName(boolean critical, String subjectEmailAddress) 
    	throws CertificateEncodingException;

    
    /**
     * Adds DNS name to subjectAltName
     **/
    public abstract void addDNSNameSubjectAltName(boolean critical, String serverDNSName) 
    	throws CertificateEncodingException;
    
    /**
     * Adds ip address to subjectAltName.
     * @param ipAddress string form of the IP address. Assumed to be in either
     * IPv4 form, "n.n.n.n", with 0<=n<256, orIPv6 form, 
     * "n.n.n.n.n.n.n.n", where the n's are the HEXADECIMAL form of the
     * 16-bit address components.
     **/
    public abstract void addIPAddressSubjectAltName(boolean critical, String ipAddress) 
	throws CertificateEncodingException;

    /**
     * Adds a basic constraints extension to this
     * certificate. If one is already present, its value is replaced.
     *
     * @param critical mark this extension critical
     * @param isCA is this a CA cert 
     * @param pathLenConstraint if this entity is a CA, how long of certification
     *      paths can it issue. This element is only present in the final
     *      extension if isCA is true. This element is optional, even if isCA
     * 	is true. Use -1 to indicate that it should be omitted, indicating that
     * 	an infinite length path is allowed.  A pathLenConstraint
     * 	of 0 indicates that only end-entities may follow this root.
     */
    public abstract void addBasicConstraints(boolean critical,
                                    boolean isCA,
                                    int pathLenConstraint);

    /**
     * Adds a basic constraints extension to this
     * certificate. If one is already present, its value is replaced.
     * Omits the optional pathLenConstraint.
     *
     * @param critical mark this extension critical
     * @param isCA is this a CA cert
      */
    public abstract void addBasicConstraints(boolean critical,
                                    boolean isCA);
    
   /**
     * Checks an aspect of the basic constraint of the
     * underlying certificate (enhances function of getBasicConstraints provided
     * by superclass). Returns whether this is a CA certificate. Unfortunately
     * right now this only works if the optional pathLenConstraint is present
     * in the exception (it isn't hard to extend it to work without; you
     * just have to parse the extension by hand. The superclass gives the
     * same answer for a cert with no BC extension, for one with a BC
     * extension but not a CA, for one that is a CA and has an extension but
     * which does not have the (optional) pathLenConstraint. Certs in the
     * last category will be mistakenly identified as non-CA certs. All others
     * will be correctly
     */
    public abstract boolean isCACertificate();

    /**
     * Check an aspect of the basic constraint of the
     * underlying certificate (enhances function of getBasicConstraints provided
     * by superclass). If this is a CA certificate, returns the path len
     * constraint in its basic constraints extension. (Equivalent to
     * X509Certificate.getBasicConstraints, with a more intuitive name.)
     */
    public abstract int getPathLenConstraint();

    /**
     * Adds (or set) the KeyUsage extension of the
     * certificate. A KeyUsage is a 9-bit bit string, with the following
     * meaning to the bits:
     * 0 -- digital signature       5 -- key certificate signing
     * 1 -- non-repudiation         6 -- CRL signing
     * 2 -- key encipherment        7 -- encipher only
     * 3 -- data encipherment       8 -- decipher only
     * 4 -- key agreement
     *
     * @param critical should this extension be marked critical
     * @param bits an array booleans containing the values of each of the bits
     * in the order above (should be 9 long, if not, returns doing nothing --
     * needs to throw exception DKS)
     */
    public abstract void addKeyUsage(boolean critical, boolean [] bits)
	throws CertificateEncodingException;
    
	/**
	 * Adds a subjectKeyIdentifier extension to the certificate. Uses the SHA-1 digest
	 * of the DER-encoded SubjectPublicKeyInfo as the KeyID.
	 * 
	 * This extension takes a critical flag argument for consistency's sake, but according
	 * to rfc 2459, it MUST NOT be marked critical.
	 * 
	 * For now, if the extension is already present, this function does nothing and just
	 * returns.
	 **/
	public abstract void addSubjectKeyIdentifierExtension(boolean critical)
	    throws CertificateEncodingException;

	/**
	 * Takes a critical argument, even though rfc 2459 says that this extension MUST NOT
	 * be marked critical.
	 * If the extension is already present in the certificate, replaces its contents with those indicated
	 * here. Otherwise adds the extension.
	 * 
	 * @param issuerKey if not null, adds the issuer key's key ID to the extension
	 * @param issuerName if not null, adds the issuer name to the extension.
	 * @param issuerSerialNumber if not null, adds the issuer serial number to the extension.
	 * It makes little sense to include just one of name and serial number, typically both or
	 * neither will be added.
	 **/
	public abstract void addAuthorityKeyIdentifierExtension(boolean critical,
						       byte [] issuerKeyID,
						       String issuerName, // CN=...
						       BigInteger issuerSerialNumber) throws CertificateEncodingException;

    /**
     * Sets the lifetime of the certificate. The
     * notBefore time is set to NOW, the notAfter time is set to NOW + duration.
     *
     * @param duration in milliseconds
     */
	public abstract void setDuration(long duration);

    /**
     * Sets the lifetime of the certificate. The
     * notBefore time is set to startTime, the notAfter time is set to
     * startTime + duration.
     *
     * @param startTime
     * @param duration in milliseconds
     */
    public abstract void setDuration(Date startTime, long duration);

    /**
     * Generate a full-blown, signed X509 certificate.
     *
     * @param publicKey the public key to be encoded into the certificate
     * @param issuer the issuer's RDN  must be parsable according to RFC 1779
     * (e.g. CN=Evil Minion, OU=World Domination, O=Microsoft Corporation, C=US)
     * @param subject the subject's RDN  must be parsable according to RFC 1779
     * @param serialNumber the serial number for the certificate. If null, an
     * attempt is made to generate a novel serial number using a hash function.
     * @param notBefore the first time the certificate will be valid
     * @param notAfter the time at which the certificate expires
     * @param extensions the extensions to add to the certificate (can be null)
     * @param signingKey the private key to use to sign the certificate
     * @param hashAlgorithm the hash algorithm to be used. If null, SHA-1 is used.
     * Expects standard Java string identifiers for algorithms.
     *
     * @return returns the signed certificate
     * @throws codec.x501.BadNameException if the issuer or subject cannot be
     *  parsed
     *  @throws java.security.InvalidKeyException
     * @throws java.security.cert.CertificateEncodingException if one of the
     *  extensions cannot be encoded properly
     * @throws java.security.NoSuchAlgorithmException if the hash algorithm is
     * not a hash algorithm, or cannot be used with this key type
     */
    /* XXX  No "generate" function until we solve the "extensions" problem
    public static X509Certificate
                  GenerateX509Certificate(PublicKey publicKey,
                                          String issuer,
                                          String subject,
                                          BigInteger serialNumber,
                                          Date notBefore,
                                          Date notAfter,
                                          X509Extension [] extensions,
                                          PrivateKey signingKey,
                                          String hashAlgorithm)
                throws InvalidKeyException,
                       BadNameException,
                       CertificateEncodingException,
                       NoSuchAlgorithmException,
                       InvalidParameterSpecException,
	InvalidAlgorithmParameterException;
    */

    /**
     * Generate a full-blown, signed X509 certificate.
     *
     * @param publicKey the public key to be encoded into the certificate
     * @param issuer the issuer's RDN  must be parsable according to RFC 1779
     * (e.g. CN=Evil Minion, OU=World Domination, O=Microsoft Corporation, C=US)
     * @param subject the subject's RDN  must be parsable according to RFC 1779
     * @param serialNumber the serial number for the certificate. If null, an
     * attempt is made to generate a novel serial number using a hash function.
     * @param duration the certificate lifetime (from now) in msec. "Now" is
     *      defined to be the time at which the certificate fields are filled
     *      in. Therefore the duration should take into account the time consumed
     *      by signature generation (the start time of the certificate is the
     *      beginning of signing, but the certificate is not actually returned
     *      for use till the end of signing).  With a Java implementation of the
     *      signature algorithm and a slow-to-warm-up JIT, the first signature of
     *      a program can take several seconds. On low-power hardware, it can
     *      take minutes.
     * @param extensions the extensions to add to the certificate (can be null)
     * @param signingKey the private key to use to sign the certificate
     * @param hashAlgorithm the hash algorithm to be used. If null, SHA-1 is used.
     * Expects standard Java string identifiers for algorithms.
     *
     * @throws codec.x501.BadNameException if the issuer or subject cannot be
     *  parsed
     *  @throws java.security.InvalidKeyException
     * @throws java.security.cert.CertificateEncodingException if one of the
     *  extensions cannot be encoded properly
     * @throws java.security.NoSuchAlgorithmException if the hash algorithm is
     * not a hash algorithm, or cannot be used with this key type
     */
    /* XXX  No "generate" function until we solve the "extensions" problem
    public static X509Certificate
                    GenerateX509Certificate(PublicKey publicKey,
                                            String issuer,
                                            String subject,
                                            BigInteger serialNumber,
                                            long duration,
                                            X509Extension [] extensions,
                                            PrivateKey signingKey,
                                            String hashAlgorithm)
                throws BadNameException,
                        InvalidKeyException,
                        CertificateEncodingException,
	NoSuchAlgorithmException, InvalidParameterSpecException, InvalidAlgorithmParameterException;
    */

    /**
     * Generate a self-signed X509 certificate.
     *
     * @param keyPair the public key to be encoded into the certificate, and
     *  the corresponding private key used to sign it.  If you haven't got a
     *  KeyPair object for your key pair, you can use the previous function.
     * @param subject the subject's RDN. For a self-signed certificate, the
     * issuer == the subject. This must be parsable according to RFC 1779
     * (e.g. CN=Evil Minion, OU=World Domination, O=Microsoft Corporation, C=US)
     * @param serialNumber the serial number for the certificate. If null, an
     * attempt is made to generate a novel serial number using a hash function.
     * @param notBefore the first time the certificate will be valid
     * @param notAfter the time at which the certificate expires
     * @param extensions the extensions to add to the certificate (can be null)
     * @param hashAlgorithm the hash algorithm to be used. If null, SHA-1 is used.
     * Expects standard Java string identifiers for algorithms.
     *
     * @throws java.security.InvalidKeyException
     * @throws codec.x501.BadNameException if the issuer or subject cannot be
     * parsed
     * @throws java.security.cert.CertificateEncodingException if the extensions
     * cannot be properly encoded
     * @throws java.security.NoSuchAlgorithmException if the hash algorithm is
     * not a hash algorithm, or cannot be used with this key type
     */
    /* XXX  No "generate" function until we solve the "extensions" problem
    public static X509Certificate
                GenerateX509Certificate(KeyPair keyPair,
                                        String subject,
                                        BigInteger serialNumber,
                                        Date notBefore,
                                        Date notAfter,
                                        X509Extension [] extensions,
                                        String hashAlgorithm)
                throws InvalidKeyException,
                        BadNameException,
                        CertificateEncodingException,
                        NoSuchAlgorithmException,
                        InvalidAlgorithmParameterException,
	InvalidParameterSpecException;
    */

    /**
     * Generate a self-signed X509 certificate.
     *
     * @param keyPair the public key to be encoded into the certificate, and
     *  the corresponding private key used to sign it.  If you haven't got a
     *  KeyPair object for your key pair, you can use the previous function.
     * @param subject the subject's RDN. For a self-signed certificate, the
     * issuer == the subject. This must be parsable according to RFC 1779
     * (e.g. CN=Evil Minion, OU=World Domination, O=Microsoft Corporation, C=US)
     * @param serialNumber the serial number for the certificate. If null, an
     * attempt is made to generate a novel serial number using a hash function.
     * @param duration the certificate lifetime (from now) in msec. "Now" is
     *      defined to be the time at which the certificate fields are filled
     *      in. Therefore the duration should take into account the time consumed
     *      by signature generation (the start time of the certificate is the
     *      beginning of signing, but the certificate is not actually returned
     *      for use till the end of signing).  With a Java implementation of the
     *      signature algorithm and a slow-to-warm-up JIT, the first signature of
     *      a program can take several seconds. On low-power hardware, it can
     *      take minutes.
     * @param extensions the extensions to add to the certificate (can be null)
     * @param hashAlgorithm the hash algorithm to be used. If null, SHA-1 is used.
     * Expects standard Java string identifiers for algorithms.
     *
     * @throws codec.x509.BadNameException if either the issuer or subject cannot
     *  be parsed
     * @throws java.security.InvalidKeyException
     * @throws java.security.cert.CertificateEncodingException if one of the
     *  extensions cannot be encoded properly
     * @throws java.security.NoSuchAlgorithmException if the hash algorithm is
     * not a hash algorithm, or cannot be used with this key type
     */
    /* XXX  No "generate" function until we solve the "extensions" problem
    public static X509Certificate
                    GenerateX509Certificate(KeyPair keyPair,
                                            String subject,
                                            BigInteger serialNumber,
                                            long duration,
                                            X509Extension [] extensions,
                                            String hashAlgorithm)
                throws BadNameException,
                       InvalidKeyException,
                       CertificateEncodingException,
                       NoSuchAlgorithmException,
                       InvalidAlgorithmParameterException,
	InvalidParameterSpecException;
    */

    /**
     * Compressed call to GenerateX509Certificate, with serialNumber,
     * extensions, and hashAlgorithm set to null (thus getting their
     * default values).
     *
     * @throws codec.x501.BadNameException
     * @throws java.security.InvalidKeyException
     * @throws java.security.NoSuchAlgorithmException if the default hash
     *      algorithm (SHA-1) cannot be used with this key
     */
    /* XXX  No "generate" function until we solve the "extensions" problem
    public static X509Certificate
                GenerateX509Certificate(KeyPair keyPair,
                                        String subject,
                                        long duration)
           throws BadNameException,
                       InvalidKeyException,
                       NoSuchAlgorithmException,
                       InvalidParameterSpecException,
	InvalidAlgorithmParameterException;
    */

    /**
     * Compressed call to GenerateX509Certificate, with serialNumber,
     * extensions, and hashAlgorithm set to null (thus getting their
     * default values).
     *
     * @throws codec.x501.BadNameException
     * @throws java.security.InvalidKeyException
     * @throws java.security.NoSuchAlgorithmException if the default hash
     *      algorithm (SHA-1) cannot be used with this key
     */
    /* XXX  No "generate" function until we solve the "extensions" problem
    public static X509Certificate
                GenerateX509Certificate(KeyPair keyPair,
                                        String subject,
                                        Date notBefore, 
                                        Date notAfter)
           throws BadNameException,
                 	   InvalidKeyException,
                 	   NoSuchAlgorithmException,
                 	   InvalidAlgorithmParameterException,
	InvalidParameterSpecException;
    */

    /**
     * Generate a self-signed X509 certificate containing the appropriate extensions
     * to make it a CA root certificate.
     *
     * @param keyPair the public key to be encoded into the certificate, and
     *  the corresponding private key used to sign it.
     * @param subject the subject's RDN. For a self-signed certificate, the
     * issuer == the subject. This must be parsable according to RFC 1779
     * (e.g. CN=Evil Minion, OU=World Domination, O=Microsoft Corporation, C=US)
     * @param serialNumber the serial number for the certificate. If null, an
     * attempt is made to generate a novel serial number using a hash function.
     * @param notBefore the time when the certificate should begin to be valid
     * @param notAfter the time when the certificate should stop being valid
     * @param pathLen the path length constraint for the CA. If you don't know what to do with
     * this, set it to 0 (infinite).
     * @param extensions the extensions to add to the certificate (can be null). The function
     *  	will already add BasicConstraints (necessary for a CA to the certificate). Use this
     * 	argument to add items like IssuerKeyId, CRLDistributionPoints, etc, if desired.
     * @param hashAlgorithm the hash algorithm to be used. If null, SHA-1 is used.
     * Expects standard Java string identifiers for algorithms.
     *
     * @throws codec.x509.BadNameException if either the issuer or subject cannot
     *  be parsed
     * @throws java.security.InvalidKeyException
     * @throws java.security.cert.CertificateEncodingException if one of the
     *  extensions cannot be encoded properly
     * @throws java.security.NoSuchAlgorithmException if the hash algorithm is
     * not a hash algorithm, or cannot be used with this key type
     */
    /* XXX  No "generate" function until we solve the "extensions" problem
    public static X509Certificate
                    GenerateRootCertificate(KeyPair keyPair,
                                            String subject,
                                            BigInteger serialNumber,
	                                        Date notBefore, 
    	                                    Date notAfter,
    	                                    int pathLen,
                                            X509Extension [] extensions,
                                            String hashAlgorithm)
                throws BadNameException,
                       InvalidKeyException,
                       CertificateEncodingException,
                       NoSuchAlgorithmException,
                       InvalidAlgorithmParameterException,
	InvalidParameterSpecException;
    */

    /**
     * Generate a self-signed X509 certificate containing the appropriate extensions
     * to make it a CA root certificate.
     *
     * @param keyPair the public key to be encoded into the certificate, and
     *  the corresponding private key used to sign it.
     * @param subject the subject's RDN. For a self-signed certificate, the
     * issuer == the subject. This must be parsable according to RFC 1779
     * (e.g. CN=Evil Minion, OU=World Domination, O=Microsoft Corporation, C=US)
     * @param serialNumber the serial number for the certificate. If null, an
     * attempt is made to generate a novel serial number using a hash function.
     * @param notBefore the time when the certificate should begin to be valid
     * @param notAfter the time when the certificate should stop being valid
     * Expects standard Java string identifiers for algorithms.
     *
     * @throws codec.x509.BadNameException if either the issuer or subject cannot
     *  be parsed
     * @throws java.security.InvalidKeyException
     * @throws java.security.cert.CertificateEncodingException if one of the
     *  extensions cannot be encoded properly
     * @throws java.security.NoSuchAlgorithmException if the hash algorithm is
     * not a hash algorithm, or cannot be used with this key type
     */
    /* XXX  No "generate" function until we solve the "extensions" problem
    public static X509Certificate
                    GenerateRootCertificate(KeyPair keyPair,
                                            String subject,
                                            BigInteger serialNumber,
	                                        Date notBefore, 
    	                                    Date notAfter)
                throws BadNameException,
                       InvalidKeyException,
                       CertificateEncodingException,
                       NoSuchAlgorithmException,
                       InvalidAlgorithmParameterException,
	InvalidParameterSpecException;
    */

    public abstract PublicKey getSubjectPublicKey();

    public abstract BigInteger getSerialNumber();

    public abstract void addCRLDistributionPointsExtension(boolean critical, String [] urls) 
	throws CertificateEncodingException;
}
