package com.parc.ccn.crypto.certificates;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidParameterSpecException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.SimpleTimeZone;
import java.util.Vector;

import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.ASN1InputStream;
import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.ASN1StreamParser;
import org.bouncycastle.asn1.DERBitString;
import org.bouncycastle.asn1.DEREncodable;
import org.bouncycastle.asn1.DERInteger;
import org.bouncycastle.asn1.DERObject;
import org.bouncycastle.asn1.DERObjectIdentifier;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.DERTaggedObject;
import org.bouncycastle.asn1.DERTags;
import org.bouncycastle.asn1.DERUTCTime;
import org.bouncycastle.asn1.DERUnknownTag;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.CRLDistPoint;
import org.bouncycastle.asn1.x509.DistributionPoint;
import org.bouncycastle.asn1.x509.DistributionPointName;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.SubjectKeyIdentifier;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.asn1.x509.TBSCertificateStructure;
import org.bouncycastle.asn1.x509.V1TBSCertificateGenerator;
import org.bouncycastle.asn1.x509.V3TBSCertificateGenerator;
import org.bouncycastle.asn1.x509.X509CertificateStructure;
import org.bouncycastle.asn1.x509.X509Extension;
import org.bouncycastle.asn1.x509.X509Extensions;
import org.bouncycastle.asn1.x509.X509Name;
import org.bouncycastle.jce.provider.X509CertificateObject;

/**
 * Title:        CA
 * Description:  Basic Certification Authority classes
 * Copyright:    Copyright (c) 2002
 * Company:      PARC
 * @author D. K. Smetters
 * @version 1.0
 */

/**
 * This is a helper class meant to ease the generation of certificates.
 * It tries to provide two sets of interfaces: a full-fledged one, which tries
 * to automate generation of the most common certificate types, and a
 * streamlined one, meant for generating minimal, temporary certificates
 * (something for which SPKI or other certificate formats would be better;
 * but some things just insist on X.509). It can be used both non-statically,
 * (e.g. if you want to generate many certificates with slightly changing
 * data), or statically, in which case it builds a temporary X509 certificate
 * internally (actually by building a temporary X509CertificateGenerator).
 * 
 * It's one major flaw (inherited from the BouncyCastle minimal certificate
 * generator, on which is is based), is that it generates only V3 certificates,
 * whether they contain any extensions or not. Turns out BouncyCastle
 * has a separate V1 and V3 certificate generator. To deal with this,
 * we don't build the generator till the very end.
 */
