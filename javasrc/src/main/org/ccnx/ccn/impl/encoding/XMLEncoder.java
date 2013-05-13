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
import org.ccnx.ccn.protocol.CCNTime;

/**
 * @see XMLDecoder
 */
public interface XMLEncoder {
	
	/**
	 * Initiates encoding and handles any startup steps, including writing the start
	 * document if one is defined for this codec.
	 * @param ostream the output stream to encode to
	 * @throws ContentEncodingException if there is an error encoding or writing the content
	 */
	public void beginEncoding(OutputStream ostream) throws ContentEncodingException;
	
	/**
	 * Handles any necessary steps for ending the encoding, including writing the end
	 * document if one is defined for this codec.
	 * @throws ContentEncodingException if there is an error encoding or writing the content
	 */
	public void endEncoding() throws ContentEncodingException;
	
	/**
	 * Writes a start element tag in the format defined by this codec to the stream.
	 * @param tag the element start tag
	 * @throws ContentEncodingException if there is an error encoding or writing the content
	 */
	public void writeStartElement(String tag) throws ContentEncodingException;
	
	/**
	 * Writes a start element tag in the format defined by this codec to the stream.
	 * @param tag the element start tag value defined by the dictionary, to skip 
	 *   string processing.
	 * @throws ContentEncodingException if there is an error encoding or writing the content
	 */
	public void writeStartElement(long tag) throws ContentEncodingException;

	/**
	 * Writes a start element tag in the format defined by this codec to the stream, together with
	 * a set of attributes.
	 * @param tag the element start tag
	 * @param attributes the (attribute, value) pairs to write as attributes of the element start tag,
	 * 	if null or empty no attributes are written
	 * @throws ContentEncodingException if there is an error encoding or writing the content
	 */
	public void writeStartElement(String tag, TreeMap<String,String> attributes) throws ContentEncodingException;
	
	/**
	 * Writes a start element tag in the format defined by this codec to the stream, together with
	 * a set of attributes. This does string lookup of the attribute names.
	 * @param tag the element start tag
	 * @param attributes the (attribute, value) pairs to write as attributes of the element start tag,
	 * 	if null or empty no attributes are written
	 * @throws ContentEncodingException if there is an error encoding or writing the content
	 */
	public void writeStartElement(long tag, TreeMap<String,String> attributes) throws ContentEncodingException;


	/**
	 * Writes the end element defined by this codec to the stream.
	 * @throws ContentEncodingException if there is an error encoding or writing the content
	 */
	public void writeEndElement() throws ContentEncodingException;   		
	
	/**
	 * Writes a UTF-8 encoded string to the stream formatted according to this codec.
	 * @param tag start tag to use
	 * @param utf8Content the string data to encode
	 * @throws ContentEncodingException if there is an error encoding or writing the content
	 */
	public void writeElement(String tag, String utf8Content) throws ContentEncodingException;
	
	/**
	 * Writes a UTF-8 encoded string to the stream formatted according to this codec.
	 * @param tag start tag to use
	 * @param utf8Content the string data to encode
	 * @throws ContentEncodingException if there is an error encoding or writing the content
	 */
	public void writeElement(long tag, String utf8Content) throws ContentEncodingException;
	
	/**
	 * Writes a UTF-8 encoded string to the stream formatted according to this codec.
	 * @param tag start tag to use
	 * @param utf8Content the string data to encode
	 * @param attributes the XML attributes to add to this tag
	 * @throws ContentEncodingException if there is an error encoding or writing the content
	 */
	public void writeElement(String tag, String utf8Content, 
			TreeMap<String,String> attributes) throws ContentEncodingException;
	
	/**
	 * Writes a UTF-8 encoded string to the stream formatted according to this codec.
	 * @param tag start tag to use
	 * @param utf8Content the string data to encode
	 * @param attributes the XML attributes to add to this tag
	 * @throws ContentEncodingException if there is an error encoding or writing the content
	 */
	public void writeElement(long tag, String utf8Content, 
			TreeMap<String,String> attributes) throws ContentEncodingException;
	
	/**
	 * Writes a binary element to the stream formatted according to this codec.
	 * @param tag start tag to use
	 * @param binaryContent the binary data to encode
	 * @throws ContentEncodingException if there is an error encoding or writing the content
	 */
	public void writeElement(String tag, byte [] binaryContent) throws ContentEncodingException;
	
	/**
	 * Writes a binary element to the stream formatted according to this codec.
	 * @param tag start tag to use
	 * @param binaryContent the binary data to encode
	 * @throws ContentEncodingException if there is an error encoding or writing the content
	 */
	public void writeElement(long tag, byte [] binaryContent) throws ContentEncodingException;

