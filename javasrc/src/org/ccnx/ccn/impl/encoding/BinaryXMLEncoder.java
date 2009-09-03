package org.ccnx.ccn.impl.encoding;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.sql.Timestamp;
import java.util.Iterator;
import java.util.Set;
import java.util.Stack;
import java.util.TreeMap;

import javax.xml.stream.XMLStreamException;

import org.ccnx.ccn.impl.support.CCNTime;
import org.ccnx.ccn.impl.support.Log;



public class BinaryXMLEncoder extends GenericXMLEncoder implements XMLEncoder {
	
	protected OutputStream _ostream = null;
	protected Stack<BinaryXMLDictionary> _dictionary = new Stack<BinaryXMLDictionary>();
	
	public BinaryXMLEncoder() {
		this(null);
	}

	public BinaryXMLEncoder(BinaryXMLDictionary dictionary) {
		if (null == dictionary)
			_dictionary.push(BinaryXMLDictionary.getDefaultDictionary());
		else
			_dictionary.push(dictionary);
	}
	
	public void beginEncoding(OutputStream ostream) throws XMLStreamException {
		if (null == ostream)
			throw new IllegalArgumentException("BinaryXMLEncoder: output stream cannot be null!");
		_ostream = ostream;		
	}
	
	public void endEncoding() throws XMLStreamException {
		try {
			_ostream.flush();
		} catch (IOException e) {
			throw new XMLStreamException(e.getMessage(), e);
		}
	}

	public void writeElement(String tag, String utf8Content)
			throws XMLStreamException {
		writeElement(tag, utf8Content, null);
	}

	public void writeElement(String tag, String utf8Content,
			TreeMap<String, String> attributes)
			throws XMLStreamException {
		try {
			writeStartElement(tag, attributes);
			// Will omit if 0-length
			BinaryXMLCodec.encodeUString(_ostream, utf8Content);
			writeEndElement();
		} catch (IOException e) {
			throw new XMLStreamException(e.getMessage(), e);
		}
	}

	public void writeElement(String tag, byte[] binaryContent)
			throws XMLStreamException {
		writeElement(tag, binaryContent, null);
	}

	public void writeElement(String tag, byte[] binaryContent, int offset, int length)
			throws XMLStreamException {
		writeElement(tag, binaryContent, offset, length, null);
	}

	public void writeElement(String tag, byte[] binaryContent,
			TreeMap<String, String> attributes)
			throws XMLStreamException {
		try {
			writeStartElement(tag, attributes);
			// Will omit if 0-length
			BinaryXMLCodec.encodeBlob(_ostream, binaryContent);
			writeEndElement();
		} catch (IOException e) {
			throw new XMLStreamException(e.getMessage(), e);
		}
	}

	public void writeElement(String tag, byte[] binaryContent,
			int offset, int length,
			TreeMap<String, String> attributes)
			throws XMLStreamException {
		try {
			writeStartElement(tag, attributes);
			// Will omit if 0-length
			BinaryXMLCodec.encodeBlob(_ostream, binaryContent, offset, length);
			writeEndElement();
		} catch (IOException e) {
			throw new XMLStreamException(e.getMessage(), e);
		}
	}

	/**
	 * Compact binary encoding of time. Same as used for versions.
	 */
	public void writeDateTime(String tag, Timestamp dateTime) throws XMLStreamException {
		writeElement(tag, 
				CCNTime.timestampToBinaryTime12(dateTime));
	}

	public void writeStartElement(String tag) throws XMLStreamException {
		writeStartElement(tag, null);
	}
	
	public void writeStartElement(String tag, TreeMap<String,String> attributes) throws XMLStreamException {
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
			
		} catch (UnsupportedEncodingException e) {
			Log.severe("We don't understand UTF-8! Giving up!");
			throw new RuntimeException("Do not know UTF-8 charset! Significant configuration error!");
		} catch (IOException e) {
			throw new XMLStreamException(e.getMessage(),e);
		}
	}
	
	public void writeEndElement() throws XMLStreamException {
		try {
			_ostream.write(BinaryXMLCodec.XML_CLOSE);
		} catch (IOException e) {
			throw new XMLStreamException(e.getMessage(),e);
		}
	}

	public BinaryXMLDictionary popXMLDictionary() {
		return _dictionary.pop();
	}

	public void pushXMLDictionary(BinaryXMLDictionary dictionary) {
		_dictionary.push(dictionary);
	}
}
