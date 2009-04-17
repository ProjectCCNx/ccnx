package com.parc.ccn.data.security;

import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.HashMap;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.Library;
import com.parc.ccn.data.util.DataUtils;
import com.parc.ccn.data.util.GenericXMLEncodable;
import com.parc.ccn.data.util.XMLDecoder;
import com.parc.ccn.data.util.XMLEncodable;
import com.parc.ccn.data.util.XMLEncoder;
import com.parc.ccn.security.crypto.CCNDigestHelper;
import com.parc.security.crypto.certificates.GenericX509CertificateGenerator;

/**
 * Helper wrapper class for publisher IDs. This encodes and decodes
 * as one of 4 inline options, one of which also appears separately
 * as the PublisherPublicKeyDigest.
 * @author smetters
 *
 */
public class PublisherID extends GenericXMLEncodable implements XMLEncodable, Comparable<PublisherID> {

	public static final String PUBLISHER_ID_DIGEST_ALGORITHM = "SHA-256";
    public static final int PUBLISHER_ID_LEN = 256/8;
    public enum PublisherType {KEY, CERTIFICATE, ISSUER_KEY, ISSUER_CERTIFICATE};

    protected static final HashMap<PublisherType, String> TypeNames = new HashMap<PublisherType, String>();
    protected static final HashMap<String, PublisherType> NameTypes = new HashMap<String, PublisherType>();
    
    public static final String PUBLISHER_CERTIFICATE_DIGEST_ELEMENT = "PublisherCertificateDigest";
    public static final String PUBLISHER_ISSUER_KEY_DIGEST = "PublisherIssuerKeyDigest";
    public static final String PUBLISHER_ISSUER_CERTFICIATE_DIGEST = "PublisherIssuerCertificateDigest";
    
    static {
        TypeNames.put(PublisherType.KEY, PublisherPublicKeyDigest.PUBLISHER_PUBLIC_KEY_DIGEST_ELEMENT);
        TypeNames.put(PublisherType.CERTIFICATE, PUBLISHER_CERTIFICATE_DIGEST_ELEMENT);
        TypeNames.put(PublisherType.ISSUER_KEY, PUBLISHER_ISSUER_KEY_DIGEST);
        TypeNames.put(PublisherType.ISSUER_CERTIFICATE, PUBLISHER_ISSUER_CERTFICIATE_DIGEST);
        NameTypes.put(PublisherPublicKeyDigest.PUBLISHER_PUBLIC_KEY_DIGEST_ELEMENT, PublisherType.KEY);
        NameTypes.put(PUBLISHER_CERTIFICATE_DIGEST_ELEMENT, PublisherType.CERTIFICATE);
        NameTypes.put(PUBLISHER_ISSUER_KEY_DIGEST, PublisherType.ISSUER_KEY);
        NameTypes.put(PUBLISHER_ISSUER_CERTFICIATE_DIGEST, PublisherType.ISSUER_CERTIFICATE);
    }

    protected byte [] _publisherID;
    protected PublisherType _publisherType;
    
    public PublisherID(PublicKey key, boolean isIssuer) {
    	_publisherID = generatePublicKeyDigest(key);
    	_publisherType = isIssuer ? PublisherType.ISSUER_KEY : PublisherType.KEY;
    }
    
    public PublisherID(X509Certificate cert, boolean isIssuer) throws CertificateEncodingException {
    	_publisherID = generateCertificateDigest(cert);
    	_publisherType = isIssuer ? PublisherType.ISSUER_CERTIFICATE : PublisherType.CERTIFICATE;
    }
	
	public PublisherID(byte [] publisherID, PublisherType publisherType) {
		if ((null == publisherID) || (publisherID.length != PUBLISHER_ID_LEN)) {
			throw new IllegalArgumentException("Invalid publisherID!");
		}
		// Alas, Arrays.copyOf doesn't exist in 1.5, and we'd like
		// to be mostly 1.5 compatible for the macs...
		// _publisherPublicKeyDigest = Arrays.copyOf(publisherID, PUBLISHER_ID_LEN);
		_publisherID = new byte[PUBLISHER_ID_LEN];
		System.arraycopy(publisherID, 0, _publisherID, 0, publisherID.length);
		_publisherType = publisherType;
	}
	
	public PublisherID(PublisherPublicKeyDigest keyID) {
		this(keyID.digest(), PublisherType.KEY);
	}
	
    public PublisherID() {} // for use by decoders
	
	public byte [] id() { return _publisherID; }
	public PublisherType type() { return _publisherType; }
	
