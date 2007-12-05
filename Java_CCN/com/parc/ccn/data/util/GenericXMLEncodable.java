package com.parc.ccn.data.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.PrivateKey;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

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
	
    protected GenericXMLEncodable(byte [] encoded) throws XMLStreamException {
    	ByteArrayInputStream bais = new ByteArrayInputStream(encoded);
    	decode(bais);
    }
    
 	public void decode(InputStream iStream) throws XMLStreamException {
		XMLEventReader reader = XMLHelper.beginDecoding(iStream);
		decode(reader);
		XMLHelper.endDecoding(reader);
	}
	
	public void encode(OutputStream oStream) throws XMLStreamException {
		XMLStreamWriter writer = XMLHelper.beginEncoding(oStream);
		encode(writer, true);
		XMLHelper.endEncoding(writer);	
	}

	public void encode(XMLStreamWriter writer) throws XMLStreamException {
		encode(writer, false);
	}
	
	public abstract void encode(XMLStreamWriter writer, 
				boolean isFirstElement) throws XMLStreamException;
	
	public abstract void decode(XMLEventReader reader) throws XMLStreamException;

	public abstract boolean validate();
	
	public byte [] encode() throws XMLStreamException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		encode(baos);
		return baos.toByteArray();
	}
	
	/**
	 * Right now only easy canonicalization interface requires
	 * a key to make a context...
	 * @param key
	 * @return
	 * @throws XMLStreamException
	 */
	public byte [] canonicalizeAndEncode(PrivateKey key) throws XMLStreamException {
		byte [] encodedContents = null;
		try {
			// DKS TODO figure out canonicalization of content
		//	encodedContents = SignatureHelper.canonicalize(this, key);
			encodedContents = encode();
//		} catch (SignatureException e) {
		} catch (Exception e) {
			Library.logger().warning("Cannot canonicalize " + this.getClass().getName());
			Library.warningStackTrace(e);
			throw new XMLStreamException(e);
		}
		return encodedContents;
	}

	@Override
	public String toString() {
		return XMLHelper.toString(this);
	}
}


