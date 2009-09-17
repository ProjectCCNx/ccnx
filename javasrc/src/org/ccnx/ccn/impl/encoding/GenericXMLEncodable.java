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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;

import javax.xml.stream.XMLStreamException;

import org.ccnx.ccn.impl.support.Log;


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
	
 	public void decode(InputStream istream) throws XMLStreamException {
 		decode(istream, null);
 	}
 	
	public void decode(InputStream istream, String codec) throws XMLStreamException {
		XMLDecoder decoder = XMLCodecFactory.getDecoder(codec);
		decoder.beginDecoding(istream);
		decode(decoder);
		decoder.endDecoding();
	}
 	
	public void decode(byte [] content) throws XMLStreamException {
		decode(content, null);
	}

	public void decode(byte [] content, String codec) throws XMLStreamException {
 		ByteArrayInputStream bais = new ByteArrayInputStream(content);
 		decode(bais, codec);
 	}
	
	public void decode(ByteBuffer buf) throws XMLStreamException {
		decode(buf, null);
	}
	
	public void decode(ByteBuffer buf, String codec) throws XMLStreamException {
		if (!buf.hasArray()) {
			throw new XMLStreamException("Unusable ByteBuffer: has no array");
		}
		byte[] array = buf.array();
		
		byte[] tmp = new byte[8];
		System.arraycopy(array, buf.position(), tmp, 0, (buf.remaining() > tmp.length) ? tmp.length : buf.remaining());
		BigInteger tmpBuf = new BigInteger(1,tmp);
		Log.finest("decode (buf.pos: " + buf.position() + " remaining: " + buf.remaining() + ") start: " + tmpBuf.toString(16));
		
		ByteArrayInputStream bais = new ByteArrayInputStream(array, buf.position(), buf.remaining());
		decode(bais, codec);
	}
	
	public void encode(OutputStream ostream) throws XMLStreamException {
		encode(ostream, null);
	}

	public void encode(OutputStream ostream, String codec) throws XMLStreamException {
		XMLEncoder encoder = XMLCodecFactory.getEncoder(codec);
		encoder.beginEncoding(ostream);
		encode(encoder);
		encoder.endEncoding();	
	}

	public byte [] encode() throws XMLStreamException {
		return encode((String)null);
	}
	
	public byte [] encode(String codec) throws XMLStreamException {
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
		} catch (XMLStreamException e) {
			Log.info("GenericXMLEncodable.toString(): cannot encode: " + e.getMessage());
			return new String();
		}
		return new String(encoded);
	}

	/*
	 * These are the methods that a subclass really does need to implement.
	 */
	
	public abstract void decode(XMLDecoder decoder) throws XMLStreamException;
	
	public abstract void encode(XMLEncoder encoder) throws XMLStreamException;
	
	public abstract String getElementLabel();

	public abstract boolean validate();
	
}


