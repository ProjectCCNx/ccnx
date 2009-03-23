package com.parc.ccn.data.security;

import java.sql.Timestamp;
import java.util.HashMap;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.Library;
import com.parc.ccn.data.util.GenericXMLEncodable;
import com.parc.ccn.data.util.XMLDecoder;
import com.parc.ccn.data.util.XMLEncodable;
import com.parc.ccn.data.util.XMLEncoder;

public class SignedInfo extends GenericXMLEncodable implements XMLEncodable {

	public enum ContentType {FRAGMENT, LINK, COLLECTION, LEAF, SESSION, HEADER, KEY};
    protected static final HashMap<ContentType, String> ContentTypeNames = new HashMap<ContentType, String>();
    protected static final HashMap<String, ContentType> ContentNameTypes = new HashMap<String, ContentType>();
    public static final String SIGNED_INFO_ELEMENT = "SignedInfo";
    protected static final String TIMESTAMP_ELEMENT = "Timestamp";
    protected static final String CONTENT_TYPE_ELEMENT = "Type";
    protected static final String FRESHNESS_SECONDS_ELEMENT = "FreshnessSeconds";
    
    static {
        ContentTypeNames.put(ContentType.FRAGMENT, "FRAGMENT");
        ContentTypeNames.put(ContentType.LINK, "LINK");
        ContentTypeNames.put(ContentType.COLLECTION, "COLLECTION");
        ContentTypeNames.put(ContentType.LEAF, "LEAF");
        ContentTypeNames.put(ContentType.SESSION, "SESSION");
        ContentTypeNames.put(ContentType.HEADER, "HEADER");
        ContentTypeNames.put(ContentType.KEY, "KEY");
        ContentNameTypes.put("FRAGMENT", ContentType.FRAGMENT);
        ContentNameTypes.put("LINK", ContentType.LINK);
        ContentNameTypes.put("COLLECTION", ContentType.COLLECTION);
        ContentNameTypes.put("LEAF", ContentType.LEAF);
        ContentNameTypes.put("SESSION", ContentType.SESSION);
        ContentNameTypes.put("HEADER", ContentType.HEADER);
        ContentNameTypes.put("KEY", ContentType.KEY);
    }
    
    protected PublisherKeyID _publisher;
    protected Timestamp		_timestamp;
    protected ContentType 	_type;
    protected KeyLocator 	_locator;
    protected Integer 		_freshnessSeconds;
   
    public SignedInfo(
    		PublisherKeyID publisher, 
			Timestamp timestamp, 
			ContentType type,
			KeyLocator locator
			) {
    	super();
    	this._publisher = publisher;
    	this._timestamp = timestamp;
    	if (null == this._timestamp)
    		this._timestamp = now();
    	this._type = type;
    	this._locator = locator;
     }
    
    public SignedInfo(
    		PublisherKeyID publisher, 
			ContentType type,
			KeyLocator locator
			) {
    	this(publisher, null, type, locator);
    }
 
    /**
     * For queries.
     * @param publisher
     */
    public SignedInfo(PublisherKeyID publisher) {
    	super();
    	this._publisher = publisher;
    }

    public SignedInfo(SignedInfo other) {
    	this(other.publisherKeyID(), 
    		 other.timestamp(),
    		 other.type(), 
       		 other.keyLocator());
    }

    public SignedInfo() {}
        
	public SignedInfo clone() {
		// more clonage needed
		KeyLocator kl = keyLocator();
		return new SignedInfo(publisherKeyID(), timestamp(), type(), null == kl ? null : kl.clone());
	}

	public boolean empty() {
    	return (emptyPublisher() && emptyContentType() && 
    			emptyTimestamp());
    }
    
    public boolean emptyPublisher() {
    	if ((null != publisherKeyID()) && (0 != publisher().length))
    		return false;
    	return true;
    }
        
    public boolean emptyContentType() { 
    	return (null == _type);
    }
    
    public boolean emptyTimestamp() {
    	return (null == _timestamp);
    }
    
    public boolean emptyKeyLocator() { 
    	return (null == _locator); 
    }
    
    /**
     * DKS -- start by returning final versions of members, will refactor
     * to store as final.
     * @return
     */
 	public final byte[] publisher() { return _publisher.id(); }
 	
	public final PublisherKeyID publisherKeyID() { return _publisher; }

	public final Timestamp timestamp() { return _timestamp; }
	
	public final KeyLocator keyLocator() { return _locator; }
	
	public final int freshnessSeconds() { return _freshnessSeconds; }
	
	public boolean emptyFreshnessSeconds() {
		return (null == _freshnessSeconds);
	}
	
