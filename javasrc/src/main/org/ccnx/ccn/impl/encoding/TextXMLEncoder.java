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
import java.io.OutputStream;
import java.util.Iterator;
import java.util.TreeMap;

import org.ccnx.ccn.io.content.ContentEncodingException;
import org.ccnx.ccn.protocol.CCNTime;
import org.xmlpull.v1.XmlPullParserFactory;
import org.xmlpull.v1.XmlSerializer;

/**
 * An implementation of XMLEncoder for the Text codec. 
 * 
 * @see TextXMLCodec
 * @see XMLEncoder
 */
public class TextXMLEncoder extends GenericXMLEncoder implements XMLEncoder {

	protected XmlSerializer _serializer = null;
	
	/**
	 * Create a BinaryXMLEncoder initialized with the default dictionary obtained
	 * from BinaryXMLDictionary#getDefaultDictionary().
	 */
	public TextXMLEncoder() {
		super();
	}

	/**
	 * Create a BinaryXMLEncoder initialized with a specified dictionary.
	 * @param dictionary the dictionary to use, if null the default dictionary is used.
	 */
	public TextXMLEncoder(XMLDictionary dictionary) {
		super(dictionary);
	}

	public void beginEncoding(OutputStream ostream) throws ContentEncodingException {
		if (null == ostream)
			throw new IllegalArgumentException("TextXMLEncoder: output stream cannot be null!");
		
		_ostream = ostream;
		
		try {
			//XmlPullParserFactory factory = XmlPullParserFactory.newInstance("org.kxml2.io.XmlSerializer", null);
			XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
    		factory.setNamespaceAware(true);
    		_serializer = factory.newSerializer();
    		_serializer.setOutput(ostream, "UTF-8");
    		_serializer.setPrefix(TextXMLCodec.CCN_PREFIX, TextXMLCodec.CCN_NAMESPACE);
			// DKS -- need to set encoding when creating factory, and set it here.
    		//Write <?xml declaration with encoding (if encoding not null) and standalone flag (if standalone not null)
    		_serializer.startDocument(null, Boolean.valueOf(true));
    		//set indentation option
    		_serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
		} catch (Exception e) {
			throw new ContentEncodingException(e.getMessage(), e);
		}
	}
	
	public void endEncoding() throws ContentEncodingException {
		try {
			_serializer.endDocument();
			_serializer.flush();
			_ostream.close();
		} catch (Exception e) {
			throw new ContentEncodingException(e.getMessage(), e);
		}
	}
	
	public void writeStartElement(long tag, TreeMap<String, String> attributes) throws ContentEncodingException {
		String strTag = tagToString(tag);
		if (null == strTag) {
			strTag = XMLDictionaryStack.unknownTagMarker(tag);
		}
		writeStartElement(strTag, attributes);
	}
	
	public void writeStartElement(String tag, TreeMap<String, String> attributes) throws ContentEncodingException {
		try {
			_serializer.startTag(null, tag);

			if (null != attributes) {
				// keySet of a TreeMap is ordered
				Iterator<String> atIt = attributes.keySet().iterator();
				while (atIt.hasNext()) {
					String name = atIt.next();
					// Might not play well if this is the first element (namespace writing issues...)
					_serializer.attribute(null, name, attributes.get(name));
				}
			}
		} catch (Exception e) {
			throw new ContentEncodingException(e.getMessage(), e);
		}
	}

	public void writeUString(String utf8Content) throws ContentEncodingException {
		try {
			_serializer.text(utf8Content);
		} catch (IOException e) {
			throw new ContentEncodingException(e.getMessage(), e);
		}
	}

	public void writeBlob(byte [] binaryContent) throws ContentEncodingException {
		try {
			_serializer.text(TextXMLCodec.encodeBinaryElement(binaryContent));
		} catch (IOException e) {
			throw new ContentEncodingException(e.getMessage(), e);
		}
	}

	public void writeBlob(byte [] binaryContent, int offset, int length) throws ContentEncodingException {
		try {
			_serializer.text(TextXMLCodec.encodeBinaryElement(binaryContent, offset, length));
		} catch (IOException e) {
			throw new ContentEncodingException(e.getMessage(), e);
		}
	}

	public void writeElement(String tag, byte[] binaryContent,
			TreeMap<String, String> attributes) throws ContentEncodingException {
		if (null == attributes) {
			attributes = new TreeMap<String,String>();
		}
		if (!attributes.containsKey(TextXMLCodec.BINARY_ATTRIBUTE)) {
			attributes.put(TextXMLCodec.BINARY_ATTRIBUTE, TextXMLCodec.BINARY_ATTRIBUTE_VALUE);
		}
		super.writeElement(tag, binaryContent, attributes);
	}

	public void writeElement(String tag, byte[] binaryContent, int offset, int length,
			TreeMap<String, String> attributes) throws ContentEncodingException {
		if (null == attributes) {
			attributes = new TreeMap<String,String>();
		}
		if (!attributes.containsKey(TextXMLCodec.BINARY_ATTRIBUTE)) {
			attributes.put(TextXMLCodec.BINARY_ATTRIBUTE, TextXMLCodec.BINARY_ATTRIBUTE_VALUE);
		}
		super.writeElement(tag, binaryContent, offset, length, attributes);
	}

	public void writeDateTime(String tag, CCNTime dateTime) throws ContentEncodingException {
		writeElement(tag, TextXMLCodec.formatDateTime(dateTime));
	}

	public void writeDateTime(long tag, CCNTime dateTime) throws ContentEncodingException {
		writeElement(tag, TextXMLCodec.formatDateTime(dateTime));
	}

	public void writeEndElement() throws ContentEncodingException {
		try {
			_serializer.endTag(null, _serializer.getName());
		} catch (Exception e) {
			throw new ContentEncodingException(e.getMessage(), e);
		}		
	}
}
