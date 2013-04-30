/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008, 2009, 2012 Palo Alto Research Center, Inc.
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License version 2.1
 * as published by the Free Software Foundation. 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details. You should have received
 * a copy of the GNU Lesser General Public License along with this library;
 * if not, write to the Free Software Foundation, Inc., 51 Franklin Street,
 * Fifth Floor, Boston, MA 02110-1301 USA.
 */

package org.ccnx.ccn.protocol;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map.Entry;

import org.ccnx.ccn.impl.encoding.CCNProtocolDTags;
import org.ccnx.ccn.impl.encoding.GenericXMLEncodable;
import org.ccnx.ccn.impl.encoding.XMLDecoder;
import org.ccnx.ccn.impl.encoding.XMLEncodable;
import org.ccnx.ccn.impl.encoding.XMLEncoder;
import org.ccnx.ccn.impl.support.DataUtils;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.ContentDecodingException;
import org.ccnx.ccn.io.content.ContentEncodingException;


/**
 * SignedInfo is the metadata portion of a ContentObject that contains information about that
 * object which is signed by the publisher. It incluedes a timestamp, which doesn't
 * imply a certified notion of time -- it requires loose consistency within that publisher
 * only. It lets you order things with the same name, or signed by the same publisher. From the security
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
 *    words, sometimes you want to find /obj/timestamp and sometimes /obj/publisher and
 *    you can't decide which should go first in the name.
 */
public class SignedInfo extends GenericXMLEncodable implements XMLEncodable {

	/*
	 * The binary encodings for the types are chosen to print well as they go by in packet dumps.
	 */
	public enum ContentType {DATA, ENCR, GONE, KEY, LINK, NACK};
    public static final byte [] DATA_VAL = new byte[]{(byte)0x0c, (byte)0x04, (byte)0xc0};
    public static final byte [] ENCR_VAL = new byte[]{(byte)0x10, (byte)0xd0, (byte)0x91};
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
 
    // These are encoded as 3-byte binary values, whose base64 encodings 
    // are chosen to make sense and look like the tags.
    static {
        ContentTypeNames.put(ContentType.DATA, "DATA");
        ContentTypeNames.put(ContentType.ENCR, "ENCR");
        ContentTypeNames.put(ContentType.GONE, "GONE");
        ContentTypeNames.put(ContentType.KEY, "KEY/");
        ContentTypeNames.put(ContentType.LINK, "LINK");
        ContentTypeNames.put(ContentType.NACK, "NACK");
        ContentNameTypes.put("DATA", ContentType.DATA);
        ContentNameTypes.put("ENCR", ContentType.ENCR);
        ContentNameTypes.put("GONE", ContentType.GONE);
        ContentNameTypes.put("KEY/", ContentType.KEY);
        ContentNameTypes.put("LINK", ContentType.LINK);
        ContentNameTypes.put("NACK", ContentType.NACK);
        ContentTypeValues.put(ContentType.DATA, DATA_VAL);
        ContentTypeValues.put(ContentType.ENCR, ENCR_VAL);
        ContentTypeValues.put(ContentType.GONE, GONE_VAL);
        ContentTypeValues.put(ContentType.KEY, KEY_VAL);
        ContentTypeValues.put(ContentType.LINK, LINK_VAL);
        ContentTypeValues.put(ContentType.NACK, NACK_VAL);
        ContentValueTypes.put(DATA_VAL, ContentType.DATA);
        ContentValueTypes.put(ENCR_VAL, ContentType.ENCR);
        ContentValueTypes.put(GONE_VAL, ContentType.GONE);
        ContentValueTypes.put(KEY_VAL, ContentType.KEY);
        ContentValueTypes.put(LINK_VAL, ContentType.LINK);
        ContentValueTypes.put(NACK_VAL, ContentType.NACK);
    }
    
    protected PublisherPublicKeyDigest _publisher;
    protected CCNTime		_timestamp;
    protected ContentType 	_type;
    protected KeyLocator 	_locator;
    protected Integer 		_freshnessSeconds;
    protected byte []		_finalBlockID;
    protected byte []		_extOpt;

