package com.parc.ccn.data.content;

import java.io.InputStream;
import java.io.OutputStream;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

public interface XMLEncodable {
	
	public static final String CCN_NAMESPACE = "http://www.parc.com/CCN";
	public static final String CCN_PREFIX = "ccn";
	
	/**
	 * Decode this object as the top-level item in a new
	 * XML document.
	 * @param iStream
	 * @throws XMLStreamException
	 */
	public void decode(InputStream iStream) throws XMLStreamException;
	
	/**
	 * Pull this item from an ongoing decoding pass.
	 */
	public void decode(XMLEventReader reader) throws XMLStreamException;

	/**
	 * Encode this object as the top-level item in a new 
	 * XML document.
	 * @param oStream
	 * @throws XMLStreamException
	 */
	public void encode(OutputStream oStream) throws XMLStreamException;

	/**
	 * Write this item to an ongoing encoding pass.
	 */
	public void encode(XMLStreamWriter writer) throws XMLStreamException;

}
