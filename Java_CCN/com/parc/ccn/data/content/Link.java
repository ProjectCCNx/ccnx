package com.parc.ccn.data.content;

import java.io.InputStream;
import java.io.OutputStream;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.security.ContentAuthenticator;

/**
 * Mapping from a link to the underlying XML representation.
 * Basically a Link is a content object containing
 * a name and optionally some content authentication
 * information to specify whose value for the name
 * to take.
 * @author smetters
 *
 */
public class Link implements XMLEncodable {
	
	protected static final String LINK_ELEMENT = "Link";
	
	protected ContentName _destName;
	protected ContentAuthenticator _destAuthenticator;
	
	public Link(ContentName destName, ContentAuthenticator destAuthenticator) {
		_destName = destName;
		_destAuthenticator = destAuthenticator;
	}
	
	public Link(ContentName destName) {
		this(destName, null);
	}
	
	public Link(InputStream iStream) throws XMLStreamException {
		decode(iStream);
	}
		
	/**
	 * XML format:
	 * @throws XMLStreamException 
	 * 
	 */
	
	public void decode(InputStream iStream) throws XMLStreamException {
		XMLEventReader reader = XMLHelper.beginDecoding(iStream);
		decode(reader);
	}
	
	public void encode(OutputStream oStream) throws XMLStreamException {
		XMLStreamWriter writer = XMLHelper.beginEncoding(oStream);
		encode(writer);
		XMLHelper.endEncoding(writer);	
	}

	public void decode(XMLEventReader reader) throws XMLStreamException {
		XMLHelper.readStartElement(reader, LINK_ELEMENT);

		_destName = new ContentName();
		_destName.decode(reader);
		
		if (XMLHelper.peekStartElement(reader, ContentAuthenticator.CONTENT_AUTHENTICATOR_ELEMENT)) {
			_destAuthenticator = new ContentAuthenticator();
			_destAuthenticator.decode(reader);
		}
		XMLHelper.readEndElement(reader);
	}

	public void encode(XMLStreamWriter writer) throws XMLStreamException {
		writer.writeStartElement(LINK_ELEMENT);
		_destName.encode(writer);
		if (null != _destAuthenticator) {
			_destAuthenticator.encode(writer);
		}
		writer.writeEndElement();   		
	}
}
