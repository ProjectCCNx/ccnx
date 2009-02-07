package com.parc.ccn.data.security;

import java.sql.Timestamp;
import java.util.Arrays;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.data.util.GenericXMLEncodable;
import com.parc.ccn.data.util.XMLDecoder;
import com.parc.ccn.data.util.XMLEncodable;
import com.parc.ccn.data.util.XMLEncoder;

public class LinkAuthenticator extends GenericXMLEncodable implements XMLEncodable {

    public static final String LINK_AUTHENTICATOR_ELEMENT = "LinkAuthenticator";
    protected static final String TIMESTAMP_ELEMENT = "Timestamp";
    protected static final String CONTENT_TYPE_ELEMENT = "Type";
    protected static final String CONTENT_DIGEST_ELEMENT = "ContentDigest";
   
    protected PublisherID	_publisher = null;
    protected Timestamp		_timestamp = null;
    protected SignedInfo.ContentType 	_type = null;
    protected byte []		_contentDigest = null; // encoded DigestInfo
    
    public LinkAuthenticator(
    		PublisherID publisher, 
			Timestamp timestamp, 
			SignedInfo.ContentType type, 
       		byte [] contentDigest) {
    	super();
    	this._publisher = publisher;
    	this._timestamp = timestamp;
    	this._type = type;
    	this._contentDigest = contentDigest;
    }
    	    
    public LinkAuthenticator(PublisherID publisher) {
    	super();
    	this._publisher = publisher;
    }

    public LinkAuthenticator() {}
    
    public boolean empty() {
    	return (emptyPublisher() && 
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
	
	public SignedInfo.ContentType type() {
		return _type;
	}
	public void type(SignedInfo.ContentType type) {
		this._type = type;
	}
	
	@Override
	public int hashCode() {
		final int PRIME = 31;
		int result = 1;
		result = PRIME * result + Arrays.hashCode(_contentDigest);
		result = PRIME * result + ((_publisher == null) ? 0 : _publisher.hashCode());;
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

	public void decode(XMLDecoder decoder) throws XMLStreamException {
		decoder.readStartElement(LINK_AUTHENTICATOR_ELEMENT);
		
		if (decoder.peekStartElement(PublisherID.PUBLISHER_ID_ELEMENT)) {
			_publisher = new PublisherID();
			_publisher.decode(decoder);
		}

		if (decoder.peekStartElement(TIMESTAMP_ELEMENT)) {
			_timestamp = decoder.readDateTime(TIMESTAMP_ELEMENT);
		}

		if (decoder.peekStartElement(CONTENT_TYPE_ELEMENT)) {
			String strType = decoder.readUTF8Element(CONTENT_TYPE_ELEMENT);
			_type = SignedInfo.nameToType(strType);
			if (null == _type) {
				throw new XMLStreamException("Cannot parse authenticator type: " + strType);
			}
		}
		
		if (decoder.peekStartElement(CONTENT_DIGEST_ELEMENT)) {
			_contentDigest = decoder.readBinaryElement(CONTENT_DIGEST_ELEMENT);
			if (null == _contentDigest) {
				throw new XMLStreamException("Cannot parse content hash.");
			}
		}
				
		decoder.readEndElement();
	}

	public void encode(XMLEncoder encoder) throws XMLStreamException {
		if (!validate()) {
			throw new XMLStreamException("Cannot encode " + this.getClass().getName() + ": field values missing.");
		}
		encoder.writeStartElement(LINK_AUTHENTICATOR_ELEMENT);
		
		if (!emptyPublisher()) {
			publisherID().encode(encoder);
		}

		// TODO DKS - make match correct XML timestamp format
		// dateTime	1999-05-31T13:20:00.000-05:00
		// currently writing 2007-10-23 21:36:05.828
		if (!emptyTimestamp()) {
			encoder.writeDateTime(TIMESTAMP_ELEMENT, timestamp());
		}
		
		if (!emptyContentType()) {
			encoder.writeElement(CONTENT_TYPE_ELEMENT, SignedInfo.typeToName(type()));
		}
		
		if (!emptyContentDigest()) {
			encoder.writeElement(CONTENT_DIGEST_ELEMENT, contentDigest());
		}
		encoder.writeEndElement();   		
	}
	
	public boolean validate() {
		// any of the fields could be null when used 
		// as a partial-match pattern
		return true;
	}
}
