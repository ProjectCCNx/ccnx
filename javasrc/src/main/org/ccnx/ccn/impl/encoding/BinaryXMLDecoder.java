/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2012 Palo Alto Research Center, Inc.
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

import java.io.IOException;
import java.io.InputStream;
import java.util.TreeMap;
import java.util.logging.Level;

import org.ccnx.ccn.impl.CCNNetworkManager;
import org.ccnx.ccn.impl.support.DataUtils;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.ContentDecodingException;
import org.ccnx.ccn.protocol.CCNTime;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Interest;



/**
 * A highly optimized decoder for wire packets.
 *
 * Can be used as an normal XMLDecoder.
 *
 * It also exposes the segment buffer through getBytes() and the
 * segment DOM via getElement().
 *
 * TODO:
 * - Try buffering reads from the network channel rather than byte-by-byte.
 *   CCNNetworkChannel is rewindable, so if we read past the end of the
 *   segment, we can re-position to where we end.  A better organization
 *   would be to have a framer just doing the decode and once it gets
 *   a full segment, hand that off to a parser without rewinding.
 * - Another thing to do is to not actually decode the Type/Value pairs
 *   except for BLOB and UDATA, where you need to know what the value is.
 *   for all the DTAG and CLOSE, we should just use them in their encoded
 *   form.
 *
 * Notes about resync:
 *   - Is it really needed at all?  For instance ccnd won't output a bad packet.
 *   - By default resync is turned off. We normally don't want it when decoding packets
 *     outside the wire.
 *   - The current resync is primitive. It should work in most cases though it hasn't had
 *     much in the way of real testing over a lossy network.
 *   - It doesn't work in some cases - the resync happens when an error is detected during
 *     the initial "DOM" style parsing but in some cases an error can't be detected until
 *     the actual decode of the packet occurs. But we don't want to pre-decode every packet
 *     just to see if it has an error as this code is potentially one of our major
 *     bottlenecks.
 *   - It only works up to the "resync limit" which is currently 512 bytes. If we increase
 *     this too much, it potentially means more overhead during readin since we need to
 *     insure that the read buffer can be rewound back to the mark.
 */
public final class BinaryXMLDecoder extends GenericXMLDecoder implements XMLDecoder {

	public final int RESYNC_LIMIT = 512;	// Default max we can go back for a resync
	protected int _resyncLimit = RESYNC_LIMIT;
	protected boolean _resyncable = false;

	public BinaryXMLDecoder() {
		super();
	}

	public final XMLEncodable getPacket() throws ContentDecodingException {
//		long value = peekStartElementAsLong();

		if( _parsingElement >= _elementCount ) {
			throw new ContentDecodingException("Past end of DOM!");
		}

		// ensures it's a DTAG
		if( _elements_type[_parsingElement] == BinaryXMLCodec.XML_DTAG ) {
			if( _elements_value[_parsingElement] == CCNProtocolDTags.Interest ) {
				Log.fine(Log.FAC_ENCODING, "Decoding INTEREST");

				Interest interest = new Interest();
				interest.decode(this);
				return interest;
			}

			if( _elements_value[_parsingElement]  == CCNProtocolDTags.ContentObject ) {
				Log.fine(Log.FAC_ENCODING, "Decoding ContentObject");

				ContentObject co = new ContentObject();
				co.decode(this);
				return co;
			}

			Log.severe(Log.FAC_ENCODING,
					String.format("Error decoding packet - unknown element 0x%02x 0x%04x position %d",
							_elements_type[_parsingElement], _elements_value[_parsingElement],
							_parsingElement));

		}

		// It's something we can't deal with, likely a heartbeat
		return null;
	}

	/**
	 * Reset the Decoder's state and start parsing the input stream.
	 * Handle resyncing.
	 *
	 * @param istream
	 */
	@Override
	public final void beginDecoding(InputStream istream) throws ContentDecodingException {
		if (_resyncable)
			istream.mark(_resyncLimit);
		
		_elements_type = new byte[_currentElements];
		_elements_value = new int[_currentElements];
		_elements_blob = new byte[_currentElements][];

		try {
			setupForDecoding(istream);
		} catch (IOException ioe) {
			if (! _resyncable)
				throw new ContentDecodingException(ioe.getMessage());
			Log.severe("Saw error: {0} - attempting resync", ioe.getMessage());
			while (true) {
				try {
					resync(istream);
				} catch (IOException resyncIOE) {
					throw new ContentDecodingException(resyncIOE.getMessage());
				}
				try {
					setupForDecoding(istream);
				} catch (IOException sfdIOE) {}
				try {
					// If we are trying a resync we are slowing down anyway, so
					// we want to do our best to get things right - so here we
					// test the packet.
					XMLEncodable testPacket = getPacket();
					if (null != testPacket) {
						_parsingElement = 0;
						return;  // We successfully resynced (we hope :-))
					}
				} catch (IOException gpIOE) {}
			}
		}
	}

