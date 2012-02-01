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

import java.io.InputStream;
import java.util.TreeMap;

import org.ccnx.ccn.io.content.ContentDecodingException;
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
	 * @throws ContentDecodingException if there is an error in decoding
	 */
	public void beginDecoding(InputStream istream) throws ContentDecodingException;
	
	/**
	 * Completes top-level decoding, handling any necessary shutdown. Calls readEndDocment()
	 * to read the document end. 
	 * @throws ContentDecodingException if there is an error in decoding
	 */
	public void endDecoding() throws ContentDecodingException;

	/**
	 * Reads the document start marker, if there is one.
	 * @throws ContentDecodingException if there is an error in decoding
	 */
	public void readStartDocument() throws ContentDecodingException;

	/**
	 * Reads the document end marker, if there is one.
	 * @throws ContentDecodingException if there is an error in decoding
	 */
	public void readEndDocument() throws ContentDecodingException;
	
	/**
	 * Reads an expected element start tag from the stream
	 * @param startTag next tag we expect to occur
	 * @throws ContentDecodingException if that tag does not occur, or another error is encountered
	 */
	public void readStartElement(String startTag) throws ContentDecodingException;
		
	/**
	 * Reads an expected element start tag from the stream
	 * @param startTag next tag we expect to occur
	 * @throws ContentDecodingException if that tag does not occur, or another error is encountered
	 */
	public void readStartElement(long startTag) throws ContentDecodingException;
		
	/**
	 * Reads an expected element start tag from the stream, extracting any
	 * attributes that are present on the tag
	 * @param startTag next tag we expect to occur
	 * @param attributes map into which we store (attribute, value) pairs
	 * @throws ContentDecodingException if that tag does not occur, or another error is encountered
	 */
	public void readStartElement(String startTag,
			TreeMap<String,String> attributes) throws ContentDecodingException;

	/**
	 * Reads an expected element start tag from the stream, extracting any
	 * attributes that are present on the tag
	 * @param startTag next tag we expect to occur
	 * @param attributes map into which we store (attribute, value) pairs
	 * @throws ContentDecodingException if that tag does not occur, or another error is encountered
	 */
	public void readStartElement(long startTag,
			TreeMap<String,String> attributes) throws ContentDecodingException;

	/**
	 * Peeks ahead in the stream to see if an expected element is next. Requires
	 * the underlying stream to return true from InputStream#markSupported(). Resets
	 * the stream to the point at which it was called afterwards
	 * @param startTag next tag we expect to occur
	 * @return true if that is the next tag, false otherwise
	 * @throws ContentDecodingException if an error is encountered
	 */
	public boolean peekStartElement(String startTag) throws ContentDecodingException;
	
	/**
	 * Peeks ahead in the stream to see if an expected element is next. Requires
	 * the underlying stream to return true from InputStream#markSupported(). Resets
	 * the stream to the point at which it was called afterwards
	 * @param startTag next tag we expect to occur
	 * @return true if that is the next tag, false otherwise
	 * @throws ContentDecodingException if an error is encountered
	 */
	public boolean peekStartElement(long startTag) throws ContentDecodingException;
	
	/**
	 * Pulls the next tag out of the stream and then resets the stream to the
	 * point before that tag. Requires the underlying stream to return true
	 * from InputStream#markSupported().
	 * @return the next tag found
	 * @throws ContentDecodingException if there is an error reading the stream or decoding the tag
	 */
	public String peekStartElementAsString() throws ContentDecodingException;
	
	/**
	 * Pulls the next tag out of the stream and then resets the stream to the
	 * point before that tag. Requires the underlying stream to return true
	 * from InputStream#markSupported().
	 * @return the next tag found, or null if not a start element
	 * @throws ContentDecodingException if there is an error reading the stream or decoding the tag
	 */
	public Long peekStartElementAsLong() throws ContentDecodingException;

	/**
	 * Reads an end element from the stream, if this codec has end elements.
	 * @throws ContentDecodingException if the next element in the stream is not an end element, or
	 * 	there is another error reading
	 */
	public void readEndElement() throws ContentDecodingException;

	/**
	 * Read a UTF-8 encoded string element from the stream.
	 * @param startTag expected start tag
	 * @param attributes will be used to hold attributes on this tag, if there are any
	 * @return the decoded String
	 * @throws ContentDecodingException if startTag is not the next tag in the stream, or there is an error
	 * 		decoding the element
	 */
	public String readUTF8Element(String startTag,
			TreeMap<String,String> attributes) throws ContentDecodingException;
	
	/**
	 * Read a UTF-8 encoded string element from the stream.
	 * @param startTag expected start tag
	 * @param attributes will be used to hold attributes on this tag, if there are any
	 * @return the decoded String
	 * @throws ContentDecodingException if startTag is not the next tag in the stream, or there is an error
	 * 		decoding the element
	 */
	public String readUTF8Element(long startTag,
			TreeMap<String,String> attributes) throws ContentDecodingException;	

	/**
	 * Read a UTF-8 encoded string element which has no attributes from the stream.
	 * @param startTag expected start tag
	 * @return the decoded String
	 * @throws ContentDecodingException if startTag is not the next tag in the stream, or there is an error
	 * 		decoding the element
	 */
	public String readUTF8Element(String startTag) throws ContentDecodingException;
	
	/**
	 * Read a UTF-8 encoded string element which has no attributes from the stream.
	 * @param startTag expected start tag
	 * @return the decoded String
	 * @throws ContentDecodingException if startTag is not the next tag in the stream, or there is an error
	 * 		decoding the element
	 */
	public String readUTF8Element(long startTag) throws ContentDecodingException;
	
	/**
	 * Read a binary element from the stream.
	 * @param startTag expected start tag
	 * @param attributes will be used to hold attributes on this tag, if there are any
	 * @return the decoded byte array
	 * @throws ContentDecodingException if startTag is not the next tag in the stream, or there is an error
	 * 		decoding the element
	 */
	public byte [] readBinaryElement(String startTag, TreeMap<String,String> attributes) throws ContentDecodingException;
	
	/**
	 * Read a binary element from the stream.
	 * @param startTag expected start tag
	 * @param attributes will be used to hold attributes on this tag, if there are any
	 * @return the decoded byte array
	 * @throws ContentDecodingException if startTag is not the next tag in the stream, or there is an error
	 * 		decoding the element
	 */
	public byte [] readBinaryElement(long startTag, TreeMap<String,String> attributes) throws ContentDecodingException;
	
	/**
	 * Read a binary element with no attributes from the stream.
	 * @param startTag expected start tag
	 * @return the decoded byte array
	 * @throws ContentDecodingException if startTag is not the next tag in the stream, or there is an error
	 * 		decoding the element
	 */
	public byte [] readBinaryElement(String startTag) throws ContentDecodingException;
	
	/**
	 * Read a binary element with no attributes from the stream.
	 * @param startTag expected start tag
	 * @return the decoded byte array
	 * @throws ContentDecodingException if startTag is not the next tag in the stream, or there is an error
	 * 		decoding the element
	 */
	public byte [] readBinaryElement(long startTag) throws ContentDecodingException;
	
	/**
	 * Read and parse a number from the stream. 
	 * @param startTag expected start tag
	 * @return the decoded value, or null if content was empty
	 * @throws ContentDecodingException if startTag is not the next tag in the stream, or there is an error
	 * 		decoding the element or parsing the integer
	 */
	public long readLongElement(String startTag) throws ContentDecodingException;

	/**
	 * Read and parse an integer from the stream. 
	 * @param startTag expected start tag
	 * @return the decoded value, or null if content was empty
	 * @throws ContentDecodingException if startTag is not the next tag in the stream, or there is an error
	 * 		decoding the element or parsing the integer
	 */
	public long readLongElement(long startTag) throws ContentDecodingException;

	/**
	 * Read and parse a number from the stream. 
	 * @param startTag expected start tag
	 * @return the decoded value, or null if content was empty
	 * @throws ContentDecodingException if startTag is not the next tag in the stream, or there is an error
	 * 		decoding the element or parsing the integer
	 */
	public int readIntegerElement(String startTag) throws ContentDecodingException;

	/**
	 * Read and parse an integer from the stream. 
	 * @param startTag expected start tag
	 * @return the decoded value, or null if content was empty
	 * @throws ContentDecodingException if startTag is not the next tag in the stream, or there is an error
	 * 		decoding the element or parsing the integer
	 */
	public int readIntegerElement(long startTag) throws ContentDecodingException;

	/**
	 * Read and parse a timestamp from the stream. 
	 * @param startTag expected start tag
	 * @return the decoded timestamp, using a quantized CCNTime
	 * @throws ContentDecodingException if startTag is not the next tag in the stream, or there is an error
	 * 		decoding the element or parsing the integer
	 */
	public CCNTime readDateTime(String startTag) throws ContentDecodingException;
	
	/**
	 * Read and parse a timestamp from the stream. 
	 * @param startTag expected start tag
	 * @return the decoded timestamp, using a quantized CCNTime
	 * @throws ContentDecodingException if startTag is not the next tag in the stream, or there is an error
	 * 		decoding the element or parsing the integer
	 */
	public CCNTime readDateTime(long startTag) throws ContentDecodingException;
	
	/**
	 * Read UTF-8 string data starting from beginning of element (text element
	 * in text XML, type/value for UDATA in binary, etc). Consumes the end
	 * element (text has to, so we copy).
	 * @return the string
	 * @throws ContentDecodingException
	 */
	public String readUString() throws ContentDecodingException;
	
	/**
	 * Read BLOB data starting from beginning of element (encoded binary element
	 * in text XML, type/value for BLOB in binary, etc). Consumes the end
	 * element (text has to, so we copy).
	 * @return the BLOB data
	 * @throws ContentDecodingException
	 */
	public byte [] readBlob() throws ContentDecodingException;
	
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
	 * decode.
	 * @return returns the removed dictionary
	 */
	public XMLDictionary popXMLDictionary();
	
	/**
	 * Get the decoder's input stream - needed for error recovery
	 */
	public InputStream getInputStream();
}