	/**
	 * Writes a binary element to the stream formatted according to this codec.
	 * @param tag start tag to use
	 * @param binaryContent the binary data to encode
	 * @param offset the offset into binaryContent at which to start
	 * @param length the number of bytes of binaryContent to encode
	 * @throws ContentEncodingException if there is an error encoding or writing the content
	 */
	public void writeElement(String tag, byte [] binaryContent, int offset, int length) throws ContentEncodingException;
	
	/**
	 * Writes a binary element to the stream formatted according to this codec.
	 * @param tag start tag to use
	 * @param binaryContent the binary data to encode
	 * @param offset the offset into binaryContent at which to start
	 * @param length the number of bytes of binaryContent to encode
	 * @throws ContentEncodingException if there is an error encoding or writing the content
	 */
	public void writeElement(long tag, byte [] binaryContent, int offset, int length) throws ContentEncodingException;
	
	/**
	 * Writes a binary element to the stream formatted according to this codec.
	 * @param tag start tag to use
	 * @param binaryContent the binary data to encode
	 * @param attributes the XML attributes to add to this tag
	 * @throws ContentEncodingException if there is an error encoding or writing the content
	 */
	public void writeElement(String tag, byte [] binaryContent, 
			TreeMap<String,String> attributes) throws ContentEncodingException;

	/**
	 * Writes a binary element to the stream formatted according to this codec.
	 * @param tag start tag to use
	 * @param binaryContent the binary data to encode
	 * @param attributes the XML attributes to add to this tag
	 * @throws ContentEncodingException if there is an error encoding or writing the content
	 */
	public void writeElement(long tag, byte [] binaryContent, 
			TreeMap<String,String> attributes) throws ContentEncodingException;

	/**
	 * Writes a binary element to the stream formatted according to this codec.
	 * @param tag start tag to use
	 * @param binaryContent the binary data to encode
	 * @param offset the offset into binaryContent at which to start
	 * @param length the number of bytes of binaryContent to encode
	 * @param attributes the XML attributes to add to this tag
	 * @throws ContentEncodingException if there is an error encoding or writing the content
	 */
	public void writeElement(String tag, byte [] binaryContent, int offset, int length,
			TreeMap<String,String> attributes) throws ContentEncodingException;
	
	/**
	 * Writes a binary element to the stream formatted according to this codec.
	 * @param tag start tag to use
	 * @param binaryContent the binary data to encode
	 * @param offset the offset into binaryContent at which to start
	 * @param length the number of bytes of binaryContent to encode
	 * @param attributes the XML attributes to add to this tag
	 * @throws ContentEncodingException if there is an error encoding or writing the content
	 */
	public void writeElement(long tag, byte [] binaryContent, int offset, int length,
			TreeMap<String,String> attributes) throws ContentEncodingException;
	
	/**
	 * Writes a number to the stream formatted according to this codec.
	 * @param tag start tag to use
	 * @param value the number to encode
	 * @throws ContentEncodingException if there is an error encoding or writing the content
	 */
	public void writeElement(String tag, long value) throws ContentEncodingException;
	
	/**
	 * Writes a number to the stream formatted according to this codec.
	 * @param tag start tag to use
	 * @param value the number to encode
	 * @throws ContentEncodingException if there is an error encoding or writing the content
	 */
	public void writeElement(long tag, long value) throws ContentEncodingException;
	
	/**
	 * Writes a quantized timestamp to the stream formatted according to this codec.
	 * @param tag start tag to use
	 * @param dateTime the timestamp to encode
	 * @throws ContentEncodingException if there is an error encoding or writing the content
	 */
	public void writeDateTime(String tag, CCNTime dateTime) throws ContentEncodingException;
	
	/**
	 * Writes a quantized timestamp to the stream formatted according to this codec.
	 * @param tag start tag to use
	 * @param dateTime the timestamp to encode
	 * @throws ContentEncodingException if there is an error encoding or writing the content
	 */
	public void writeDateTime(long tag, CCNTime dateTime) throws ContentEncodingException;
	
	public void writeUString(String utf8Content) throws ContentEncodingException;

	public void writeBlob(byte [] binaryContent) throws ContentEncodingException;

	public void writeBlob(byte [] binaryContent, int offset, int length) throws ContentEncodingException;

	
	/**
	 * Some codecs use a dictionary to make encoding and decoding more efficient. Those
	 * codecs typically load their default dictionary automatically. This call allows a user
	 * to manipulate the dictionary stack, adding their own dictionaries to the set used to
	 * decode.
	 * @param dictionary a dictionary to add to the set used for decoding
	 */
	public void pushXMLDictionary(XMLDictionary dictionary);
	
	/**
	 * Some codecs use a dictionary to make encoding and decoding more efficient. Those
	 * codecs typically load their default dictionary automatically. This call allows a user
	 * to manipulate the dictionary stack, removing the most recently added dictionary from the set used to
	 * encode.
	 * @return returns the removed dictionary
	 */
	public XMLDictionary popXMLDictionary();
}
