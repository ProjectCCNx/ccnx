package com.parc.ccn.data.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import javax.xml.stream.events.XMLEvent;

import com.parc.ccn.Library;


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
		try {
			factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE , 
					Boolean.TRUE);
		} catch (IllegalArgumentException e) {
			Library.logger().warning("XMLHelper: XMLInputFactory is not namespace aware.");
		}
		XMLEventReader reader = factory.createXMLEventReader(iStream);
		return reader;
	}
	
	public static XMLStreamWriter beginEncoding(OutputStream oStream) throws XMLStreamException {
		XMLOutputFactory factory = XMLOutputFactory.newInstance();
		try {
			factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE , 
					Boolean.TRUE);
		} catch (IllegalArgumentException e) {
			Library.logger().warning("XMLHelper: XMLOutputFactory is not namespace aware.");
		}
		XMLStreamWriter writer = factory.createXMLStreamWriter(oStream);
		writer.setPrefix(XMLEncodable.CCN_PREFIX, XMLEncodable.CCN_NAMESPACE);
		writer.setDefaultNamespace(XMLEncodable.CCN_NAMESPACE);
		// DKS -- need to set encoding when creating factory, and set it here.
		writer.writeStartDocument();
		// Can't write default namespace till we write an element...
		return writer;
	}
	
	public static void endEncoding(XMLStreamWriter writer) throws XMLStreamException {
		writer.writeEndDocument();
		writer.flush();   		
	}
	
	/**
	 * Need to use this to get default namespace written.
	 * @param writer
	 * @param tag
	 * @throws XMLStreamException
	 */
	public static void startFirstElement(XMLStreamWriter writer, String tag) throws XMLStreamException {
		writer.writeStartElement(tag);
		writer.writeDefaultNamespace(XMLEncodable.CCN_NAMESPACE);
	}
	
	public static String encodeElement(byte [] element) {
		return new BASE64Encoder().encode(element);
	}
	
	public static byte [] decodeElement(String element) throws IOException {
		return new BASE64Decoder().decodeBuffer(element);
	}

	public static void writeElement(XMLStreamWriter writer, String tag, String content) throws XMLStreamException {
		writer.writeStartElement(tag);
		writer.writeCharacters(content);
		writer.writeEndElement();
	}
	
	public static void readStartDocument(XMLEventReader reader) throws XMLStreamException {
		XMLEvent event = reader.nextEvent();
		if (!event.isStartDocument()) {
			throw new XMLStreamException("Expected start document, got: " + event.toString());
		}			
	}

	public static void readEndDocument(XMLEventReader reader) throws XMLStreamException {
		XMLEvent event = reader.nextEvent();
		if (!event.isEndDocument()) {
			throw new XMLStreamException("Expected end document, got: " + event.toString());
		}
	}
	
	public static void readStartElement(XMLEventReader reader, String startTag) throws XMLStreamException {
		XMLEvent event = reader.nextTag();
		if (!event.isStartElement() || (!startTag.equals(event.asStartElement().getName()))) {
			// Coming back with namespace decoration doesn't match
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

