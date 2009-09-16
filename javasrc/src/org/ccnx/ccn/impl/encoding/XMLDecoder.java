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

/**
 * Each XMLCodec consists effectively of an encoder/decoder pair. The decoder implements
 * this interface, the encoder must implement XMLEncoder. These do the work of encoding
 * or decoding content. The ccnb binary encoding supports only a number of simple types,
 * all codecs are limited to these same types for simplicity.
 */
public interface XMLDecoder {
	
	/**
	 * Initiates top-level decoding, handling any necessary initialization. Calls readStartDocment()
	 * to read the document start. 
	 * @param istream stream to decode from
	 * @throws XMLStreamException if there is an error in decoding
	 */
	public void beginDecoding(InputStream istream) throws XMLStreamException;
	
	/**
	 * Completes top-level decoding, handling any necessary shutdown. Calls readEndDocment()
	 * to read the document end. 
	 * @throws XMLStreamException if there is an error in decoding
	 */
	public void endDecoding() throws XMLStreamException;

	/**
	 * Reads the document start marker, if there is one.
	 * @throws XMLStreamException if there is an error in decoding
	 */
	public void readStartDocument() throws XMLStreamException;

	/**
	 * Reads the document end marker, if there is one.
	 * @throws XMLStreamException if there is an error in decoding
	 */
	public void readEndDocument() throws XMLStreamException;
	
	/**
	 * Reads an expected element start tag from the stream
	 * @param startTag next tag we expect to occur
	 * @throws XMLStreamException if that tag does not occur, or another error is encountered
	 */
	public void readStartElement(String startTag) throws XMLStreamException;
		
	/**
	 * Reads an expected element start tag from the stream, extracting any
	 * attributes that are present on the tag
	 * @param startTag next tag we expect to occur
	 * @param attributes map into which we store (attribute, value) pairs
	 * @throws XMLStreamException if that tag does not occur, or another error is encountered
	 */
	public void readStartElement(String startTag,
			TreeMap<String,String> attributes) throws XMLStreamException;

	/**
	 * Peeks ahead in the stream to see if an expected element is next. Requires
	 * the underlying stream to return true from InputStream#markSupported(). Resets
	 * the stream to the point at which it was called afterwards
	 * @param startTag next tag we expect to occur
	 * @return true if that is the next tag, false otherwise
	 * @throws XMLStreamException if an error is encountered
	 */
	public boolean peekStartElement(String startTag) throws XMLStreamException;
	
	/**
	 * Pulls the next tag out of the stream and then resets the stream to the
	 * point before that tag. Requires the underlying stream to return true
	 * from InputStream#markSupported().
	 * @return the next tag found
	 * @throws XMLStreamException if there is an error reading the stream or decoding the tag
	 */
	public String peekStartElement() throws XMLStreamException;
	
	/**
	 * Reads an end element from the stream, if this codec has end elements.
	 * @throws XMLStreamException if the next element in the stream is not an end element, or
	 * 	there is another error reading
	 */
	public void readEndElement() throws XMLStreamException;

	/**
	 * Read a UTF-8 encoded string element from the stream.
	 * @param startTag expected start tag
	 * @param attributes will be used to hold attributes on this tag, if there are any
	 * @return the decoded String
	 * @throws XMLStreamException if startTag is not the next tag in the stream, or there is an error
	 * 		decoding the element
	 */
	public String readUTF8Element(String startTag,
			TreeMap<String,String> attributes) throws XMLStreamException;
	
	/**
	 * Read a UTF-8 encoded string element which has no attributes from the stream.
	 * @param startTag expected start tag
	 * @return the decoded String
	 * @throws XMLStreamException if startTag is not the next tag in the stream, or there is an error
	 * 		decoding the element
	 */
	public String readUTF8Element(String startTag) throws XMLStreamException;
	
	/**
	 * Read a binary element from the stream.
	 * @param startTag expected start tag
	 * @param attributes will be used to hold attributes on this tag, if there are any
	 * @return the decoded byte array
	 * @throws XMLStreamException if startTag is not the next tag in the stream, or there is an error
	 * 		decoding the element
	 */
	public byte [] readBinaryElement(String startTag, TreeMap<String,String> attributes) throws XMLStreamException;
	
	/**
	 * Read a binary element with no attributes from the stream.
	 * @param startTag expected start tag
	 * @return the decoded byte array
	 * @throws XMLStreamException if startTag is not the next tag in the stream, or there is an error
	 * 		decoding the element
	 */
	public byte [] readBinaryElement(String startTag) throws XMLStreamException;
	
	/**
	 * Read and parse an integer from the stream. 
	 * @param startTag expected start tag
	 * @return the decoded integer, or null if content was empty
	 * @throws XMLStreamException if startTag is not the next tag in the stream, or there is an error
	 * 		decoding the element or parsing the integer
	 */
	public Integer readIntegerElement(String startTag) throws XMLStreamException;

	/**
	 * Read and parse a timestamp from the stream. 
	 * @param startTag expected start tag
	 * @return the decoded timestamp, using a quantized CCNTime
	 * @throws XMLStreamException if startTag is not the next tag in the stream, or there is an error
	 * 		decoding the element or parsing the integer
	 */
	public CCNTime readDateTime(String startTag) throws XMLStreamException;
	
	/**
	 * Some codecs use a dictionary to make encoding and decoding more efficient. Those
	 * codecs typically load their default dictionary automatically. This call allows a user
	 * to manipulate the dictionary stack, adding their own dictionaries to the set used to
	 * decode.
	 * @param dictionary a dictionary to add to the set used for decoding
	 */
	public void pushXMLDictionary(BinaryXMLDictionary dictionary);
	
	/**
	 * Some codecs use a dictionary to make encoding and decoding more efficient. Those
	 * codecs typically load their default dictionary automatically. This call allows a user
	 * to manipulate the dictionary stack, removing the most recently added dictionary from the set used to
	 * decode.
	 * @return returns the removed dictionary
	 */
	public BinaryXMLDictionary popXMLDictionary();
}