    /**
     * Constructor
     * @param publisher
     * @param locator
     */
    public SignedInfo(
    		PublisherPublicKeyDigest publisher, 
			KeyLocator locator
			) {
    	this(publisher, null, null, locator);
    }
 
    /**
     * Constructor
     * @param publisher
     * @param type
     * @param locator
     */
    public SignedInfo(
    		PublisherPublicKeyDigest publisher, 
			ContentType type,
			KeyLocator locator
			) {
    	this(publisher, null, type, locator);
    }

    /**
     * Constructor
     * @param publisher
     * @param timestamp
     * @param type
     * @param locator
     */
    public SignedInfo(
    		PublisherPublicKeyDigest publisher, 
			CCNTime timestamp, 
			ContentType type,
			KeyLocator locator) {
    	this(publisher, timestamp, type, locator, null, null);
    }
 
     /**
     * Constructor
     * @param publisher
     * @param type
     * @param locator
     * @param freshnessSeconds
     * @param finalBlockID
     */
    public SignedInfo(
    		PublisherPublicKeyDigest publisher, 
			ContentType type,
			KeyLocator locator,
			Integer freshnessSeconds,
			byte [] finalBlockID
			) {
    	this(publisher, null, type, locator, freshnessSeconds, finalBlockID);
    }
    
    /**
     * Constructor
     * @param publisher
     * @param timestamp
     * @param type
     * @param locator
     * @param freshnessSeconds
     * @param finalBlockID
     */
    public SignedInfo(
    		PublisherPublicKeyDigest publisher, 
    		CCNTime timestamp, 
			ContentType type,
			KeyLocator locator,
			Integer freshnessSeconds,
			byte [] finalBlockID
			) {
    	this(publisher, timestamp, type, locator, freshnessSeconds, finalBlockID, null);
    };
    /**
     * Constructor
     * @param publisher
     * @param timestamp
     * @param type
     * @param locator
     * @param freshnessSeconds
     * @param finalBlockID
     * @param extOpt
     */
    public SignedInfo(
    		PublisherPublicKeyDigest publisher, 
    		CCNTime timestamp, 
			ContentType type,
			KeyLocator locator,
			Integer freshnessSeconds,
			byte [] finalBlockID,
			byte [] extOpt
			) {
    	super();
    	this._publisher = publisher;
    	if (null == timestamp) {
    		this._timestamp = CCNTime.now(); // msec only
    	} else {
    		this._timestamp = timestamp;
    	}
    	
    	this._type = (null == type) ? ContentType.DATA : type;
    	this._locator = locator;
    	this._freshnessSeconds = freshnessSeconds;
    	this._finalBlockID = finalBlockID;
    	this._extOpt = extOpt;
     }

    /**
     * Copy constructor
     * @param other
     */
    public SignedInfo(SignedInfo other) {
    	this(other.getPublisherKeyID(), 
    		 other.getTimestamp(),
    		 other.getType(), 
       		 other.getKeyLocator(),
       		 other.getFreshnessSeconds(),
       		 other.getFinalBlockID(),
       		 other.getExtOpt());
    }

    /**
     * For decoders
     */
    public SignedInfo() {}
        
    /**
     * Implement Cloneable
     */
	public SignedInfo clone() {
		// more clonage needed
		KeyLocator kl = getKeyLocator();
		return new SignedInfo(getPublisherKeyID(), getTimestamp(), getType(), 
								null == kl ? null : kl.clone(),
								getFreshnessSeconds(), getFinalBlockID());
	}
    
	/**
	 * Do we have a publisher?
	 * @return True if the publisher is empty
	 */
    public boolean emptyPublisher() {
    	if ((null != getPublisherKeyID()) && (0 != getPublisher().length))
    		return false;
    	return true;
    }
       
    /**
     * True if we're using the default content type
     * @return true if we're using the default content type
     */
    public boolean defaultContentType() { 
    	return ((null == _type) || (ContentType.DATA == _type));
    }
    
    /**
     * Do we have a timestamp?
     * @return true if timestamp is empty, false if we have one
     */
    public boolean emptyTimestamp() {
    	return (null == _timestamp);
    }
    
    /**
     * Do we have a key locator
     * @return true if KeyLocator is empty, false if we have one
     */
    public boolean emptyKeyLocator() { 
    	return (null == _locator); 
    }
    