	/**
	 * This method does the initial parsing into elements
	 * @param istream
	 * @throws IOException
	 */
	private final void setupForDecoding(InputStream istream) throws IOException {
		int type = -1;
		initialize();

		int opentags = 0;

		do {
			int	index = readTypeAndValue(istream);
			type = _elements_type[index] ;

			if( type == BinaryXMLCodec.XML_DTAG ) {
				opentags++;
				continue;
			}

			if( type  == BinaryXMLCodec.XML_CLOSE ) {
				opentags--;
				continue;
			}

			if( type  == BinaryXMLCodec.XML_BLOB || type == BinaryXMLCodec.XML_UDATA ) {
					readBlob(istream, _elements_blob[index]);
			}
		} while(opentags > 0);

//			System.out.println("count = " + _elements.size() + ", bytes = " + _buffer.position());
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();

		int count = 0;
		for( int i = 0; i < _elementCount; i++ ) {
			sb.append(String.format("%3d ", count));
			sb.append(String.format("Type 0x%02x Value 0x%04x",
					_elements_type[i],
					_elements_value[i]));
			sb.append('\n');
			count++;
		}

		return sb.toString();
	}

	private final void advanceParser() {
		_parsingElement++;
//		Log.fine("Advance Parser to " + _parsingElement);
	}

	// ===================================================================
	// END OF USER METHODS, START PRIVATE STUFF AND XMLDECODER INTERFACE
	// ===================================================================
	// should probably move away from an ArrayList to a plain array
//	private final ArrayList<Element> _elements = new ArrayList<Element>(128);
	private final static int ELEM_FIRST = 100;
	private final static int ELEM_INCR_FRAC = 2;    // increase by 1/2 each time
	private int _currentElements = ELEM_FIRST;

//	private final Element [] _elements = new Element[ELEM_MAX];
	private byte [] _elements_type;
	private int [] _elements_value;
	private byte [][] _elements_blob;

	// BLOB and UDATA now go in their own buffers, so don't really need the full BLOCKSIZE

//	private final byte [] _bytes = new byte[_blockSize];
//	private final ByteBuffer _buffer = ByteBuffer.wrap(_bytes);
	private int _elementCount = 0;
	private final static byte [] _byte0 = new byte[0];

	// the current DOM element
	private int _parsingElement = 0;

	private void initialize() {
		_elementCount = 0;
		_parsingElement = 0;
	}

	/**
	 * From the current position of the input stream, read in a blob of @count bytes.
	 *
	 * @param istream
	 * @param count
	 * @throws IOException
	 */
	private void readBlob(final InputStream istream, final byte [] buffer) throws IOException {
		// read in count bytes from the stream directly in to our
		// backing buffer, then adjust its position.

//		int offset = _buffer.position();
		int read = 0;
		do {
//			read += istream.read(_bytes, offset + read, count-read);
			try {
				read += istream.read(buffer, read, buffer.length -read);
			} catch (Exception e) {
				throw new IOException(e.getMessage());
			}
		} while(read < buffer.length);

		// now advance the buffers position
//		_buffer.position(offset + read);
	}

