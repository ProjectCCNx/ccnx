package org.ccnx.ccn.impl.encoding;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Stack;
import java.util.TreeMap;

import javax.xml.stream.XMLStreamException;

import org.ccnx.ccn.impl.support.DataUtils;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.protocol.CCNTime;



public class BinaryXMLDecoder  extends GenericXMLDecoder implements XMLDecoder {
	
	protected static final int MARK_LEN = 512; // tag length in UTF-8 encoded bytes, plus length/val bytes
	protected static final int DEBUG_MAX_LEN = 32768;
	
	protected Stack<BinaryXMLDictionary> _dictionary = new Stack<BinaryXMLDictionary>();
	
	protected InputStream _istream = null;
	
	public BinaryXMLDecoder() {
		this(null);
	}

	public BinaryXMLDecoder(BinaryXMLDictionary dictionary) {
		if (null == dictionary)
			_dictionary.push(BinaryXMLDictionary.getDefaultDictionary());
		else
			_dictionary.push(dictionary);
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
			
			if (null == tv) {
				throw new XMLStreamException("Expected start element: " + startTag + " got something not a tag.");
			}
			
			String decodedTag = null;
			
			if (tv.type() == BinaryXMLCodec.XML_TAG) {
				Log.info("Unexpected: got tag in readStartElement; looking for tag " + startTag + " got length: " + (int)tv.val()+1);
				// Tag value represents length-1 as tags can never be empty.
				decodedTag = BinaryXMLCodec.decodeUString(_istream, (int)tv.val()+1);
				
			} else if (tv.type() == BinaryXMLCodec.XML_DTAG) {
				decodedTag = _dictionary.peek().decodeTag(tv.val());					
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
						attributeName = _dictionary.peek().decodeTag(tv.val());
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
		String decodedTag = peekStartElement();
		if ((null !=  decodedTag) && (decodedTag.equals(startTag))) {
			return true;
		}
		return false;
	}

	public String peekStartElement() throws XMLStreamException {
		if (!_istream.markSupported()) {
			Log.info("Cannot peek -- stream without marking ability!");
			throw new XMLStreamException("No lookahead in stream!");
		}

		_istream.mark(MARK_LEN);

		String decodedTag = null;
		try {
			// Have to distinguish genuine errors from wrong tags. Could either use
			// a special exception subtype, or redo the work here.
			BinaryXMLCodec.TypeAndVal tv = BinaryXMLCodec.decodeTypeAndVal(_istream);

			if (null != tv) {

				if (tv.type() == BinaryXMLCodec.XML_TAG) {
					if (tv.val()+1 > DEBUG_MAX_LEN) {
						throw new XMLStreamException("Decoding error: length " + tv.val()+1 + " longer than expected maximum length!");
					}

					// Tag value represents length-1 as tags can never be empty.
					decodedTag = BinaryXMLCodec.decodeUString(_istream, (int)tv.val()+1);
					
					Log.info("Unexpected: got text tag in peekStartElement; length: " + (int)tv.val()+1 + " decoded tag = " + decodedTag);

				} else if (tv.type() == BinaryXMLCodec.XML_DTAG) {
					decodedTag = _dictionary.peek().decodeTag(tv.val());					
				}

			} // else, not a type and val, probably an end element. rewind and return false.

		} catch (XMLStreamException e) {
			try {
				_istream.reset();
				_istream.mark(MARK_LEN);
				long ms = System.currentTimeMillis();
				File tempFile = new File("data_" + Long.toString(ms) + ".ccnb");
				FileOutputStream fos = new FileOutputStream(tempFile);
				byte buf[] = new byte[1024];
				while (_istream.available() > 0) {
					int count = _istream.read(buf);
					fos.write(buf,0, count);
				}
				fos.close();
				_istream.reset();
				Log.info("BinaryXMLDecoder: exception in peekStartElement, dumping offending object to file: " + tempFile.getAbsolutePath());
				throw e;
				
			} catch (IOException ie) {
				Log.info("IOException in BinaryXMLDecoder error handling: " + e.getMessage());
				throw new XMLStreamException("peekStartElement", e);

			}
		} catch (IOException e) {
			Log.info("IOException in BinaryXMLDecoder: " + e.getMessage());
			throw new XMLStreamException("peekStartElement", e);

		} finally {
			try {
				_istream.reset();
			} catch (IOException e) {
				throw new XMLStreamException("Cannot reset stream! " + e.getMessage(), e);
			}
		}
		return decodedTag;
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
	
	public CCNTime readDateTime(String startTag) throws XMLStreamException {
		byte [] byteTimestamp = readBinaryElement(startTag);
		CCNTime timestamp = new CCNTime(byteTimestamp);
		if (null == timestamp) {
			throw new XMLStreamException("Cannot parse timestamp: " + DataUtils.printHexBytes(byteTimestamp));
		}		
		return timestamp;
	}

	public BinaryXMLDictionary popXMLDictionary() {
		_dictionary.pop();
		return null;
	}

	public void pushXMLDictionary(BinaryXMLDictionary dictionary) {
		_dictionary.push(dictionary);
	}	
}
