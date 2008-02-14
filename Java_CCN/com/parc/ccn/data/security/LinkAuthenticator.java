package com.parc.ccn.data.security;

import java.io.IOException;
import java.sql.Timestamp;
import java.util.Arrays;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

import com.parc.ccn.data.util.GenericXMLEncodable;
import com.parc.ccn.data.util.XMLEncodable;
import com.parc.ccn.data.util.XMLHelper;

public class LinkAuthenticator extends GenericXMLEncodable implements XMLEncodable {

    public static final String LINK_AUTHENTICATOR_ELEMENT = "LinkAuthenticator";
    protected static final String NAME_COMPONENT_COUNT_ELEMENT = "NameComponentCount";
    protected static final String TIMESTAMP_ELEMENT = "Timestamp";
    protected static final String CONTENT_TYPE_ELEMENT = "Type";
    protected static final String CONTENT_HASH_ELEMENT = "ContentHash";
   
    protected PublisherID	_publisher = null;
    protected Integer 		_nameComponentCount = null; // how many name components are signed
    									 // if omitted, assume all
    protected Timestamp		_timestamp = null;
    protected ContentAuthenticator.ContentType 	_type = null;
    protected byte []		_contentDigest = null; // encoded DigestInfo
    
    public LinkAuthenticator(
    		PublisherID publisher, 
    		Integer nameLength,
			Timestamp timestamp, 
			ContentAuthenticator.ContentType type, 
       		byte [] contentDigest) {
    	super();
    	this._publisher = publisher;
    	this._nameComponentCount = nameLength;
    	this._timestamp = timestamp;
    	this._type = type;
    	this._contentDigest = contentDigest;
    }
    	    
    public LinkAuthenticator(PublisherID publisher) {
    	super();
    	this._publisher = publisher;
    }

    public LinkAuthenticator(byte [] encoded) throws XMLStreamException {
    	super(encoded);
    }

    public LinkAuthenticator() {}
    
    public boolean empty() {
    	return (emptyPublisher() && 
    			emptyNameComponentCount() && 
    			emptyTimestamp() &&
    			emptyContentType() &&
    			emptyContentDigest());
    }
    
    public boolean emptyPublisher() {
    	if ((null != publisherID()) && (0 != publisher().length))
    		return false;
    	return true;
    }
    
    public boolean emptyContentDigest() {
    	if ((null != contentDigest()) && (0 != contentDigest().length))
    		return false;
    	return true;   	
    }
    
    public boolean emptyContentType() { 
    	return (null == _type);
    }
    
    public boolean emptyNameComponentCount() {
    	return (null == _nameComponentCount);
    }
    
    public boolean emptyTimestamp() {
    	return (null == _timestamp);
    }
    
	public byte[] contentDigest() {
		return _contentDigest;
	}
	