	/**
	 * Parse the type and value.
	 * If the type is BLOB or UDATA, also allocates the byte buffer for it in Element.
	 * @param istream
	 * @return the index in to the _element_X arrays
	 * @throws IOException If not DTAG or BLOB/UDATA or CLOSE (END), throws exception
	 */
	private final int readTypeAndValue(final InputStream istream) throws IOException {
		byte typ = -1;
		long val = 0;

		int next;
		boolean more = false;
		while( (next = istream.read()) > -1 ) {

			// detect the CLOSE marker
			if( !more && (0 == next) ) {
				typ = 0;
				val = 0;
				break;
			}

			more = (0 == (next & BinaryXMLCodec.XML_TT_NO_MORE));

			if  (more) {
				val = val << BinaryXMLCodec.XML_REG_VAL_BITS;
				val |= (next & BinaryXMLCodec.XML_REG_VAL_MASK);
			} else {
				// last byte
				typ = (byte) (next & BinaryXMLCodec.XML_TT_MASK);
				val = val << BinaryXMLCodec.XML_TT_VAL_BITS;
				val |= ((next >>> BinaryXMLCodec.XML_TT_BITS) & BinaryXMLCodec.XML_TT_VAL_MASK);
				break;
			}
		}

		if (next < 0)
			throw new IOException("Unexpected EOF");

		// sanity check.  tag needs to be either a DTAG or a BLOB
		if( typ != BinaryXMLCodec.XML_DTAG && typ != BinaryXMLCodec.XML_BLOB &&
				typ != BinaryXMLCodec.XML_UDATA && typ != BinaryXMLCodec.XML_CLOSE )
			throw new ContentDecodingException("Type value invalid: " + typ);

		byte [] buffer = null;
		if( typ == BinaryXMLCodec.XML_BLOB || typ == BinaryXMLCodec.XML_UDATA ) {
			if (val < 0 || val > CCNNetworkManager.MAX_PAYLOAD)
				throw new ContentDecodingException("Invalid blob size: " + val);
			buffer = new byte[(int) val];
		}

//		System.out.println(String.format("Decode tag 0x%02x value 0x%02x pos %d", typ, val, pos));

		int index = _elementCount;
		setElement(index, typ, (int)val, buffer);
		_elementCount++;
		return index;
	}

	/**
	 * Build the current element. Handle expansion of the arrays is necessary
	 * @param index
	 * @param typ
	 * @param val
	 * @param buffer
	 */
	private void setElement(int index, byte typ, int val, byte[] buffer) {
		try {
			_elements_type[index]  = typ;
		} catch (ArrayIndexOutOfBoundsException aiobe) {
            int prevElements = _currentElements;
			_currentElements += _currentElements / ELEM_INCR_FRAC;
			byte[] newTypes = new byte[_currentElements];
			System.arraycopy(_elements_type, 0, newTypes, 0, prevElements);
			_elements_type = newTypes;
			int[] newValues = new int[_currentElements];
			System.arraycopy(_elements_value, 0, newValues, 0, prevElements);
			_elements_value = newValues;
			byte[][] newBlobs = new byte[_currentElements][];
			System.arraycopy(_elements_blob, 0, newBlobs, 0, prevElements);
			_elements_blob = newBlobs;
			_elements_type[index] = typ;
			if (Log.isLoggable(Log.FAC_ENCODING, Level.INFO))
				Log.info(Log.FAC_ENCODING, "Reset decode array sizes to {0}", _currentElements);
		}
		_elements_value[index] = val;
		_elements_blob[index]  = buffer;
	}

	/**
	 * Checks the current DOM element, and ensure it matches @expected.
	 * The element must be @type and its value equal @expected.
	 * DOES NOT ADVANCE THE CURRENT ELEMENT
	 * @param expected
	 * @return none
	 * @throws ContentDecodingException if current DOM element does not match @expected
	 */
	private final void peekTag(byte type, long expected) throws ContentDecodingException {
		if( _parsingElement >= _elementCount )
			throw new ContentDecodingException(
					String.format("Past end of DOM! size %d position %d", _elementCount, _parsingElement));

		if( _elements_type[_parsingElement] == type && _elements_value[_parsingElement] == expected )
			return;

		throw new ContentDecodingException(
				String.format("Element type mismatch: expected 0x%02x 0x%04x got type 0x%02x 0x%02x position %d",
						type, expected, _elements_type[_parsingElement],
						_elements_value[_parsingElement], _parsingElement));
	}

	/**
	 * Verifies the current tag is of type @type
	 * @return
	 * @throws ContentDecodingException
	 */
	private final void peekTag(byte type) throws ContentDecodingException {
		if( _parsingElement >= _elementCount )
			throw new ContentDecodingException(
					String.format("Past end of DOM! size %d position %d", _elementCount, _parsingElement));

		if( _elements_type[_parsingElement] == type )
			return;

		// This seems a little bogus but it emulates what the original code did...
		if (type == BinaryXMLCodec.XML_BLOB) {
			for (int i = _parsingElement; i < _elementCount; i++) {
				setElement(i + 1, _elements_type[i], _elements_value[i], _elements_blob[i]);
			}
			_elementCount++;
			_elements_blob[_parsingElement] = new byte[0];
			_elements_type[_parsingElement] = type;
			return;
		}

		throw new ContentDecodingException(
				String.format("Element type mismatch: expected 0x%02x got type 0x%02x 0x%02x position %d",
						type, _elements_type[_parsingElement],
						_elements_value[_parsingElement], _parsingElement));
	}

