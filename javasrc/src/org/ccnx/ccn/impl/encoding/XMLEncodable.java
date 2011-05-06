/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008, 2009, 2010 Palo Alto Research Center, Inc.
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

import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.io.content.ContentDecodingException;
import org.ccnx.ccn.io.content.ContentEncodingException;

/**
 * Top-level interface implemented by objects that want to make use of our stream encoding
 * and decoding infrastructure. Note - this uses a compact binary encoding (ccnb) normally
 * rather than text XML.
 * 
 * The choice between compact binary encoding and text encoding is made for the
 * general case using a system-wide configuration parameter set in SystemConfiguration.
 * Subclasses wishing to can use this infrastructure for encoding/decoding objects that
 * should be read/edited by users can force their particular subtype to always use
 * the text encoding; or callers that read/write that data can specify the text encoding
 * when reading or writing. The latter is the better option, as it allows the underlying
 * content to use the compact binary encoding in settings where that would be preferable,
 * without needing to modify the code. See GenericXMLEncodable#toString()
 * for an example.
 * 
 * Most classes wishing to make use of this infrastructure will do so by subclassing
 * GenericXMLEncodable, which provides default implementations of almost all of the necessary
 * behavior, leaving very little work for the class implementor to do. For examples,
 * see existing subclasses of GenericXMLEncodable.
 *
 * @see SystemConfiguration
 * @see GenericXMLEncodable
 */
public interface XMLEncodable {
	
	/**
	 * Decode this object as the top-level item in a new XML document, 
	 * reading it from an InputStream. Reads document start and end. Assumes
	 * default encoding.
	 * @param istream input stream to read from
	 * @throws ContentDecodingException if there is an error decoding the content
	 */
	public void decode(InputStream istream) throws ContentDecodingException;
	
	/**
	 * Decode this object as the top-level item in a new XML document, 
	 * reading it from an InputStream. Reads document start and end. 
	 * @param istream input stream to read from
	 * @param codec the codec to use; must be recognized by XMLCodecFactory
	 * @throws ContentDecodingException if there is an error decoding the content
	 * 
	 * @see XMLCodecFactory
	 */
	public void decode(InputStream istream, String codec) throws ContentDecodingException;

	/**
	 * Helper method to decode from a byte array rather than an
	 * InputStream. Decode this object as the top-level item in a new
	 * XML document. Reads document start and end. Assumes
	 * default encoding.
	 * @param objectBuffer input buffer to read from
	 * @throws ContentDecodingException if there is an error decoding the content
	 * 
	 * @see decode(InputStream)
	 */
	public void decode(byte [] objectBuffer) throws ContentDecodingException;
	
	/**
	 * Helper method to decode from a byte array rather than an
	 * InputStream. Decode this object as the top-level item in a new
	 * XML document. Reads document start and end. 
	 * @param objectBuffer input buffer to read from
	 * @param codec the codec to use; must be recognized by XMLCodecFactory
	 * @throws ContentDecodingException if there is an error decoding the content
	 * 
	 * @see decode(InputStream,String)
	 * @see XMLCodecFactory
	 */
	public void decode(byte [] objectBuffer, String codec) throws ContentDecodingException;

	/**
	 * Decode this object as the top-level item in a new XML document, 
	 * reading it from a network buffer. Reads document start and end. Assumes
	 * default encoding.
	 * @param buf input stream to read from
	 * @throws ContentDecodingException if there is an error decoding the content
	 */
	//public void decode(ByteBuffer buf) throws ContentDecodingException;
	
	/**
	 * Decode this object as the top-level item in a new XML document, 
	 * reading it from a network buffer. Reads document start and end. 
	 * @param buf input stream to read from
	 * @param codec the codec to use; must be recognized by XMLCodecFactory
	 * @throws ContentDecodingException if there is an error decoding the content
	 * 
	 * @see XMLCodecFactory
	 */
	//public void decode(ByteBuffer buf, String codec) throws ContentDecodingException;

	/**
	 * Decode this object during an ongoing decoding pass; this is what subclasses
	 * generally need to know how to implement. Reads just the object itself,
	 * higher-level processes have handled start and end document if need be.
	 * Allows object to be read using the same code whether it is a top-level
	 * element written alone, or nested inside another element.
	 * @param decoder the decoder being used; encapsulates state including the 
	 * 	codec being used as well as the input source and current offset
	 */
	public void decode(XMLDecoder decoder) throws ContentDecodingException;

	/**
	 * Encode this object as the top-level item in a new 
	 * XML document. Writes start and end document. Assumes default encoding.
	 * @param ostream stream to encode object to
	 * @throws ContentEncodingException if there is an error encoding the object
	 */
	public void encode(OutputStream ostream) throws ContentEncodingException;

	/**
	 * Encode this object as the top-level item in a new 
	 * XML document. Writes start and end document. 
	 * @param ostream stream to encode object to
	 * @param codec the codec to use; must be recognized by XMLCodecFactory
	 * @throws ContentEncodingException if there is an error encoding the object
	 * 
	 * @see XMLCodecFactory
	 */
	public void encode(OutputStream ostream, String codec) throws ContentEncodingException;
	
	/**
	 * Helper method to encode to a byte array rather than an
	 * OutputStream. Encode this object as the top-level item in a new
	 * XML document. Writes document start and end. Assumes
	 * default encoding.
	 * @return returns the encoded object
	 * @throws ContentEncodingException if there is an error encoding the content
	 * 
	 * @see encode(OutputStream)
	 */
	public byte [] encode() throws ContentEncodingException;
	
	/**
	 * Helper method to encode to a byte array rather than an
	 * OutputStream. Encode this object as the top-level item in a new
	 * XML document. Writes document start and end. 
	 * @param codec the codec to use; must be recognized by XMLCodecFactory
	 * @return returns the encoded object
	 * @throws ContentEncodingException if there is an error encoding the content
	 * 
	 * @see encode(OutputStream,String)
	 * @see XMLCodecFactory
	 */
	public byte [] encode(String codec) throws ContentEncodingException;

	/**
	 * Encode this object during an ongoing encoding pass; this is what subclasses
	 * generally need to know how to implement. Writes just the object itself,
	 * higher-level processes have handled start and end document if need be.
	 * Allows object to be written using the same code whether it is a top-level
	 * element written alone, or nested inside another element.
	 * @param encoder the encoder being used; encapsulates state including the 
	 * 	codec being used as well as the output destination and current offset
	 */
	public void encode(XMLEncoder encoder) throws ContentEncodingException;
	
	/** 
	 * Allow the encoder/decoder to retrieve the top-level element name
	 * programmatically. This allows subclasses to rename elements without
	 * changing their encoder/decoders.
	 * @return the element label to use, as a key in a loaded encoding dictionary
	 */
	public long getElementLabel();

	/**
	 * Make sure all of the necessary fields are filled in
	 * prior to attempting to encode. All implementations of encode(XMLEncoder)
	 * should call this for their classes prior to encoding.
	 * @return true if object is valid and can be encoded, false if there is a 
	 * 	problem; for example mandatory fields are uninitialized
	 */
	public boolean validate();
}
