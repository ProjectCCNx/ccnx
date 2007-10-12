package com.parc.ccn.data;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

import com.parc.ccn.data.security.ContentAuthenticator;
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
public class CompleteName implements XMLEncodable {
	
	protected static final String COMPLETE_NAME_ELEMENT = "CompleteName";

	protected ContentName _name;
	protected ContentAuthenticator _authenticator;
	
	public CompleteName(ContentName name, ContentAuthenticator authenticator) {
		_name = name;
		_authenticator = authenticator;
	}

	public CompleteName(byte [] encoded) throws XMLStreamException {
		ByteArrayInputStream bais = new ByteArrayInputStream(encoded);
		decode(bais);
	}

	public CompleteName() {} // for use by decoders

	public ContentName name() { return _name; }
	
	public ContentAuthenticator authenticator() { return _authenticator; }
	
	public void decode(InputStream iStream) throws XMLStreamException {
		XMLEventReader reader = XMLHelper.beginDecoding(iStream);
		decode(reader);
		XMLHelper.endDecoding(reader);
	}

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

	public void encode(OutputStream oStream) throws XMLStreamException {
		XMLStreamWriter writer = XMLHelper.beginEncoding(oStream);
		encode(writer);
		XMLHelper.endEncoding(writer);	
	}

	public void encode(XMLStreamWriter writer) throws XMLStreamException {
		if (!validate()) {
			throw new XMLStreamException("Cannot encode " + this.getClass().getName() + ": field values missing.");
		}
		writer.writeStartElement(COMPLETE_NAME_ELEMENT);
		
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

	@Override
	public String toString() {
		return XMLHelper.toString(this);
	}
}