public class BCX509CertificateGenerator
extends GenericX509CertificateGenerator {


	/**
	 * Debugging flag
	 */
	private static final boolean debug = false;

	/**
	 * Cache a random number generator (non-secure, used for generating
	 * certificate serial numbers.)
	 */
	protected static Random cachedRandom;

	protected static SimpleDateFormat          DATE_FORMAT = new SimpleDateFormat("yyMMddHHmmss");
	protected static SimpleTimeZone              TZ = new SimpleTimeZone(0, "Z");

	static {
		DATE_FORMAT.setTimeZone(TZ);
	}

	/**
	 * For some strange reason, BouncyCastle's extension class doesn't contain
	 * the extension OID...
	 **/
	public static class X509ExtensionHelper {
		protected DERObjectIdentifier _oid;
		protected X509Extension _extension;

		/** Right now doesn't clone values passed in, uses pointers. */
		public X509ExtensionHelper(DERObjectIdentifier oid, X509Extension extension) {
			_oid = oid;
			_extension = extension;
		}

		public X509ExtensionHelper(DERObjectIdentifier oid, boolean critical,
				byte [] value) {
			this(oid, critical, new DEROctetString(value));
		}

		public X509ExtensionHelper(DERObjectIdentifier oid, boolean critical,
				ASN1OctetString value) {
			_oid = oid;
			_extension = new X509Extension(critical, value);
		}

		public DERObjectIdentifier getOID() { return _oid; }
		public boolean getCritical() { return _extension.isCritical(); }
		public ASN1OctetString getValue() { return _extension.getValue(); }
		public X509Extension getExtension() { return _extension; }
	}

	/**
	 * Our internal representation of the certificate we're building.
	 * Unfortunately, BouncyCastle's X509V3CertificateGenerator doesn't let
	 * us get values back out once we've put them in, and doesn't let us set the
	 * parameters in the algorithm identifier. So build our own version of the 
	 * X509V3CertificateGenerator using BouncyCastle components.
	 * 
	 * We must mirror the data set into the generator if we're going to need it again,
	 * as we can't get it back out.
	 **/
	protected X509Name					_issuerDN;
	protected X509Name					_subjectDN;
	protected BigInteger 					_serialNumber;
	protected AlgorithmIdentifier 		_sigAlgID;
	protected Date							_startDate;
	protected Date							_endDate;
	protected PublicKey 					_publicKey;
	protected SubjectPublicKeyInfo	_subjectPublicKeyInfo;
	protected Hashtable<DERObjectIdentifier,X509Extension> _extensions = null;
	protected Vector<DERObjectIdentifier>		_extOrdering = null;

	/**
	 * Generate a blank X509CertificateGenerator. Use the standard
	 * certificate functions to add data before signing.
	 */
	public BCX509CertificateGenerator() {
	}

	/**
	 * Create a pre-initialized CertificateGenerator. Extensions must
	 * be added later. (Could be modified to take extensions now, but note that
	 * BouncyCastle's X509Extension object contains only the value, not
	 * the OID...
	 *
	 *
	 * @param publicKey the public key to be encoded into the certificate
	 * @param issuer the issuer's RDN  must be parsable according to RFC 1779
	 * (e.g. CN=Evil Minion, OU=World Domination, O=Microsoft Corporation, C=US)
	 * @param subject the subject's RDN  must be parsable according to RFC 1779
	 * @param serialNumber the serial number for the certificate. If null, an
	 * attempt is made to generate a novel serial number using a hash function.
	 * @param notBefore the first time the certificate will be valid; if null
	 * will be set to now. Preferable to use Date over Calendar; java certs
	 * dont' handle timezones properly sometimes.
	 * @param notAfter the time at which the certificate expires
	 * @param extensions the extensions to add to the certificate (can be null,
	 * extensions can also be added later using other functions). Wrapped in
	 * helper classes, as BC's extension class doesn't contain OID.
	 * @throws java.security.InvalidKeyException
	 *  extensions cannot be encoded properly
	 */
	public BCX509CertificateGenerator(PublicKey publicKey,
			String issuer,
			String subject,
			BigInteger serialNumber,
			Date notBefore,
			Date notAfter,
			X509ExtensionHelper [] extensions) throws CertificateEncodingException
			{
		this(publicKey, issuer, subject, serialNumber, extensions);

		if (null != notBefore) {
			setNotBefore(notBefore);
		} else {
			setNotBefore(new Date());
		}
		setNotAfter(notAfter);
			}

	/**
	 * Create a pre-initialized CertificateGenerator with empty time fields,
	 * for the purpose of generating short-lived certificates. 
	 *
	 * @param publicKey the public key to be encoded into the certificate
	 * @param issuer the issuer's RDN  must be parsable according to RFC 1779
	 * (e.g. CN=Evil Minion, OU=World Domination, O=Microsoft Corporation, C=US)
	 * Will be added *in order*. Be careful of x509Certificate implementations, like
	 * Suns, that don't output the components in their internal order, but instead
	 * reorder them -- that will break chaining if someone's screwed up the order.
	 * @param subject the subject's RDN  must be parsable according to RFC 1779
	 * @param serialNumber the serial number for the certificate. If null, an
	 * attempt is made to generate a novel serial number using a hash function.
	 *@param extensions the extensions to add to the certificate (more can be
	 * added later)
	 * 
	 * @throws java.security.InvalidKeyException if the public key cannot be parsed
	 * @throws codec.x509.BadNameException if either the issuer or subject
	 *  cannot be parsed
	 * @throws java.security.cert.CertificateEncodingException if any of the
	 *   extensions cannot be properly encoded
	 */
	public BCX509CertificateGenerator(PublicKey publicKey,
			String issuer,
			String subject,
			BigInteger serialNumber,
			X509ExtensionHelper [] extensions) 
	throws CertificateEncodingException
	{
		this();
		setSubjectPublicKey(publicKey);
		setIssuerDN(issuer);
		setSubjectDN(subject);

		if (null != serialNumber) {
			setSerialNumber(serialNumber);
		} else {
			setSerialNumber(generateRandomSerialNumber());
		}

		if (null != extensions) {
			for (int i=0; i < extensions.length; ++i) {
				addExtension(extensions[i].getOID(), extensions[i].getExtension());
			}
		}
	}

	/**
	 * If you're using this to reissue certificates,
	 * be sure to pass in serialNumber as null or something
	 * different -- DO NOT issue two certificates for the
	 * same issuer with the same serial number.
	 * @param publicKey
	 * @param issuer
	 * @param subject
	 * @param serialNumber
	 * @param extensions
	 * @throws CertificateEncodingException
	 */
	public BCX509CertificateGenerator(PublicKey publicKey,
			X509Name issuer,
			X509Name subject,
			BigInteger serialNumber,
			X509Extensions extensions) 
	throws CertificateEncodingException
	{
		this();
		setSubjectPublicKey(publicKey);
		setIssuerDN(issuer);
		setSubjectDN(subject);

		if (null != serialNumber) {
			setSerialNumber(serialNumber);
		} else {
			setSerialNumber(generateRandomSerialNumber());
		}

		Enumeration oids = extensions.oids();
		while (oids.hasMoreElements()) {
			DERObjectIdentifier oid = (DERObjectIdentifier)oids.nextElement();
			X509Extension extension = extensions.getExtension(oid);
			addExtension(oid, extension);
		}
	}

	/**
	 * Sign this certificate, using the provided private key. Encodes the
	 * TBSCertificate given the current state of the certificate fields. Builds
	 * the certificate generator here so that we can make a V1 or V3
	 * certificate depending on whether or not it has extensions.
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
	public X509Certificate sign(String hashAlgorithm, PrivateKey signingKey) 
	throws NoSuchAlgorithmException, InvalidKeyException, 
	CertificateEncodingException, InvalidParameterSpecException, 
	InvalidAlgorithmParameterException 
	{
		TBSCertificateStructure tbsCertificate = null; 

		AlgorithmIdentifier sigAlg =
			getSignatureAlgorithm(((null == hashAlgorithm) || (hashAlgorithm.length() == 0)) ?
					DEFAULT_HASH : hashAlgorithm,
					signingKey);

		String sigAlgName =
			getSignatureAlgorithmName(((null == hashAlgorithm) || (hashAlgorithm.length() == 0)) ?
					DEFAULT_HASH : hashAlgorithm,
					signingKey);

		if ((null == _extensions) || (_extensions.size() == 0)) {
			V1TBSCertificateGenerator tbsGen = new V1TBSCertificateGenerator();	

			tbsGen.setSerialNumber(new DERInteger(_serialNumber));
			tbsGen.setIssuer(_issuerDN);
			tbsGen.setSubject(_subjectDN);
			tbsGen.setStartDate(new DERUTCTime(DATE_FORMAT.format(_startDate) + "Z"));
			tbsGen.setEndDate(new DERUTCTime(DATE_FORMAT.format(_endDate) + "Z"));
			tbsGen.setSubjectPublicKeyInfo(_subjectPublicKeyInfo);
			tbsGen.setSignature(sigAlg);

			// This is almost what we want. It gets us a V1 certificate, but does put the
			// version number (optional) in, which isn't really the normal thing to do. But
			// barring building our own cert generator base class and TBSCertificate, we're
			// stuck with this. 
			tbsCertificate = tbsGen.generateTBSCertificate();

		} else {
			V3TBSCertificateGenerator tbsGen = new V3TBSCertificateGenerator();	

			tbsGen.setSerialNumber(new DERInteger(_serialNumber));
			tbsGen.setIssuer(_issuerDN);
			tbsGen.setSubject(_subjectDN);
			tbsGen.setStartDate(new DERUTCTime(DATE_FORMAT.format(_startDate) + "Z"));
			tbsGen.setEndDate(new DERUTCTime(DATE_FORMAT.format(_endDate) + "Z"));
			tbsGen.setSubjectPublicKeyInfo(_subjectPublicKeyInfo);
			tbsGen.setSignature(sigAlg);
			tbsGen.setExtensions(new X509Extensions(_extOrdering, _extensions));

			tbsCertificate = tbsGen.generateTBSCertificate();
		}

		X509Certificate cert = null;
		try {

			Signature sig=null;
			try {
				sig = Signature.getInstance(sigAlgName);
			} catch (Exception e) {
				System.out.println("This should not happen: cannot get signature instance of type " + sigAlgName);
				e.printStackTrace();
			}

			sig.initSign(signingKey);

			byte [] encodedTBSCertificate = CryptoUtil.encode(tbsCertificate);        	
			sig.update(encodedTBSCertificate);
			byte [] signature = sig.sign();

			ASN1EncodableVector seqVector = new ASN1EncodableVector();
			seqVector.add(tbsCertificate);
			seqVector.add(sigAlg);
			seqVector.add(new DERBitString(signature));

			X509CertificateObject co = 
				new X509CertificateObject(
						new X509CertificateStructure(
								new DERSequence(seqVector)));

			// This gets us a BC X509CertificateObject, which
			// appears to have some troubld verifying itself.
			// Move it to a standard Java object before
			// returning it
			CertificateFactory cf;
			try {
				cf = CertificateFactory.getInstance("X.509");
			} catch (CertificateException e) {
				System.out.println("This should not happen: cannot get X.509 certificate factory.");
				e.printStackTrace();
				return null;
			}
			try {
				cert = (X509Certificate)cf.generateCertificate(new ByteArrayInputStream(co.getEncoded()));
			} catch (CertificateException e) {
				System.out.println("This should not happen: cannot encode or parse certificate we just generated.");
				e.printStackTrace();
				return null;
			}

		} catch (SignatureException ex) {
			System.out.println("This should not happen: SignatureException in sign.");
			System.out.println("Indicates Signature object not properly initialized: " +
					ex.getMessage());
			ex.printStackTrace();
		} catch (CertificateParsingException ex) {
			System.out.println("This should not happen: CertificateParsingException in sign.");
			System.out.println("Indicates Signature object not properly initialized: " +
					ex.getMessage());
			ex.printStackTrace();
		} 
		return cert;
	}

	/**
	 * generate a random serial number for this certificate
	 */
	protected BigInteger generateRandomSerialNumber() {
		final int RAND_BYT_LEN = 100;
		final int SERIAL_NUM_LEN = 10;

		byte [] digest = null;
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-1");

			if (null == cachedRandom) {
				cachedRandom = new Random();
			}
			byte [] randomBytes = new byte[RAND_BYT_LEN];
			cachedRandom.nextBytes(randomBytes);

			md.update(randomBytes);

			PublicKey publicKey = getSubjectPublicKey();
			if (publicKey != null) {
				byte [] keyBytes = publicKey.getEncoded();
				md.update(keyBytes);
			}

			digest = md.digest();

		} catch (NoSuchAlgorithmException ex) {
			System.out.println(
					"This should not happen: no such algorithm exception in generateRandomSerialNumber.");
			ex.getMessage();
			ex.printStackTrace();
		}
		byte [] sn = new byte[SERIAL_NUM_LEN];
		System.arraycopy(digest, 0, sn, 0, sn.length);
		// Force serial number to be positive. Some packages complain
		// about negative serial numbers
		return new BigInteger(1, sn);
	}

	/**
	 * does this certificate contain an extension of this type (OID)
	 * Use our getExtension, as codec's is broken (resolves down to an ill-chosen
	 * equals()).
	 */
	public boolean hasExtension(String oid) {
		return hasExtension(new DERObjectIdentifier(oid));
	}

	public boolean hasExtension(DERObjectIdentifier oid) {
		if (null == _extensions)
			return false;
		return _extensions.containsKey(oid);
	}

	/**
	 * If this extension is present in the certificate, return a pointer
	 *  to the actual extension object (used for changing its value). Hopes the
	 *  underlying certificate returns its actual extension list; not a copy.
	 * 
	 * Works better than codec.x509.X509Certificate.getExtension(s), which
	 * resolve down to a non-working equals method in ASN1ObjectIdentifier...
	 * @param oid string version of oid. Unless you already have this in
	 * 
	 * a string, better to use the next version with one of the constant
	 * DERObjectIdentifiers in X509Extensions
	 */
	public X509Extension getExtension(String oid) {
		return getExtension(new DERObjectIdentifier(oid));
	}

	public X509Extension getExtension(DERObjectIdentifier derOID) {
		if (null == _extensions)
			return null;
		return (X509Extension)_extensions.get(derOID);
	}

	public ASN1OctetString getExtensionValue(DERObjectIdentifier derOID) {
		X509Extension ext = getExtension(derOID);
		if (null != ext) 
			return ext.getValue();
		return null;
	}

	/**
	 * Check criticality of extension. 
	 **/
	public boolean isCritical(String oid) {
		return isCritical(new DERObjectIdentifier(oid));
	}

	public boolean isCritical(DERObjectIdentifier oid) {
		X509Extension ext = getExtension(oid);
		if (null == ext) {
			throw new IllegalArgumentException("Certificate does not contain extension: " +
					oid.toString());
		}
		return ext.isCritical();
	}

	public void setCritical(DERObjectIdentifier oid, boolean critical) {
		X509Extension ext = getExtension(oid);
		if (null == ext) {
			throw new IllegalArgumentException("Certificate does not contain extension: " +
					oid.toString());
		}
		updateExtension(oid, new X509Extension(critical, ext.getValue()));
		return;
	}

	/**
	 * Add this extension to the certificate. If it's already present,
	 * change its value to the one given (and change its critical/noncrit
	 * status as well). This only works if getExtension returns a pointer
	 * to the actual extension in the certificate, not a copy.
	 *
	 * @throws java.security.cert.CertificateEncodingException if the
	 *  extension value cannot be encoded properly
	 */
	public void addExtension(DERObjectIdentifier derOID, boolean critical, DEREncodable value)
	throws CertificateEncodingException {
		addExtension(derOID, critical, CryptoUtil.encode(value));
	}

	public void addExtension(String oid, boolean critical, DEREncodable value) 
	throws CertificateEncodingException {
		addExtension(new DERObjectIdentifier(oid), critical, value);
	}

	/**
	 * Adds the octet string wrapper to the encoded value.
	 **/
	public void addExtension(DERObjectIdentifier derOID, 
			boolean critical, ASN1OctetString encodedValue) {
		addExtension(derOID, new X509Extension(critical, encodedValue));
	}

	public void addExtension(DERObjectIdentifier derOID, boolean critical,
			byte [] value) {
		addExtension(derOID, critical, new DEROctetString(value));
	}

	/**
	 * Used to copy extensions.
	 * @param derOID
	 * @param extension
	 */
	public void addExtension(DERObjectIdentifier derOID, X509Extension extension) {
		if (null == _extensions) {
			_extensions = new Hashtable<DERObjectIdentifier,X509Extension>();
			_extOrdering = new Vector<DERObjectIdentifier>();
		}

		synchronized(_extensions) {
			if (hasExtension(derOID)) 
				removeExtension(derOID);
			_extensions.put(derOID, extension);
			_extOrdering.add(derOID);
		}
	}

	/**
	 * Updates the extension value, without changing its position in the extension order.
	 * If the certificate does not contain the extension, throws an exception.
	 * @return returns the previous extension value
	 **/
	protected ASN1OctetString setExtensionValue(DERObjectIdentifier derOID, 
			ASN1OctetString newValue) {
		X509Extension ext = getExtension(derOID);
		if (null == ext) {
			throw new IllegalArgumentException("Certificate does not contain extension: " +
					derOID.toString());
		}
		X509Extension oldExt = updateExtension(derOID, new X509Extension(ext.isCritical(), newValue));
		return oldExt.getValue(); // can't be null if ext != null...
	}

	/**
	 * Remove the extension with the given OID, if present. Expensive.
	 * Usually when we change the value of an extension, we get the current
	 * extension object and alter its value, rather than remove & replace.
	 * This function returns the old extension, or null if none was present.
	 */
	public X509Extension removeExtension(DERObjectIdentifier derOID) {

		if (!hasExtension(derOID)) {
			return null;
		}

		X509Extension value = null;
		synchronized(_extensions) {
			if (!hasExtension(derOID)) 
				return null;

			value = (X509Extension)_extensions.get(derOID);
			_extensions.remove(derOID);
			_extOrdering.remove(derOID);
		}
		return value;
	}

	/**
	 * Update the X509Extension object associated with a given OID. Do not
	 * change the location of that extension in the extension ordering. As we can't
	 * change the member values in the extension object, have to replace it whole
	 * hog.
	 * @param newExtension new X509Extension containing the proper criticality
	 * and value.
	 * @return oldExtension returns the previous extension value.
	 * If the certificate does not contain this extension already, it is added, and the
	 * previous value returned is null.
	 **/
	protected X509Extension updateExtension(DERObjectIdentifier derOID, 
			X509Extension newExtension) {
		if (!hasExtension(derOID)) {
			addExtension(derOID, newExtension);
			return null;
		}

		X509Extension oldExtension = null;
		synchronized(_extensions) {
			oldExtension = (X509Extension)_extensions.get(derOID);
			_extensions.remove(derOID);
			// Leave OID in ordering to preserve location
			_extensions.put(derOID, newExtension);
		}
		return oldExtension;
	}   	

	protected X509Extension updateExtension(DERObjectIdentifier derOID,
			boolean critical, DEREncodable newValue) 
	throws CertificateEncodingException {
		return updateExtension(derOID, 
				new X509Extension(critical,
						new DEROctetString(
								CryptoUtil.encode(newValue))));
	}

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
	public void addEKUExtension(boolean critical, String [] purposes)
	throws java.security.cert.CertificateEncodingException{

		if ((null == purposes) || (purposes.length == 0)) {
			return;
		}

		try {
			Vector<DERObjectIdentifier> vecPurposes = 
				new Vector<DERObjectIdentifier>(purposes.length);
			for (int i=0; i < purposes.length; ++i) {
				vecPurposes.add(new DERObjectIdentifier(purposes[i]));
			}
			ExtendedKeyUsage eku = new ExtendedKeyUsage(vecPurposes);

			addExtension(id_ce_extendedKeyUsage, critical, eku);

		} catch (CertificateEncodingException cex) {
			throw(cex);
		} catch (Exception ex) {
			System.out.println(
					"X509CertificateGenerator.addEKUExtension: got unexpected error passed up ");
			System.out.println("from the X509Extension constructor: type: " +
					ex.getClass().getName());
			System.out.println("Message: " + ex.getMessage());
			ex.printStackTrace();
		}
	}

	/**
	 * Adds an additional key usage to an Extended Key
	 * Usage extension. If the certificate has no EKU extension, one is
	 * added. Duplicates are automatically filtered.
	 * If no previous extension, sets criticality to false (can be changed with
	 * setCritical).
	 * Requires that getExtension returns the actual extension in the
	 * cert. If it doesn't, we have to copy and replace (but if it doesn't,
	 * we can't replace...?)
	 *
	 * @param purpose the OID of the desired key usage
	 *
	 * @throws java.io.IOException if the old extension value can't be parsed
	 * or new one encoded
	 */
	public void addExtendedKeyUsage(DERObjectIdentifier purpose)
	throws CertificateEncodingException {

		ExtendedKeyUsage eku = null;
		boolean critical = false;
		if (hasExtension(X509Extensions.ExtendedKeyUsage)) {
			X509Extension ext = getExtension(X509Extensions.ExtendedKeyUsage);
			critical = ext.isCritical();
			DERObject decoded = CryptoUtil.decode(ext.getValue().getOctets());
			if (hasExtendedKeyUsage((ASN1Sequence)decoded, purpose)) {
				// done, return
				return;
			}
			ExtendedKeyUsage oldEku = ExtendedKeyUsage.getInstance(decoded);
			Enumeration oldPurposes = ((ASN1Sequence)oldEku.getDERObject()).getObjects();
			Vector<DERObjectIdentifier> purposes =
				new Vector<DERObjectIdentifier>();
			while (oldPurposes.hasMoreElements()) {
				purposes.add((DERObjectIdentifier)oldPurposes.nextElement());
			}
			purposes.add(purpose);
			eku = new ExtendedKeyUsage(purposes);
		} else {
			Vector<DERObjectIdentifier> purposes =
				new Vector<DERObjectIdentifier>();
			purposes.add(purpose);
			eku = new ExtendedKeyUsage(purposes);
		}

		updateExtension(X509Extensions.ExtendedKeyUsage, critical, eku);
	}

	/**
	 * Helper.
	 **/
	public void addExtendedKeyUsage(String oid)  throws CertificateEncodingException {
		addExtendedKeyUsage(new DERObjectIdentifier(oid));
	}

	/**
	 * Removes a key usage from the Extended Key Usage
	 * extension.  If the certificate does not have an EKU extension, or the
	 * usage is not present therein, the function returns false (nothing to
	 * remove). If it actually removed the usage, the function returns true.
	 * @param purpose the OID of the desired key usage to remove
	 */
	public boolean removeExtendedKeyUsage(DERObjectIdentifier purpose) 
	throws CertificateEncodingException {

		if (!hasExtension(X509Extensions.ExtendedKeyUsage)) {
			return false;
		}

		X509Extension ext = getExtension(X509Extensions.ExtendedKeyUsage);
		DERObject decoded = CryptoUtil.decode(ext.getValue().getOctets());
		if (!hasExtendedKeyUsage((ASN1Sequence)decoded, purpose)) {
			// done, return
			return false;
		}
		// Have to make a copy of the sequence containing everything but the
		// one to remove.
		ASN1EncodableVector newPurposes = new ASN1EncodableVector();
		Enumeration oldPurposes = ((ASN1Sequence)decoded).getObjects();
		DERObjectIdentifier oldPurpose = null;
		while (oldPurposes.hasMoreElements()) {
			oldPurpose = (DERObjectIdentifier)oldPurposes.nextElement();
			if (!oldPurpose.equals(purpose)) {
				newPurposes.add(oldPurpose);
			}
		}

		updateExtension(X509Extensions.ExtendedKeyUsage, ext.isCritical(), new DERSequence(newPurposes));
		return true;
	}

	public boolean removeExtendedKeyUsage(String purpose) throws CertificateEncodingException {
		return removeExtendedKeyUsage(new DERObjectIdentifier(purpose));
	}    	

	/**
	 * Tests whether this certificate is enabled for a
	 * given Extended Key Usage.
	 *
	 * @param purpose the OID of the desired usage to test
	 */
	public boolean hasExtendedKeyUsage(DERObjectIdentifier purpose) 
	throws CertificateEncodingException {
		X509Extension ext = getExtension(X509Extensions.ExtendedKeyUsage);
		if (null == ext) {
			return false;
		}
		DERObject decoded = CryptoUtil.decode(ext.getValue().getOctets());
		return hasExtendedKeyUsage((ASN1Sequence)decoded, purpose);
	}

	public boolean hasExtendedKeyUsage(String purpose) throws CertificateEncodingException {
		return hasExtendedKeyUsage(new DERObjectIdentifier(purpose));
	}

	/**
	 * Hack around the fact that BouncyCastle's universe only allows a fixed set of usages...
	 **/
	protected static boolean hasExtendedKeyUsage(ASN1Sequence usages, 
			DERObjectIdentifier usage) {
		Enumeration e = usages.getObjects();
		while (e.hasMoreElements()) {
			if (e.nextElement().equals(usage))
				return true;
		}
		return false;
	}

	/**
	 * Both adds the server authentication OID to the EKU
	 * extension, and adds the DNS name to the subject alt name
	 * extension (not marked critical). (Combines addServerAuthenticationEKU and
	 * addDNSNameSubjectAltName).
	 * @throws java.security.cert.CertificateEncodingException if the DNS name
	 * is not a DNS name
	 */
	public void setServerAuthenticationUsage(String serverDNSName)
	throws java.security.cert.CertificateEncodingException {

		addDNSNameSubjectAltName(false, serverDNSName);
		addServerAuthenticationEKU();
	}

	/**
	 * Adds server authentication as a usage for this
	 * certificate. Should also add DNS name to subjectAltName, to do both
	 * use setServerAuthenticationUsage.
	 * NOTE: Bouncy Castle OIDs for key purposes are WRONG.
	 */
	public void addServerAuthenticationEKU() {
		try {
			addExtendedKeyUsage(id_kp_serverAuth);
		} catch (CertificateEncodingException cex) {
			System.out.println(
					"This should not happen, IO encoding exception in addServerAuthenticationEKU");
			cex.printStackTrace();
		}
	}

	/**
	 * Adds client authentication as a usage for this
	 * certificate.
	 */
	public void addClientAuthenticationEKU() {
		try {
			addExtendedKeyUsage(id_kp_clientAuth);
		} catch (CertificateEncodingException cex) {
			System.out.println(
					"This should not happen, IO encoding exception in addClientAuthenticationEKU");
			cex.printStackTrace();
		}
	}

	/**
	 * Both adds the secure email OID to the EKU
	 * extension, and adds the email address to the subject alt name
	 * extension (not marked critical). (Combines addSecureEmailEKU and addEmailSubjectAltName).
	 * @throws java.security.cert.CertificateEncodingException if the email address
	 * is not an email address
	 */
	public void setSecureEmailUsage(String subjectEmailAddress)
	throws java.security.cert.CertificateEncodingException {
		addEmailSubjectAltName(false, subjectEmailAddress);
		addSecureEmailEKU();
	}

	/**
	 * Adds secure email as a usage for this
	 * certificate. Should also add email address to subjectAltName;
	 * done as a whole with setSecureEmailUsage.
	 */
	public void addSecureEmailEKU() {
		try {
			addExtendedKeyUsage(id_kp_emailProtection);
		} catch (CertificateEncodingException cex) {
			System.out.println(
					"This should not happen, IO encoding exception in addClientAuthenticationEKU");
			cex.printStackTrace();
		}
	}

	public void addSubjectAlternativeName(boolean critical, Integer type, String value) throws CertificateEncodingException {

		if(type.equals(IPADDRESSAlternativeNameType)) {
			addIPAddressSubjectAltName(critical, value);
		} else if(type.equals(DNSNAMEAlternativeNameType)) {
			addDNSNameSubjectAltName(critical, value);
		} if(type.equals(RFC822AlternativeNameType)) {
			addEmailSubjectAltName(critical, value);
		}
	}

	/**
	 * Adds email address to subjectAltName
	 **/
	public void addEmailSubjectAltName(boolean critical, String subjectEmailAddress) 
	throws CertificateEncodingException {
		ASN1GeneralName name = ASN1GeneralName.fromEmailAddress(subjectEmailAddress);
		addSubjectAltName(critical, name);
	}


	/**
	 * Adds DNS name to subjectAltName
	 **/
	public void addDNSNameSubjectAltName(boolean critical, String serverDNSName) 
	throws CertificateEncodingException {
		ASN1GeneralName name = ASN1GeneralName.fromDNSName(serverDNSName);
		addSubjectAltName(critical, name);
	}

	/**
	 * Adds ip address to subjectAltName.
	 * @param ipAddress string form of the IP address. Assumed to be in either
	 * IPv4 form, "n.n.n.n", with 0<=n<256, orIPv6 form, 
	 * "n.n.n.n.n.n.n.n", where the n's are the HEXADECIMAL form of the
	 * 16-bit address components.
	 **/
	public void addIPAddressSubjectAltName(boolean critical, String ipAddress) 
	throws CertificateEncodingException {
		ASN1GeneralName name = ASN1GeneralName.fromIPAddress(ipAddress);
		addSubjectAltName(critical, name);
	}

	/**
	 * Adds a SubjectAltName extension; if there is already one
	 * present, adds an additional name to that extension.
	 * Currently, if it's a double, just adds in a second value (can have more than one
	 * name of a particular type in a GeneralNames). Doesn't remove duplicates.
	 * @param critical critical value for extension. Overrides critical value of preexisting
	 * extension, if any.
	 * @throws java.security.cert.CertificateEncodingException if the name is
	 * not a properly formatted general name
	 */
	public void addSubjectAltName(boolean critical, ASN1GeneralName name)
	throws CertificateEncodingException {

		X509Extension oldExt = getExtension(X509Extensions.SubjectAlternativeName);
		GeneralNames genNames = null;

		if (null != oldExt) {
			GeneralNames oldGenNames = new GeneralNames((ASN1Sequence)
					CryptoUtil.decode(oldExt.getValue().getOctets()));
			Enumeration oldNames = ((ASN1Sequence)oldGenNames.getDERObject()).getObjects();
			ASN1EncodableVector newNames = new ASN1EncodableVector();
			while (oldNames.hasMoreElements()) {
				newNames.add((DERTaggedObject) oldNames.nextElement());
			}
			newNames.add(name);
			genNames = new GeneralNames(new DERSequence(newNames));

		} else {
			genNames = new GeneralNames(new DERSequence(name));
		}
		updateExtension(X509Extensions.SubjectAlternativeName, critical, genNames);
	}

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
	public void addBasicConstraints(boolean critical,
			boolean isCA,
			int pathLenConstraint) {

		BasicConstraints bc = null;
		if (isCA && (pathLenConstraint >= 0)) {
			bc = new BasicConstraints(pathLenConstraint);
		} else {
			bc = new BasicConstraints(isCA);
		}

		try {
			addExtension(id_ce_basicConstraints, critical, bc);
		} catch (CertificateEncodingException cex) {
			System.out.println(
					"This should not happen, certificate encoding exception in addBasicConstraints");
			cex.printStackTrace();
		}
	}

	/**
	 * Adds a basic constraints extension to this
	 * certificate. If one is already present, its value is replaced.
	 * Omits the optional pathLenConstraint.
	 *
	 * @param critical mark this extension critical
	 * @param isCA is this a CA cert
	 */
	public void addBasicConstraints(boolean critical,
			boolean isCA) {
		this.addBasicConstraints(critical, isCA, -1);
	}

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
	public boolean isCACertificate() {
		ASN1OctetString encoded = getExtensionValue(X509Extensions.BasicConstraints);
		if (null == encoded)
			return false;
		BasicConstraints bc = null;
		try {
			bc = BasicConstraints.getInstance(CryptoUtil.decode(encoded.getOctets()));
		} catch (CertificateEncodingException cex) {
			System.out.println(
					"This should not happen, certificate encoding exception decoding BasicConstraints");
			cex.printStackTrace();
		}
		if (null == bc)
			return false;
		return bc.isCA();
	}

	/**
	 * Check an aspect of the basic constraint of the
	 * underlying certificate (enhances function of getBasicConstraints provided
	 * by superclass). If this is a CA certificate, returns the path len
	 * constraint in its basic constraints extension. (Equivalent to
	 * X509Certificate.getBasicConstraints, with a more intuitive name.)
	 */
	public int getPathLenConstraint() {
		ASN1OctetString encoded = getExtensionValue(X509Extensions.BasicConstraints);
		if (null == encoded)
			return -1;
		BasicConstraints bc = null;
		try {
			bc = BasicConstraints.getInstance(CryptoUtil.decode(encoded.getOctets()));
		} catch (CertificateEncodingException cex) {
			System.out.println(
					"This should not happen, certificate encoding exception decoding BasicConstraints");
			cex.printStackTrace();
		}
		if (null == bc)
			return -1;
		return bc.getPathLenConstraint().intValue();
	}

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
	public void addKeyUsage(boolean critical, boolean [] bits) throws CertificateEncodingException {
		if ((null == bits) || (bits.length != NUM_KEY_USAGE_BITS)) {
			throw new CertificateEncodingException("Wrong number of bits presented to addKeyUsage: got: " +
					((null == bits) ? 0: bits.length) +
					" expected: " + NUM_KEY_USAGE_BITS);
		}
		// BouncyCastle's KeyUsage object does not encode the bit string properly --
		// it always encodes them as 9 bits with 7 pad bits, regardless of where the 
		// highest set bit is.
		final int BITS_IN_BYTE = 8;
		byte lowByte = 0;
		byte highByte = 0;
		int highBit = 0;
		for (int i=0; i < NUM_KEY_USAGE_BITS - 1; ++i) {
			if (bits[i]) {
				lowByte |= 1 << (BITS_IN_BYTE - i - 1);
				highBit = i;
			}
		}
		if (bits[NUM_KEY_USAGE_BITS-1]) {
			highByte |= 1 << (BITS_IN_BYTE - 1);
			highBit = NUM_KEY_USAGE_BITS-1;
		}

		byte [] usage = new byte[(highByte == 0) ? 1 : 2];
		usage[0] = lowByte;
		if (highByte != 0)
			usage[1] = highByte;
		// one byte only. Padding bits is BITS_IN_BYTE - highBit - 1
		DERBitString keyUsage = 
			new DERBitString(usage, BITS_IN_BYTE - (highBit %BITS_IN_BYTE) - 1);

		updateExtension(X509Extensions.KeyUsage, critical, keyUsage);
	}

	/**
	 * Helper function to make it easier to add a CRL distribution point extension.
	 * Expects an array of URLs, each corresponding to a single DistributionPoint in the extension.
	 * Will strip leading URI: from strings if present.
	 * If urls is null or length 0, does nothing.
	 **/
	public void addCRLDistributionPointsExtension(boolean critical, String [] urls) 
	throws CertificateEncodingException {
		if ((null == urls) || (urls.length == 0)) {
			return;
		}

		DistributionPoint [] points = new DistributionPoint[urls.length];
		ASN1GeneralName name = null;
		DistributionPointName dpName = null;
		GeneralNames genName = null;
		for (int i=0; i < urls.length; ++i) {
			if (urls[i].startsWith("URI:")) {
				name = ASN1GeneralName.fromURI(urls[i].substring(4, urls[i].length()));
			} else {
				name = ASN1GeneralName.fromURI(urls[i]);
			}
			genName = new GeneralNames(new DERSequence(name));
			dpName = new DistributionPointName(DistributionPointName.FULL_NAME,
					genName);
			points[i] = new DistributionPoint(dpName, null, null);
		}

		addCRLDistributionPointsExtension(critical, points);
	}

	/**
	 * Add a CRL distribution points extension if one not present, otherwise replaces it
	 * (one could build incremental behavior by getting the current value, decoding it,
	 * and then creating a new list with the additional points). 
	 * @param critical the criticality of the extension
	 * Either the distPointFullNames or the crlIssuer MUST be non-null, but not both.
	 * We and BouncyCastle provide code to make it easy to use full DNs as distribution
	 * points; it should be possible to extend BC's DistributionPointName for better
	 * support for RDNs.
	 **/
	public void addCRLDistributionPointsExtension(boolean critical, DistributionPoint [] points) 
	throws CertificateEncodingException {

		if ((null == points) || (points.length == 0)) {
			// no points, don't add extension
			return;
		}		

		CRLDistPoint crlDistPoint = new CRLDistPoint(points);
		updateExtension(X509Extensions.CRLDistributionPoints, critical, crlDistPoint);
	}

	/**
	 * Adds a subjectKeyIdentifier extension to the certificate. Uses the SHA-1 digest
	 * of the DER-encoded SubjectPublicKeyInfo as the KeyID.
	 * 
	 * This extension takes a critical flag argument for consistency's sake, but according
	 * to rfc 2459, it MUST NOT be marked critical.
	 * 
	 * For now, if the extension is already present, this function replaces it.
	 **/
	public void addSubjectKeyIdentifierExtension(boolean critical)
	throws CertificateEncodingException {

		byte [] keyID = generateKeyID(getSubjectPublicKey());

		SubjectKeyIdentifier ski = new SubjectKeyIdentifier(keyID);

		updateExtension(X509Extensions.SubjectKeyIdentifier, critical, ski);
	}

	/**
	 * Takes a critical argument, even though rfc 2459 says that this extension MUST NOT
	 * be marked critical.
	 * If the extension is already present in the certificate, replaces its contents with those indicated
	 * here. Otherwise adds the extension.
	 * 
	 * @param issuerKeyID if not null, adds the issuer key ID to the extension. This must
	 * match the subjectKeyID found in the issuer's certificate if present, otherwise a new
	 * one can be generated using one of several algorithms. See the static helper functions
	 * present in this class.
	 * @param issuerName if not null, adds the issuer name to the extension. 
	 * @param issuerSerialNumber if not null, adds the issuer serial number to the extension.
	 * It makes little sense to include just one of name and serial number, typically both or
	 * neither will be added.
	 **/
	public void addAuthorityKeyIdentifierExtension(boolean critical,
			byte [] issuerKeyID,
			GeneralNames issuerName, // CN=...
			BigInteger issuerSerialNumber)
	throws CertificateEncodingException {

		// we don't need to check whether it's there already or not, simply replace it.
		AuthorityKeyIdentifier aki = new AuthorityKeyIdentifier(issuerKeyID,
				issuerName,
				issuerSerialNumber);
		updateExtension(X509Extensions.AuthorityKeyIdentifier, critical, aki);
	}							

	/**
	 * Helper versions
	 **/
	public void addAuthorityKeyIdentifierExtension(boolean critical, byte [] issuerKeyID) 
	throws CertificateEncodingException {
		addAuthorityKeyIdentifierExtension(critical, issuerKeyID, (GeneralNames)null, null);
	}

	public void addAuthorityKeyIdentifierExtension(boolean critical,
			byte [] issuerKeyID,
			String issuerName,	// CN=...
			BigInteger issuerSerialNumber) 
	throws CertificateEncodingException {
		ASN1GeneralName issuer = null;
		if (issuerName != null) {
			issuer = ASN1GeneralName.fromDirectoryName(issuerName);
		}

		AuthorityKeyIdentifier aki = new AuthorityKeyIdentifier(issuerKeyID, issuer,
				issuerSerialNumber);
		updateExtension(X509Extensions.AuthorityKeyIdentifier, critical, aki);
	}		

	/**
	 * Sets the lifetime of the certificate. The
	 * notBefore time is set to NOW, the notAfter time is set to NOW + duration.
	 *
	 * @param duration in milliseconds
	 */
	public void setDuration(long duration) {
		setDuration(new Date(), duration);
	}

	/**
	 * Sets the lifetime of the certificate. The
	 * notBefore time is set to startTime, the notAfter time is set to
	 * startTime + duration.
	 *
	 * @param startTime
	 * @param duration in milliseconds
	 */
	public void setDuration(Date startTime, long duration) {

		if (null == startTime) {
			startTime = new Date();
		}
		Date stopTime = new Date(startTime.getTime() + duration);

		setNotBefore(startTime);
		setNotAfter(stopTime);
	}

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
	public static X509Certificate
	GenerateX509Certificate(PublicKey publicKey,
			String issuer,
			String subject,
			BigInteger serialNumber,
			Date notBefore,
			Date notAfter,
			X509ExtensionHelper [] extensions,
			PrivateKey signingKey,
			String hashAlgorithm)
	throws InvalidKeyException,
	NoSuchAlgorithmException,
	InvalidParameterSpecException,
	InvalidAlgorithmParameterException,
	CertificateEncodingException
	{
		BCX509CertificateGenerator generator =
			new BCX509CertificateGenerator(publicKey, issuer, subject,
					serialNumber, notBefore, notAfter,
					extensions);

		return generator.sign(hashAlgorithm, signingKey);
	}


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
	 *  @throws java.security.InvalidKeyException
	 * @throws java.security.cert.CertificateEncodingException if one of the
	 *  extensions cannot be encoded properly
	 * @throws java.security.NoSuchAlgorithmException if the hash algorithm is
	 * not a hash algorithm, or cannot be used with this key type
	 */
	public static X509Certificate
	GenerateX509Certificate(PublicKey publicKey,
			String issuer,
			String subject,
			BigInteger serialNumber,
			long duration,
			X509ExtensionHelper [] extensions,
			PrivateKey signingKey,
			String hashAlgorithm)
	throws InvalidKeyException,
	NoSuchAlgorithmException, InvalidParameterSpecException, 
	InvalidAlgorithmParameterException, CertificateEncodingException
	{
		BCX509CertificateGenerator generator =
			new BCX509CertificateGenerator(publicKey, issuer, subject,
					serialNumber,
					extensions);
		generator.setDuration(duration);
		return generator.sign(hashAlgorithm, signingKey);
	}


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
	public static X509Certificate
	GenerateX509Certificate(KeyPair keyPair,
			String subject,
			BigInteger serialNumber,
			Date notBefore,
			Date notAfter,
			X509ExtensionHelper [] extensions,
			String hashAlgorithm)
	throws InvalidKeyException,
	NoSuchAlgorithmException,
	InvalidAlgorithmParameterException,
	InvalidParameterSpecException,
	CertificateEncodingException
	{
		BCX509CertificateGenerator generator =
			new BCX509CertificateGenerator(keyPair.getPublic(),
					subject, subject,
					serialNumber,
					notBefore, notAfter,
					extensions);
		return generator.sign(hashAlgorithm, keyPair.getPrivate());
	}

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
	public static X509Certificate
	GenerateX509Certificate(KeyPair keyPair,
			String subject,
			BigInteger serialNumber,
			long duration,
			X509ExtensionHelper [] extensions,
			String hashAlgorithm)
	throws InvalidKeyException,
	NoSuchAlgorithmException,
	InvalidAlgorithmParameterException,
	InvalidParameterSpecException, CertificateEncodingException
	{
		BCX509CertificateGenerator generator =
			new BCX509CertificateGenerator(keyPair.getPublic(),
					subject, subject,
					serialNumber,
					extensions);
		generator.setDuration(duration);
		return generator.sign(hashAlgorithm, keyPair.getPrivate());
	}

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
	public static X509Certificate
	GenerateX509Certificate(KeyPair keyPair,
			String subject,
			long duration)
	throws InvalidKeyException,
	NoSuchAlgorithmException,
	InvalidParameterSpecException,
	InvalidAlgorithmParameterException,
	CertificateEncodingException {
		X509Certificate newCert = null;
		newCert = GenerateX509Certificate(keyPair, subject, null,
				duration, null, null);
		return newCert;
	}

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
	public static X509Certificate
	GenerateX509Certificate(KeyPair keyPair,
			String subject,
			Date notBefore, 
			Date notAfter) 
	throws InvalidParameterSpecException, InvalidKeyException, 
	NoSuchAlgorithmException, InvalidAlgorithmParameterException,
	CertificateEncodingException {
		X509Certificate newCert = null;
		newCert = GenerateX509Certificate(keyPair, subject, null,
				notBefore, notAfter, null, null);
		return newCert;
	}


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
	 *  	will already add BasicConstraints (necessary for a CA to the certificate), an
	 * 	appropriate keyUsage, and subjectKeyIdentifier. Use this
	 * 	argument to add items like IssuerKeyId, CRLDistributionPoints, etc, if desired.
	 * @param hashAlgorithm the hash algorithm to be used. If null, SHA-1 is used.
	 * Expects standard Java string identifiers for algorithms.
	 *
	 * @throws java.security.InvalidKeyException
	 * @throws java.security.cert.CertificateEncodingException if one of the
	 *  extensions cannot be encoded properly
	 * @throws java.security.NoSuchAlgorithmException if the hash algorithm is
	 * not a hash algorithm, or cannot be used with this key type
	 */
	public static X509Certificate
	GenerateRootCertificate(KeyPair keyPair,
			String subject,
			BigInteger serialNumber,
			Date notBefore, 
			Date notAfter,
			int pathLen,
			X509ExtensionHelper [] extensions,
			String hashAlgorithm)
	throws InvalidKeyException,
	NoSuchAlgorithmException,
	InvalidAlgorithmParameterException,
	InvalidParameterSpecException, CertificateEncodingException
	{
		BCX509CertificateGenerator generator =
			new BCX509CertificateGenerator(keyPair.getPublic(),
					subject, subject,
					serialNumber,
					notBefore, notAfter,
					extensions);
		generator.addBasicConstraints(true, true, pathLen);
		boolean [] keyUsageBits = new boolean[] {true, true, false, false, false, true, true, false, false};
		generator.addKeyUsage(false, keyUsageBits);
		generator.addSubjectKeyIdentifierExtension(false);
		return generator.sign(hashAlgorithm, keyPair.getPrivate());
	}

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
	public static X509Certificate
	GenerateRootCertificate(KeyPair keyPair,
			String subject,
			BigInteger serialNumber,
			Date notBefore, 
			Date notAfter)
	throws InvalidKeyException,
	NoSuchAlgorithmException,
	InvalidAlgorithmParameterException,
	InvalidParameterSpecException, CertificateEncodingException
	{
		BCX509CertificateGenerator generator =
			new BCX509CertificateGenerator(keyPair.getPublic(),
					subject, subject,
					serialNumber,
					notBefore, notAfter, null);
		generator.addBasicConstraints(true, true, 0);
		boolean [] keyUsageBits = new boolean[] {true, true, false, false, false, true, true, false, false};
		generator.addKeyUsage(false, keyUsageBits);
		generator.addSubjectKeyIdentifierExtension(false);
		return generator.sign(null, keyPair.getPrivate());
	}

	/**
	 * Perform a certificate renewal, re-using the public key, subject
	 * and issuer DNs and extensions used in the original certificate.
	 * Be sure to pass in serialNumber as null or something
	 * different -- DO NOT issue two certificates for the
	 * same issuer with the same serial number.
	 * @param origCert
	 * @param subjectPublicKey set to null if you want to reuse from old cert
	 * @param issuerSigningKey
	 * @param notBefore
	 * @param notAfter
	 * @param serialNumber
	 * @param hashAlgorithm
	 * @throws CertificateEncodingException
	 */
	public static X509Certificate GenerateRenewal(
			X509Certificate origCert,
			PublicKey subjectPublicKey,
			PrivateKey issuerSigningKey,
			Date notBefore,
			Date notAfter,
			BigInteger serialNumber,
			String hashAlgorithm)
	throws CertificateEncodingException, IOException,
	NoSuchAlgorithmException, InvalidAlgorithmParameterException,
	InvalidKeyException, InvalidParameterSpecException
	{
		// First we convert the input Java certificate into a bouncy castle
		// certificate.
		ByteArrayInputStream certInputStream =
			new ByteArrayInputStream(origCert.getEncoded());
		ASN1StreamParser ain = new ASN1StreamParser(certInputStream);
		DEREncodable der = ain.readObject();
		X509CertificateStructure structure = 
			new X509CertificateStructure((DERSequence)der.getDERObject());

		// Now we can pull apart bits and pieces from the cert structure to
		// create a new one just like it.
		if (subjectPublicKey == null) {
			subjectPublicKey = origCert.getPublicKey();
		}
		X509Name issuer = structure.getTBSCertificate().getIssuer();
		X509Name subject  = structure.getTBSCertificate().getSubject();

		X509Extensions exts = structure.getTBSCertificate().getExtensions();

		BCX509CertificateGenerator gen =
			new BCX509CertificateGenerator(subjectPublicKey, issuer, subject,
					serialNumber, exts);
		if (notBefore == null) {
			notBefore = new Date();
		}
		gen.setNotBefore(notBefore);
		gen.setNotAfter(notAfter);
		return gen.sign(hashAlgorithm, issuerSigningKey);
	}

	/**
	 * Get the keyID from a CA certificate to use as the key id in an AuthorityKeyIdentifier
	 * extension for certificates issued by that CA. This should come out of the SubjectKeyIdentifier
	 * extension of the certificate if present. If that extension is missing, this function
	 * will return null, and generateKeyID can be used to generate a new key id.
	 **/
	public static byte [] getKeyIDFromCertificate(X509Certificate issuerCert) 
	throws IOException, CertificateEncodingException {
		byte [] keyIDExtensionValue = issuerCert.getExtensionValue(id_ce_subjectKeyIdentifier);
		if (null == keyIDExtensionValue)
			return null;
		// extension should decode to an OCTET STRING containing the key id.
		DERObject decodedValue = CryptoUtil.decode(keyIDExtensionValue);
		if (!(decodedValue instanceof ASN1OctetString)) {
			throw new CertificateEncodingException("Cannot parse SubjectKeyIdentifier extension!");
		}
		// now decode the inner octet string to get key ID
		DERObject keyID = CryptoUtil.decode(((ASN1OctetString)decodedValue).getOctets());
		if (!(keyID instanceof ASN1OctetString)) {
			throw new CertificateEncodingException("Cannot parse SubjectKeyIdentifier extension!");
		}
		return ((ASN1OctetString)keyID).getOctets();
	}

	/**
	 * Generates a CertID -- the SHA-1 digest of the DER encoding
	 * of a java.security.cert.Certificate
	 */
	public static byte [] generateCertID(Certificate cert)  
	throws CertificateEncodingException {
		final String DIGEST_ALG = "SHA-1";
		byte [] id = null;
		try {
			byte [] encoding = cert.getEncoded();
			id = Digest(DIGEST_ALG, encoding);
		} catch (java.security.NoSuchAlgorithmException ex) {
			// DKS --big configuration problem
			throw new RuntimeException("Error: can't find " + DIGEST_ALG + "!  " + ex.toString());
		}
		return id;
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
		if (debug) {
			System.out.println(
					"X509CertificateGenerator: getSignatureAlgorithm, hash: " +
					hashAlgorithm + " key alg: " + signingKey.getAlgorithm());
		}
		String signatureAlgorithmOID = getSignatureAlgorithmOID(
				hashAlgorithm, signingKey.getAlgorithm());

		if (signatureAlgorithmOID == null) {
			if (debug) {
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
		if (debug) {
			System.out.println("getSignatureName: combining " +
					hashAlgorithm  + " and " + keyAlgorithm +
					" results in: " + signatureAlgorithm);
		}
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
		if (debug) {
			System.out.println("getSignatureAlgorithmOID: combining " +
					hashAlgorithm  + " and " + keyAlgorithm +
					" results in: " + signatureAlgorithm);
		}
		return signatureAlgorithm;
	}

	static public boolean hasExtendedKeyUsage(X509Certificate cert, String usage) 
	throws CertificateParsingException {

		List ekus = cert.getExtendedKeyUsage();
		if (null == ekus)
			return false;
		Iterator it = ekus.iterator();
		String thisEKU = null;
		while (it.hasNext()) {
			thisEKU = (String)it.next();
			if (usage.equals(thisEKU))
				return true;
		}
		return false;
	}

	static public boolean hasKeyUsage(X509Certificate cert, int usageBit) {

		boolean [] keyUsageBits = cert.getKeyUsage();
		if (keyUsageBits[usageBit])
			return true;
		return false;
	}

	public PublicKey getSubjectPublicKey() {
		return _publicKey;
	}

	protected void setSubjectPublicKey(PublicKey key) throws CertificateEncodingException {
		try {
			_subjectPublicKeyInfo = 
				new SubjectPublicKeyInfo(
						(DERSequence)new ASN1InputStream(
								new ByteArrayInputStream(key.getEncoded())).readObject());
		} catch (IOException e) {
			throw new CertificateEncodingException("Cannot encode public key: " +
					e.getMessage());
		}
		_publicKey = key;
	}

	public X509Name getIssuerDN() {
		return _issuerDN;
	}

	protected void setIssuerDN(X509Name issuer) {
		_issuerDN = issuer;
	}

	protected void setIssuerDN(String issuer) {
		setIssuerDN(new X509Name(issuer));
	}

	public X509Name getSubjectDN() {
		return _subjectDN;
	}

	public void setSubjectDN(X509Name subject) {
		_subjectDN = subject;
	}

	public void setSubjectDN(String subject) {
		setSubjectDN(new X509Name(subject));
	}

	public BigInteger getSerialNumber() {
		return _serialNumber;
	}

	public void setSerialNumber(BigInteger serial) {
		_serialNumber = serial;
	}

	public void setNotBefore(Date  date) {
		_startDate = date;
	}

	public void setNotAfter(Date date) {
		_endDate = date;
	}

	public Date getNotBefore() { return _startDate; }
	public Date getNotAfter() { return _endDate; }
}
