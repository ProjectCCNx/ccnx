package com.parc.ccn.data.security;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.HashMap;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

import com.parc.ccn.data.util.XMLEncodable;
import com.parc.ccn.data.util.XMLHelper;

public class ContentAuthenticator implements XMLEncodable {

    public enum ContentType {FRAGMENT, LINK, CONTAINER, LEAF, SESSION};
    protected static final HashMap<ContentType, String> ContentTypeNames = new HashMap<ContentType, String>();
    protected static final HashMap<String, ContentType> ContentNameTypes = new HashMap<String, ContentType>();
    public static final String CONTENT_AUTHENTICATOR_ELEMENT = "ContentAuthenticator";
    protected static final String TIMESTAMP_ELEMENT = "Timestamp";
    protected static final String CONTENT_TYPE_ELEMENT = "Type";
    protected static final String CONTENT_HASH_ELEMENT = "ContentHash";
    protected static final String SIGNATURE_ELEMENT = "Signature";
    
    static {
        ContentTypeNames.put(ContentType.FRAGMENT, "FRAGMENT");
        ContentTypeNames.put(ContentType.LINK, "LINK");
        ContentTypeNames.put(ContentType.CONTAINER, "CONTAINER");
        ContentTypeNames.put(ContentType.LEAF, "LEAF");
        ContentTypeNames.put(ContentType.SESSION, "SESSION");
        ContentNameTypes.put("FRAGMENT", ContentType.FRAGMENT);
        ContentNameTypes.put("LINK", ContentType.LINK);
        ContentNameTypes.put("CONTAINER", ContentType.CONTAINER);
        ContentNameTypes.put("LEAF", ContentType.LEAF);
        ContentNameTypes.put("SESSION", ContentType.SESSION);
    }
    
    protected PublisherID	_publisher;
    // int   		_version; // Java types are signed, must cope
    protected Timestamp		_timestamp;
    protected ContentType 	_type;
    // long	  	_size; // signed, must cope
    protected byte []		_contentHash; // encoded DigestInfo
    protected KeyLocator  	_keyLocator;
    protected byte[]		_signature; // might want to use Signature type

    public ContentAuthenticator(
    		byte[] publisher,
    		PublisherID.PublisherType publisherType,
    		Timestamp timestamp, 
    		ContentType type, 
    		byte[] hash, 
    		KeyLocator locator, 
    		byte[] signature) {
		this(new PublisherID(publisher, publisherType), 
				timestamp, type, hash,
				locator, signature);
	}
    
    public ContentAuthenticator(
    		PublisherID publisher, 
			Timestamp timestamp, 
			ContentType type, 
			byte[] hash, 
			KeyLocator locator, 
			byte[] signature) {
    	super();
    	this._publisher = publisher;
    	this._timestamp = timestamp;
    	this._type = type;
    	_contentHash = hash;
    	_keyLocator = locator;
    	this._signature = signature;
    }

    public ContentAuthenticator() {}
    
    public boolean empty() {
    	return (emptyPublisher() && emptySignature() && emptyContentHash());
    }
    
    public boolean emptyPublisher() {
    	if ((null != publisherID()) && (0 != publisher().length))
    		return false;
    	return true;
    }
    
    public boolean emptySignature() {
       	if ((null != signature()) && (0 != signature().length))
    		return false;
       	return true;
    }
    
    public boolean emptyContentHash() {
    	if ((null != contentHash()) && (0 != contentHash().length))
    		return false;
    	return true;   	
    }
    
	public byte[] contentHash() {
		return _contentHash;
	}
	public void contentHash(byte[] hash) {
		_contentHash = hash;
	}
	public KeyLocator keyLocator() {
		return _keyLocator;
	}
	public void keyLocator(KeyLocator locator) {
		_keyLocator = locator;
	}
	public byte[] publisher() {
		return _publisher.id();
	}
	public PublisherID.PublisherType publisherType() {
		return _publisher.type();
	}
	public PublisherID publisherID() {
		return _publisher;
	}
	public void publisher(byte[] publisher, PublisherID.PublisherType publisherType) {
		this._publisher = new PublisherID(publisher, publisherType);
	}
	public byte[] signature() {
		return _signature;
	}
	public void signature(byte[] signature) {
		this._signature = signature;
	}
	public Timestamp timestamp() {
		return _timestamp;
	}
	public void timestamp(Timestamp timestamp) {
		this._timestamp = timestamp;
	}
	public ContentType type() {
		return _type;
	}
	public void type(ContentType type) {
		this._type = type;
	}
	
	public String typeName() {
		return typeToName(type());
	}
	
	public static String typeToName(ContentType type) {
		return ContentTypeNames.get(type);
	}

	public static ContentType nameToType(String name) {
		return ContentNameTypes.get(name);
	}
	
