package com.parc.ccn.data.security;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.HashMap;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

import com.parc.ccn.data.content.XMLEncodable;
import com.parc.ccn.data.content.XMLHelper;

/**
 * Helper wrapper class for publisher IDs.
 * @author smetters
 *
 */
public class PublisherID implements XMLEncodable {

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
    }

    protected byte [] _publisherID;
    protected PublisherType _publisherType;
	
	public PublisherID(byte [] publisherID, PublisherType publisherType) {
		_publisherID = Arrays.copyOf(publisherID, PUBLISHER_ID_LEN);
		_publisherType = publisherType;
	}	
	
	PublisherID() {} // for use by decoders
	
	public byte [] id() { return _publisherID; }
	public PublisherType type() { return _publisherType; }

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

	public static String typeToName(PublisherType type) {
		return TypeNames.get(type);
	}

	public static PublisherType nameToType(String name) {
		return NameTypes.get(name);
	}

	public void decode(InputStream iStream) throws XMLStreamException {
		XMLEventReader reader = XMLHelper.beginDecoding(iStream);
		decode(reader);
	}

	public void decode(XMLEventReader reader) throws XMLStreamException {
		XMLHelper.readStartElement(reader, PUBLISHER_ID_ELEMENT);
		XMLHelper.readStartElement(reader, PUBLISHER_TYPE_ELEMENT);
		String strType = reader.getElementText();
		_publisherType = nameToType(strType);
		if (null == _publisherType) {
			throw new XMLStreamException("Cannot parse publisher type: " + strType);
		}
		XMLHelper.readEndElement(reader);
		
		XMLHelper.readStartElement(reader, PUBLISHER_ID_ID_ELEMENT);
		String strID = reader.getElementText();
		try {
			_publisherID = XMLHelper.decodeElement(strID);
		} catch (IOException e) {
			throw new XMLStreamException("Cannot parse publisher ID: " + strID, e);
		}
		if (null == _publisherID) {
			throw new XMLStreamException("Cannot parse publisher ID: " + strID);
		}
		XMLHelper.readEndElement(reader);
		
		XMLHelper.readEndElement(reader);
	}

	public void encode(OutputStream oStream) throws XMLStreamException {
		XMLStreamWriter writer = XMLHelper.beginEncoding(oStream);
		encode(writer);
		XMLHelper.endEncoding(writer);	
	}

	public void encode(XMLStreamWriter writer) throws XMLStreamException {
		writer.writeStartElement(PUBLISHER_ID_ELEMENT);
		writer.writeStartElement(PUBLISHER_TYPE_ELEMENT);
		writer.writeCharacters(typeToName(type()));
		writer.writeEndElement();   
		writer.writeStartElement(PUBLISHER_ID_ID_ELEMENT);
		writer.writeCharacters(XMLHelper.encodeElement(id()));
		writer.writeEndElement();   		
		writer.writeEndElement();   		
	}
}
