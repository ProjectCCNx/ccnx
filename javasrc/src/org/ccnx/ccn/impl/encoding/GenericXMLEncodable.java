/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008-2012 Palo Alto Research Center, Inc.
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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.ContentDecodingException;
import org.ccnx.ccn.io.content.ContentEncodingException;


/**
 * Implementation of generic XML encode/decode functionality for objects.
 * Subclasses will be capable of being encoded to and decoded from both normal
 * text-based XML and the ccnb compact binary encoding. (Though a subclass could
 * mandate only one be used, or a caller can choose to specify. It is useful,
 * for example, to use this approach to write classes that can be encoded &
 * decoded to & from user-editable text XML only. See GenericXMLEncodable#toString()
 * for an example.)
 * 
 * This class handles most of the generic methods required by XMLEncodable, leaving
 * only a very small number that subclasses need to actually implement.
 * 
 * @see XMLEncodable
 */
public abstract class GenericXMLEncodable implements XMLEncodable {

	/**
	 * All subclasses should provide a public no-argument constructor to be used
	 * by decoding methods. 
	 * 
	 * Don't provide a constructor that takes a byte[]. A class with no subclasses
	 * will decode fine, but its subclasses won't have their members
	 * set up to accept the data yet and so bad things will happen. (And even if you
	 * don't see why anyone would need to subclass your type, someone else might.) 
	 * Clients wishing to decode content will call the no-argument constructor
	 * first, and then call decode(InputStream) or decode(ByteBuffer).
	 */
	protected GenericXMLEncodable() {}
	
 	public void decode(InputStream istream) throws ContentDecodingException {
 		decode(istream, null);
 	}
 	
	public void decode(InputStream istream, String codec) throws ContentDecodingException {
		XMLDecoder decoder = XMLCodecFactory.getDecoder(codec);
		decoder.beginDecoding(istream);
		decode(decoder);
		decoder.endDecoding();
	}
 	
	public void decode(byte [] content) throws ContentDecodingException {
		decode(content, (String)null);
	}

	public void decode(byte [] content, String codec) throws ContentDecodingException {
 		ByteArrayInputStream bais = new ByteArrayInputStream(content);
 		decode(bais, codec);
 	}
	
	public void decode(byte [] content, XMLDecoder decoder) throws ContentDecodingException {
 		ByteArrayInputStream bais = new ByteArrayInputStream(content);
 		decoder.beginDecoding(bais);
		decode(decoder);
		decoder.endDecoding();
 	}
	
	public void encode(OutputStream ostream) throws ContentEncodingException {
		encode(ostream, null);
	}

	public void encode(OutputStream ostream, String codec) throws ContentEncodingException {
		XMLEncoder encoder = XMLCodecFactory.getEncoder(codec);
		encoder.beginEncoding(ostream);
		encode(encoder);
		encoder.endEncoding();	
	}

	public byte [] encode() throws ContentEncodingException {
		return encode((String)null);
	}
	
	public byte [] encode(String codec) throws ContentEncodingException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		encode(baos, codec);
		return baos.toByteArray();
	}

	/**
	 * Default toString() implementation simply prints the text encoding of the
	 * object. This demonstrates how to force use of the text encoding.
	 */
	@Override
	public String toString() {
		byte[] encoded;
		try {
			encoded = encode(TextXMLCodec.codecName());
		} catch (ContentEncodingException e) {
			Log.info(Log.FAC_ENCODING, "GenericXMLEncodable.toString(): cannot encode: " + e.getMessage());
			return new String();
		}
		return new String(encoded);
	}

	/*
	 * These are the methods that a subclass really does need to implement.
	 */
	
	public abstract void decode(XMLDecoder decoder) throws ContentDecodingException;
	
	public abstract void encode(XMLEncoder encoder) throws ContentEncodingException;
	
	public abstract long getElementLabel();

	public abstract boolean validate();
	
}


