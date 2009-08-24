package org.ccnx.ccn.impl.encoding;

import java.io.OutputStream;
import java.sql.Timestamp;
import java.util.TreeMap;

import javax.xml.stream.XMLStreamException;


public interface XMLEncoder {
	
	/**
	 * Handles writing the document start.
	 * @param ostream
	 * @throws XMLStreamException
	 */
	public void beginEncoding(OutputStream ostream) throws XMLStreamException;
	
	/**
	 * Handles writing the document end.
	 * @throws XMLStreamException
	 */
	public void endEncoding() throws XMLStreamException;
	
	public void writeStartElement(String tag) throws XMLStreamException;
	
	public void writeStartElement(String tag, TreeMap<String,String> attributes) throws XMLStreamException;
	
	public void writeEndElement() throws XMLStreamException;   		
	
	public void writeElement(String tag, String utf8Content) throws XMLStreamException;
	
	public void writeElement(String tag, String utf8Content, 
			TreeMap<String,String> attributes) throws XMLStreamException;
	
	public void writeElement(String tag, byte [] binaryContent) throws XMLStreamException;
	
	public void writeElement(String tag, byte [] binaryContent, int offset, int length) throws XMLStreamException;
	
	public void writeElement(String tag, byte [] binaryContent, 
			TreeMap<String,String> attributes) throws XMLStreamException;

	public void writeElement(String tag, byte [] binaryContent, int offset, int length,
			TreeMap<String,String> attributes) throws XMLStreamException;
	
	/**
	 * Encapsulate string handling.
	 */
	public void writeIntegerElement(String tag, Integer value) throws XMLStreamException;
	
	/**
	 * Encapsulate our handling of timestamps.
	 */
	public void writeDateTime(String tag, Timestamp dateTime) throws XMLStreamException;
	
	public void pushXMLDictionary(BinaryXMLDictionary dictionary);
	
	public BinaryXMLDictionary popXMLDictionary();
}
