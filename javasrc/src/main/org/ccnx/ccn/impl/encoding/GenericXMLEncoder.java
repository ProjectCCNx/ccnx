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

import java.io.OutputStream;
import java.util.TreeMap;

import org.ccnx.ccn.io.content.ContentEncodingException;

/**
 * This class contains methods for content encoding/decoding common to all or many codecs.
 */
public abstract class GenericXMLEncoder extends GenericXMLHandler implements XMLEncoder {

	protected OutputStream _ostream = null;
	
	public GenericXMLEncoder() {
		super();
	}

	public GenericXMLEncoder(XMLDictionary dictionary) {
		super(dictionary);
	}

	public void writeStartElement(String tag) throws ContentEncodingException {
		writeStartElement(tag, null);
	}
	
	public void writeStartElement(long tag) throws ContentEncodingException {
		writeStartElement(tag, null);
	}
	
	public void writeElement(String tag, String utf8Content) throws ContentEncodingException {
		writeElement(tag, utf8Content, null);
	}

	public void writeElement(long tag, String utf8Content) throws ContentEncodingException {
		writeElement(tag, utf8Content, null);
	}

	public void writeElement(String tag, String utf8Content,
			TreeMap<String, String> attributes) throws ContentEncodingException {
		writeStartElement(tag, attributes);
		// Will omit if 0-length
		writeUString(utf8Content);
		writeEndElement();
	}
	
	public void writeElement(long tag, String utf8Content,
			TreeMap<String, String> attributes) throws ContentEncodingException {
		writeStartElement(tag, attributes);
		// Will omit if 0-length
		writeUString(utf8Content);
		writeEndElement();
	}

	public void writeElement(String tag, byte[] binaryContent) throws ContentEncodingException {
		writeElement(tag, binaryContent, null);
	}

	public void writeElement(long tag, byte[] binaryContent) throws ContentEncodingException {
		writeElement(tag, binaryContent, null);
	}

	public void writeElement(String tag, byte[] binaryContent, int offset, int length) throws ContentEncodingException {
		writeElement(tag, binaryContent, offset, length, null);
	}

	public void writeElement(long tag, byte[] binaryContent, int offset, int length) throws ContentEncodingException {
		writeElement(tag, binaryContent, offset, length, null);
	}


	public void writeElement(String tag, byte[] binaryContent,
			TreeMap<String, String> attributes) throws ContentEncodingException {
		writeStartElement(tag, attributes);
		// Will omit if 0-length
		writeBlob(binaryContent);
		writeEndElement();
	}

	public void writeElement(long tag, byte[] binaryContent,
			TreeMap<String, String> attributes) throws ContentEncodingException {
		writeStartElement(tag, attributes);
		// Will omit if 0-length
		writeBlob(binaryContent);
		writeEndElement();
	}

	public void writeElement(String tag, byte[] binaryContent,
			int offset, int length,
			TreeMap<String, String> attributes) throws ContentEncodingException {
		writeStartElement(tag, attributes);
		// Will omit if 0-length
		writeBlob(binaryContent, offset, length);
		writeEndElement();
	}

	public void writeElement(long tag, byte[] binaryContent,
			int offset, int length,
			TreeMap<String, String> attributes) throws ContentEncodingException {
		writeStartElement(tag, attributes);
		// Will omit if 0-length
		writeBlob(binaryContent, offset, length);
		writeEndElement();
	}

	public void writeElement(String tag, long value) throws ContentEncodingException {
		writeElement(tag, Long.toString(value));
	}

	public void writeElement(long tag, long value) throws ContentEncodingException {
		writeElement(tag, Long.toString(value));
	}
}