    /**
     * Do we have an ExtOpt
     * @return true if KeyLocator is empty, false if we have one
     */
    public boolean emptyExtOpt() { 
    	return (null == _extOpt); 
    }
     /**
     * Return the publisher
     * @return the publisher
     */
 	public final byte[] getPublisher() { return _publisher.digest(); }
 	
	public final PublisherPublicKeyDigest getPublisherKeyID() { return _publisher; }

	/**
	 * Get the timestamp
	 * @return the timestamp
	 */
	public final CCNTime getTimestamp() { return _timestamp; }
	
	/**
	 * Get the KeyLocator. This is optional in encoding, and can be null
	 * @return the KeyLocator
	 */
	public final KeyLocator getKeyLocator() { return _locator; }
	
	/**
	 * Get the freshness seconds
	 * @return the freshnessSeconds
	 */
	public final int getFreshnessSeconds() { return _freshnessSeconds; }
	
	/**
	 * Do we have a value for freshnessSeconds?
	 * @return true if the freshnessSeconds is emtpy, false if it is set
	 */
	public boolean emptyFreshnessSeconds() {
		return (null == _freshnessSeconds);
	}
	
	/**
	 * Get the finalBlockID as binary
	 * @return the finalBlockID
	 */
	public final byte [] getFinalBlockID() { return _finalBlockID; }
	
	/**
	 * Do we have a finalBlockID set?
	 * @return true if the finalBlockID is empty, false if we have one set
	 */
	public boolean emptyFinalBlockID() {
		return (null == _finalBlockID);
	}
	
	/**
	 * Set the finalBlockID for this set of content segments
	 * @param finalBlockID the new finalBlockID as binary
	 */
	public void setFinalBlockID(byte [] finalBlockID) { _finalBlockID = finalBlockID; }

	/**
	 * Get the extOpt as binary
	 * @return the extOpt
	 */
	public void setExtOpt(byte [] extOpt) { _extOpt = extOpt; }
	
	/**
	 * Get the extOpt as binary
	 * @return the extOpt
	 */
	public final byte [] getExtOpt() { return _extOpt; }
	
	/**
	 * Set the content type for this content object
	 * @param type
	 */
	public void setType(ContentType type) {
		if (null == type) {
			_type = ContentType.DATA;
		} else {
			_type = type;
		}
	}
	
	/**
	 * Get our content type.
	 * @return
	 */
	public final ContentType getType() { 
		if (null == _type)
			return ContentType.DATA;
		return _type; 
	}

	/**
	 * Get the String representation of our content type.
	 * @return
	 */
	public String getTypeName() { return typeToName(getType()); }
	
	/**
	 * String/enum conversions, unnecessary, will be removed
	 * @param type
	 * @return
	 */
	public static final String typeToName(ContentType type) {
		if (ContentTypeNames.get(type) == null) {
			Log.warning("Cannot find name for type: " + type);
		}
		return ContentTypeNames.get(type);
	}

	/**
	 * String/enum conversions, unnecessary, will be removed
	 * @param name
	 * @return
	 */
	public static final ContentType nameToType(String name) {
		return ContentNameTypes.get(name);
	}
	
	/**
	 * Get our type as a binary value
	 * @return the binary value of our type
	 */
	public byte [] getTypeValue() { return typeToValue(getType()); }
	
	/**
	 * Convert between ContentType and its binary representation.
	 * Unfortunately, straight hash table lookup doesn't work right on byte array
	 * keys. Have to do straight comparison. Could speed it up from linear
	 * search, but for 5 types, might not matter.
	 * @param type
	 * @return the binary value
	 */
	public static final byte [] typeToValue(ContentType type) {
		if (ContentTypeValues.get(type) == null) {
			Log.warning("Cannot find name for type: " + type);
		}
		return ContentTypeValues.get(type);
	}

