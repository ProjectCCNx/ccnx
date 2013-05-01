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

import java.io.IOException;
import java.io.OutputStream;
import java.util.Iterator;
import java.util.Set;
import java.util.TreeMap;

import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.ContentEncodingException;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.protocol.CCNTime;


/**
 * An implementation of XMLEncoder for the Binary (ccnb) codec.
 * 
 * @see BinaryXMLCodec
 * @see XMLEncoder
 */
public class BinaryXMLEncoder extends GenericXMLEncoder implements XMLEncoder {
	
	/**
	 * Create a BinaryXMLEncoder initialized with the default dictionary obtained
	 * from BinaryXMLDictionary#getDefaultDictionary().
	 */
	public BinaryXMLEncoder() {
		super();
	}

	/**
	 * Create a BinaryXMLEncoder initialized with a specified dictionary.
	 * @param dictionary the dictionary to use, if null the default dictionary is used.
	 */
	public BinaryXMLEncoder(XMLDictionary dictionary) {
		super(dictionary);
	}
	
	public void beginEncoding(OutputStream ostream) throws ContentEncodingException {
		if (null == ostream)
			throw new IllegalArgumentException("BinaryXMLEncoder: output stream cannot be null!");
		_ostream = ostream;		
	}
	
	public void endEncoding() throws ContentEncodingException {
		try {
			_ostream.flush();
		} catch (IOException e) {
			throw new ContentEncodingException(e.getMessage(), e);
		}
	}

	public void writeUString(String utf8Content) throws ContentEncodingException {
		try {
			BinaryXMLCodec.encodeUString(_ostream, utf8Content);
		} catch (IOException e) {
			throw new ContentEncodingException(e.getMessage(), e);
		}
	}
	
	public void writeBlob(byte [] binaryContent) throws ContentEncodingException {
		try {
			BinaryXMLCodec.encodeBlob(_ostream, binaryContent);
		} catch (IOException e) {
			throw new ContentEncodingException(e.getMessage(), e);
		}
	}

	public void writeBlob(byte [] binaryContent, int offset, int length) throws ContentEncodingException {
		try {
			BinaryXMLCodec.encodeBlob(_ostream, binaryContent, offset, length);
		} catch (IOException e) {
			throw new ContentEncodingException(e.getMessage(), e);
		}
	}
	
	/**
	 * Compact binary encoding of time, same as used for versions.
	 * @see VersioningProfile
	 */
	public void writeDateTime(String tag, CCNTime dateTime) throws ContentEncodingException {
		writeElement(tag, dateTime.toBinaryTime());
	}

	/**
	 * Compact binary encoding of time, same as used for versions.
	 * @see VersioningProfile
	 */
	public void writeDateTime(long tag, CCNTime dateTime) throws ContentEncodingException {
		writeElement(tag, dateTime.toBinaryTime());
	}

	public void writeStartElement(String tag, TreeMap<String,String> attributes) throws ContentEncodingException {
		try {
			Long dictionaryVal = stringToTag(tag);
			
			if (null == dictionaryVal) {
				Log.info(Log.FAC_ENCODING, "Unexpected: tag found that is not in our dictionary: " + tag);
				// not in dictionary
				// compressed format wants length of tag represented as length-1
				// to save that extra bit, as tag cannot be 0 length.
				// encodeUString knows to do that.
				BinaryXMLCodec.encodeUString(_ostream, tag, BinaryXMLCodec.XML_TAG);
				
			} else {
				BinaryXMLCodec.encodeTypeAndVal(BinaryXMLCodec.XML_DTAG, dictionaryVal, _ostream);
			}
			
			if (null != attributes) {
				writeAttributes(attributes); 
			}
			
		} catch (IOException e) {
			throw new ContentEncodingException(e.getMessage(),e);
		}
	}
	
	public void writeStartElement(long tag, TreeMap<String,String> attributes) throws ContentEncodingException {
		try {
			
			BinaryXMLCodec.encodeTypeAndVal(BinaryXMLCodec.XML_DTAG, tag, _ostream);
			if (null != attributes) {
				writeAttributes(attributes); 
			}
			
		} catch (IOException e) {
			throw new ContentEncodingException(e.getMessage(),e);
		}
	}

	public void writeAttributes(TreeMap<String,String> attributes) throws IOException {
		
		if (null == attributes) {
			return;
		}

		// the keySet of a TreeMap is sorted.
		Set<String> keySet = attributes.keySet();
		Iterator<String> it = keySet.iterator();

		while (it.hasNext()) {
			String strAttr = it.next();
			String strValue = attributes.get(strAttr);

			Long dictionaryAttr = stringToTag(strAttr);
			if (null == dictionaryAttr) {
				// not in dictionary, encode as attr
				// compressed format wants length of tag represented as length-1
				// to save that extra bit, as tag cannot be 0 length.
				// encodeUString knows to do that.
				BinaryXMLCodec.encodeUString(_ostream, strAttr, BinaryXMLCodec.XML_ATTR);
			} else {
				BinaryXMLCodec.encodeTypeAndVal(BinaryXMLCodec.XML_DATTR, dictionaryAttr, _ostream);
			}
			// Write value
			BinaryXMLCodec.encodeUString(_ostream, strValue);
		}
	}
	
	public void writeEndElement() throws ContentEncodingException {
		try {
			_ostream.write(BinaryXMLCodec.XML_CLOSE);
		} catch (IOException e) {
			throw new ContentEncodingException(e.getMessage(),e);
		}
	}
}