	public final ContentType type() { return _type; }
	
	public String typeName() { return typeToName(type()); }
	
	public static final String typeToName(ContentType type) {
		if (ContentTypeNames.get(type) == null) {
			Library.logger().warning("Cannot find name for type: " + type);
		}
		return ContentTypeNames.get(type);
	}

	public static final ContentType nameToType(String name) {
		return ContentNameTypes.get(name);
	}
	
	public void decode(XMLDecoder decoder) throws XMLStreamException {
		decoder.readStartElement(SIGNED_INFO_ELEMENT);
		
		if (decoder.peekStartElement(PublisherKeyID.PUBLISHER_KEY_ID_ELEMENT)) {
			_publisher = new PublisherKeyID();
			_publisher.decode(decoder);
		}

		if (decoder.peekStartElement(TIMESTAMP_ELEMENT)) {
			_timestamp = decoder.readDateTime(TIMESTAMP_ELEMENT);
		}

		if (decoder.peekStartElement(CONTENT_TYPE_ELEMENT)) {
			String strType = decoder.readUTF8Element(CONTENT_TYPE_ELEMENT);
			_type = nameToType(strType);
			if (null == _type) {
				throw new XMLStreamException("Cannot parse signedInfo type: " + strType);
			}
		}
		
		if (decoder.peekStartElement(FRESHNESS_SECONDS_ELEMENT)) {
			_freshnessSeconds = decoder.readIntegerElement(FRESHNESS_SECONDS_ELEMENT);
		}
		
		if (decoder.peekStartElement(KeyLocator.KEY_LOCATOR_ELEMENT)) {
			_locator = new KeyLocator();
			_locator.decode(decoder);
		}
				
		decoder.readEndElement();
	}

	public void encode(XMLEncoder encoder) throws XMLStreamException {
		if (!validate()) {
			throw new XMLStreamException("Cannot encode " + this.getClass().getName() + ": field values missing.");
		}
		encoder.writeStartElement(SIGNED_INFO_ELEMENT);
		
		if (!emptyPublisher()) {
			publisherKeyID().encode(encoder);
		}

		// TODO DKS - make match correct XML timestamp format
		// dateTime	1999-05-31T13:20:00.000-05:00
		// currently writing 2007-10-23 21:36:05.828
		if (!emptyTimestamp()) {
			encoder.writeDateTime(TIMESTAMP_ELEMENT, timestamp());
		}
		
		if (!emptyContentType()) {
			encoder.writeElement(CONTENT_TYPE_ELEMENT, typeName());
		}
		
		if (!emptyFreshnessSeconds()) {
			encoder.writeIntegerElement(FRESHNESS_SECONDS_ELEMENT, freshnessSeconds());
		}
		
		if (!emptyKeyLocator()) {
			keyLocator().encode(encoder);
		}

		encoder.writeEndElement();   		
	}
	
	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		final SignedInfo other = (SignedInfo) obj;
		if (publisherKeyID() == null) {
			if (other.publisherKeyID() != null)
				return false;
		} else if (!publisherKeyID().equals(other.publisherKeyID()))
			return false;
		if (timestamp() == null) {
			if (other.timestamp() != null)
				return false;
		} else if (!timestamp().equals(other.timestamp()))
			return false;
		if (type() == null) {
			if (other.type() != null)
				return false;
		} else if (!type().equals(other.type()))
			return false;
		if (keyLocator() == null) {
			if (other.keyLocator() != null)
				return false;
		} else if (!keyLocator().equals(other.keyLocator()))
			return false;
		if (emptyFreshnessSeconds()) {
			if (other.emptyFreshnessSeconds())
				return false;
		} else if (freshnessSeconds() != other.freshnessSeconds())
			return false;
		return true;
	}
	
	@Override
	public int hashCode() {
		final int PRIME = 31;
		int result = 1;
		result = PRIME * result + ((_publisher == null) ? 0 : _publisher.hashCode());
		result = PRIME * result + ((_timestamp == null) ? 0 : _timestamp.hashCode());
		result = PRIME * result + ((_type == null) ? 0 : _type.hashCode());
		result = PRIME * result + ((_locator == null) ? 0 : _locator.hashCode());
		result = PRIME * result + ((_freshnessSeconds == null) ? 0 : _freshnessSeconds.hashCode());
		return result;
	}
	
	public boolean validate() {
		// We don't do partial matches any more, even though encoder/decoder
		// is still pretty generous.
		if (emptyPublisher() || emptyTimestamp() || emptyKeyLocator())
			return false;
		return true;
	}

	public static Timestamp now() {
		return new Timestamp(System.currentTimeMillis());
	}

	
}