	@Override
	public int hashCode() {
		final int PRIME = 31;
		int result = 1;
		result = PRIME * result + Arrays.hashCode(_contentHash);
		result = PRIME * result + ((_keyLocator == null) ? 0 : _keyLocator.hashCode());
		result = PRIME * result + ((_publisher == null) ? 0 : _publisher.hashCode());;
		result = PRIME * result + Arrays.hashCode(_signature);
		result = PRIME * result + ((_timestamp == null) ? 0 : _timestamp.hashCode());
		result = PRIME * result + ((_type == null) ? 0 : _type.hashCode());
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
		final ContentAuthenticator other = (ContentAuthenticator) obj;
		if (!Arrays.equals(_contentHash, other._contentHash))
			return false;
		if (_keyLocator == null) {
			if (other._keyLocator != null)
				return false;
		} else if (!_keyLocator.equals(other._keyLocator))
			return false;
		if (!_publisher.equals(other._publisher))
			return false;
		if (!Arrays.equals(_signature, other._signature))
			return false;
		if (_timestamp == null) {
			if (other._timestamp != null)
				return false;
		} else if (!_timestamp.equals(other._timestamp))
			return false;
		if (_type == null) {
			if (other.type() != null)
				return false;
		} else if (!_type.equals(other.type()))
			return false;
		return true;
	}

	public void decode(InputStream iStream) throws XMLStreamException {
		XMLEventReader reader = XMLHelper.beginDecoding(iStream);
		decode(reader);
	}

	public void decode(XMLEventReader reader) throws XMLStreamException {
		XMLHelper.readStartElement(reader, CONTENT_AUTHENTICATOR_ELEMENT);
		
		if (XMLHelper.peekStartElement(reader, TIMESTAMP_ELEMENT)) {
			XMLHelper.readStartElement(reader, TIMESTAMP_ELEMENT);
			String strTimestamp = reader.getElementText();
			_timestamp = Timestamp.valueOf(strTimestamp);
			if (null == _timestamp) {
				throw new XMLStreamException("Cannot parse timestamp: " + strTimestamp);
			}
			XMLHelper.readEndElement(reader);
		}

		XMLHelper.readStartElement(reader, CONTENT_TYPE_ELEMENT);
		String strType = reader.getElementText();
		_type = nameToType(strType);
		if (null == _type) {
			throw new XMLStreamException("Cannot parse authenticator type: " + strType);
		}
		XMLHelper.readEndElement(reader);
		
		if (XMLHelper.peekStartElement(reader, CONTENT_HASH_ELEMENT)) {
			XMLHelper.readStartElement(reader, CONTENT_HASH_ELEMENT);
			String strHash = reader.getElementText();
			try {
				_contentHash = XMLHelper.decodeElement(strHash);
			} catch (IOException e) {
				throw new XMLStreamException("Cannot parse content hash: " + strHash, e);
			}
			if (null == _contentHash) {
				throw new XMLStreamException("Cannot parse content hash: " + strHash);
			}
			XMLHelper.readEndElement(reader);
		}
		
		if (XMLHelper.peekStartElement(reader, KeyLocator.KEY_LOCATOR_ELEMENT)) {
			_keyLocator = new KeyLocator();
			_keyLocator.decode(reader);
		}
		
		if (XMLHelper.peekStartElement(reader, SIGNATURE_ELEMENT)) {
			XMLHelper.readStartElement(reader, SIGNATURE_ELEMENT);
			String strSig = reader.getElementText();
			try {
				_signature = XMLHelper.decodeElement(strSig);
			} catch (IOException e) {
				throw new XMLStreamException("Cannot parse signature: " + strSig, e);
			}
			if (null == _contentHash) {
				throw new XMLStreamException("Cannot parse signature: " + strSig);
			}
			XMLHelper.readEndElement(reader);
		}
		
		XMLHelper.readEndElement(reader);
	}

	public void encode(OutputStream oStream) throws XMLStreamException {
		XMLStreamWriter writer = XMLHelper.beginEncoding(oStream);
		encode(writer);
		XMLHelper.endEncoding(writer);	
	}

	public void encode(XMLStreamWriter writer) throws XMLStreamException {
		writer.writeStartElement(CONTENT_AUTHENTICATOR_ELEMENT);
		if (!emptyPublisher()) {
			publisherID().encode(writer);
		}
		if (null != timestamp()) {
			writer.writeStartElement(TIMESTAMP_ELEMENT);
			writer.writeCharacters(timestamp().toString());
			writer.writeEndElement();
		}
		writer.writeStartElement(CONTENT_TYPE_ELEMENT);
		writer.writeCharacters(typeToName(type()));
		writer.writeEndElement();   
		if (!emptyContentHash()) {
			writer.writeStartElement(CONTENT_HASH_ELEMENT);
			writer.writeCharacters(XMLHelper.encodeElement(contentHash()));
			writer.writeEndElement();   
		}
		if (null != keyLocator()) {
			keyLocator().encode(writer);
		}
		if (!emptySignature()) {
			writer.writeStartElement(SIGNATURE_ELEMENT);
			writer.writeCharacters(XMLHelper.encodeElement(signature()));
			writer.writeEndElement();   
		}
		writer.writeEndElement();   		
	}

}
