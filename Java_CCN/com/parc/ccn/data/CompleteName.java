package com.parc.ccn.data;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

import com.parc.ccn.data.security.ContentAuthenticator;
import com.parc.ccn.data.util.GenericXMLEncodable;
import com.parc.ccn.data.util.XMLEncodable;
import com.parc.ccn.data.util.XMLHelper;

/**
 * We sometimes need to refer to the "complete" name
 * of an object -- the unique combination of a ContentName
 * and a ContentAuthenticator. The authenticator can
 * be null, as can any of its fields.
 * @author smetters
 *
 */
public class CompleteName extends GenericXMLEncodable implements XMLEncodable {
	
	protected static final String COMPLETE_NAME_ELEMENT = "CompleteName";

	protected ContentName _name;
	protected ContentAuthenticator _authenticator;
	
	/**
	 * TODO: DKS figure out how to handle encoding faster,
	 * and how to handle shorter version of names without
	 * copying, particularly without 1.6 array ops.
	 * @param name
	 * @param authenticator
	 */
	public CompleteName(ContentName name, 
						Integer nameComponentCount,
						ContentAuthenticator authenticator) {
		if ((null == nameComponentCount) || (nameComponentCount == name.count())) {
			_name = name;
		} else {
			_name = name.copy(nameComponentCount);
		}
		_authenticator = authenticator;
	}
	
	public CompleteName(ContentName name, ContentAuthenticator authenticator) {
		this(name, null, authenticator);
	}

	public CompleteName(byte [] encoded) throws XMLStreamException {
		super(encoded);
	}

	public CompleteName() {} // for use by decoders

	public ContentName name() { return _name; }
	
	public ContentAuthenticator authenticator() { return _authenticator; }
	
	/**
	 * Thought about encoding and decoding as flat -- no wrapping
	 * declaration. But then couldn't use these solo.
	 */
	public void decode(XMLEventReader reader) throws XMLStreamException {
		XMLHelper.readStartElement(reader, COMPLETE_NAME_ELEMENT);

		_name = new ContentName();
		_name.decode(reader);
		
		if (XMLHelper.peekStartElement(reader, ContentAuthenticator.CONTENT_AUTHENTICATOR_ELEMENT)) {
			_authenticator = new ContentAuthenticator();
			_authenticator.decode(reader);
		}
		
		XMLHelper.readEndElement(reader);
	}

	public void encode(XMLStreamWriter writer, boolean isFirstElement) throws XMLStreamException {
		if (!validate()) {
			throw new XMLStreamException("Cannot encode " + this.getClass().getName() + ": field values missing.");
		}
		XMLHelper.writeStartElement(writer, COMPLETE_NAME_ELEMENT, isFirstElement);
		
		name().encode(writer);
		if (null != authenticator())
			authenticator().encode(writer);

		writer.writeEndElement();   		
	}
	
	public boolean validate() {
		// DKS -- do we do recursive validation?
		// null authenticator ok
		return (null != name());
	}

	@Override
	public int hashCode() {
		final int PRIME = 31;
		int result = 1;
		result = PRIME * result + ((_authenticator == null) ? 0 : _authenticator.hashCode());
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
		final CompleteName other = (CompleteName) obj;
		if (_authenticator == null) {
			if (other._authenticator != null)
				return false;
		} else if (!_authenticator.equals(other._authenticator))
			return false;
		if (_name == null) {
			if (other._name != null)
				return false;
		} else if (!_name.equals(other._name))
			return false;
		return true;
	}
}
