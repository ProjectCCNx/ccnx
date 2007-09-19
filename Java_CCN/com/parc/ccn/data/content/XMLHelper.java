package com.parc.ccn.data.content;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import javax.xml.stream.events.XMLEvent;

import sun.misc.BASE64Decoder;
import sun.misc.BASE64Encoder;

/**
 * Helper class for objects that use the JAXP stream
 * encode and decode operations to read and write
 * themselves.
 * @author smetters
 *
 */
public class XMLHelper {

	public static XMLEventReader beginDecoding(InputStream iStream) throws XMLStreamException {
		XMLInputFactory factory = XMLInputFactory.newInstance();
		factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE , 
				Boolean.TRUE);
		XMLEventReader reader = factory.createXMLEventReader(iStream);
		return reader;
	}
	
	public static XMLStreamWriter beginEncoding(OutputStream oStream) throws XMLStreamException {
		XMLOutputFactory factory = XMLOutputFactory.newInstance();
		factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE , 
				Boolean.TRUE);
		XMLStreamWriter writer = factory.createXMLStreamWriter(oStream);
		writer.setPrefix(XMLEncodable.CCN_PREFIX, XMLEncodable.CCN_NAMESPACE);
		writer.setDefaultNamespace(XMLEncodable.CCN_NAMESPACE);
		// DKS -- need to set encoding when creating factory, and set it here.
		writer.writeStartDocument();
		writer.writeDefaultNamespace(XMLEncodable.CCN_NAMESPACE);
		return writer;
	}
	
	public static void endEncoding(XMLStreamWriter writer) throws XMLStreamException {
		writer.writeEndDocument();
		writer.flush();   		
	}
	
	public static String encodeElement(byte [] element) {
		return new BASE64Encoder().encode(element);
	}
	
	public static byte [] decodeElement(String element) throws IOException {
		return new BASE64Decoder().decodeBuffer(element);
	}

	public static void readStartElement(XMLEventReader reader, String startTag) throws XMLStreamException {
		XMLEvent event = reader.nextTag();
		if (!event.isStartElement() || (!startTag.equals(event.asStartElement().getName()))) {
			throw new XMLStreamException("Expected start element: " + startTag + " got: " + event.toString());
		}	
	}

	public static void readEndElement(XMLEventReader reader) throws XMLStreamException {
		XMLEvent event = reader.nextTag();
		if (!event.isEndElement()) {
			throw new XMLStreamException("Expected end element, got: " + event.toString());
		}
	}

	public static boolean peekStartElement(XMLEventReader reader, String startTag) throws XMLStreamException {
		XMLEvent event = reader.peek();
		if ((null == event) || !event.isStartElement() || (!startTag.equals(event.asStartElement().getName()))) {
			return false;
		}	
		return true;
	}
}

