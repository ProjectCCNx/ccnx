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

import java.io.OutputStream;
import java.util.Iterator;
import java.util.TreeMap;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

import org.ccnx.ccn.protocol.CCNTime;

public class TextXMLEncoder extends GenericXMLEncoder implements XMLEncoder {

	protected OutputStream _ostream = null;
	protected XMLStreamWriter _writer = null;
	protected boolean _isFirstElement = true;
	
	public TextXMLEncoder() {}

	public void beginEncoding(OutputStream ostream) throws XMLStreamException {
		if (null == ostream)
			throw new IllegalArgumentException("TextXMLEncoder: output stream cannot be null!");
		
		_ostream = ostream;
		
		XMLOutputFactory factory = XMLOutputFactory.newInstance();
		_writer = factory.createXMLStreamWriter(ostream);
		_writer.setPrefix(TextXMLCodec.CCN_PREFIX, TextXMLCodec.CCN_NAMESPACE);
		_writer.setDefaultNamespace(TextXMLCodec.CCN_NAMESPACE);
		// DKS -- need to set encoding when creating factory, and set it here.
		_writer.writeStartDocument();
		// Can't write default namespace till we write an element...
		_isFirstElement = true;
	}
	
	public void endEncoding() throws XMLStreamException {
		_writer.writeEndDocument();
		_writer.flush();   				
	}
	
	public void writeElement(String tag, String utf8Content)
			throws XMLStreamException {
		writeElement(tag, utf8Content, null);
	}

	public void writeElement(String tag, String utf8Content,
			TreeMap<String, String> attributes) throws XMLStreamException {
		writeStartElement(tag, attributes);
		_writer.writeCharacters(utf8Content);
		writeEndElement();
	}

	public void writeElement(String tag, byte[] binaryContent)
			throws XMLStreamException {
		writeElement(tag, binaryContent, null);
	}

	public void writeElement(String tag, byte[] binaryContent, int offset, int length)
			throws XMLStreamException {
		writeElement(tag, binaryContent, offset, length, null);
	}

	public void writeElement(String tag, byte[] binaryContent,
			TreeMap<String, String> attributes) throws XMLStreamException {
		if (null == attributes) {
			attributes = new TreeMap<String,String>();
		}
		if (!attributes.containsKey(TextXMLCodec.BINARY_ATTRIBUTE)) {
			attributes.put(TextXMLCodec.BINARY_ATTRIBUTE, TextXMLCodec.BINARY_ATTRIBUTE_VALUE);
		}
		writeStartElement(tag, attributes);
		_writer.writeCharacters(TextXMLCodec.encodeBinaryElement(binaryContent));
		writeEndElement();
	}

	public void writeElement(String tag, byte[] binaryContent, int offset, int length,
			TreeMap<String, String> attributes) throws XMLStreamException {
		if (null == attributes) {
			attributes = new TreeMap<String,String>();
		}
		if (!attributes.containsKey(TextXMLCodec.BINARY_ATTRIBUTE)) {
			attributes.put(TextXMLCodec.BINARY_ATTRIBUTE, TextXMLCodec.BINARY_ATTRIBUTE_VALUE);
		}
		writeStartElement(tag, attributes);
		_writer.writeCharacters(TextXMLCodec.encodeBinaryElement(binaryContent, offset, length));
		writeEndElement();
	}

	/**
	 * For now, same as text. Might want something more compact.
	 */
	public void writeDateTime(String tag, CCNTime dateTime) throws XMLStreamException {
		writeElement(tag, 
				TextXMLCodec.formatDateTime(dateTime));
	}

	public void writeStartElement(String tag) throws XMLStreamException {
		writeStartElement(tag, null);
	}
	
	public void writeStartElement(String tag, TreeMap<String, String> attributes) throws XMLStreamException {
		writeStartElement(tag, attributes, null);
	}

	public void writeStartElement(String tag, TreeMap<String, String> attributes, BinaryXMLDictionary dictionary)
			throws XMLStreamException {
		_writer.writeStartElement(TextXMLCodec.CCN_NAMESPACE, tag);
		if (_isFirstElement) {
			_writer.writeDefaultNamespace(TextXMLCodec.CCN_NAMESPACE);
			_isFirstElement = false;
		}
		
		if (null != attributes) {
			// keySet of a TreeMap is ordered
			Iterator<String> atIt = attributes.keySet().iterator();
			while (atIt.hasNext()) {
				String name = atIt.next();
				// Might not play well if this is the first element (namespace writing issues...)
				_writer.writeAttribute(TextXMLCodec.CCN_NAMESPACE, name, attributes.get(name));
			}
		}
	}

	public void writeEndElement() throws XMLStreamException {
		_writer.writeEndElement();
	}

	public BinaryXMLDictionary popXMLDictionary() {
		return null;
	}

	public void pushXMLDictionary(BinaryXMLDictionary dictionary) {
	}
}
