package com.parc.ccn.data.util;

import java.io.IOException;
import java.io.InputStream;
import java.util.TreeMap;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.Library;

public class BinaryXMLDecoder implements XMLDecoder {
	
	protected static final int MARK_LEN = 512; // tag length in UTF-8 encoded bytes, plus length/val bytes
	
	protected InputStream _istream = null;
	protected BinaryXMLDictionary _dictionary = null;
	
	public BinaryXMLDecoder() {
		this(null);
	}

	public BinaryXMLDecoder(BinaryXMLDictionary dictionary) {
		if (null == dictionary)
			_dictionary = BinaryXMLDictionary.getDefaultDictionary();
		else
			_dictionary = dictionary;
	}
	
	public void beginDecoding(InputStream istream) throws XMLStreamException {
		if (null == istream)
			throw new IllegalArgumentException("BinaryXMLEncoder: input stream cannot be null!");
		_istream = istream;		
	}
	
	public void endDecoding() throws XMLStreamException {}

	public void readStartDocument() throws XMLStreamException {
		// Currently no start document in binary encoding.
	}

	public void readEndDocument() throws XMLStreamException {
		// Currently no end document in binary encoding.
	}

	public void readStartElement(String startTag) throws XMLStreamException {
		readStartElement(startTag, null);
	}

	public void readStartElement(String startTag,
							    TreeMap<String, String> attributes) throws XMLStreamException {
		try {
			BinaryXMLCodec.TypeAndVal tv = BinaryXMLCodec.decodeTypeAndVal(_istream);
			
			String decodedTag = null;
			
			if (tv.type() == BinaryXMLCodec.XML_TAG) {
				// Tag value represents length-1 as tags can never be empty.
				decodedTag = BinaryXMLCodec.decodeUString(_istream, (int)tv.val()+1);
				
			} else if (tv.type() == BinaryXMLCodec.XML_DTAG) {
				decodedTag = _dictionary.decodeTag(tv.val());					
			}
			
			if ((null ==  decodedTag) || (!decodedTag.equals(startTag))) {
				throw new XMLStreamException("Expected start element: " + startTag + " got: " + decodedTag + "(" + tv.val() + ")");
			}
			
			// DKS: does not read attributes out of stream if caller doesn't
			// ask for them. Should possibly peek and skip over them regardless.
			// TODO: fix this
			if (null != attributes) {
			
				// Now need to get attributes.
				BinaryXMLCodec.TypeAndVal nextTV = BinaryXMLCodec.peekTypeAndVal(_istream);
				
				while ((null != nextTV) && ((BinaryXMLCodec.XML_ATTR == nextTV.type()) ||
											(BinaryXMLCodec.XML_DATTR == nextTV.type()))) {
					
					// Decode this attribute. First, really read the type and value.
					BinaryXMLCodec.TypeAndVal thisTV = BinaryXMLCodec.decodeTypeAndVal(_istream);
					
					String attributeName = null;
					if (BinaryXMLCodec.XML_ATTR == thisTV.type()) {
						// Tag value represents length-1 as attribute names cannot be empty.
						attributeName = BinaryXMLCodec.decodeUString(_istream, (int)thisTV.val()+1);
					} else if (BinaryXMLCodec.XML_DATTR == thisTV.type()) {
						// DKS TODO are attributes same or different dictionary?
						attributeName = _dictionary.decodeTag(tv.val());
						if (null == attributeName) {
							throw new XMLStreamException("Unknown DATTR value" + tv.val());
						}
					}
					// Attribute values are always UDATA
					String attributeValue = BinaryXMLCodec.decodeUString(_istream);
					
					attributes.put(attributeName, attributeValue);
					
					nextTV = BinaryXMLCodec.peekTypeAndVal(_istream);
				}
			}
			
		} catch (IOException e) {
			throw new XMLStreamException("readStartElement", e);
		}
	}

	public boolean peekStartElement(String startTag) throws XMLStreamException {
		if (!_istream.markSupported()) {
			Library.logger().info("Cannot peek -- stream without marking ability!");
			throw new XMLStreamException("No lookahead in stream!");
		}
		
		boolean isCorrectTag = false; 
		
		_istream.mark(MARK_LEN);
		
		try {
			// Have to distinguish genuine errors from wrong tags. Could either use
			// a special exception subtype, or redo the work here.
			BinaryXMLCodec.TypeAndVal tv = BinaryXMLCodec.decodeTypeAndVal(_istream);
			
			String decodedTag = null;
			
			if (tv.type() == BinaryXMLCodec.XML_TAG) {
				decodedTag = BinaryXMLCodec.decodeUString(_istream, (int)tv.val());
				
			} else if (tv.type() == BinaryXMLCodec.XML_DTAG) {
				decodedTag = _dictionary.decodeTag(tv.val());					
			}
			
			if ((null !=  decodedTag) && (decodedTag.equals(startTag))) {
				isCorrectTag = true;
			}
			
		} catch (IOException e) {
			throw new XMLStreamException("peekStartElement", e);

		} finally {
			try {
				_istream.reset();
			} catch (IOException e) {
				throw new XMLStreamException("Cannot reset stream! " + e.getMessage(), e);
			}
		}
		return isCorrectTag;
	}

	public void readEndElement() throws XMLStreamException {
		try {
			int next = _istream.read();
			if (next != BinaryXMLCodec.XML_CLOSE) {
				throw new XMLStreamException("Expected end element, got: " + next);
			}
		} catch (IOException e) {
			throw new XMLStreamException(e);
		}
	}

	/**
	 * Expect a start tag (label), a UDATA, and an end element.
	 */
	public String readUTF8Element(String startTag) throws XMLStreamException {
		return readUTF8Element(startTag, null);
	}

	/**
	 * Expect a start tag (label), optional attributes, a UDATA, and an end element.
	 */
	public String readUTF8Element(String startTag,
			TreeMap<String, String> attributes) throws XMLStreamException {
		
		String ustring = null;
		try {
			readStartElement(startTag, attributes);
			ustring = BinaryXMLCodec.decodeUString(_istream);
			readEndElement();
		} catch (IOException e) {
			throw new XMLStreamException(e.getMessage(),e);
		}
		
		return ustring;
	}
	
	/**
	 * Expect a start tag (label), a BLOB, and an end element.
	 */
	public byte [] readBinaryElement(String startTag) throws XMLStreamException {
		return readBinaryElement(startTag, null);
	}

	/**
	 * Expect a start tag (label), optional attributes, a BLOB, and an end element.
	 */
	public byte [] readBinaryElement(String startTag,
			TreeMap<String, String> attributes) throws XMLStreamException {
		byte [] blob = null;
		try {
			readStartElement(startTag, attributes);
			blob = BinaryXMLCodec.decodeBlob(_istream);
			readEndElement();
		} catch (IOException e) {
			throw new XMLStreamException(e.getMessage(),e);
		}
		
		return blob;
	}


}
