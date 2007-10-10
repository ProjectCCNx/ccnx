package com.parc.ccn.data;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

import com.parc.ccn.data.security.ContentAuthenticator;
import com.parc.ccn.data.util.XMLEncodable;
import com.parc.ccn.data.util.XMLHelper;

/**
 * Utility class for grouping all of the bits associated
 * with a piece of content.
 * @author smetters
 *
 */
public class ContentObject implements XMLEncodable {
	
	protected static final String CONTENT_OBJECT_ELEMENT = "Mapping";
	protected static final String CONTENT_ELEMENT = "Content";
	
	protected ContentName _name;
    protected ContentAuthenticator _authenticator;
    protected byte [] _content;
    
    public ContentObject(ContentName name,
    					 ContentAuthenticator authenticator,
    					 byte [] content) {
    	_name = name;
    	_authenticator = authenticator;
    	_content = content;
    }
    
    public ContentName name() { return _name; }
    public ContentAuthenticator authenticator() { return _authenticator; }
    public byte [] content() { return _content; }

	public void decode(InputStream iStream) throws XMLStreamException {
		XMLEventReader reader = XMLHelper.beginDecoding(iStream);
		decode(reader);
		XMLHelper.endDecoding(reader);
	}

	public void decode(XMLEventReader reader) throws XMLStreamException {
		XMLHelper.readStartElement(reader, CONTENT_OBJECT_ELEMENT);

		_name = new ContentName();
		_name.decode(reader);
		
		_authenticator = new ContentAuthenticator();
		_authenticator.decode(reader);
		
		String strContent = XMLHelper.readElementText(reader, CONTENT_ELEMENT); 
		try {
			_content = XMLHelper.decodeElement(strContent);
		} catch (IOException e) {
			throw new XMLStreamException("Cannot decode content : " + strContent, e);
		}
		
		XMLHelper.readEndElement(reader);
	}

	public void encode(OutputStream oStream) throws XMLStreamException {
		XMLStreamWriter writer = XMLHelper.beginEncoding(oStream);
		encode(writer);
		XMLHelper.endEncoding(writer);	
	}

	public void encode(XMLStreamWriter writer) throws XMLStreamException {
		writer.writeStartElement(CONTENT_OBJECT_ELEMENT);
		
		_name.encode(writer);
		_authenticator.encode(writer);
		XMLHelper.writeElement(writer, CONTENT_ELEMENT, 
				XMLHelper.encodeElement(_content));

		writer.writeEndElement();   		
	}
}
