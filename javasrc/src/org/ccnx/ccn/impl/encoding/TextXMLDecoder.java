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
import java.util.TreeMap;

import org.ccnx.ccn.io.content.ContentDecodingException;
import org.ccnx.ccn.protocol.CCNTime;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

/**
 * An implementation of XMLDecoder for the Text codec.
 * 
 * @see TextXMLCodec
 * @see XMLDecoder
 */
public class TextXMLDecoder extends GenericXMLDecoder implements XMLDecoder {

	protected InputStream _istream = null;
	protected XmlPullParser _reader = null;

	public TextXMLDecoder() {
	}
	
	public void beginDecoding(InputStream istream) throws ContentDecodingException {
		if (null == istream)
			throw new IllegalArgumentException("TextXMLDecoder: input stream cannot be null!");
		_istream = istream;
		try {
			XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            factory.setNamespaceAware(true);
			_reader = factory.newPullParser();
			_reader.setInput(istream, null);
		} catch (XmlPullParserException e) {
			throw new ContentDecodingException(e.getMessage(), e);
		}
		
		readStartDocument();
	}
	
	public void endDecoding() throws ContentDecodingException {
		readEndDocument();
	}

	public void readStartDocument() throws ContentDecodingException {
		try {
			int event = _reader.getEventType();
			_reader.next();
			if (event != XmlPullParser.START_DOCUMENT) {
				throw new ContentDecodingException("Expected start document, got: " + XmlPullParser.TYPES[event]);
			}
		} catch (XmlPullParserException e) {
			throw new ContentDecodingException(e.getMessage(), e);
		} catch (IOException e) {
			throw new ContentDecodingException(e.getMessage(), e);
		}
	}

	public void readEndDocument() throws ContentDecodingException {
		int event;
		try {
			event = _reader.getEventType();
		} catch (XmlPullParserException e) {
			throw new ContentDecodingException(e.getMessage(), e);
		}
		if (event != XmlPullParser.END_DOCUMENT) {
			throw new ContentDecodingException("Expected end document, got: " + XmlPullParser.TYPES[event]);
		}
	}

	public void readStartElement(String startTag) throws ContentDecodingException {
		readStartElement(startTag, null);
	}

	public void readStartElement(String startTag,
								TreeMap<String, String> attributes) throws ContentDecodingException {

		int event = readToNextTag(XmlPullParser.START_TAG);
		if (event != XmlPullParser.START_TAG) {
			throw new ContentDecodingException("Expected start element, got: " + XmlPullParser.TYPES[event]);
		}
		// Use getLocalPart to strip namespaces.
		// Assumes we are working with a global default namespace of CCN.
		if (!startTag.equals(_reader.getName())) {
			// Coming back with namespace decoration doesn't match
			throw new ContentDecodingException("Expected start element: " + startTag + " got: " + _reader.getName());
		}	
		if (null != attributes) {
			// we might be expecting attributes
			for (int i=0; i < _reader.getAttributeCount(); ++i) {
				// may need fancier namespace handling.
				attributes.put(_reader.getAttributeName(i), _reader.getAttributeValue(i));
			}
		}
		try {
			_reader.next();
		} catch (XmlPullParserException e) {
			throw new ContentDecodingException(e.getMessage());
		} catch (IOException e) {
			throw new ContentDecodingException(e.getMessage());
		}
	}

	public String peekStartElement() throws ContentDecodingException {
		int event = readToNextTag(XmlPullParser.START_TAG);
		if (event != XmlPullParser.START_TAG) {
			return null;
		}
		return _reader.getName();
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
		try {
			int event = _reader.getEventType();;
			// Handles empty text element.
			while (event == XmlPullParser.TEXT) {
				buf.append(_reader.getText());
				event = _reader.next();
			}
			if (event == XmlPullParser.START_TAG) {
				throw new ContentDecodingException("readElementText expects start element to have been previously consumed, got: " + XmlPullParser.TYPES[event]);
			} else if (event != XmlPullParser.END_TAG) {
				throw new ContentDecodingException("Expected end of text element, got: " + XmlPullParser.TYPES[event]);
			}
			event = _reader.next();
			return buf.toString();
		} catch (XmlPullParserException e) {
			throw new ContentDecodingException(e.getMessage(), e);
		} catch (IOException e) {
			throw new ContentDecodingException(e.getMessage(), e);
		}
	}

	public void readEndElement() throws ContentDecodingException {
		int event = readToNextTag(XmlPullParser.END_TAG);
		if (event != XmlPullParser.END_TAG) {
			throw new ContentDecodingException("Expected end element, got: " + XmlPullParser.TYPES[event]);
		}
		try {
			_reader.next();
		} catch (XmlPullParserException e) {
			throw new ContentDecodingException(e.getMessage());
		} catch (IOException e) {
			throw new ContentDecodingException(e.getMessage());
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
	
	private int readToNextTag(int type) throws ContentDecodingException {
		int event;
		try {
			do {
				event = _reader.getEventType();
				if (event == type)
					return event;
				event = _reader.next();
			} while (event == XmlPullParser.TEXT || event == XmlPullParser.COMMENT);		// Assume if its not a startElement it's a comment
		} catch (XmlPullParserException e) {
			throw new ContentDecodingException(e.getMessage(), e);
		} catch (IOException e) {
			throw new ContentDecodingException(e.getMessage(), e);
		}
		return event;
	}
}
