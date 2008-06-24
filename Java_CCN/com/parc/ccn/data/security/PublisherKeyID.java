package com.parc.ccn.data.security;

import java.security.PublicKey;
import java.util.Arrays;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.data.util.DataUtils;
import com.parc.ccn.data.util.GenericXMLEncodable;
import com.parc.ccn.data.util.XMLDecoder;
import com.parc.ccn.data.util.XMLEncodable;
import com.parc.ccn.data.util.XMLEncoder;
import com.parc.ccn.security.crypto.DigestHelper;

/**
 * Helper wrapper class for publisher IDs.
 * @author smetters
 *
 */
public class PublisherKeyID extends GenericXMLEncodable implements XMLEncodable, Comparable<PublisherKeyID> {
    
    public static final String PUBLISHER_KEY_ID_ELEMENT = "PublisherKeyID";

    protected byte [] _publisherID;
    
    public PublisherKeyID(PublicKey key) {
    	_publisherID = PublisherID.generateID(key);
    }
    	
	public PublisherKeyID(byte [] publisherID) {
		// Alas, Arrays.copyOf doesn't exist in 1.5, and we'd like
		// to be mostly 1.5 compatible for the macs...
		// _publisherID = Arrays.copyOf(publisherID, PUBLISHER_ID_LEN);
		_publisherID = new byte[PublisherID.PUBLISHER_ID_LEN];
		System.arraycopy(publisherID, 0, _publisherID, 0, PublisherID.PUBLISHER_ID_LEN);
	}	
	
	/**
	 * Expects the equivalent of publisherKeyID.toString
	 * @param publisherID
	 */
	public PublisherKeyID(String publisherID) {
		this(DigestHelper.scanBytes(publisherID, 32));
	}
	
    public PublisherKeyID() {} // for use by decoders
	
	public byte [] id() { return _publisherID; }
	
	@Override
	public int hashCode() {
		final int PRIME = 31;
		int result = 1;
		result = PRIME * result + Arrays.hashCode(_publisherID);
		return result;
	}
	
	public boolean equals(PublisherID publisher) {
		if (PublisherID.PublisherType.KEY != publisher.type()) 
			return false;
		if (!Arrays.equals(id(), publisher.id()))
			return false;
		return true;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (PublisherID.class == obj.getClass())
			return obj.equals(this); // put complex implementation in one place
		if (getClass() != obj.getClass())
			return false;
		final PublisherKeyID other = (PublisherKeyID) obj;
		if (!Arrays.equals(_publisherID, other._publisherID))
			return false;
		return true;
	}
	
	public void decode(XMLDecoder decoder) throws XMLStreamException {
		
		// The format of a publisher ID is an octet string.

		_publisherID = decoder.readBinaryElement(PUBLISHER_KEY_ID_ELEMENT);
		if (null == _publisherID) {
			throw new XMLStreamException("Cannot parse publisher key ID.");
		}
	}

	public void encode(XMLEncoder encoder) throws XMLStreamException {
		if (!validate()) {
			throw new XMLStreamException("Cannot encode " + this.getClass().getName() + ": field values missing.");
		}
		// The format of a publisher ID is:
		// <PublisherID type=<type> id_content />
		encoder.writeElement(PUBLISHER_KEY_ID_ELEMENT,id());
	}
	
	public boolean validate() {
		return (null != id());
	}

	public int compareTo(PublisherKeyID o) {
		int result = DataUtils.compareTo(this.id(), o.id());
		return result;
	}

	@Override
	public String toString() {
		// 	16 would be the most familiar option, but 32 is shorter
		return DigestHelper.printBytes(id(), 32);
	}
}
