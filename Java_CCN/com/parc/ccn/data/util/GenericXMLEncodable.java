package com.parc.ccn.data.util;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

/**
 * Helper class for objects that use the JAXP stream
 * encode and decode operations to read and write
 * themselves.
 * @author smetters
 *
 */
public abstract class GenericXMLEncodable implements XMLEncodable {

	protected GenericXMLEncodable() {}
	
    protected GenericXMLEncodable(byte [] encoded) throws XMLStreamException {
    	ByteArrayInputStream bais = new ByteArrayInputStream(encoded);
    	decode(bais);
    }
    
 	public void decode(InputStream iStream) throws XMLStreamException {
		XMLEventReader reader = XMLHelper.beginDecoding(iStream);
		decode(reader);
		XMLHelper.endDecoding(reader);
	}
	
	public void encode(OutputStream oStream) throws XMLStreamException {
		XMLStreamWriter writer = XMLHelper.beginEncoding(oStream);
		encode(writer, true);
		XMLHelper.endEncoding(writer);	
	}

	public void encode(XMLStreamWriter writer) throws XMLStreamException {
		encode(writer, false);
	}
	
	public abstract void encode(XMLStreamWriter writer, 
				boolean isFirstElement) throws XMLStreamException;
	
	public abstract void decode(XMLEventReader reader) throws XMLStreamException;

	public abstract boolean validate();

	@Override
	public String toString() {
		return XMLHelper.toString(this);
	}
}


