package com.parc.ccn.data.security;

import java.io.IOException;
import java.security.PublicKey;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.HashMap;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

import com.parc.ccn.Library;
import com.parc.ccn.data.util.GenericXMLEncodable;
import com.parc.ccn.data.util.XMLEncodable;
import com.parc.ccn.data.util.XMLHelper;
import com.parc.ccn.security.crypto.Digest;
import com.parc.ccn.security.crypto.certificates.GenericX509CertificateGenerator;

/**
 * Helper wrapper class for publisher IDs.
 * @author smetters
 *
 */
public class PublisherID extends GenericXMLEncodable implements XMLEncodable {

	public static final String PUBLISHER_ID_DIGEST_ALGORITHM = "SHA256";
    public static final int PUBLISHER_ID_LEN = 256/8;
    public enum PublisherType {KEY, CERTIFICATE, ISSUER_KEY, ISSUER_CERTIFICATE};

    protected static final HashMap<PublisherType, String> TypeNames = new HashMap<PublisherType, String>();
    protected static final HashMap<String, PublisherType> NameTypes = new HashMap<String, PublisherType>();
    
    protected static final String PUBLISHER_ID_ELEMENT = "PublisherID";
    protected static final String PUBLISHER_TYPE_ELEMENT = "Type";
    protected static final String PUBLISHER_ID_ID_ELEMENT = "ID";
    
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
    	_publisherID = generateID(key);
    	_publisherType = isIssuer ? PublisherType.ISSUER_KEY : PublisherType.KEY;
    }
    
    public PublisherID(X509Certificate cert, boolean isIssuer) throws CertificateEncodingException {
    	_publisherID = generateCertificateID(cert);
    	_publisherType = isIssuer ? PublisherType.ISSUER_CERTIFICATE : PublisherType.CERTIFICATE;
    }
	
	public PublisherID(byte [] publisherID, PublisherType publisherType) {
		_publisherID = Arrays.copyOf(publisherID, PUBLISHER_ID_LEN);
		_publisherType = publisherType;
	}	
	
    public PublisherID(byte [] encoded) throws XMLStreamException {
    	super(encoded);
    }

    public PublisherID() {} // for use by decoders
	
	public byte [] id() { return _publisherID; }
	public PublisherType type() { return _publisherType; }
	
	public static byte [] generateID(PublicKey key) {
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

	public void decode(XMLEventReader reader) throws XMLStreamException {
		XMLHelper.readStartElement(reader, PUBLISHER_ID_ELEMENT);

		String strType = XMLHelper.readElementText(reader, PUBLISHER_TYPE_ELEMENT);
		_publisherType = nameToType(strType);
		if (null == _publisherType) {
			throw new XMLStreamException("Cannot parse publisher type: " + strType);
		}
		
		String strID = XMLHelper.readElementText(reader, PUBLISHER_ID_ID_ELEMENT);
		try {
			_publisherID = XMLHelper.decodeElement(strID);
		} catch (IOException e) {
			throw new XMLStreamException("Cannot parse publisher ID: " + strID, e);
		}
		if (null == _publisherID) {
			throw new XMLStreamException("Cannot parse publisher ID: " + strID);
		}
		
		XMLHelper.readEndElement(reader);
	}

	public void encode(XMLStreamWriter writer, boolean isFirstElement) throws XMLStreamException {
		if (!validate()) {
			throw new XMLStreamException("Cannot encode " + this.getClass().getName() + ": field values missing.");
		}
		XMLHelper.writeStartElement(writer, PUBLISHER_ID_ELEMENT, isFirstElement);
		XMLHelper.writeElement(writer, PUBLISHER_TYPE_ELEMENT, typeToName(type()));
		XMLHelper.writeElement(writer, PUBLISHER_ID_ID_ELEMENT, XMLHelper.encodeElement(id()));
		writer.writeEndElement();   		
	}
	
	public boolean validate() {
		return ((null != id() && (null != type())));
	}

	public static byte [] generateCertificateID(X509Certificate cert) throws CertificateEncodingException {
		return generateCertificateID(PUBLISHER_ID_DIGEST_ALGORITHM, cert);
	}
	
    public static byte [] generateCertificateID(String digestAlg, X509Certificate cert) throws CertificateEncodingException  {
    	
        byte [] id = null;
        try {
            byte [] encoding = cert.getEncoded();
            id = Digest.hash(digestAlg, encoding);
        } catch (CertificateEncodingException e) {
			Library.logger().warning("Cannot encode certificate in PublisherID.generateCertificateID: " + e.getMessage());
			Library.warningStackTrace(e);
			throw e;
		}
        return id;
    }

}
