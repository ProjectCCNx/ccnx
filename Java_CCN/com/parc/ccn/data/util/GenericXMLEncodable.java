package com.parc.ccn.data.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.Library;

/**
 * Helper class for objects that use the JAXP stream
 * encode and decode operations to read and write
 * themselves.
 * @author smetters
 *
 */
public abstract class GenericXMLEncodable implements XMLEncodable {

	protected GenericXMLEncodable() {}
	
	/**
	 * Don't provide a constructor that takes a byte[]. It
	 * can decode fine, but subclasses don't have their members
	 * set up to accept the data yet. Do the base constructor
	 * and then call decode.
	 */
	
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
	
	public abstract void decode(XMLDecoder decoder) throws XMLStreamException;
	
	public abstract void encode(XMLEncoder encoder) throws XMLStreamException;

	public abstract boolean validate();
	
	@Override
	public String toString() {
		byte[] encoded;
		try {
			encoded = encode(TextXMLCodec.codecName());
		} catch (XMLStreamException e) {
			Library.logger().info("GenericXMLEncodable.toString(): cannot encode: " + e.getMessage());
			return new String();
		}
		return new String(encoded);
	}
}


