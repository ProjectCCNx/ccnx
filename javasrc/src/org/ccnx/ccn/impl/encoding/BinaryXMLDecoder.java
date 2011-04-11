/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008, 2009, 2010, 2011 Palo Alto Research Center, Inc.
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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.TreeMap;
import java.util.logging.Level;

import org.ccnx.ccn.impl.support.DataUtils;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.ContentDecodingException;
import org.ccnx.ccn.protocol.CCNTime;

/**
 * An implementation of XMLDecoder for the Binary (ccnb) codec.
 * 
 * @see BinaryXMLCodec
 * @see XMLDecoder
 */
public class BinaryXMLDecoder  extends GenericXMLDecoder implements XMLDecoder {
	
	protected static final int MARK_LEN = 512; // tag length in UTF-8 encoded bytes, plus length/val bytes
	protected static final int DEBUG_MAX_LEN = 32768;
	
	public BinaryXMLDecoder() {
		super();
	}

	public BinaryXMLDecoder(BinaryXMLDictionary dictionary) {
		super(dictionary);
	}
	
	public void initializeDecoding() {
		if (!_istream.markSupported()) {
			throw new IllegalArgumentException(this.getClass().getName() + ": input stream must support marking!");
		}
	}

	public void readStartDocument() throws ContentDecodingException {
		// Currently no start document in binary encoding.
	}

	public void readEndDocument() throws ContentDecodingException {
		// Currently no end document in binary encoding.
	}

	public void readStartElement(String startTag,
							    TreeMap<String, String> attributes) throws ContentDecodingException {
		try {
			BinaryXMLCodec.TypeAndVal tv = BinaryXMLCodec.decodeTypeAndVal(_istream);
			
			if (null == tv) {
				throw new ContentDecodingException("Expected start element: " + startTag + " got something not a tag.");
			}
			
			String decodedTag = null;
			
			if (tv.type() == BinaryXMLCodec.XML_TAG) {
				Log.info(Log.FAC_ENCODING, "Unexpected: got tag in readStartElement; looking for tag " + startTag + " got length: " + (int)tv.val()+1);
				// Tag value represents length-1 as tags can never be empty.
				decodedTag = BinaryXMLCodec.decodeUString(_istream, (int)tv.val()+1);
				
			} else if (tv.type() == BinaryXMLCodec.XML_DTAG) {
				decodedTag = tagToString(tv.val());	
			}
			
			if ((null ==  decodedTag) || (!decodedTag.equals(startTag))) {
				throw new ContentDecodingException("Expected start element: " + startTag + " got: " + decodedTag + "(" + tv.val() + ")");
			}
			
			// DKS: does not read attributes out of stream if caller doesn't
			// ask for them. Should possibly peek and skip over them regardless.
			// TODO: fix this
			if (null != attributes) {
				readAttributes(attributes); 
			}
			
		} catch (IOException e) {
			throw new ContentDecodingException("readStartElement", e);
		}
	}
	
