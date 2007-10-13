package com.parc.ccn.data;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

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
	
	protected CompleteName _completeName;
    protected byte [] _content;
    
    public ContentObject(ContentName name,
    					 ContentAuthenticator authenticator,
    					 byte [] content) {
    	_completeName = new CompleteName(name, authenticator);
    	_content = content;
    }
    
    public ContentObject(CompleteName completeName,
    					 byte [] content) {
    	_completeName = completeName;
    	_content = content;
    }
    
    public ContentObject() {} // for use by decoders
    
    public CompleteName completeName() { return _completeName; }
    
    public ContentName name() { 
    	if (null != _completeName)
    		return _completeName.name(); 
    	return null;
    }
    
    public ContentAuthenticator authenticator() { 
    	if (null != _completeName)
    		return _completeName.authenticator(); 
    	return null;
    }
    
    public byte [] content() { return _content; }

	public void decode(InputStream iStream) throws XMLStreamException {
		XMLEventReader reader = XMLHelper.beginDecoding(iStream);
		decode(reader);
		XMLHelper.endDecoding(reader);
	}

	public void decode(XMLEventReader reader) throws XMLStreamException {
		XMLHelper.readStartElement(reader, CONTENT_OBJECT_ELEMENT);

		// For the moment, include complete name level...
		_completeName = new CompleteName();
		_completeName.decode(reader);
		
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
		// TODO: DKS validate all these things before encoding		
		if (!validate()) {
			throw new XMLStreamException("Cannot encode " + this.getClass().getName() + ": field values missing.");
		}
		writer.writeStartElement(CONTENT_OBJECT_ELEMENT);

		completeName().encode(writer);

		// needs to handle null content
		XMLHelper.writeElement(writer, CONTENT_ELEMENT, 
				XMLHelper.encodeElement(_content));

		writer.writeEndElement();   		
	}
	
	public boolean validate() { 
		// recursive?
		// null content ok
		return (null != completeName());
	}

	@Override
	public int hashCode() {
		final int PRIME = 31;
		int result = 1;
		result = PRIME * result + ((_completeName == null) ? 0 : _completeName.hashCode());
		result = PRIME * result + Arrays.hashCode(_content);
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
		final ContentObject other = (ContentObject) obj;
		if (_completeName == null) {
			if (other._completeName != null)
				return false;
		} else if (!_completeName.equals(other._completeName))
			return false;
		if (!Arrays.equals(_content, other._content))
			return false;
		return true;
	}

	@Override
	public String toString() {
		return XMLHelper.toString(this);
	}
}
