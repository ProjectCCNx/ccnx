package com.parc.ccn.data.security;

import java.sql.Timestamp;
import java.util.HashMap;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.Library;
import com.parc.ccn.data.util.GenericXMLEncodable;
import com.parc.ccn.data.util.XMLDecoder;
import com.parc.ccn.data.util.XMLEncodable;
import com.parc.ccn.data.util.XMLEncoder;

/**
 * SignedInfo is the metadata portion of a ContentObject that contains information about that
 * object which is signed by the publisher. It requires loose consistency within that publisher
 * only -- it lets you order things with the same name, things like that. From the security
 * point of view it acts primarily as a nonce, and we use a timestamp for it to make it slightly
 * more useful than a random nonce.
 * <p>
 * You can think of the SignedInfo as
 * <br>
 * a) the stuff "about" a piece of CCN data that it is legal to expect routers to understand and
 *    use (as opposed to names, which are opaque), which is
 * <br>
 * b) pulled out as metadata rather than baked into the names both to allow this opaqueness and
 *    to deal with the fact that name matching is strictly component-wise ordered (you can't
 *    look for /parc/foo/&#42;/bar for reasons of routing efficiency, except through a slower
 *    search style interaction), and these items want to be matched in any order -- in other
 *    words, sometimes you want to find /obj/<timestamp> and sometimes /obj/<publisher> and
 *    you can't decide which should go first in the name.
 */
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
    
    protected PublisherPublicKeyDigest _publisher;
    protected Timestamp		_timestamp;
    protected ContentType 	_type;
    protected KeyLocator 	_locator;
    protected Integer 		_freshnessSeconds;
    protected Integer		_lastSegment; // DKS TODO -- add to schema (Michael), encoder/decoder
   
    public SignedInfo(
    		PublisherPublicKeyDigest publisher, 
			Timestamp timestamp, 
			ContentType type,
			KeyLocator locator,
			Integer freshnessSeconds,
			Integer lastSegment
			) {
    	super();
    	this._publisher = publisher;
    	this._timestamp = timestamp;
    	if (null == this._timestamp)
    		this._timestamp = now();
    	this._type = type;
    	this._locator = locator;
    	this._freshnessSeconds = freshnessSeconds;
    	this._lastSegment = lastSegment;
     }
    
    public SignedInfo(
    		PublisherPublicKeyDigest publisher, 
			Timestamp timestamp, 
			ContentType type,
			KeyLocator locator) {
    	this(publisher, timestamp, type, locator, null, null);
    }

    
    public SignedInfo(
    		PublisherPublicKeyDigest publisher, 
			ContentType type,
			KeyLocator locator
			) {
    	this(publisher, null, type, locator);
    }
 
    public SignedInfo(
    		PublisherPublicKeyDigest publisher, 
			ContentType type,
			KeyLocator locator,
			Integer freshnessSeconds,
			Integer lastSegment
			) {
    	this(publisher, null, type, locator, freshnessSeconds, lastSegment);
    }

    public SignedInfo(SignedInfo other) {
    	this(other.getPublisherKeyID(), 
    		 other.getTimestamp(),
    		 other.getType(), 
       		 other.getKeyLocator(),
       		 other.getFreshnessSeconds(),
       		 other.getLastSegment());
    }

    public SignedInfo() {}
        
	public SignedInfo clone() {
		// more clonage needed
		KeyLocator kl = getKeyLocator();
		return new SignedInfo(getPublisherKeyID(), getTimestamp(), getType(), null == kl ? null : kl.clone());
	}

	public boolean empty() {
    	return (emptyPublisher() && emptyContentType() && 
    			emptyTimestamp());
    }
    
    public boolean emptyPublisher() {
    	if ((null != getPublisherKeyID()) && (0 != getPublisher().length))
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
 	public final byte[] getPublisher() { return _publisher.id(); }
 	
	public final PublisherPublicKeyDigest getPublisherKeyID() { return _publisher; }

	public final Timestamp getTimestamp() { return _timestamp; }
	
	public final KeyLocator getKeyLocator() { return _locator; }
	
	public final int getFreshnessSeconds() { return _freshnessSeconds; }
	
	public boolean emptyFreshnessSeconds() {
		return (null == _freshnessSeconds);
	}
	
	public final int getLastSegment() { return _lastSegment; }
	
	public boolean emptyLastSegment() {
		return (null == _lastSegment);
	}
	
	// Do we want to make this an immutable type (or merely an immutable member of ContentObject?)
	public void setLastSegment(int lastSegment) { _lastSegment = lastSegment; }

	public final ContentType getType() { return _type; }
	
	public String getTypeName() { return typeToName(getType()); }
	
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
		
		if (decoder.peekStartElement(PublisherPublicKeyDigest.PUBLISHER_KEY_ID_ELEMENT)) {
			_publisher = new PublisherPublicKeyDigest();
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
		
		// DKS TODO -- last timestamp
		
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
			getPublisherKeyID().encode(encoder);
		}

		// TODO DKS - make match correct XML timestamp format
		// dateTime	1999-05-31T13:20:00.000-05:00
		// currently writing 2007-10-23 21:36:05.828
		if (!emptyTimestamp()) {
			encoder.writeDateTime(TIMESTAMP_ELEMENT, getTimestamp());
		}
		
		if (!emptyContentType()) {
			encoder.writeElement(CONTENT_TYPE_ELEMENT, getTypeName());
		}
		
		if (!emptyFreshnessSeconds()) {
			encoder.writeIntegerElement(FRESHNESS_SECONDS_ELEMENT, getFreshnessSeconds());
		}
		// DKS TODO -- last timestamp
		
		if (!emptyKeyLocator()) {
			getKeyLocator().encode(encoder);
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
		if (getPublisherKeyID() == null) {
			if (other.getPublisherKeyID() != null)
				return false;
		} else if (!getPublisherKeyID().equals(other.getPublisherKeyID()))
			return false;
		if (getTimestamp() == null) {
			if (other.getTimestamp() != null)
				return false;
		} else if (!getTimestamp().equals(other.getTimestamp()))
			return false;
		if (getType() == null) {
			if (other.getType() != null)
				return false;
		} else if (!getType().equals(other.getType()))
			return false;
		if (getKeyLocator() == null) {
			if (other.getKeyLocator() != null)
				return false;
		} else if (!getKeyLocator().equals(other.getKeyLocator()))
			return false;
		if (emptyFreshnessSeconds()) {
			if (!other.emptyFreshnessSeconds())
				return false;
		} else if (getFreshnessSeconds() != other.getFreshnessSeconds())
			return false;
		if (emptyLastSegment()) {
			if (!other.emptyLastSegment())
				return false;
		} else if (getLastSegment() != other.getLastSegment())
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
		result = PRIME * result + ((_lastSegment == null) ? 0 : _lastSegment.hashCode());
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