	public static byte [] generatePublicKeyDigest(PublicKey key) {
		return GenericX509CertificateGenerator.generateKeyID(PUBLISHER_ID_DIGEST_ALGORITHM, key);
	}

	@Override
	public int hashCode() {
		final int PRIME = 31;
		int result = 1;
		result = PRIME * result + Arrays.hashCode(_publisherID);
		result = PRIME * result + ((_publisherType == null) ? 0 : _publisherType.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (PublisherPublicKeyDigest.class == obj.getClass()) {
			if (PublisherType.KEY == this.type())
				return (Arrays.equals(_publisherID, ((PublisherPublicKeyDigest)obj).digest()));
			// TODO DKS fill in...
			throw new UnsupportedOperationException("Have to finish up equals!");			
		}
		if (getClass() != obj.getClass())
			return false;
		final PublisherID other = (PublisherID) obj;
		if (!Arrays.equals(_publisherID, other._publisherID))
			return false;
		if (_publisherType == null) {
			if (other.type() != null)
				return false;
		} else if (!_publisherType.equals(other.type()))
			return false;
		return true;
	}
		
	public boolean isSigner() {
		return ((PublisherType.KEY == type()) || (PublisherType.CERTIFICATE == type()));
	}
	
	public boolean isCertifier() {
		return ((PublisherType.ISSUER_CERTIFICATE == type()) || (PublisherType.ISSUER_KEY == type()));
	}

	public static String typeToName(PublisherType type) {
		return TypeNames.get(type);
	}

	public static PublisherType nameToType(String name) {
		return NameTypes.get(name);
	}
	
	public static boolean isPublisherType(String name) {
		return NameTypes.containsKey(name);
	}
	
	/**
	 * This is a choice. Make it possible for users of this class to peek it
	 * when it might be optional, without them having to know about the structure.
	 */
	public static boolean peek(XMLDecoder decoder) throws XMLStreamException {
		String nextTag = decoder.peekStartElement();
		return (null != nameToType(nextTag));
	}

	public void decode(XMLDecoder decoder) throws XMLStreamException {
		
		// We have a choice here of one of 4 binary element types.
		String nextTag = decoder.peekStartElement();
		
		if (null == nextTag) {
			throw new XMLStreamException("Cannot parse publisher ID.");
		} 
		
		_publisherType = nameToType(nextTag); 
		
		if (null == _publisherType) {
			throw new XMLStreamException("Invalid publisher ID, got unexpected type: " + nextTag);
		}
		_publisherID = decoder.readBinaryElement(nextTag);
		if (null == _publisherID) {
			throw new XMLStreamException("Cannot parse publisher ID of type : " + nextTag + ".");
		}
	}

	public void encode(XMLEncoder encoder) throws XMLStreamException {
		if (!validate()) {
			throw new XMLStreamException("Cannot encode " + this.getClass().getName() + ": field values missing.");
		}
		// The format of a publisher ID is a choice, a binary element tagged with
		// one of the 4 publisher types.
		
		encoder.writeElement(typeToName(type()), id());
	}
	
	public boolean validate() {
		return ((null != id() && (null != type())));
	}

	public static byte [] generateCertificateDigest(X509Certificate cert) throws CertificateEncodingException {
		try {
			return generateCertificateDigest(PUBLISHER_ID_DIGEST_ALGORITHM, cert);
		} catch (NoSuchAlgorithmException e) {
			// DKS --big configuration problem
			Library.logger().warning("Fatal Error: cannot find default algorithm " + PUBLISHER_ID_DIGEST_ALGORITHM);
			throw new RuntimeException("Error: can't find default algorithm " + PUBLISHER_ID_DIGEST_ALGORITHM + "!  " + e.toString());
		}
	}
	
    public static byte [] generateCertificateDigest(String digestAlg, X509Certificate cert) 
    							throws CertificateEncodingException, NoSuchAlgorithmException  {
    	
        byte [] id = null;
        try {
            byte [] encoding = cert.getEncoded();
            id = CCNDigestHelper.digest(digestAlg, encoding);
        } catch (CertificateEncodingException e) {
			Library.logger().warning("Cannot encode certificate in PublisherID.generateCertificateID: " + e.getMessage());
			Library.warningStackTrace(e);
			throw e;
		}
        return id;
    }

 	public int compareTo(PublisherID o) {
		int result = DataUtils.compare(this.id(), o.id());
		if (0 == result) {
			result = typeToName(this.type()).compareTo(typeToName(o.type()));
		}
		return result;
	}

	@Override
	public String toString() {
		// 	16 would be the most familiar option, but 32 is shorter
		return typeToName(type()) + ":" + CCNDigestHelper.printBytes(id(), 32);
	}
}
