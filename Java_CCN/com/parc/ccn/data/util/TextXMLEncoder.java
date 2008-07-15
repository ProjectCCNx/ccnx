package com.parc.ccn.data.util;

import java.io.OutputStream;
import java.sql.Timestamp;
import java.util.Iterator;
import java.util.TreeMap;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

public class TextXMLEncoder implements XMLEncoder {

	protected OutputStream _ostream = null;
	protected XMLStreamWriter _writer = null;
	protected boolean _isFirstElement = true;
	
	public TextXMLEncoder() {}

	public void beginEncoding(OutputStream ostream) throws XMLStreamException {
		if (null == ostream)
			throw new IllegalArgumentException("TextXMLEncoder: output stream cannot be null!");
		
		_ostream = ostream;
		
		XMLOutputFactory factory = XMLOutputFactory.newInstance();
		_writer = factory.createXMLStreamWriter(ostream);
		_writer.setPrefix(TextXMLCodec.CCN_PREFIX, TextXMLCodec.CCN_NAMESPACE);
		_writer.setDefaultNamespace(TextXMLCodec.CCN_NAMESPACE);
		// DKS -- need to set encoding when creating factory, and set it here.
		_writer.writeStartDocument();
		// Can't write default namespace till we write an element...
		_isFirstElement = true;
	}
	
	public void endEncoding() throws XMLStreamException {
		_writer.writeEndDocument();
		_writer.flush();   				
	}
	
	public void writeElement(String tag, String utf8Content)
			throws XMLStreamException {
		writeElement(tag, utf8Content, null);
	}

	public void writeElement(String tag, String utf8Content,
			TreeMap<String, String> attributes) throws XMLStreamException {
		writeStartElement(tag, attributes);
		_writer.writeCharacters(utf8Content);
		writeEndElement();
	}

	public void writeElement(String tag, byte[] binaryContent)
			throws XMLStreamException {
		writeElement(tag, binaryContent, null);
	}

	public void writeElement(String tag, byte[] binaryContent,
			TreeMap<String, String> attributes) throws XMLStreamException {
		if (null == attributes) {
			attributes = new TreeMap<String,String>();
		}
		if (!attributes.containsKey(TextXMLCodec.BINARY_ATTRIBUTE)) {
			attributes.put(TextXMLCodec.BINARY_ATTRIBUTE, TextXMLCodec.BINARY_ATTRIBUTE_VALUE);
		}
		writeStartElement(tag, attributes);
		_writer.writeCharacters(TextXMLCodec.encodeBinaryElement(binaryContent));
		writeEndElement();
	}

	public void writeStartElement(String tag) throws XMLStreamException {
		writeStartElement(tag, null);
	}

	public void writeStartElement(String tag, TreeMap<String, String> attributes)
			throws XMLStreamException {
		_writer.writeStartElement(TextXMLCodec.CCN_NAMESPACE, tag);
		if (_isFirstElement) {
			_writer.writeDefaultNamespace(TextXMLCodec.CCN_NAMESPACE);
			_isFirstElement = false;
		}
		
		if (null != attributes) {
			// keySet of a TreeMap is ordered
			Iterator<String> atIt = attributes.keySet().iterator();
			while (atIt.hasNext()) {
				String name = atIt.next();
				// Might not play well if this is the first element (namespace writing issues...)
				_writer.writeAttribute(TextXMLCodec.CCN_NAMESPACE, name, attributes.get(name));
			}
		}
	}

	public void writeEndElement() throws XMLStreamException {
		_writer.writeEndElement();
	}

	public void writeDateTime(String tag, Timestamp dateTime)
			throws XMLStreamException {
		writeElement(tag, 
				 TextXMLCodec.formatDateTime(dateTime));
	}

}