	// ===================================
	// XMLDecoder methods

	@Override
	public final boolean peekStartElement(long startTag) throws ContentDecodingException {
		if( _parsingElement >= _elementCount )
			throw new ContentDecodingException(
					String.format("Past end of DOM! size %d position %d", _elementCount, _parsingElement));

		if( _elements_type[_parsingElement]  == BinaryXMLCodec.XML_DTAG && _elements_value[_parsingElement]  == startTag)
			return true;

		return false;
	}

	/**
	 * Return the value of the current XML_DTAG.
	 * This is expected to return NULL if the tag is a CLOSE
	 * Does not advance the parser.
	 *  @throws ContentDecodingException if not XML_DTAG or past end of DOM
	 */
	public final Long peekStartElementAsLong() throws ContentDecodingException {
		if( _parsingElement >= _elementCount )
			throw new ContentDecodingException(
					String.format("Past end of DOM! size %d position %d", _elementCount, _parsingElement));

		if( _elements_type[_parsingElement]  == BinaryXMLCodec.XML_DTAG )
			return (long) _elements_value[_parsingElement];

		if( _elements_type[_parsingElement]  == BinaryXMLCodec.XML_CLOSE )
			return null;

		throw new ContentDecodingException(
				String.format("Element type mismatch: got type 0x%04x 0x%02x position %d",
						_elements_type[_parsingElement] , _elements_value[_parsingElement] , _parsingElement));
	}


	/**
	 * As with the BinaryXMLDecoder, this will consume the next END element,
	 * which actually closes the preceeding start element, not the Blob's END
	 * (blobs don't have an end element).
	 */
	public final byte[] readBinary(byte type) throws ContentDecodingException {
		if( type != BinaryXMLCodec.XML_BLOB && type != BinaryXMLCodec.XML_UDATA )
			throw new ContentDecodingException("Must be BLOB or UDATA");

		peekTag(type);

		int index = _parsingElement;

		advanceParser();

		// By definition, we need to consume the next END element
		readEndElement();

		if( 0 == _elements_value[index] ) {
			return _byte0;
		}

//		Log.fine(Log.FAC_ENCODING, "readBinary type {0} start {1} length {2} buffer len {3}",
//				type, elem.position, elem.value, _bytes.length);

		final byte [] buffer = _elements_blob[index];

		return buffer;
	}

	/**
	 * Advances the parser by 3 elements (start tag, blob, end tag)
	 */
	@Override
	public final byte[] readBinaryElement(long startTag) throws ContentDecodingException {
		readStartElement(startTag);
		return readBlob();
	}

	/**
	 * Advances the parser by 3 elements (start tag, blob, end tag)
	 */
	@Override
	public final byte[] readBinaryElement(long startTag,
			TreeMap<String, String> attributes) throws ContentDecodingException {
		readStartElement(startTag);
		return readBlob();
	}

	public final byte [] readBlob() throws ContentDecodingException {
		return readBinary(BinaryXMLCodec.XML_BLOB);
	}

	/**
	 * Read the current tag, ensure its XML_DTAG and matches @startTag.
	 * Read a binary blob
	 * Read the END tag
	 * So, this advances the parser by 3 elements.
	 */
	public CCNTime readDateTime(long startTag) throws ContentDecodingException {
		// +1
		readStartElement(startTag);
		// +2
		byte [] byteTimestamp = readBlob();

		return new CCNTime(byteTimestamp);
	}

	public void readEndDocument() throws ContentDecodingException {
		// there is no EndDocument element in binary
		return;
	}

	/**
	 * Ensures the current DOM object is XML_CLOSE and advances parser.
	 */
	public void readEndElement() throws ContentDecodingException {
		peekTag(BinaryXMLCodec.XML_CLOSE, BinaryXMLCodec.XML_CLOSE);
		advanceParser();
	}

	public void readStartDocument() throws ContentDecodingException {
		// no StartDocument element in binary
		return;
	}

