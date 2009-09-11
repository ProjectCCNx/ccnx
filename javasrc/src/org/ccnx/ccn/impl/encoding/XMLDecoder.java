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

import java.io.InputStream;
import java.util.TreeMap;

import javax.xml.stream.XMLStreamException;

import org.ccnx.ccn.protocol.CCNTime;

public interface XMLDecoder {
	
	/**
	 * Handles writing any necessary start document, etc.
	 * @param istream
	 * @throws XMLStreamException
	 */
	public void beginDecoding(InputStream istream) throws XMLStreamException;
	
	public void endDecoding() throws XMLStreamException;

	public void readStartDocument() throws XMLStreamException;

	public void readEndDocument() throws XMLStreamException;
	
	public void readStartElement(String startTag) throws XMLStreamException;
		
	public void readStartElement(String startTag,
			TreeMap<String,String> attributes) throws XMLStreamException;

	public boolean peekStartElement(String startTag) throws XMLStreamException;
	
	public String peekStartElement() throws XMLStreamException;
	
	public void readEndElement() throws XMLStreamException;

	public String readUTF8Element(String startTag,
			TreeMap<String,String> attributes) throws XMLStreamException;
	
	public String readUTF8Element(String startTag) throws XMLStreamException;
	
	public byte [] readBinaryElement(String startTag, TreeMap<String,String> attributes) throws XMLStreamException;
	
	public byte [] readBinaryElement(String startTag) throws XMLStreamException;
	
	/**
	 * Encapsulate integer parsing.
	 */
	public Integer readIntegerElement(String startTag) throws XMLStreamException;

	/**
	 * Encapsulate our handling of timestamps.
	 */
	public CCNTime readDateTime(String startTag) throws XMLStreamException;
	
	public void pushXMLDictionary(BinaryXMLDictionary dictionary);
	
	public BinaryXMLDictionary popXMLDictionary();
}
