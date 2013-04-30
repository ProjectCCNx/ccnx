/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008, 2009, 2011 Palo Alto Research Center, Inc.
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

	protected XmlPullParser _reader = null;

	public TextXMLDecoder() {
		super();
	}

	public TextXMLDecoder(XMLDictionary dictionary) {
		super(dictionary);
	}
		
	public void initializeDecoding() throws ContentDecodingException {
		try {
			XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            factory.setNamespaceAware(true);
			_reader = factory.newPullParser();
			_reader.setInput(_istream, null);
		} catch (XmlPullParserException e) {
			throw new ContentDecodingException(e.getMessage(), e);
		}		
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

	public void readStartElement(long startTagLong,
			TreeMap<String, String> attributes) throws ContentDecodingException {
		
		String startTag = tagToString(startTagLong);

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
	public String peekStartElementAsString() throws ContentDecodingException {
		int event = readToNextTag(XmlPullParser.START_TAG);
		if (event != XmlPullParser.START_TAG) {
			return null;
		}
		return _reader.getName();
	}
	
	public Long peekStartElementAsLong() throws ContentDecodingException {
		String strTag = peekStartElementAsString();
		if (null == strTag) {
			return null; // e.g. hit an end tag...
		}
		return stringToTag(strTag);
	}
	
	/**
	 * Helper method to decode text (UTF-8) and binary elements. Consumes the end element,
	 * behavior which other decoders are forced to match.
	 * @return the read data, as a String
	 * @throws ContentDecodingException if there is a problem decoding the data
	 */
	public String readUString() throws ContentDecodingException {
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
			readEndElement();
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

	/**
	 * Read a BLOB. Consumes the end element, so force other versions
	 * to match.
	 */
	public byte [] readBlob() throws ContentDecodingException {
		try {
			String strElementText = readUString();
			// readEndElement(); // readElementText consumes end element
			return TextXMLCodec.decodeBinaryElement(strElementText);
		} catch (IOException e) {
			throw new ContentDecodingException(e.getMessage(),e);
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

	public CCNTime readDateTime(long startTag) throws ContentDecodingException {
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

	private int readToNextTag(int type) throws ContentDecodingException {
		int event;
		try {
			event = _reader.getEventType();
			if (event == type)
				return event;
			if (event == XmlPullParser.TEXT || event == XmlPullParser.COMMENT)
				event = _reader.next();
		} catch (IOException e) {
			throw new ContentDecodingException(e.getMessage(), e);
		} catch (XmlPullParserException e) {
			throw new ContentDecodingException(e.getMessage(), e);
		}
		return event;
	}
}
