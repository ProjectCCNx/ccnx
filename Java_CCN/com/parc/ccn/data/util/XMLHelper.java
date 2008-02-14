package com.parc.ccn.data.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Iterator;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import javax.xml.stream.events.Attribute;
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
		XMLEventReader reader = factory.createXMLEventReader(iStream);
		XMLHelper.readStartDocument(reader);
		return reader;
	}
	
	public static XMLStreamWriter beginEncoding(OutputStream oStream) throws XMLStreamException {
		XMLOutputFactory factory = XMLOutputFactory.newInstance();
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
	public static void writeStartElement(XMLStreamWriter writer, 
										 String tag, 
										 HashMap<String,String> attributes,
										 boolean isFirstElement) throws XMLStreamException {
		writer.writeStartElement(XMLEncodable.CCN_NAMESPACE, tag);
		if (isFirstElement)
			writer.writeDefaultNamespace(XMLEncodable.CCN_NAMESPACE);
		
		if (null != attributes) {
			Iterator<String> atIt = attributes.keySet().iterator();
			while (atIt.hasNext()) {
				String name = atIt.next();
				writeAttribute(writer, name, attributes.get(name));
			}
		}
	}
	
	public static void writeStartElement(XMLStreamWriter writer, String tag, boolean isFirstElement) throws XMLStreamException {
		writeStartElement(writer, tag, null, isFirstElement);
	}
	
	public static void writeStartElement(XMLStreamWriter writer, String tag) throws XMLStreamException {
		writeStartElement(writer, tag, null, false);
	}
	
	protected static void writeAttribute(XMLStreamWriter writer, String name, String value) throws XMLStreamException {
		// Might not play well if this is the first element (namespace writing issues...)
		writer.writeAttribute(XMLEncodable.CCN_NAMESPACE, name, value);
	}

	// Needs to handle null and 0-length elements.
	public static String encodeElement(byte [] element) {
		if ((null == element) || (0 == element.length)) 
			return new String("");
		return new BASE64Encoder().encode(element);
	}
	
	public static byte [] decodeElement(String element) throws IOException {
		if ((null == element) || (0 == element.length()))
			return new byte[0];
		return new BASE64Decoder().decodeBuffer(element);
	}

	public static void writeElement(XMLStreamWriter writer, String tag, String content) throws XMLStreamException {
		writeElement(writer, tag, content, null, false);
	}
	
	public static void writeElement(XMLStreamWriter writer, 
									String tag, String content,
									HashMap<String,String> attributes,
									boolean isFirstElement) throws XMLStreamException {

		writer.writeStartElement(XMLEncodable.CCN_NAMESPACE, tag);
		if (isFirstElement)
			writer.writeDefaultNamespace(XMLEncodable.CCN_NAMESPACE);
		if (null != attributes) {
			Iterator<String> atIt = attributes.keySet().iterator();
			while (atIt.hasNext()) {
				String name = atIt.next();
				writeAttribute(writer, name, attributes.get(name));
			}
		}
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
	
	/**
	 * XMLEventReader.getElementText eats the endElement of
	 * an element. This is confusing. So make a helper function
	 * that handles reading the whole element to help hide this
	 * fact.
	 * @param reader
	 * @param startTag
	 * @param attributes can be null if we don't expect any.
	 * @return
	 * @throws XMLStreamException
	 */
	public static String readElementText(XMLEventReader reader, 
										 String startTag,
										 HashMap<String,String> attributes) throws XMLStreamException {
		XMLHelper.readStartElement(reader, startTag, attributes);
		String strElementText = reader.getElementText();
		// XMLHelper.readEndElement(reader); // getElementText eats the endElement
		return strElementText;
	}
	
	public static String readElementText(XMLEventReader reader, 
			 							 String startTag) throws XMLStreamException {
		return readElementText(reader, startTag, null);
	}
	
	public static void readStartElement(XMLEventReader reader, String startTag) throws XMLStreamException {
		readStartElement(reader, startTag, null);
	}
	
	public static void readStartElement(XMLEventReader reader, String startTag,
										HashMap<String,String> attributes) throws XMLStreamException {
		XMLEvent event = reader.nextEvent();
		// Use getLocalPart to strip namespaces. Right now assumes we are working
		// with a global default namespace of CCN. Make it easier to use namespaces
		// while still interoperating with the C implementation.
		if (!event.isStartElement() || (!startTag.equals(event.asStartElement().getName().getLocalPart()))) {
			// Coming back with namespace decoration doesn't match
			throw new XMLStreamException("Expected start element: " + startTag + " got: " + event.toString());
		}	
		if (null != attributes) {
			// we might be expecting attributes
			Iterator<?> it = event.asStartElement().getAttributes();
			while (it.hasNext()) {
				Attribute a = (Attribute)it.next();
				// may need fancier namespace handling.
				attributes.put(a.getName().getLocalPart(), a.getValue());
			}
		}
	}

	public static void readEndElement(XMLEventReader reader) throws XMLStreamException {
		XMLEvent event = reader.nextEvent();
		if (!event.isEndElement()) {
			throw new XMLStreamException("Expected end element, got: " + event.toString());
		}
	}

	public static boolean peekStartElement(XMLEventReader reader, String startTag) throws XMLStreamException {
		XMLEvent event = reader.peek();
		if ((null == event) || !event.isStartElement() || (!startTag.equals(event.asStartElement().getName().getLocalPart()))) {
			return false;
		}	
		return true;
	}

	public static void endDecoding(XMLEventReader reader) throws XMLStreamException {
		XMLHelper.readEndDocument(reader);
	}
	
	public static String toString(XMLEncodable obj)  {
		try {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			obj.encode(baos);
			return baos.toString();
		} catch (XMLStreamException e) {
			Library.logger().warning("Exception in XML encoding:" + e.getMessage());
			Library.warningStackTrace(e);
			return new String("Unencodable object.");
		}
	}			
	
	public static String printBytes(byte [] data) {
		StringBuffer buf = new StringBuffer();
		for (int j=0; j < data.length; ++j) {
			buf.append(Integer.toString((int)data[j], 16));
		}
		return buf.toString();
	}
}


