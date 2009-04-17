package com.parc.ccn.data.security;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.util.GenericXMLEncodable;
import com.parc.ccn.data.util.XMLDecoder;
import com.parc.ccn.data.util.XMLEncodable;
import com.parc.ccn.data.util.XMLEncoder;

/**
 * We sometimes need to refer to the "complete" name
 * of an object -- the unique combination of a ContentName
 * and a SignedInfo. The authenticator can
 * be null, as can any of its fields.
 * @author smetters
 *
 */
public class KeyName extends GenericXMLEncodable implements XMLEncodable {
	
	protected static final String KEY_NAME_ELEMENT = "KeyName";

	protected ContentName _name;
	protected PublisherID _publisher;
	
	/**
	 * TODO: DKS figure out how to handle encoding faster,
	 * and how to handle shorter version of names without
	 * copying, particularly without 1.6 array ops.
	 * @param name
	 * @param authenticator
	 */
	public KeyName(ContentName name, 
				   PublisherID publisher) {
		_name = name;
		_publisher = publisher;
	}
	
	public KeyName(ContentName name) {
		this(name, null);
	}

	public KeyName() {} // for use by decoders

	public ContentName name() { return _name; }
	
	public PublisherID publisher() { return _publisher; }
	
	/**
	 * Thought about encoding and decoding as flat -- no wrapping
	 * declaration. But then couldn't use these solo.
	 */
	public void decode(XMLDecoder decoder) throws XMLStreamException {
		decoder.readStartElement(KEY_NAME_ELEMENT);

		_name = new ContentName();
		_name.decode(decoder);
		
		if (PublisherID.peek(decoder)) {
			_publisher = new PublisherID();
			_publisher.decode(decoder);
		}
		
		decoder.readEndElement();
	}

	public void encode(XMLEncoder encoder) throws XMLStreamException {
		if (!validate()) {
			throw new XMLStreamException("Cannot encode " + this.getClass().getName() + ": field values missing.");
		}
		encoder.writeStartElement(KEY_NAME_ELEMENT);
		
		name().encode(encoder);
		if (null != publisher())
			publisher().encode(encoder);

		encoder.writeEndElement();   		
	}
	
	public boolean validate() {
		// DKS -- do we do recursive validation?
		// null signedInfo ok
		return (null != name());
	}

	@Override
	public int hashCode() {
		final int PRIME = 31;
		int result = 1;
		result = PRIME * result + ((_publisher == null) ? 0 : _publisher.hashCode());
		result = PRIME * result + ((_name == null) ? 0 : _name.hashCode());
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
		final KeyName other = (KeyName) obj;
		if (_publisher == null) {
			if (other._publisher != null)
				return false;
		} else if (!_publisher.equals(other._publisher))
			return false;
		if (_name == null) {
			if (other._name != null)
				return false;
		} else if (!_name.equals(other._name))
			return false;
		return true;
	}
}