	/**
	 * Read the current DOM element and ensure it matches startTag,
	 * otherwise throw ContentDecodingException.  Advances parser.
	 */
	@Override
	public void readStartElement(long startTag) throws ContentDecodingException {
		peekTag(BinaryXMLCodec.XML_DTAG, startTag);
		advanceParser();
	}

	/**
	 * Read the current DOM element and ensure it matches startTag,
	 * otherwise throw ContentDecodingException.  Advances parser.
	 *
	 * There are no attributes in the Wire format, so never do anything
	 * with @attributes.
	 */
	public void readStartElement(long startTag,
			TreeMap<String, String> attributes) throws ContentDecodingException {
		peekTag(BinaryXMLCodec.XML_DTAG, startTag);
		advanceParser();
	}

	/**
	 * Reads a blob of bytes as UTF-8 data.
	 * current element must be XML_UDATA.
	 * Consumes the next END element too.
	 * Advances parser by 2
	 */
	public String readUString() throws ContentDecodingException {
		byte [] buffer = readBinary(BinaryXMLCodec.XML_UDATA);

		return DataUtils.getUTF8StringFromBytes(buffer);
	}

	// ===================================
	// Resync

	/**
	 * Attempt Error recover from receipt of a bad packet which can cause the read to error out in the
	 * middle of reading the packet. For UDP we can just ignore this and skip to the next packet, but
	 * for TCP where data is continuous we need to skip over the rest of the bad data so that we can
	 * resync on the next good packet.
	 *
	 * Algorithm is we move the read ahead 1 byte at a time from the last good spot and then try to
	 * parse the data from there. Declare victory if we are successful.
	 *
	 * @throws IOException
	 */
	private void resync(InputStream istream) throws IOException {

		if (!istream.markSupported())
			throw new IOException("Can't resync on unresettable stream");
		backup(istream);
		int index = 0;

		while (true) {
			try {
				index = readTypeAndValue(istream);
			} catch (IOException ioe) {
				if (!(ioe instanceof ContentDecodingException)) {
					if (index == 0)
						throw ioe;
					backup(istream);
				}
			}
			if (_elements_type[index] == BinaryXMLCodec.XML_DTAG) {
				initialize();
				istream.reset();
				break;
			}
		}
	}

	/**
	 * Backup to last good spot + 1
	 * @param istream
	 * @throws IOException
	 */
	private void backup(InputStream istream) throws IOException {
		initialize();
		istream.reset();
		istream.read();
		istream.mark(_resyncLimit);
	}

	public void setResyncable(boolean value) {
		_resyncable = value;
	}

	public void setLimit(int limit) {
		_resyncLimit = limit;
	}
	
	public void setInitialBufferSize(int size) {
		_currentElements = size;
	}

	// ==============================================================
	// Unimplemented.  Yes, they're easy.  But its better to get rid
	// of using STRINGS when parsing a WIRE PACKET, so throw exceptions.


	/**
	 * Dont use strings!  will always throw exception to find any
	 * such uses when decoding the wire format.
	 */
	@Deprecated
	public String peekStartElementAsString() throws ContentDecodingException {
		throw new ContentDecodingException("Don't use STRING values when decoding WIRE objects!");
//		return tagToString(peekStartElementAsLong());
	}

	/**
	 * Dont use STRINGS when parsing a WIRE PACKET
	 */
	@Deprecated
	public CCNTime readDateTime(String startTag)
			throws ContentDecodingException {
		throw new ContentDecodingException("Don't use STRING values when decoding WIRE objects!");
	}

	/**
	 * Dont use strings!  will always throw exception to find any
	 * such uses when decoding the wire format.
	 */
	@Deprecated
	public void readStartElement(String startTag,
			TreeMap<String, String> attributes) throws ContentDecodingException {


		throw new ContentDecodingException("Don't use STRING values when decoding WIRE objects!");

		/*
		 * Ok, if you really want to do this, its like this

		final int type = _elements.get(_parsingElement).type;
		final long value = _elements.get(_parsingElement).value;
		final String str = tagToString(value);

		if( type == BinaryXMLCodec.XML_DTAG && startTag.equals(str) )
			return;

		throw new ContentDecodingException(
				String.format("Element type mismatch: expected '%s' got type 0x%04x 0x%02x ('%s')",
						startTag, type, value, str));
		 */
	}





}
