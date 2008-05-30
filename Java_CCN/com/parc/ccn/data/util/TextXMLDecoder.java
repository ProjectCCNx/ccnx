package com.parc.ccn.data.util;

import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.TreeMap;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.Attribute;
import javax.xml.stream.events.Characters;
import javax.xml.stream.events.XMLEvent;

public class TextXMLDecoder implements XMLDecoder {

	protected InputStream _istream = null;
	protected XMLEventReader _reader = null;

	public TextXMLDecoder() {
	}
	
	public void beginDecoding(InputStream istream) throws XMLStreamException {
		if (null == istream)
			throw new IllegalArgumentException("TextXMLDecoder: input stream cannot be null!");
		_istream = istream;		
		XMLInputFactory factory = XMLInputFactory.newInstance();
		_reader = factory.createXMLEventReader(_istream);
		
		readStartDocument();
	}
	
	public void endDecoding() throws XMLStreamException {
		readEndDocument();
	}

	public void readStartDocument() throws XMLStreamException {
		XMLEvent event = _reader.nextEvent();
		if (!event.isStartDocument()) {
			throw new XMLStreamException("Expected start document, got: " + event.toString());
		}
	}

	public void readEndDocument() throws XMLStreamException {
		XMLEvent event = _reader.nextEvent();
		if (!event.isEndDocument()) {
			throw new XMLStreamException("Expected end document, got: " + event.toString());
		}
	}

	public void readStartElement(String startTag) throws XMLStreamException {
		readStartElement(startTag, null);
	}

	public void readStartElement(String startTag,
								TreeMap<String, String> attributes) throws XMLStreamException {

		XMLEvent event = _reader.nextEvent();
		// Use getLocalPart to strip namespaces.
		// Assumes we are working with a global default namespace of CCN.
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

	public boolean peekStartElement(String startTag) throws XMLStreamException {
		XMLEvent event = _reader.peek();
		if ((null == event) || !event.isStartElement() || (!startTag.equals(event.asStartElement().getName().getLocalPart()))) {
			return false;
		}	
		return true;
	}
	/**
	 * Consumes the end element.
	 * @return
	 * @throws XMLStreamException
	 */
	public String readElementText() throws XMLStreamException {
		StringBuffer buf = new StringBuffer();
		XMLEvent event = _reader.nextEvent();
		
		// Handles empty text element.
		while (event.isCharacters()) {
			buf.append(((Characters)event).getData());
			event = _reader.nextEvent();
		}
		if (event.isStartElement()) {
			throw new XMLStreamException("readElementText expects start element to have been previously consumed, got: " + event.toString());
		} else if (!event.isEndElement()) {
			throw new XMLStreamException("Expected end of text element, got: " + event.toString());
		}
		return buf.toString();
	}

	public void readEndElement() throws XMLStreamException {
		XMLEvent event = _reader.nextEvent();
		if (!event.isEndElement()) {
			throw new XMLStreamException("Expected end element, got: " + event.toString());
		}
	}

	public String readUTF8Element(String startTag) throws XMLStreamException {
		return readUTF8Element(startTag, null);
	}

	public String readUTF8Element(String startTag,
								  TreeMap<String, String> attributes) throws XMLStreamException {
		readStartElement(startTag, attributes); // can't use getElementText, can't get attributes
		String strElementText = readElementText();
		// readEndElement(); // readElementText consumes end element
		return strElementText;
	}

	public byte[] readBinaryElement(String startTag) throws XMLStreamException {
		return readBinaryElement(startTag, null);
	}

	public byte[] readBinaryElement(String startTag,
			TreeMap<String, String> attributes) throws XMLStreamException {
		try {
			readStartElement(startTag, attributes); // can't use getElementText, can't get attributes
			String strElementText = readElementText();
			// readEndElement(); // readElementText consumes end element
			return TextXMLCodec.decodeBinaryElement(strElementText);
		} catch (IOException e) {
			throw new XMLStreamException(e.getMessage(), e);
		}
	}

}
