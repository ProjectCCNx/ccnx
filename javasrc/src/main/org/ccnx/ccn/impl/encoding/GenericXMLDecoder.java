/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008-2012 Palo Alto Research Center, Inc.
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
import java.util.TreeMap;

import org.ccnx.ccn.io.content.ContentDecodingException;

/**
 * This class contains methods for content encoding/decoding common to all or many codecs.
 */
public abstract class GenericXMLDecoder extends GenericXMLHandler implements XMLDecoder {

	protected InputStream _istream = null;

	public GenericXMLDecoder() {
		super();
	}

	public GenericXMLDecoder(XMLDictionary dictionary) {
		super(dictionary);
	}

	public void beginDecoding(InputStream istream) throws ContentDecodingException {
		if (null == istream)
			throw new IllegalArgumentException(this.getClass().getName() + ": input stream cannot be null!");
		_istream = istream;
		initializeDecoding();
		readStartDocument();
	}

	/**
	 * Default implementation does nothing. Subclass-specific parser setup.
	 */
	public void initializeDecoding() throws ContentDecodingException {
	}

	public void endDecoding() throws ContentDecodingException {
		readEndDocument();
	}

	public void readStartElement(String startTag) throws ContentDecodingException {
		readStartElement(startTag, null);
	}

	public void readStartElement(long startTag) throws ContentDecodingException {
		readStartElement(startTag, null);
	}

	public boolean peekStartElement(String startTag) throws ContentDecodingException {
		String decodedTag = peekStartElementAsString();
		if ((null !=  decodedTag) && (decodedTag.equals(startTag))) {
			return true;
		}
		return false;
	}

	public boolean peekStartElement(long startTag) throws ContentDecodingException {
		Long decodedTag = peekStartElementAsLong();
		if ((null !=  decodedTag) && (decodedTag.longValue() == startTag)) {
			return true;
		}
		return false;
	}

	public String readUTF8Element(String startTag) throws ContentDecodingException {
		return readUTF8Element(startTag, null);
	}

	public String readUTF8Element(long startTag) throws ContentDecodingException {
		return readUTF8Element(startTag, null);
	}

	/**
	 * Force low-level readers to all consume the end element, to get behavior
	 * matching the text decoder (lowest common denominator); allows us to
	 * collapse this level of behavior here.
	 */
	public String readUTF8Element(String startTag,
			TreeMap<String, String> attributes) throws ContentDecodingException {
		readStartElement(startTag, attributes); // can't use getElementText, can't get attributes
		String strElementText = readUString();
		// readEndElement(); // readUString consumes end element
		return strElementText;
	}

	/**
	 * Force low-level readers to all consume the end element, to get behavior
	 * matching the text decoder (lowest common denominator); allows us to
	 * collapse this level of behavior here.
	 */
	public String readUTF8Element(long startTag,
			TreeMap<String, String> attributes) throws ContentDecodingException {
		readStartElement(startTag, attributes); // can't use getElementText, can't get attributes
		String strElementText = readUString();
		// readEndElement(); // readUString consumes end element
		return strElementText;
	}

	public byte[] readBinaryElement(String startTag) throws ContentDecodingException {
		return readBinaryElement(startTag, null);
	}

	public byte[] readBinaryElement(long startTag) throws ContentDecodingException {
		return readBinaryElement(startTag, null);
	}

	/**
	 * Expect a start tag (label), optional attributes, a BLOB, and an end element.
	 * Force low-level readers to all consume the end element, to get behavior
	 * matching the text decoder (lowest common denominator); allows us to
	 * collapse this level of behavior here.
	 */
	public byte [] readBinaryElement(String startTag,
			TreeMap<String, String> attributes) throws ContentDecodingException {
		byte [] blob = null;
		try {
			readStartElement(startTag, attributes);
			blob = readBlob();
			// readEndElement(); // readBlob consumes end element
		} catch (IOException e) {
			throw new ContentDecodingException(e.getMessage(),e);
		}

		return blob;
	}

	/**
	 * Force low-level readers to all consume the end element, to get behavior
	 * matching the text decoder (lowest common denominator); allows us to
	 * collapse this level of behavior here.
	 */
	public byte[] readBinaryElement(long startTag,
			TreeMap<String, String> attributes) throws ContentDecodingException {
		byte [] blob = null;
		try {
			readStartElement(startTag, attributes);
			blob = readBlob();
			// readEndElement(); // readBlob consumes end element
		} catch (IOException e) {
			throw new ContentDecodingException(e.getMessage(), e);
		}

		return blob;
	}

	public long readLongElement(String startTag) throws ContentDecodingException {
		String strVal = readUTF8Element(startTag);
		try {
			return Long.valueOf(strVal);
		} catch (NumberFormatException e) {
			throw new ContentDecodingException("Cannot parse " + startTag + ": " + strVal, e);
		}
	}

	public long readLongElement(long startTag) throws ContentDecodingException {
		String strVal = readUTF8Element(startTag);
		try {
			return Long.valueOf(strVal);
		} catch (NumberFormatException e) {
			throw new ContentDecodingException("Cannot parse " + startTag + ": " + strVal, e);
		}
	}

	public int readIntegerElement(String startTag) throws ContentDecodingException {
		String strVal = readUTF8Element(startTag);
		try {
			return Integer.valueOf(strVal);
		} catch (NumberFormatException e) {
			throw new ContentDecodingException("Cannot parse " + startTag + ": " + strVal, e);
		}
	}

	public int readIntegerElement(long startTag) throws ContentDecodingException {
		String strVal = readUTF8Element(startTag);
		try {
			return Integer.valueOf(strVal);
		} catch (NumberFormatException e) {
			throw new ContentDecodingException("Cannot parse " + startTag + ": " + strVal, e);
		}
	}

	public InputStream getInputStream() {
		return _istream;
	}

}
