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

import java.io.IOException;
import java.io.OutputStream;
import java.sql.Timestamp;
import java.util.Iterator;
import java.util.Set;
import java.util.Stack;
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
	
	protected OutputStream _ostream = null;
	protected Stack<BinaryXMLDictionary> _dictionary = new Stack<BinaryXMLDictionary>();
	
	/**
	 * Create a BinaryXMLEncoder initialized with the default dictionary obtained
	 * from BinaryXMLDictionary#getDefaultDictionary().
	 */
	public BinaryXMLEncoder() {
		this(null);
	}

	/**
	 * Create a BinaryXMLEncoder initialized with a specified dictionary.
	 * @param dictionary the dictionary to use, if null the default dictionary is used.
	 */
	public BinaryXMLEncoder(BinaryXMLDictionary dictionary) {
		if (null == dictionary)
			_dictionary.push(BinaryXMLDictionary.getDefaultDictionary());
		else
			_dictionary.push(dictionary);
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

	public void writeElement(String tag, String utf8Content)
			throws ContentEncodingException {
		writeElement(tag, utf8Content, null);
	}

	public void writeElement(String tag, String utf8Content,
			TreeMap<String, String> attributes)
			throws ContentEncodingException {
		try {
			writeStartElement(tag, attributes);
			// Will omit if 0-length
			BinaryXMLCodec.encodeUString(_ostream, utf8Content);
			writeEndElement();
		} catch (IOException e) {
			throw new ContentEncodingException(e.getMessage(), e);
		}
	}

	public void writeElement(String tag, byte[] binaryContent)
			throws ContentEncodingException {
		writeElement(tag, binaryContent, null);
	}

	public void writeElement(String tag, byte[] binaryContent, int offset, int length)
			throws ContentEncodingException {
		writeElement(tag, binaryContent, offset, length, null);
	}

	public void writeElement(String tag, byte[] binaryContent,
			TreeMap<String, String> attributes)
			throws ContentEncodingException {
		try {
			writeStartElement(tag, attributes);
			// Will omit if 0-length
			BinaryXMLCodec.encodeBlob(_ostream, binaryContent);
			writeEndElement();
		} catch (IOException e) {
			throw new ContentEncodingException(e.getMessage(), e);
		}
	}

	public void writeElement(String tag, byte[] binaryContent,
			int offset, int length,
			TreeMap<String, String> attributes)
			throws ContentEncodingException {
		try {
			writeStartElement(tag, attributes);
			// Will omit if 0-length
			BinaryXMLCodec.encodeBlob(_ostream, binaryContent, offset, length);
			writeEndElement();
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

	public void writeDateTime(String tag, Timestamp dateTime) throws ContentEncodingException {
		writeDateTime(tag, new CCNTime(dateTime));
	}

	public void writeStartElement(String tag) throws ContentEncodingException {
		writeStartElement(tag, null);
	}
	
	public void writeStartElement(String tag, TreeMap<String,String> attributes) throws ContentEncodingException {
		try {
			long dictionaryVal = _dictionary.peek().encodeTag(tag);
			
			if (dictionaryVal < 0) {
				Log.info("Unexpected: tag found that is not in our dictionary: " + tag);
				// not in dictionary
				// compressed format wants length of tag represented as length-1
				// to save that extra bit, as tag cannot be 0 length.
				// encodeUString knows to do that.
				BinaryXMLCodec.encodeUString(_ostream, tag, BinaryXMLCodec.XML_TAG);
				
			} else {
				BinaryXMLCodec.encodeTypeAndVal(BinaryXMLCodec.XML_DTAG, dictionaryVal, _ostream);
			}
			
			if (null != attributes) {
				// the keySet of a TreeMap is sorted.
				Set<String> keySet = attributes.keySet();
				Iterator<String> it = keySet.iterator();
				
				while (it.hasNext()) {
					String strAttr = it.next();
					String strValue = attributes.get(strAttr);
					
					// TODO DKS are attributes in different dictionary? right now not using DATTRS
					long dictionaryAttr = _dictionary.peek().encodeAttr(strAttr);
					if (dictionaryAttr < 0) {
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
			
		} catch (IOException e) {
			throw new ContentEncodingException(e.getMessage(),e);
		}
	}
	
	public void writeEndElement() throws ContentEncodingException {
		try {
			_ostream.write(BinaryXMLCodec.XML_CLOSE);
		} catch (IOException e) {
			throw new ContentEncodingException(e.getMessage(),e);
		}
	}

	public BinaryXMLDictionary popXMLDictionary() {
		return _dictionary.pop();
	}

	public void pushXMLDictionary(BinaryXMLDictionary dictionary) {
		_dictionary.push(dictionary);
	}
}
