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
import java.io.OutputStream;
import java.nio.ByteBuffer;

import javax.xml.stream.XMLStreamException;

public interface XMLEncodable {
	
	/**
	 * Decode this object as the top-level item in a new
	 * XML document. Reads document start and end. Assumes
	 * default encoding.
	 * @param iStream
	 * @throws XMLStreamException
	 */
	public void decode(InputStream istream) throws XMLStreamException;
	
	public void decode(InputStream istream, String codec) throws XMLStreamException;

	/**
	 * Helper method. Assumes default encoding.
	 */
	public void decode(byte [] objectBuffer) throws XMLStreamException;
	
	public void decode(byte [] objectBuffer, String codec) throws XMLStreamException;

	/** 
	 * Helper method using network buffer objects.
	 */
	public void decode(ByteBuffer buf) throws XMLStreamException;
	
	public void decode(ByteBuffer buf, String codec) throws XMLStreamException;

	/**
	 * Pull this item from an ongoing decoding pass.
	 */
	public void decode(XMLDecoder decoder) throws XMLStreamException;

	/**
	 * Encode this object as the top-level item in a new 
	 * XML document. Writes start and end document. Assumes default encoding.
	 * @param oStream
	 * @throws XMLStreamException
	 */
	public void encode(OutputStream ostream) throws XMLStreamException;

	public void encode(OutputStream ostream, String codec) throws XMLStreamException;
	
	/**
	 * Helper function. Should return canonicalized encoding.
	 * @return
	 * @throws XMLStreamException
	 */
	public byte [] encode() throws XMLStreamException;
	
	public byte [] encode(String codec) throws XMLStreamException;

	/**
	 * Write this item to an ongoing encoding pass. 
	 * @param isFirstElement is this the first element after the
	 * 	start of the document; if so it needs to start the
	 * 	default namespace.
	 */
	public void encode(XMLEncoder encoder) throws XMLStreamException;
	
	/** 
	 * Allow the encoder/decoder to retrieve the top-level element name
	 * programmatically. This allows subclasses to rename elements without
	 * changing their encoder/decoders. 
	 */
	public String getElementLabel();

	/**
	 * Make sure all of the necessary fields are filled in
	 * prior to attempting to encode.
	 */
	public boolean validate();
}
