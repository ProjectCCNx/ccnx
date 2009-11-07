/**
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008, 2009 Palo Alto Research Center, Inc.
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License version 2.1
 * as published by the Free Software Foundation. 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details. You should have received
 * a copy of the GNU Lesser General Public License along with this library;
 * if not, write to the Free Software Foundation, Inc., 51 Franklin Street,
 * Fifth Floor, Boston, MA 02110-1301 USA.
 */

package org.ccnx.ccn.impl.encoding;

import java.io.IOException;
import java.io.InputStream;
import java.text.ParseException;
import java.util.Iterator;
import java.util.TreeMap;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.Attribute;
import javax.xml.stream.events.Characters;
import javax.xml.stream.events.XMLEvent;

import org.ccnx.ccn.io.content.ContentDecodingException;
import org.ccnx.ccn.protocol.CCNTime;

/**
 * An implementation of XMLDecoder for the Text codec. Uses javax.xml.stream interfaces.
 * These are standard in Java 1.6, but require installing an add on package from JSR 173
 * for Java 1.5. See README for details.
 * 
 * @see TextXMLCodec
 * @see XMLDecoder
 */
public class TextXMLDecoder extends GenericXMLDecoder implements XMLDecoder {

	protected InputStream _istream = null;
	protected XMLEventReader _reader = null;

	public TextXMLDecoder() {
	}
	
	public void beginDecoding(InputStream istream) throws ContentDecodingException {
		if (null == istream)
			throw new IllegalArgumentException("TextXMLDecoder: input stream cannot be null!");
		_istream = istream;		
		XMLInputFactory factory = XMLInputFactory.newInstance();
		try {
			_reader = factory.createXMLEventReader(_istream);
		} catch (XMLStreamException e) {
			throw new ContentDecodingException(e.getMessage(), e);
		}
		
		readStartDocument();
	}
	
	public void endDecoding() throws ContentDecodingException {
		readEndDocument();
	}

	public void readStartDocument() throws ContentDecodingException {
		XMLEvent event;
		try {
			event = _reader.nextEvent();
		} catch (XMLStreamException e) {
			throw new ContentDecodingException(e.getMessage(), e);
		}
		if (!event.isStartDocument()) {
			throw new ContentDecodingException("Expected start document, got: " + event.toString());
		}
	}

	public void readEndDocument() throws ContentDecodingException {
		XMLEvent event;
		try {
			event = _reader.nextEvent();
		} catch (XMLStreamException e) {
			throw new ContentDecodingException(e.getMessage(), e);
		}
		if (!event.isEndDocument()) {
			throw new ContentDecodingException("Expected end document, got: " + event.toString());
		}
	}

	public void readStartElement(String startTag) throws ContentDecodingException {
		readStartElement(startTag, null);
	}

	public void readStartElement(String startTag,
								TreeMap<String, String> attributes) throws ContentDecodingException {

		XMLEvent event;
		try {
			do {
				event = _reader.nextEvent();
			} while (!event.isStartElement());		// Assume if its not a startElement it's a comment
		} catch (XMLStreamException e) {
			throw new ContentDecodingException(e.getMessage(), e);
		}
		// Use getLocalPart to strip namespaces.
		// Assumes we are working with a global default namespace of CCN.
		if (!startTag.equals(event.asStartElement().getName().getLocalPart())) {
			// Coming back with namespace decoration doesn't match
			throw new ContentDecodingException("Expected start element: " + startTag + " got: " + event.toString());
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

	public String peekStartElement() throws ContentDecodingException {
		XMLEvent event;
		try {
			while (true) {
				event = _reader.peek();
				if (event.isCharacters())
					event = _reader.nextEvent();
				else
					break;
			}
			
		} catch (XMLStreamException e) {
			throw new ContentDecodingException(e.getMessage(), e);
		}
		if ((null == event) || !event.isStartElement()) {
			return null;
		}
		return event.asStartElement().getName().getLocalPart();
	}

	public boolean peekStartElement(String startTag) throws ContentDecodingException {
		String decodedTag = peekStartElement();
		if ((null == decodedTag) || (!startTag.equals(decodedTag))) {
			return false;
		}	
		return true;
	}
	
	/**
	 * Helper method to decode text (UTF-8) and binary elements. Consumes the end element.
	 * @return the read data, as a String
	 * @throws ContentDecodingException if there is a problem decoding the data
	 */
	public String readElementText() throws ContentDecodingException {
		StringBuffer buf = new StringBuffer();
		XMLEvent event;
		try {
			event = _reader.nextEvent();
		} catch (XMLStreamException e) {
			throw new ContentDecodingException(e.getMessage(), e);
		}
		
		// Handles empty text element.
		while (event.isCharacters()) {
			buf.append(((Characters)event).getData());
			try {
				event = _reader.nextEvent();
			} catch (XMLStreamException e) {
				throw new ContentDecodingException(e.getMessage(), e);
			}
		}
		if (event.isStartElement()) {
			throw new ContentDecodingException("readElementText expects start element to have been previously consumed, got: " + event.toString());
		} else if (!event.isEndElement()) {
			throw new ContentDecodingException("Expected end of text element, got: " + event.toString());
		}
		return buf.toString();
	}

	public void readEndElement() throws ContentDecodingException {
		XMLEvent event;
		try {
			event = _reader.nextEvent();
		} catch (XMLStreamException e) {
			throw new ContentDecodingException(e.getMessage(), e);
		}
		if (!event.isEndElement()) {
			throw new ContentDecodingException("Expected end element, got: " + event.toString());
		}
	}

	public String readUTF8Element(String startTag) throws ContentDecodingException {
		return readUTF8Element(startTag, null);
	}

	public String readUTF8Element(String startTag,
								  TreeMap<String, String> attributes) throws ContentDecodingException {
		readStartElement(startTag, attributes); // can't use getElementText, can't get attributes
		String strElementText = readElementText();
		// readEndElement(); // readElementText consumes end element
		return strElementText;
	}

	public byte[] readBinaryElement(String startTag) throws ContentDecodingException {
		return readBinaryElement(startTag, null);
	}

	public byte[] readBinaryElement(String startTag,
			TreeMap<String, String> attributes) throws ContentDecodingException {
		try {
			readStartElement(startTag, attributes); // can't use getElementText, can't get attributes
			String strElementText = readElementText();
			// readEndElement(); // readElementText consumes end element
			return TextXMLCodec.decodeBinaryElement(strElementText);
		} catch (IOException e) {
			throw new ContentDecodingException(e.getMessage(), e);
		}
	}
	
	public CCNTime readDateTime(String startTag) throws ContentDecodingException {
		String strTimestamp = readUTF8Element(startTag);
		CCNTime timestamp;
		try {
			timestamp = TextXMLCodec.parseDateTime(strTimestamp);
		} catch (ParseException e) {
			timestamp = null;
		}
		if (null == timestamp) {
			throw new ContentDecodingException("Cannot parse timestamp: " + strTimestamp);
		}		
		return timestamp;
	}

	public BinaryXMLDictionary popXMLDictionary() {
		return null;
	}

	public void pushXMLDictionary(BinaryXMLDictionary dictionary) {}
}
