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
import java.util.TreeMap;

import javax.xml.stream.XMLStreamException;

import org.ccnx.ccn.protocol.CCNTime;


public interface XMLEncoder {
	
	/**
	 * Handles writing the document start.
	 * @param ostream
	 * @throws XMLStreamException
	 */
	public void beginEncoding(OutputStream ostream) throws XMLStreamException;
	
	/**
	 * Handles writing the document end.
	 * @throws XMLStreamException
	 */
	public void endEncoding() throws XMLStreamException;
	
	public void writeStartElement(String tag) throws XMLStreamException;
	
	public void writeStartElement(String tag, TreeMap<String,String> attributes) throws XMLStreamException;
	
	public void writeEndElement() throws XMLStreamException;   		
	
	public void writeElement(String tag, String utf8Content) throws XMLStreamException;
	
	public void writeElement(String tag, String utf8Content, 
			TreeMap<String,String> attributes) throws XMLStreamException;
	
	public void writeElement(String tag, byte [] binaryContent) throws XMLStreamException;
	
	public void writeElement(String tag, byte [] binaryContent, int offset, int length) throws XMLStreamException;
	
	public void writeElement(String tag, byte [] binaryContent, 
			TreeMap<String,String> attributes) throws XMLStreamException;

	public void writeElement(String tag, byte [] binaryContent, int offset, int length,
			TreeMap<String,String> attributes) throws XMLStreamException;
	
	/**
	 * Encapsulate string handling.
	 */
	public void writeIntegerElement(String tag, Integer value) throws XMLStreamException;
	
	/**
	 * Encapsulate our handling of timestamps.
	 */
	public void writeDateTime(String tag, CCNTime dateTime) throws XMLStreamException;
	
	public void pushXMLDictionary(BinaryXMLDictionary dictionary);
	
	public BinaryXMLDictionary popXMLDictionary();
}
