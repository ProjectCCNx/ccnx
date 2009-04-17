package com.parc.ccn.data.security;

import java.sql.Timestamp;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map.Entry;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.Library;
import com.parc.ccn.data.util.DataUtils;
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

	public enum ContentType {DATA, GONE, KEY, LINK, NACK};
    public static final byte [] DATA_VAL = new byte[]{(byte)0x0c, (byte)0x04, (byte)0xc0};
    public static final byte [] GONE_VAL = new byte[]{(byte)0x18, (byte)0xe3, (byte)0x44};
    public static final byte [] KEY_VAL = new byte[]{(byte)0x28, (byte)0x46, (byte)0x3f};
    public static final byte [] LINK_VAL = new byte[]{(byte)0x2c, (byte)0x83, (byte)0x4a};
    public static final byte [] NACK_VAL = new byte[]{(byte)0x34, (byte)0x00, (byte)0x8a};

    protected static final HashMap<ContentType, String> ContentTypeNames = new HashMap<ContentType, String>();
    protected static final HashMap<String, ContentType> ContentNameTypes = new HashMap<String, ContentType>();
    protected static final HashMap<ContentType, byte[]> ContentTypeValues = new HashMap<ContentType, byte[]>();
    // This doesn't actually work as a hash table; lookup of byte [] doesn't have a proper hashCode function.
    // turns into object ==.
    protected static final HashMap<byte[], ContentType> ContentValueTypes = new HashMap<byte[], ContentType>();
 
    public static final String SIGNED_INFO_ELEMENT = "SignedInfo";
    protected static final String TIMESTAMP_ELEMENT = "Timestamp";
    protected static final String CONTENT_TYPE_ELEMENT = "Type";
    protected static final String FRESHNESS_SECONDS_ELEMENT = "FreshnessSeconds";
    protected static final String FINAL_BLOCK_ID_ELEMENT = "FinalBlockID";

    // These are encoded as 3-byte binary values, whose base64 encodings 
    // are chosen to make sense and look like the tags.
    static {
        ContentTypeNames.put(ContentType.DATA, "DATA");
        ContentTypeNames.put(ContentType.GONE, "GONE");
        ContentTypeNames.put(ContentType.KEY, "KEY/");
        ContentTypeNames.put(ContentType.LINK, "LINK");
        ContentTypeNames.put(ContentType.NACK, "NACK");
        ContentNameTypes.put("DATA", ContentType.DATA);
        ContentNameTypes.put("GONE", ContentType.GONE);
        ContentNameTypes.put("KEY/", ContentType.KEY);
        ContentNameTypes.put("LINK", ContentType.LINK);
        ContentNameTypes.put("NACK", ContentType.NACK);
        ContentTypeValues.put(ContentType.DATA, DATA_VAL);
        ContentTypeValues.put(ContentType.GONE, GONE_VAL);
        ContentTypeValues.put(ContentType.KEY, KEY_VAL);
        ContentTypeValues.put(ContentType.LINK, LINK_VAL);
        ContentTypeValues.put(ContentType.NACK, NACK_VAL);
        ContentValueTypes.put(DATA_VAL, ContentType.DATA);
        ContentValueTypes.put(GONE_VAL, ContentType.GONE);
        ContentValueTypes.put(KEY_VAL, ContentType.KEY);
        ContentValueTypes.put(LINK_VAL, ContentType.LINK);
        ContentValueTypes.put(NACK_VAL, ContentType.NACK);
    }
    
    protected PublisherPublicKeyDigest _publisher;
    protected Timestamp		_timestamp;
    protected ContentType 	_type;
    protected KeyLocator 	_locator;
    protected Integer 		_freshnessSeconds;
    protected byte []		_finalBlockID; 
   
    public SignedInfo(
    		PublisherPublicKeyDigest publisher, 
			Timestamp timestamp, 
			ContentType type,
			KeyLocator locator,
			Integer freshnessSeconds,
			byte [] finalBlockID
			) {
    	super();
    	this._publisher = publisher;
    	if (null == timestamp) {
    		this._timestamp = now(); // msec only
    	} else {
    		this._timestamp = timestamp;
    	}
	   	// Lower resolution of time to only what we can represent on the wire;
    	// this allows decode(encode(timestamp)) == timestamp
    	this._timestamp = DataUtils.roundTimestamp(this._timestamp);
    	
    	this._type = (null == type) ? ContentType.DATA : type;
    	this._locator = locator;
    	this._freshnessSeconds = freshnessSeconds;
    	this._finalBlockID = finalBlockID;
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
			KeyLocator locator
			) {
    	this(publisher, null, null, locator);
    }
 
    public SignedInfo(
    		PublisherPublicKeyDigest publisher, 
			ContentType type,
			KeyLocator locator,
			Integer freshnessSeconds,
			byte [] finalBlockID
			) {
    	this(publisher, null, type, locator, freshnessSeconds, finalBlockID);
    }

    public SignedInfo(SignedInfo other) {
    	this(other.getPublisherKeyID(), 
    		 other.getTimestamp(),
    		 other.getType(), 
       		 other.getKeyLocator(),
       		 other.getFreshnessSeconds(),
       		 other.getFinalBlockID());
    }

    public SignedInfo() {}
        
	public SignedInfo clone() {
		// more clonage needed
		KeyLocator kl = getKeyLocator();
		return new SignedInfo(getPublisherKeyID(), getTimestamp(), getType(), 
								null == kl ? null : kl.clone(),
								getFreshnessSeconds(), getFinalBlockID());
	}

	public boolean empty() {
    	return (emptyPublisher() && defaultContentType() && 
    			emptyTimestamp());
    }
    
    public boolean emptyPublisher() {
    	if ((null != getPublisherKeyID()) && (0 != getPublisher().length))
    		return false;
    	return true;
    }
        
    public boolean defaultContentType() { 
    	return ((null == _type) || (ContentType.DATA == _type));
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
 	public final byte[] getPublisher() { return _publisher.digest(); }
 	
	public final PublisherPublicKeyDigest getPublisherKeyID() { return _publisher; }

	public final Timestamp getTimestamp() { return _timestamp; }
	
	public final KeyLocator getKeyLocator() { return _locator; }
	
	public final int getFreshnessSeconds() { return _freshnessSeconds; }
	
	public boolean emptyFreshnessSeconds() {
		return (null == _freshnessSeconds);
	}
	
	public final byte [] getFinalBlockID() { return _finalBlockID; }
	
	public boolean emptyFinalBlockID() {
		return (null == _finalBlockID);
	}
	
	// Do we want to make this an immutable type (or merely an immutable member of ContentObject?)
	public void setFinalBlockID(byte [] finalBlockID) { _finalBlockID = finalBlockID; }

	public final ContentType getType() { 
		if (null == _type)
			return ContentType.DATA;
		return _type; 
	}
	
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
	
	public byte [] getTypeValue() { return typeToValue(getType()); }
	
	public static final byte [] typeToValue(ContentType type) {
		if (ContentTypeValues.get(type) == null) {
			Library.logger().warning("Cannot find name for type: " + type);
		}
		return ContentTypeValues.get(type);
	}

	/**
	 * Unfortunately, straight hash table lookup doesn't work right on byte array
	 * keys. Have to do straight comparison. Could speed it up from linear
	 * search, but for 5 types, might not matter.
	 * @param value
	 * @return
	 */
	public static final ContentType valueToType(byte [] value) {
		for (Entry<byte [], ContentType> entry : ContentValueTypes.entrySet()) {
			if (Arrays.equals(value, entry.getKey()))
				return entry.getValue();
		}
		return null;
	}
	
	public void decode(XMLDecoder decoder) throws XMLStreamException {
		decoder.readStartElement(SIGNED_INFO_ELEMENT);
		
		if (decoder.peekStartElement(PublisherPublicKeyDigest.PUBLISHER_PUBLIC_KEY_DIGEST_ELEMENT)) {
			_publisher = new PublisherPublicKeyDigest();
			_publisher.decode(decoder);
		}

		if (decoder.peekStartElement(TIMESTAMP_ELEMENT)) {
			_timestamp = decoder.readDateTime(TIMESTAMP_ELEMENT);
		}

		if (decoder.peekStartElement(CONTENT_TYPE_ELEMENT)) {
			byte [] binType = decoder.readBinaryElement(CONTENT_TYPE_ELEMENT);
			_type = valueToType(binType);
			if (null == _type) {
				throw new XMLStreamException("Cannot parse signedInfo type: " + DataUtils.printHexBytes(binType) + " " + binType.length + " bytes.");
			}
		} else {
			_type = ContentType.DATA; // default
		}
		
		if (decoder.peekStartElement(FRESHNESS_SECONDS_ELEMENT)) {
			_freshnessSeconds = decoder.readIntegerElement(FRESHNESS_SECONDS_ELEMENT);
		}
		
		if (decoder.peekStartElement(FINAL_BLOCK_ID_ELEMENT)) {
			_finalBlockID = decoder.readBinaryElement(FINAL_BLOCK_ID_ELEMENT);
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
			getPublisherKeyID().encode(encoder);
		}

		// TODO DKS - make match correct XML timestamp format
		// dateTime	1999-05-31T13:20:00.000-05:00
		// currently writing 2007-10-23 21:36:05.828
		if (!emptyTimestamp()) {
			encoder.writeDateTime(TIMESTAMP_ELEMENT, getTimestamp());
		}
		
		if (!defaultContentType()) {
			// DATA is default, element is optional, so omit if DATA
			encoder.writeElement(CONTENT_TYPE_ELEMENT, getTypeValue());
		}
		
		if (!emptyFreshnessSeconds()) {
			encoder.writeIntegerElement(FRESHNESS_SECONDS_ELEMENT, getFreshnessSeconds());
		}

		if (!emptyFinalBlockID()) {
			encoder.writeElement(FINAL_BLOCK_ID_ELEMENT, getFinalBlockID());
		}

		if (!emptyKeyLocator()) {
			getKeyLocator().encode(encoder);
		}

		encoder.writeEndElement();   		
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

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + Arrays.hashCode(_finalBlockID);
		result = prime
				* result
				+ ((_freshnessSeconds == null) ? 0 : _freshnessSeconds
						.hashCode());
		result = prime * result
				+ ((_locator == null) ? 0 : _locator.hashCode());
		result = prime * result
				+ ((_publisher == null) ? 0 : _publisher.hashCode());
		result = prime * result
				+ ((_timestamp == null) ? 0 : _timestamp.hashCode());
		result = prime * result + ((_type == null) ? 0 : _type.hashCode());
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
		SignedInfo other = (SignedInfo) obj;
		if (!Arrays.equals(_finalBlockID, other._finalBlockID))
			return false;
		if (_freshnessSeconds == null) {
			if (other._freshnessSeconds != null)
				return false;
		} else if (!_freshnessSeconds.equals(other._freshnessSeconds))
			return false;
		if (_locator == null) {
			if (other._locator != null)
				return false;
		} else if (!_locator.equals(other._locator))
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
			if (other._type != null)
				return false;
		} else if (!_type.equals(other._type))
			return false;
		return true;
	}	
}