	/**
	 * Convert between ContentType and its binary representation.
	 * Unfortunately, straight hash table lookup doesn't work right on byte array
	 * keys. Have to do straight comparison. Could speed it up from linear
	 * search, but for 5 types, might not matter.
	 * @param value
	 * @return the ContentType
	 */
	public static final ContentType valueToType(byte [] value) {
		for (Entry<byte [], ContentType> entry : ContentValueTypes.entrySet()) {
			if (Arrays.equals(value, entry.getKey()))
				return entry.getValue();
		}
		return null;
	}
	
	@Override
	public void decode(XMLDecoder decoder) throws ContentDecodingException {
		decoder.readStartElement(getElementLabel());
		
		if (decoder.peekStartElement(CCNProtocolDTags.PublisherPublicKeyDigest)) {
			_publisher = new PublisherPublicKeyDigest();
			_publisher.decode(decoder);
		}

		if (decoder.peekStartElement(CCNProtocolDTags.Timestamp)) {
			_timestamp = decoder.readDateTime(CCNProtocolDTags.Timestamp);
		}

		if (decoder.peekStartElement(CCNProtocolDTags.Type)) {
			byte [] binType = decoder.readBinaryElement(CCNProtocolDTags.Type);
			_type = valueToType(binType);
			if (null == _type) {
				throw new ContentDecodingException("Cannot parse signedInfo type: " + DataUtils.printHexBytes(binType) + " " + binType.length + " bytes.");
			}
		} else {
			_type = ContentType.DATA; // default
		}
		
		if (decoder.peekStartElement(CCNProtocolDTags.FreshnessSeconds)) {
			_freshnessSeconds = decoder.readIntegerElement(CCNProtocolDTags.FreshnessSeconds);
		}
		
		if (decoder.peekStartElement(CCNProtocolDTags.FinalBlockID)) {
			_finalBlockID = decoder.readBinaryElement(CCNProtocolDTags.FinalBlockID);
		}
		
		if (decoder.peekStartElement(CCNProtocolDTags.KeyLocator)) {
			_locator = new KeyLocator();
			_locator.decode(decoder);
		}
		
		if (decoder.peekStartElement(CCNProtocolDTags.ExtOpt)) {
			_extOpt = decoder.readBinaryElement(CCNProtocolDTags.ExtOpt);
		}

		decoder.readEndElement();
	}

	@Override
	public void encode(XMLEncoder encoder) throws ContentEncodingException {
		if (!validate()) {
			throw new ContentEncodingException("Cannot encode " + this.getClass().getName() + ": field values missing.");
		}
		encoder.writeStartElement(getElementLabel());
		
		if (!emptyPublisher()) {
			getPublisherKeyID().encode(encoder);
		}

		if (!emptyTimestamp()) {
			encoder.writeDateTime(CCNProtocolDTags.Timestamp, getTimestamp());
		}
		
		if (!defaultContentType()) {
			// DATA is default, element is optional, so omit if DATA
			encoder.writeElement(CCNProtocolDTags.Type, getTypeValue());
		}
		
		if (!emptyFreshnessSeconds()) {
			encoder.writeElement(CCNProtocolDTags.FreshnessSeconds, getFreshnessSeconds());
		}

		if (!emptyFinalBlockID()) {
			encoder.writeElement(CCNProtocolDTags.FinalBlockID, getFinalBlockID());
		}

		if (!emptyKeyLocator()) {
			getKeyLocator().encode(encoder);
		}
		
		if (!emptyExtOpt()) {
			encoder.writeElement(CCNProtocolDTags.ExtOpt, getExtOpt());
		}

		encoder.writeEndElement();   		
	}
	
	@Override
	public long getElementLabel() { return CCNProtocolDTags.SignedInfo; }

	@Override
	public boolean validate() {
		// We don't do partial matches any more, even though encoder/decoder
		// is still pretty generous.
		if (emptyPublisher() || emptyTimestamp())
			return false;
		return true;
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

	public String toString() {
		StringBuffer s = new StringBuffer();
		if (_type != null) s.append(String.format("si: type=%s", typeToName(_type)));
		s.append(String.format("si: timestamp=%s", _timestamp));
		s.append(String.format("si: pub=%s", _publisher));
		if (_locator != null) s.append(String.format("si: loc=%s", _locator));
		if (_freshnessSeconds != null) s.append(String.format("si: type=%s", _freshnessSeconds));
		return s.toString();
	}
}