	public void readStartElement(long startTag,
			TreeMap<String, String> attributes) throws ContentDecodingException {
		try {
			BinaryXMLCodec.TypeAndVal tv = BinaryXMLCodec.decodeTypeAndVal(_istream);

			if (null == tv) {
				throw new ContentDecodingException("Expected start element: " + startTag + " got something not a tag.");
			}

			Long decodedTag = null;

			if (tv.type() == BinaryXMLCodec.XML_TAG) {
				Log.info(Log.FAC_ENCODING, "Unexpected: got tag in readStartElement; looking for tag " + startTag + " got length: " + (int)tv.val()+1);
				// Tag value represents length-1 as tags can never be empty.
				String strTag = BinaryXMLCodec.decodeUString(_istream, (int)tv.val()+1);
				
				decodedTag = stringToTag(strTag);

			} else if (tv.type() == BinaryXMLCodec.XML_DTAG) {
				decodedTag = tv.val();
			}

			if ((null ==  decodedTag) || (decodedTag.longValue() != startTag)) {
				throw new ContentDecodingException("Expected start element: " + startTag + " got: " + decodedTag + "(" + tv.val() + ")");
			}

			// DKS: does not read attributes out of stream if caller doesn't
			// ask for them. Should possibly peek and skip over them regardless.
			// TODO: fix this
			if (null != attributes) {
				readAttributes(attributes); 
			}

		} catch (IOException e) {
			throw new ContentDecodingException("readStartElement", e);
		}
	}

	
	public void readAttributes(TreeMap<String,String> attributes) throws ContentDecodingException {
		
		if (null == attributes) {
			return;
		}

		try {
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
					attributeName = tagToString(thisTV.val());
					if (null == attributeName) {
						throw new ContentDecodingException("Unknown DATTR value" + thisTV.val());
					}
				}
				// Attribute values are always UDATA
				String attributeValue = BinaryXMLCodec.decodeUString(_istream);

				attributes.put(attributeName, attributeValue);

				nextTV = BinaryXMLCodec.peekTypeAndVal(_istream);
			}

		} catch (IOException e) {
			Log.logStackTrace(Log.FAC_ENCODING, Level.WARNING, e);
			throw new ContentDecodingException("readStartElement", e);
		}
	}
	
	public String peekStartElementAsString() throws ContentDecodingException {
		_istream.mark(MARK_LEN);

		String decodedTag = null;
		try {
			// Have to distinguish genuine errors from wrong tags. Could either use
			// a special exception subtype, or redo the work here.
			BinaryXMLCodec.TypeAndVal tv = BinaryXMLCodec.decodeTypeAndVal(_istream);

			if (null != tv) {

				if (tv.type() == BinaryXMLCodec.XML_TAG) {
					if (tv.val()+1 > DEBUG_MAX_LEN) {
						throw new ContentDecodingException("Decoding error: length " + tv.val()+1 + " longer than expected maximum length!");
					}

					// Tag value represents length-1 as tags can never be empty.
					decodedTag = BinaryXMLCodec.decodeUString(_istream, (int)tv.val()+1);
					
					Log.info(Log.FAC_ENCODING, "Unexpected: got text tag in peekStartElement; length: " + (int)tv.val()+1 + " decoded tag = " + decodedTag);

				} else if (tv.type() == BinaryXMLCodec.XML_DTAG) {
					decodedTag = tagToString(tv.val());					
				}

			} // else, not a type and val, probably an end element. rewind and return false.

		} catch (ContentDecodingException e) {
			try {
				_istream.reset();
				_istream.mark(MARK_LEN);
				long ms = System.currentTimeMillis();
				File tempFile = new File("data_" + Long.toString(ms) + ".ccnb");
				FileOutputStream fos = new FileOutputStream(tempFile);
				try {
					byte buf[] = new byte[1024];
					while (_istream.available() > 0) {
						int count = _istream.read(buf);
						fos.write(buf,0, count);
					}
				} finally {
					fos.close();
				}
				_istream.reset();
				Log.info(Log.FAC_ENCODING, "BinaryXMLDecoder: exception in peekStartElement, dumping offending object to file: " + tempFile.getAbsolutePath());
				throw e;
				
			} catch (IOException ie) {
				Log.warning(Log.FAC_ENCODING, "IOException in BinaryXMLDecoder error handling: " + e.getMessage());
				Log.logStackTrace(Log.FAC_ENCODING, Level.WARNING, ie);
				throw new ContentDecodingException("peekStartElement", e);

			}
		} catch (IOException e) {
			Log.warning(Log.FAC_ENCODING, "IOException in BinaryXMLDecoder: " + e.getMessage());
			Log.logStackTrace(Log.FAC_ENCODING, Level.WARNING, e);
			throw new ContentDecodingException("peekStartElement", e);

		} finally {
			try {
				_istream.reset();
			} catch (IOException e) {
				Log.logStackTrace(Log.FAC_ENCODING, Level.WARNING, e);
				throw new ContentDecodingException("Cannot reset stream! " + e.getMessage(), e);
			}
		}
		return decodedTag;
	}
	
	public Long peekStartElementAsLong() throws ContentDecodingException {
		_istream.mark(MARK_LEN);

		Long decodedTag = null;
		try {
			// Have to distinguish genuine errors from wrong tags. Could either use
			// a special exception subtype, or redo the work here.
			BinaryXMLCodec.TypeAndVal tv = BinaryXMLCodec.decodeTypeAndVal(_istream);

			if (null != tv) {

				if (tv.type() == BinaryXMLCodec.XML_TAG) {
					if (tv.val()+1 > DEBUG_MAX_LEN) {
						throw new ContentDecodingException("Decoding error: length " + tv.val()+1 + " longer than expected maximum length!");
					}

					// Tag value represents length-1 as tags can never be empty.
					String strTag = BinaryXMLCodec.decodeUString(_istream, (int)tv.val()+1);
					
					decodedTag = stringToTag(strTag);
					
					Log.info(Log.FAC_ENCODING, "Unexpected: got text tag in peekStartElement; length: " + (int)tv.val()+1 + " decoded tag = " + decodedTag);
					
				} else if (tv.type() == BinaryXMLCodec.XML_DTAG) {
					decodedTag = tv.val();					
				}

			} // else, not a type and val, probably an end element. rewind and return false.

		} catch (ContentDecodingException e) {
			try {
				_istream.reset();
				_istream.mark(MARK_LEN);
				long ms = System.currentTimeMillis();
				File tempFile = new File("data_" + Long.toString(ms) + ".ccnb");
				FileOutputStream fos = new FileOutputStream(tempFile);
				try {
					byte buf[] = new byte[1024];
					while (_istream.available() > 0) {
						int count = _istream.read(buf);
						fos.write(buf,0, count);
					}
				} finally {
					fos.close();
				}
				_istream.reset();
				Log.info(Log.FAC_ENCODING, "BinaryXMLDecoder: exception in peekStartElement, dumping offending object to file: " + tempFile.getAbsolutePath());
				throw e;
				
			} catch (IOException ie) {
				Log.warning(Log.FAC_ENCODING, "IOException in BinaryXMLDecoder error handling: " + e.getMessage());
				Log.logStackTrace(Log.FAC_ENCODING, Level.WARNING, e);
				throw new ContentDecodingException("peekStartElement", e);

			}
		} catch (IOException e) {
			Log.warning(Log.FAC_ENCODING, "IOException in BinaryXMLDecoder peekStartElementAsLong: " + e.getMessage());
			Log.logStackTrace(Log.FAC_ENCODING, Level.WARNING, e);
			throw new ContentDecodingException("peekStartElement", e);

		} finally {
			try {
				_istream.reset();
			} catch (IOException e) {
				Log.logStackTrace(Log.FAC_ENCODING, Level.WARNING, e);
				throw new ContentDecodingException("Cannot reset stream! " + e.getMessage(), e);
			}
		}
		return decodedTag;
	}

	public void readEndElement() throws ContentDecodingException {
		try {
			int next = _istream.read();
			if (next != BinaryXMLCodec.XML_CLOSE) {
				throw new ContentDecodingException("Expected end element, got: " + next);
			}
		} catch (IOException e) {
			throw new ContentDecodingException(e);
		}
	}

	/**
	 * Read a UString. Force this to consume the end element to match the
	 * behavior on the text side.
	 */
	public String readUString() throws ContentDecodingException {
		try {
			String ustring = BinaryXMLCodec.decodeUString(_istream);	
			readEndElement();
			return ustring;
		} catch (IOException e) {
			throw new ContentDecodingException(e.getMessage(),e);
		}
	}
	
	/**
	 * Read a BLOB. Force this to consume the end element to match the
	 * behavior on the text side.
	 */
	public byte [] readBlob() throws ContentDecodingException {
		try {
			byte [] blob = BinaryXMLCodec.decodeBlob(_istream);	
			readEndElement();
			return blob;
		} catch (IOException e) {
			throw new ContentDecodingException(e.getMessage(),e);
		}
	}
		
	public CCNTime readDateTime(String startTag) throws ContentDecodingException {
		byte [] byteTimestamp = readBinaryElement(startTag);
		CCNTime timestamp = new CCNTime(byteTimestamp);
		if (null == timestamp) {
			throw new ContentDecodingException("Cannot parse timestamp: " + DataUtils.printHexBytes(byteTimestamp));
		}		
		return timestamp;
	}
	
	public CCNTime readDateTime(long startTag) throws ContentDecodingException {
		byte [] byteTimestamp = readBinaryElement(startTag);
		CCNTime timestamp = new CCNTime(byteTimestamp);
		if (null == timestamp) {
			throw new ContentDecodingException("Cannot parse timestamp: " + DataUtils.printHexBytes(byteTimestamp));
		}		
		return timestamp;
	}	
}
