package org.ccnx.ccn.impl.encoding;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;

import javax.xml.stream.XMLStreamException;

public interface XMLEncodable {
	
	/**
	 * Decode this object as the top-level item in a new
	 * XML document. Reads document start and end. Assumes
	 * default encoding.
	 * @param iStream
	 * @throws XMLStreamException
	 */
	public void decode(InputStream istream) throws XMLStreamException;
	
	public void decode(InputStream istream, String codec) throws XMLStreamException;

	/**
	 * Helper method. Assumes default encoding.
	 */
	public void decode(byte [] objectBuffer) throws XMLStreamException;
	
	public void decode(byte [] objectBuffer, String codec) throws XMLStreamException;

	/** 
	 * Helper method using network buffer objects.
	 */
	public void decode(ByteBuffer buf) throws XMLStreamException;
	
	public void decode(ByteBuffer buf, String codec) throws XMLStreamException;

	/**
	 * Pull this item from an ongoing decoding pass.
	 */
	public void decode(XMLDecoder decoder) throws XMLStreamException;

	/**
	 * Encode this object as the top-level item in a new 
	 * XML document. Writes start and end document. Assumes default encoding.
	 * @param oStream
	 * @throws XMLStreamException
	 */
	public void encode(OutputStream ostream) throws XMLStreamException;

	public void encode(OutputStream ostream, String codec) throws XMLStreamException;
	
	/**
	 * Helper function. Should return canonicalized encoding.
	 * @return
	 * @throws XMLStreamException
	 */
	public byte [] encode() throws XMLStreamException;
	
	public byte [] encode(String codec) throws XMLStreamException;

	/**
	 * Write this item to an ongoing encoding pass. 
	 * @param isFirstElement is this the first element after the
	 * 	start of the document; if so it needs to start the
	 * 	default namespace.
	 */
	public void encode(XMLEncoder encoder) throws XMLStreamException;

	/**
	 * Make sure all of the necessary fields are filled in
	 * prior to attempting to encode.
	 */
	public boolean validate();
}