	public void contentDigest(byte[] hash) {
		_contentDigest = hash;
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

	public Timestamp timestamp() {
		return _timestamp;
	}
	public void timestamp(Timestamp timestamp) {
		this._timestamp = timestamp;
	}
	
	public ContentAuthenticator.ContentType type() {
		return _type;
	}
	public void type(ContentAuthenticator.ContentType type) {
		this._type = type;
	}
	
	public Integer nameComponentCount() { return _nameComponentCount; }
	public void nameComponentCount(Integer nameComponentCount) {
		this._nameComponentCount = nameComponentCount;
	}
		
	@Override
	public int hashCode() {
		final int PRIME = 31;
		int result = 1;
		result = PRIME * result + Arrays.hashCode(_contentDigest);
		result = PRIME * result + ((_publisher == null) ? 0 : _publisher.hashCode());;
		result = PRIME * result + ((_nameComponentCount == null) ? 0 : _nameComponentCount.hashCode());;
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
		final LinkAuthenticator other = (LinkAuthenticator) obj;
		if (_contentDigest == null) {
			if (other._contentDigest != null)
				return false;
		} else if (!Arrays.equals(_contentDigest, other._contentDigest))
			return false;
		if (_publisher == null) {
			if (other._publisher != null)
				return false;
		} else if (!_publisher.equals(other._publisher))
			return false;
		if (_nameComponentCount == null) {
			if (other._nameComponentCount != null)
				return false;
		} else if (!_nameComponentCount.equals(other._nameComponentCount))
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

	public void decode(XMLEventReader reader) throws XMLStreamException {
		XMLHelper.readStartElement(reader, LINK_AUTHENTICATOR_ELEMENT);
		
		if (XMLHelper.peekStartElement(reader, PublisherID.PUBLISHER_ID_ELEMENT)) {
			_publisher = new PublisherID();
			_publisher.decode(reader);
		}

		if (XMLHelper.peekStartElement(reader, NAME_COMPONENT_COUNT_ELEMENT)) {
			String strLength = XMLHelper.readElementText(reader, NAME_COMPONENT_COUNT_ELEMENT); 
			_nameComponentCount = Integer.valueOf(strLength);
			if (null == _nameComponentCount) {
				throw new XMLStreamException("Cannot parse name length: " + strLength);
			}
		}
			
		if (XMLHelper.peekStartElement(reader, TIMESTAMP_ELEMENT)) {
			String strTimestamp = XMLHelper.readElementText(reader, TIMESTAMP_ELEMENT);
			_timestamp = Timestamp.valueOf(strTimestamp);
			if (null == _timestamp) {
				throw new XMLStreamException("Cannot parse timestamp: " + strTimestamp);
			}
		}

		if (XMLHelper.peekStartElement(reader, CONTENT_TYPE_ELEMENT)) {
			String strType = XMLHelper.readElementText(reader, CONTENT_TYPE_ELEMENT);
			_type = ContentAuthenticator.nameToType(strType);
			if (null == _type) {
				throw new XMLStreamException("Cannot parse authenticator type: " + strType);
			}
		}
		
		if (XMLHelper.peekStartElement(reader, CONTENT_HASH_ELEMENT)) {
			String strHash = XMLHelper.readElementText(reader, CONTENT_HASH_ELEMENT);
			try {
				_contentDigest = XMLHelper.decodeElement(strHash);
			} catch (IOException e) {
				throw new XMLStreamException("Cannot parse content hash: " + strHash, e);
			}
			if (null == _contentDigest) {
				throw new XMLStreamException("Cannot parse content hash: " + strHash);
			}
		}
				
		XMLHelper.readEndElement(reader);
	}

	public void encode(XMLStreamWriter writer, boolean isFirstElement) throws XMLStreamException {
		if (!validate()) {
			throw new XMLStreamException("Cannot encode " + this.getClass().getName() + ": field values missing.");
		}
		XMLHelper.writeStartElement(writer, LINK_AUTHENTICATOR_ELEMENT, isFirstElement);
		
		if (!emptyPublisher()) {
			publisherID().encode(writer);
		}

		if (!emptyNameComponentCount()) {
			XMLHelper.writeElement(writer, NAME_COMPONENT_COUNT_ELEMENT, Integer.toString(nameComponentCount()));
		}

		// TODO DKS - make match correct XML timestamp format
		// dateTime	1999-05-31T13:20:00.000-05:00
		// currently writing 2007-10-23 21:36:05.828
		if (!emptyTimestamp()) {
			writer.writeStartElement(TIMESTAMP_ELEMENT);
			writer.writeCharacters(timestamp().toString());
			writer.writeEndElement();
		}
		
		if (!emptyContentType()) {
			writer.writeStartElement(CONTENT_TYPE_ELEMENT);
			writer.writeCharacters(ContentAuthenticator.typeToName(type()));
			writer.writeEndElement();
		}
		
		if (!emptyContentDigest()) {
			writer.writeStartElement(CONTENT_HASH_ELEMENT);
			writer.writeCharacters(XMLHelper.encodeElement(contentDigest()));
			writer.writeEndElement();   
		}
		writer.writeEndElement();   		
	}
	
	public boolean validate() {
		// any of the fields could be null when used 
		// as a partial-match pattern
		return true;
	}

	public static Timestamp now() {
		return new Timestamp(System.currentTimeMillis());
	}
}
