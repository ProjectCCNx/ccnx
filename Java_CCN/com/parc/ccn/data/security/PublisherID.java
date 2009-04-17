package com.parc.ccn.data.security;

import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.TreeMap;

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
 * Helper wrapper class for publisher IDs.
 * @author smetters
 *
 */
public class PublisherID extends GenericXMLEncodable implements XMLEncodable, Comparable<PublisherID> {

	public static final String PUBLISHER_ID_DIGEST_ALGORITHM = "SHA-256";
    public static final int PUBLISHER_ID_LEN = 256/8;
    public enum PublisherType {KEY, CERTIFICATE, ISSUER_KEY, ISSUER_CERTIFICATE};

    protected static final HashMap<PublisherType, String> TypeNames = new HashMap<PublisherType, String>();
    protected static final HashMap<String, PublisherType> NameTypes = new HashMap<String, PublisherType>();
    
    public static final String PUBLISHER_ID_ELEMENT = "PublisherID";
    protected static final String PUBLISHER_TYPE_ATTRIBUTE = "type";
    
    static {
        TypeNames.put(PublisherType.KEY, "KEY");
        TypeNames.put(PublisherType.CERTIFICATE, "CERTIFICATE");
        TypeNames.put(PublisherType.ISSUER_KEY, "ISSUER_KEY");
        TypeNames.put(PublisherType.ISSUER_CERTIFICATE, "ISSUER_CERTIFICATE");
        NameTypes.put("KEY", PublisherType.KEY);
        NameTypes.put("CERTIFICATE", PublisherType.CERTIFICATE);
        NameTypes.put("ISSUER_KEY", PublisherType.ISSUER_KEY);
        NameTypes.put("ISSUER_CERTIFICATE", PublisherType.ISSUER_CERTIFICATE);
    }

    protected byte [] _publisherID;
    protected PublisherType _publisherType;
    
    public PublisherID(PublicKey key, boolean isIssuer) {
    	_publisherID = generatePublicKeyDigest(key);
    	_publisherType = isIssuer ? PublisherType.ISSUER_KEY : PublisherType.KEY;
    }
    
    public PublisherID(X509Certificate cert, boolean isIssuer) throws CertificateEncodingException {
    	_publisherID = generateCertificateID(cert);
    	_publisherType = isIssuer ? PublisherType.ISSUER_CERTIFICATE : PublisherType.CERTIFICATE;
    }
	
	public PublisherID(byte [] publisherID, PublisherType publisherType) {
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

	public void decode(XMLDecoder decoder) throws XMLStreamException {
		
		// The format of a publisher ID is:
		// <PublisherID type=<type>>id content</PublisherID>
		TreeMap<String,String> attributes = new TreeMap<String,String>();

		_publisherID = decoder.readBinaryElement(PUBLISHER_ID_ELEMENT, attributes);
		if (null == _publisherID) {
			throw new XMLStreamException("Cannot parse publisher ID.");
		}
		// Don't check number of attributes -- binary encoding attr may have been added.
		if (!attributes.containsKey(PUBLISHER_TYPE_ATTRIBUTE)) {
			throw new XMLStreamException("Cannot parse publisher ID: did not get expected attribute: " + PUBLISHER_TYPE_ATTRIBUTE);
		}
		_publisherType = nameToType(attributes.get(PUBLISHER_TYPE_ATTRIBUTE));
		if (null == _publisherType) {
			throw new XMLStreamException("Cannot parse publisher ID: unknown publisher type: " + attributes.get(PUBLISHER_TYPE_ATTRIBUTE));
		}
	}

	public void encode(XMLEncoder encoder) throws XMLStreamException {
		if (!validate()) {
			throw new XMLStreamException("Cannot encode " + this.getClass().getName() + ": field values missing.");
		}
		// The format of a publisher ID is:
		// <PublisherID type=<type> id_content />
		TreeMap<String,String> attributes = new TreeMap<String,String>();
		attributes.put(PUBLISHER_TYPE_ATTRIBUTE,typeToName(type()));
		
		encoder.writeElement(PUBLISHER_ID_ELEMENT, id(),
								attributes);
	}
	
	public boolean validate() {
		return ((null != id() && (null != type())));
	}

	public static byte [] generateCertificateID(X509Certificate cert) throws CertificateEncodingException {
		try {
			return generateCertificateID(PUBLISHER_ID_DIGEST_ALGORITHM, cert);
		} catch (NoSuchAlgorithmException e) {
			// DKS --big configuration problem
			Library.logger().warning("Fatal Error: cannot find default algorithm " + PUBLISHER_ID_DIGEST_ALGORITHM);
			throw new RuntimeException("Error: can't find default algorithm " + PUBLISHER_ID_DIGEST_ALGORITHM + "!  " + e.toString());
		}
	}
	
    public static byte [] generateCertificateID(String digestAlg, X509Certificate cert) 
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
