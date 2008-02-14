package com.parc.ccn.data.content;

import java.io.InputStream;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.security.LinkAuthenticator;
import com.parc.ccn.data.util.XMLHelper;

/**
 * Mapping from a link to the underlying XML representation.
 * Basically a Link is a content object containing
 * a name and optionally some content authentication
 * information to specify whose value for the name
 * to take.
 * @author smetters
 *
 */
public class Link extends ContentObject {
	
	protected static final String LINK_ELEMENT = "Link";
	
	protected ContentName _targetName;
	protected LinkAuthenticator _targetAuthenticator = null;
	
	public Link(ContentName targetName, LinkAuthenticator targetAuthenticator) {
		_targetName = targetName;
		_targetAuthenticator = targetAuthenticator;
	}
	
	public Link(ContentName targetName) {
		this(targetName, null);
	}
	
	public Link(InputStream iStream) throws XMLStreamException {
		decode(iStream);
	}
	
	public Link(byte [] encoded) throws XMLStreamException {
		super(encoded);
	}
	
	/**
	 * Decoding constructor.
	 */
	public Link() {}
	
	public ContentName targetName() { return _targetName; }
	public LinkAuthenticator targetAuthenticator() { return _targetAuthenticator; }
		
	/**
	 * XML format:
	 * @throws XMLStreamException 
	 * 
	 */
	
	public void decode(XMLEventReader reader) throws XMLStreamException {
		XMLHelper.readStartElement(reader, LINK_ELEMENT);

		_targetName = new ContentName();
		_targetName.decode(reader);
		
		if (XMLHelper.peekStartElement(reader, LinkAuthenticator.LINK_AUTHENTICATOR_ELEMENT)) {
			_targetAuthenticator = new LinkAuthenticator();
			_targetAuthenticator.decode(reader);
		}

		XMLHelper.readEndElement(reader);
	}

	public void encode(XMLStreamWriter writer, boolean isFirstElement) throws XMLStreamException {

		XMLHelper.writeStartElement(writer, LINK_ELEMENT, isFirstElement);
		_targetName.encode(writer);
		_targetAuthenticator.encode(writer);
		writer.writeEndElement();   		
	}
	
	public boolean validate() {
		return (null != targetName());
	}
}
