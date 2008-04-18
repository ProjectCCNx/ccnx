package com.parc.ccn.data.security;

import java.security.cert.CertificateEncodingException;
import java.sql.Timestamp;
import java.text.ParseException;
import java.util.Arrays;
import java.util.HashMap;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.Library;
import com.parc.ccn.data.util.GenericXMLEncodable;
import com.parc.ccn.data.util.TextXMLCodec;
import com.parc.ccn.data.util.XMLDecoder;
import com.parc.ccn.data.util.XMLEncodable;
import com.parc.ccn.data.util.XMLEncoder;
import com.parc.ccn.security.crypto.DigestHelper;

public class ContentAuthenticator extends GenericXMLEncodable implements XMLEncodable {

	public enum ContentType {FRAGMENT, LINK, COLLECTION, LEAF, SESSION, HEADER};
    protected static final HashMap<ContentType, String> ContentTypeNames = new HashMap<ContentType, String>();
    protected static final HashMap<String, ContentType> ContentNameTypes = new HashMap<String, ContentType>();
    public static final String CONTENT_AUTHENTICATOR_ELEMENT = "ContentAuthenticator";
    protected static final String NAME_COMPONENT_COUNT_ELEMENT = "NameComponentCount";
    protected static final String TIMESTAMP_ELEMENT = "Timestamp";
    protected static final String CONTENT_TYPE_ELEMENT = "Type";
    protected static final String CONTENT_DIGEST_ELEMENT = "ContentDigest";
    
    static {
        ContentTypeNames.put(ContentType.FRAGMENT, "FRAGMENT");
        ContentTypeNames.put(ContentType.LINK, "LINK");
        ContentTypeNames.put(ContentType.COLLECTION, "COLLECTION");
        ContentTypeNames.put(ContentType.LEAF, "LEAF");
        ContentTypeNames.put(ContentType.SESSION, "SESSION");
        ContentTypeNames.put(ContentType.HEADER, "HEADER");
        ContentNameTypes.put("FRAGMENT", ContentType.FRAGMENT);
        ContentNameTypes.put("LINK", ContentType.LINK);
        ContentNameTypes.put("COLLECTION", ContentType.COLLECTION);
        ContentNameTypes.put("LEAF", ContentType.LEAF);
        ContentNameTypes.put("SESSION", ContentType.SESSION);
        ContentNameTypes.put("HEADER", ContentType.HEADER);
    }
    
    protected PublisherKeyID _publisher;
    // int   		_version; // Java types are signed, must cope
    protected Integer 		_nameComponentCount; // how many name components are signed
    									 // if omitted, assume all
    protected Timestamp		_timestamp;
    protected ContentType 	_type;
    // long	  	_size; // signed, must cope
    protected byte []		_contentDigest; // encoded DigestInfo
    protected KeyLocator  	_keyLocator;
    
    public ContentAuthenticator(
    		PublisherKeyID publisher, 
    		Integer nameLength,
			Timestamp timestamp, 
			ContentType type, 
			KeyLocator locator, 
       		byte [] contentOrDigest, // may be already hashed
    		boolean isDigest // should we digest it or is it already done?
			) {
    	super();
    	this._publisher = publisher;
    	this._nameComponentCount = nameLength;
    	this._timestamp = timestamp;
    	this._type = type;
    	try {
    		if (isDigest)
    			// Should check to see if it is encoded.
    			// If not, have to pass in algorithm to allow encoding.
    			_contentDigest = contentOrDigest;
    		else
	    		_contentDigest = 
	    			DigestHelper.encodedDigest(contentOrDigest);
    	} catch (CertificateEncodingException e) {
    		Library.logger().warning("This should not happen: exception encoding digest using built-in algorithms: " + e.getMessage());
    		Library.warningStackTrace(e);
    		// DKS TODO what to throw?
    	}
    	_keyLocator = locator;
     }
    
    /**
     * For queries.
     * @param publisher
     */
    public ContentAuthenticator(PublisherKeyID publisher) {
    	super();
    	this._publisher = publisher;
    }

    public ContentAuthenticator(ContentAuthenticator other) {
    	this(other.publisherKeyID(), 
    		 other.nameComponentCount(),
    		 other.timestamp(),
    		 other.type(), 
       		 other.keyLocator(),
       		 other.contentDigest(), true);
    }

    public ContentAuthenticator() {}
        
    public boolean empty() {
    	return (emptyPublisher() && emptyContentDigest() && emptyContentType() && 
    			emptyTimestamp() && emptyKeyLocator());
    }
    
