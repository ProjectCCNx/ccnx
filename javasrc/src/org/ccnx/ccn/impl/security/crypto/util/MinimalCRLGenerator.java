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

import java.math.BigInteger;
import java.security.InvalidKeyException;
import java.security.InvalidParameterException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PrivateKey;
import java.security.SignatureException;
import java.security.cert.CRLException;
import java.security.cert.X509CRL;
import java.util.Date;

import org.bouncycastle.asn1.x509.X509Name;
import org.bouncycastle.x509.X509V2CRLGenerator;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

/**
  * Helper wrapper around BouncyCastle's CRL support. BouncyCastle's CRL support
 * is a bit dodgy; as it relies on the verifier being able to inherit algorithm parameters (e.g. DSA params)
 * for the signer key, if any, from the CA certificate.
 */
public class MinimalCRLGenerator {
	
	/**
	 * Reason codes.
	 **/
	public static final int REASON_UNSPECIFIED 		= 0;
	public static final int REASON_KEY_COMPROMISE 	= 1;
	public static final int REASON_CA_COMPROMISE	= 2;
	public static final int REASON_AFFILIATION_CHANGED = 4;
	public static final int REASON_SUPERSEDED 		= 5;
	public static final int REASON_CESSATION_OF_OPERATION = 6;
	public static final int REASON_CERTIFICATE_HOLD = 7;
	public static final int REASON_REMOVE_FROM_CRL = 8;
	public static final String[] REASONS = {"unspecified", "key_compromise",
						"ca_compromise", "extra_space", "affiliation_changed",
						"superseded", "cessation_of_operation", "certificate_hold",
						"remove_from_crl"};
	public static final int REASON_CODES[] = {REASON_UNSPECIFIED, REASON_KEY_COMPROMISE,
			REASON_CA_COMPROMISE, REASON_AFFILIATION_CHANGED, REASON_SUPERSEDED,
			REASON_CESSATION_OF_OPERATION, REASON_CERTIFICATE_HOLD, REASON_REMOVE_FROM_CRL
			};
	
	/**
	 * One month (avg), in milliseconds.
	 */
	public static final int DEFAULT_DURATION = (int)(1000 * 60 * 60 * 24 * 365.25/12);
	
    protected static final String DEFAULT_HASH = "SHA1";
	
	protected X509V2CRLGenerator _crlGenerator = new X509V2CRLGenerator();
	// Local copies of useful fields
	protected Date _thisUpdate = null;
	protected Date _nextUpdate = null;

	/**
	 * Constructor for X509CRLGenerator.
	 * @param issuerName Issuer's name in X.500 format (C=US,...)
	 * @param thisUpdate date of issuance of this CRL. If null, filled in with "now".
	 * @param nextUpdate date of expiration of this CRL.
	 * @param extensions currently pass through a BouncyCastle X509Extensions object to constructor.
	 * 	No easy way to map this back into java X509Extensions....
	 */
	public MinimalCRLGenerator(String issuerName, Date thisUpdate, Date nextUpdate) {
		this(new X509Name(issuerName), thisUpdate, nextUpdate);
	}

	public MinimalCRLGenerator(X509Name issuerName, Date thisUpdate, Date nextUpdate) {

		super();
		_crlGenerator.setIssuerDN(issuerName);
		if (null == thisUpdate) {
			thisUpdate = new Date();
		}
		_thisUpdate = thisUpdate;
		_crlGenerator.setThisUpdate(thisUpdate);
		_nextUpdate = nextUpdate;
		_crlGenerator.setNextUpdate(nextUpdate);
	}

	/**
	 * Same as above, only sets thisUpdate to now and nextUpdate to now+duration.
	 * @param duration length of validity in milliseconds. If <= 0, 
	 * 	defaults to DEFAULT_DURATION.
	 **/
	public MinimalCRLGenerator(String issuerName, long duration) {
		this(new X509Name(issuerName), duration);
	}
	
	public MinimalCRLGenerator(X509Name issuerName, long duration) {

		super();
		_crlGenerator.setIssuerDN(issuerName);
		_thisUpdate = new Date();
		if (duration <= 0)
			duration = DEFAULT_DURATION;
		_nextUpdate = new Date(_thisUpdate.getTime() + duration);

		_crlGenerator.setThisUpdate(_thisUpdate);
		_crlGenerator.setNextUpdate(_nextUpdate);
	}
	
	/**
	 * Add an extension to the CRL.
	 **/
	public void addExtension(String oid, boolean critical, byte [] encodedValue) {
		_crlGenerator.addExtension(oid, critical, encodedValue);
	}
	
	/**
	 * Reason codes listed above.
	 **/
	public void addRevokedCertificate(BigInteger serialNumber, Date revocationTime, int reason) {
		if (revocationTime == null)
			revocationTime = _thisUpdate;
		_crlGenerator.addCRLEntry(serialNumber, revocationTime, reason);	
	}
	
	/**
	 * Add a certificate to a CRL. 
	 * @param serialNumber
	 * @param revocationTime
	 * @param reason must be one of the entries in the REASONS array, or null or "" for
	 * 		REASON_UNSPECIFIED.
	 */
	public void addRevokedCertificate(BigInteger serialNumber, Date revocationTime, String reason) 
			throws InvalidParameterException {
		// maps a null reason into REASON_UNSPECIFIED
		int reason_code = reasonToReasonCode(reason);
		if (reason_code < 0)
			throw new InvalidParameterException("Unknown reason code: " + reason);
		
		addRevokedCertificate(serialNumber, revocationTime, reason_code);
	}
	
	public static int reasonToReasonCode(String reason) {
		if ((null == reason) || (reason.equals("")))
			return REASON_UNSPECIFIED;
		
		for (int i=0; i < REASONS.length; i++) {
			if (REASONS[i].equalsIgnoreCase(reason))
				return REASON_CODES[i];
		}
		return -1;
	}
	
	public static String reasonCodeToReason(int code) {
		for (int i=0; i < REASON_CODES.length; i++) {
			if (REASON_CODES[i] == code)
				return REASONS[i];
		}
		return null;
	}	
	
	/**
	 * If the digestAlgorithm is null, SHA-1 is used. 
	 * @return the DER-encoded signed CRL.
	 **/
	public X509CRL sign(String hashAlgorithm, PrivateKey signingKey, String provider) 
				throws InvalidKeyException, SignatureException, NoSuchProviderException, CRLException, IllegalStateException, NoSuchAlgorithmException {
		
        String sigAlgName =
            OIDLookup.getSignatureAlgorithm(((null == hashAlgorithm) || (hashAlgorithm.length() == 0)) ?
                                    								DEFAULT_HASH : hashAlgorithm,
								                                   signingKey.getAlgorithm());


        System.out.println("Signature algorithm: " + sigAlgName + " provider: " + provider);
		_crlGenerator.setSignatureAlgorithm(sigAlgName);
		if (null == provider) {
			provider = BouncyCastleProvider.PROVIDER_NAME;
		}
		return _crlGenerator.generate(signingKey, provider);
	}
}
