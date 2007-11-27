package com.parc.ccn.security.crypto.certificates;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;

import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.DERSet;
import org.bouncycastle.asn1.DEREncodable;
import org.bouncycastle.asn1.DERInteger;
import org.bouncycastle.asn1.DERObjectIdentifier;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.DERTaggedObject;
import org.bouncycastle.asn1.pkcs.ContentInfo;
import org.bouncycastle.asn1.pkcs.SignedData;


/**
 * @author D.K. Smetters
 *
 * Helper class to build PKCS#7s that the browsers will like.
 * </pre>
 * If the data embedded in a SignedData instance shall be
 * verified then this data must be retrieved by means of the
 * {@link #getData getData} method first and must be passed
 * to one of the update methods just as the detached data in
 * the example above. Remember that only the {@link Data Data}
 * content type is supported at the moment.<p>
 *
 * Likewise, if data shall be signed and attached to a SignedData
 * instance then the signing process of that data must be completed
 * as for detached data. The signed data then can be attached to
 * the SignedData instance by means of the {@link #setData setData}
 * method.
 *
 * The definition of this structure is:
 * <blockquote><pre>
 * SignedData ::= SEQUENCE {
 *   version Version,
 *   digestAlgorithms DigestAlgorithmIdentifiers,
 *   contentInfo ContentInfo,
 *   certificates
 *     [0] IMPLICIT ExtendedCertificatesAndCertificates OPTIONAL,
 *   crls
 *     [1] IMPLICIT CertificateRevocationLists OPTIONAL,
 *   signerInfos SignerInfos
 * }
 * DigestAlgorithmIdentifiers ::= SET OF DigestAlgorithmIdentifier
 *
 * SignerInfos ::= SET OF SignerInfo
 * </pre></blockquote>
 **/
public class PKCS7Builder {
	
	protected static final DERObjectIdentifier oid_id_Data = 
							new DERObjectIdentifier("1.2.840.113549.1.7.1");

	protected static final DERObjectIdentifier oid_id_signedData = 
							new DERObjectIdentifier("1.2.840.113549.1.7.2");
							
	protected static final int tag_Certificates = 0;
	protected static final int tag_CRLs = 0;

	private ArrayList<X509Certificate> certificateContents = new ArrayList<X509Certificate>();

	/**
	 * Constructor for PKCS7Builder.
	 */
	public PKCS7Builder() {
		super();
	}
		
	public void addCertificate(X509Certificate cert) {
		certificateContents.add(cert);
	}
	
	/**
	 * Returns a PKCS#7 Data object containing these certificates concatenated
	 * together. A Data object is just an OCTET STRING containing the data.
	 * */
	public DEROctetString getAsData() throws CertificateEncodingException {
		return new DEROctetString(getEncodedCertificates());
	}
	
	/**
	 * Returns a PKCS#7 SignedData object containing these certificates.
	 * Note that this SignedData has no signers, and is unsigned. The standard SignedData
	 * functions can be used to add them and sign, or it can be encoded as is to
	 * get an unsigned SignedData.
	 * */
	public ContentInfo getAsPKCS7() throws CertificateEncodingException {
		
		// BouncyCastle makes it difficult to build a SignedData. Need to make a full
		// Sequence containing all the components, and build them together. God
		// only knows how to get the tagging to work out...
		// A signed data of the form used to contain certs contains: 
		// version number = 1
		// digest algorithms: an empty SET
		// contentinfo  - an empty contentInfo of type Data
		// an implicity tagged batch of certificates (0) or crls (1)
		// an empty SET of signer info
		DERInteger version = new DERInteger(BigInteger.valueOf(1));
		DERSet digestAlgs = new DERSet(); //empty
		ContentInfo contentInfo = new ContentInfo(oid_id_Data, null);
		DERSet signerInfos = new DERSet();//empty
		
		ASN1EncodableVector signedDataContentsBuilder = new ASN1EncodableVector();
		signedDataContentsBuilder.add(version);
		signedDataContentsBuilder.add(digestAlgs);
		signedDataContentsBuilder.add(contentInfo);

		// Now add a tagged type, tag of 0 IMPLICIT, content
		// ExtendedCertificateAndCertificates, which is really effectively
		// all the certs concatenated together. Do a SET. (didn't work, try a SEQUENCE)
		ASN1EncodableVector certSetBuilder = new ASN1EncodableVector();
		if (certificateContents.size() > 0) {
			DEREncodable decodedCert = null;
			for (int i=0; i < certificateContents.size(); ++i) {
				decodedCert = CryptoUtil.decode(((X509Certificate)certificateContents.get(i)).getEncoded());
				certSetBuilder.add(decodedCert);
			}
			DERSet certSet = new DERSet(certSetBuilder);
			DERTaggedObject taggedCerts = new
						DERTaggedObject(false, tag_Certificates, certSet);
			signedDataContentsBuilder.add(taggedCerts);
		}
		signedDataContentsBuilder.add(signerInfos);
		DERSequence signedDataContents = new DERSequence(signedDataContentsBuilder);
		
		SignedData signedData = new SignedData(signedDataContents);
		ContentInfo pkcs7 = new ContentInfo(oid_id_signedData, signedData);
		return pkcs7;
	}
		
	/**
	 * We want as the octet string in the content the set of certificates encoded and
	 * concatenated together, with no additional structure. 
	 * */
	private byte [] getEncodedCertificates() throws CertificateEncodingException {
		if (certificateContents.size() == 0) {
			return new byte[0]; // hope the encoder code copes
		}
		
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		X509Certificate thisCert = null;
		byte [] thisEncodedCert = null;
		for (int i=0; i < certificateContents.size(); ++i) {
			thisCert = (X509Certificate) certificateContents.get(i);
			thisEncodedCert = thisCert.getEncoded();
			baos.write(thisEncodedCert, 0, thisEncodedCert.length);
		}
		return baos.toByteArray();
	}

}