    public boolean emptyPublisher() {
    	if ((null != publisherKeyID()) && (0 != publisher().length))
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
    
    public boolean emptyKeyLocator() {
    	return (null == _keyLocator);
    }
    
	public byte[] contentDigest() {
		return _contentDigest;
	}
	public void contentDigest(byte[] hash) {
		_contentDigest = hash;
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
	public PublisherKeyID publisherKeyID() {
		return _publisher;
	}
	public void publisher(byte[] publisher) {
		this._publisher = new PublisherKeyID(publisher);
	}
	public void publisher(PublisherKeyID publisherKeyID) {
		this._publisher = publisherKeyID;
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
	
	public Integer nameComponentCount() { return _nameComponentCount; }
	public void nameComponentCount(Integer nameComponentCount) {
		this._nameComponentCount = nameComponentCount;
	}
	
	public String typeName() {
		return typeToName(type());
	}
	
	public static String typeToName(ContentType type) {
		if (ContentTypeNames.get(type) == null) {
			Library.logger().warning("Cannot find name for type: " + type);
		}
		return ContentTypeNames.get(type);
	}

	public static ContentType nameToType(String name) {
		return ContentNameTypes.get(name);
	}
	
	@Override
	public int hashCode() {
		final int PRIME = 31;
		int result = 1;
		result = PRIME * result + Arrays.hashCode(_contentDigest);
		result = PRIME * result + ((_keyLocator == null) ? 0 : _keyLocator.hashCode());
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
		final ContentAuthenticator other = (ContentAuthenticator) obj;
		if (_contentDigest == null) {
			if (other._contentDigest != null)
				return false;
		} else if (!Arrays.equals(_contentDigest, other._contentDigest))
			return false;
		if (_keyLocator == null) {
			if (other._keyLocator != null)
				return false;
		} else if (!_keyLocator.equals(other._keyLocator))
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

	public void decode(XMLDecoder decoder) throws XMLStreamException {
		decoder.readStartElement(CONTENT_AUTHENTICATOR_ELEMENT);
		
		if (decoder.peekStartElement(PublisherKeyID.PUBLISHER_KEY_ID_ELEMENT)) {
			_publisher = new PublisherKeyID();
			_publisher.decode(decoder);
		}

		if (decoder.peekStartElement(NAME_COMPONENT_COUNT_ELEMENT)) {
			String strLength = decoder.readUTF8Element(NAME_COMPONENT_COUNT_ELEMENT); 
			_nameComponentCount = Integer.valueOf(strLength);
			if (null == _nameComponentCount) {
				throw new XMLStreamException("Cannot parse name length: " + strLength);
			}
		}
			
		if (decoder.peekStartElement(TIMESTAMP_ELEMENT)) {
			String strTimestamp = decoder.readUTF8Element(TIMESTAMP_ELEMENT);
			try {
				_timestamp = TextXMLCodec.parseDateTime(strTimestamp);
			} catch (ParseException e) {
				throw new XMLStreamException("Cannot parse timestamp: " + strTimestamp, e);
			}
		}

		if (decoder.peekStartElement(CONTENT_TYPE_ELEMENT)) {
			String strType = decoder.readUTF8Element(CONTENT_TYPE_ELEMENT);
			_type = nameToType(strType);
			if (null == _type) {
				throw new XMLStreamException("Cannot parse authenticator type: " + strType);
			}
		}
		
		if (decoder.peekStartElement(KeyLocator.KEY_LOCATOR_ELEMENT)) {
			_keyLocator = new KeyLocator();
			_keyLocator.decode(decoder);
		}
		
		_contentDigest = decoder.readBinaryElement(CONTENT_DIGEST_ELEMENT);
		
		decoder.readEndElement();
	}

	public void encode(XMLEncoder encoder) throws XMLStreamException {
		if (!validate()) {
			throw new XMLStreamException("Cannot encode " + this.getClass().getName() + ": field values missing.");
		}
		encoder.writeStartElement(CONTENT_AUTHENTICATOR_ELEMENT);
		
		if (!emptyPublisher()) {
			publisherKeyID().encode(encoder);
		}

		if (!emptyNameComponentCount()) {
			encoder.writeElement(NAME_COMPONENT_COUNT_ELEMENT, Integer.toString(nameComponentCount()));
		}

		// TODO DKS - make match correct XML timestamp format
		// dateTime	1999-05-31T13:20:00.000-05:00
		// currently writing 2007-10-23 21:36:05.828
		if (!emptyTimestamp()) {
			encoder.writeElement(TIMESTAMP_ELEMENT, TextXMLCodec.formatDateTime(timestamp()));
		}
		
		if (!emptyContentType()) {
			encoder.writeElement(CONTENT_TYPE_ELEMENT, typeName());
		}
		
		if (!emptyKeyLocator()) {
			keyLocator().encode(encoder);
		}

		if (!emptyContentDigest()) {
			encoder.writeElement(CONTENT_DIGEST_ELEMENT, contentDigest());
		}

		encoder.writeEndElement();   		
	}
	
	public boolean validate() {
		// We don't do partial matches any more, even though encoder/decoder
		// is still pretty generous.
		if (emptyPublisher() || emptyContentDigest() || emptyTimestamp() || emptyKeyLocator())
			return false;
		return true;
	}

	public static Timestamp now() {
		return new Timestamp(System.currentTimeMillis());
	}
	
}
