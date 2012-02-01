/*
 * Part of the CCNx Java Library.
 *
<<<<<<< HEAD
 * Copyright (C) 2008, 2009, 2010, 2011 Palo Alto Research Center, Inc.
=======
 * Copyright (C) 2008-2011 Palo Alto Research Center, Inc.
>>>>>>> 0f1ce5d4dba1b9f769b4a2edcbf8583543643287
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
import java.io.OutputStream;
import java.util.logging.Level;

import org.ccnx.ccn.impl.support.DataUtils;
import org.ccnx.ccn.impl.support.Log;

/**
 * The ccnb compressed binary XML codec. This class contains utility functions used by 
 * BinaryXMLEncoder and BinaryXMLDecoder as well as setup to use this codec with XMLCodecFactory.
 * 
 * Ccnb encoding uses a dictionary to turn tag and attribute names into short
 * binary identifiers, and uses a compressed encoding for those identifiers and the lengths
 * of atomic UTF-8 and binary data. For easy encoding & decoding there are no lengths of elements;
 * this means encoding can be done as a single pass because the length of an encoded child
 * element does not need to be known in order to write the start of the parent element.
 * 
 * The possible type tags:
 *  - dtag: binary tag encoded using a dictionary
 *  - dattr: binary attribute encoded using a dictionary
 *  - tag: UTF-8 encoded tag not found in dictionary
 *  - attribute: UTF-8 encoded attribute not found in dictionary
 *  - utf8 string
 *  - binary data
 *  
 * See the protocol documentation for more details of the ccnb format.
 */
public final class BinaryXMLCodec implements XMLCodec {
	
	/**
	 * Class for managing the paired type/value representation used to encode tags
	 * and content lengths.
	 */
	public final static class TypeAndVal {
		protected int _type;
		protected long _val;
		
		public TypeAndVal(int type, long val) {
			_type = type;
			_val = val;
		}
		
		int type() { return _type; }
		long val() { return _val; }
	}
	
	public static final String CODEC_NAME = "Binary";
	
	/**
	 * Values encoded in type and value composites.
	 **/
	/**
	 * // starts composite extension - value is subtype
	 */
	public static final byte XML_EXT = 0x00; 
	
	/**
	 * // starts composite - value is tagnamelen-1
	 */
	public static final byte XML_TAG = 0x01; 
	
	/**
	 * // starts composite - value is tagdict index
	 */
	public static final byte XML_DTAG = 0x02; 
	/**
	 * // attribute - value is attrnamelen-1, attribute value follows
	 */
	public static final byte XML_ATTR = 0x03; 
 
	/**
	 * // attribute value is attrdict index
	 */
	public static final byte XML_DATTR = 0x04; 
	/**
	 * // opaque binary data - value is byte count
	 */
	public static final byte XML_BLOB = 0x05; 
	/**
	 * // UTF-8 encoded character data - value is byte count
	 */
	public static final byte XML_UDATA = 0x06; 
	
	/**
	 * // end element
	 */
	public static final byte XML_CLOSE = 0x0; 

	/**
	 * // <?name:U value:U?>
	 */
	public static final byte XML_SUBTYPE_PROCESSING_INSTRUCTIONS = 16; 
	
	/**
	 * Masks for bitwise processing. Java's bitwise operations operate
	 * on ints, so save effort of promotion.
	 */
	public static final int XML_TT_BITS = 3;
	public static final int XML_TT_MASK = ((1 << XML_TT_BITS) - 1);
	public static final int XML_TT_VAL_BITS = XML_TT_BITS + 1;
	public static final int XML_TT_VAL_MASK = ((1 << (XML_TT_VAL_BITS)) - 1);
	public static final int XML_REG_VAL_BITS = 7;
	public static final int XML_REG_VAL_MASK = ((1 << XML_REG_VAL_BITS) - 1);
	public static final int XML_TT_NO_MORE = (1 << XML_REG_VAL_BITS); // 0x80
	public static final int BYTE_MASK = 0xFF;
	public static final int LONG_BYTES = 8;
	public static final int LONG_BITS = 64;
	
    private static final long bits_11 = 0x0000007FFL;
    private static final long bits_18 = 0x00003FFFFL;
    private static final long bits_32 = 0x0FFFFFFFFL;

    
	/**
	 * The name of this codec. Used to generate XMLEncoder and XMLDecoder instances with XMLCodecFactory.
	 * @return the codec name.
	 */
	public final static String codecName() { return CODEC_NAME; }
	

	/**
	 * Encode a type identifier (from the set listed above) and an integer value
	 * together in a composite encoding.
	 * Value is encoded in the first several bytes; with the tag encoded in the
	 * last three bits. The encoding of value is variable length in the bottom
	 * 7 bits of every byte except for the last one, where it is in the next to top
	 * 4 bits; the high order bit is set on every byte where there are more bytes
	 * to follow.
	 *
	 * @param type the type value to encode
	 * @param val Positive integer, potentially of any length, allow only longs
	 * 	   here.
	 * @param buf the buffer to encode into
	 * @param offset the offset into buf at which to start encoding
	 * @return the number of bytes used to encode.
	 * @deprecated Use encodeTypeAndVal(final int type, final long value, final OutputStream ostream)
	 */
	@Deprecated
	public static int encodeTypeAndVal(int type, long val, byte [] buf, int offset) {
		
		if ((type > XML_UDATA) || (type < 0) || (val < 0)) {
			throw new IllegalArgumentException("Tag and value must be positive, and tag valid.");
		}
		
		// Encode backwards. Calculate how many bytes we need:
		int numEncodingBytes = numEncodingBytes(val);
		
		if ((offset + numEncodingBytes) > buf.length) {
			throw new IllegalArgumentException("Buffer space of " + (buf.length-offset) + 
												" bytes insufficient to hold " + 
												numEncodingBytes + " of encoded type and value.");
		}
		
		// Bottom 4 bits of val go in last byte with tag.
		buf[offset + numEncodingBytes - 1] = 
			(byte)(BYTE_MASK &
						(((XML_TT_MASK & type) | 
						 ((XML_TT_VAL_MASK & val) << XML_TT_BITS))) |
						 XML_TT_NO_MORE); // set top bit for last byte
		val = val >>> XML_TT_VAL_BITS;;
		
		// Rest of val goes into preceding bytes, 7 bits per byte, top bit
		// is "more" flag.
		int i = offset + numEncodingBytes - 2;
		while ((0 != val) && (i >= offset)) {
			buf[i] = (byte)(BYTE_MASK &
							    (val & XML_REG_VAL_MASK)); // leave top bit unset
			val = val >>> XML_REG_VAL_BITS;
			--i;
		}
		if (val != 0) {
			Log.warning(Log.FAC_ENCODING, "This should not happen: miscalculated encoding length, have " + val + " left.");
		}
		
		return numEncodingBytes;
	}
	
	/**
	 * Convenience method. Encodes type and val into fixed buffer using encodeTypeAndVal(int, long, byte [], int)
	 * and returns the result.
	 * @param type the type value to encode
	 * @param val Positive integer, potentially of any length, allow only longs
	 * 	   here.
	 * @return the encoded type and value
	 * @deprecated Use encodeTypeAndVal(final int type, final long value, final OutputStream ostream)
	 */
	@Deprecated
	public static byte [] encodeTypeAndVal(int type, long val) {
		byte [] buf = new byte[numEncodingBytes(val)];
		
		encodeTypeAndVal(type, val, buf, 0);
		return buf;
	}
	
	/**
	 * Convenience method. Encodes type and val into output stream using encodeTypeAndVal(int, long, byte [], int)
	 * and returns the number of bytes encoded.
	 * @param tag the type value to encode
	 * @param val Positive integer, potentially of any length, allow only longs
	 * 	   here.
	 * @param ostream the stream to encode to
	 * @return the number of bytes encoded
	 */
	public static int encodeTypeAndVal(final int type, final long value, final OutputStream ostream) throws IOException {
        /*
        We exploit the fact that encoding is done from the right, so this actually means
        there is a deterministic encoding from a long to a Type/Value pair:
        
        |    0    |    1    |    2    |    3    |    4    |    5    |    6    |    7    |
        |ABCD.EFGH|IJKL.MNOP|QRST.UVWX|YZ01.2345|6789.abcd|efgh.ijkl|mnop.qrst|uvwx.yz@#
        
               60>       53>       46>       39>       32>       25>       18>       11>        4>
        |_000.ABCD|_EFG.HIJK|_LMN.OPQR|_STU.VWXY|_Z01.2345|_678.9abc|_defg.hij|_klm.nopq|_rst.uvwx|_yz@#___
        
        What we want to do is compute the result in MSB order and write it directly
        to the channel without any intermediate form.
        */
   
       int bits;
       int count = 0;
       
       // once we start writing bits, we keep writing bits even if they are "0"
       boolean writing = false;
       
       // a few heuristic to catch the small-bit length patterns
       if( value < 0 || value > 15 ) {
           int start = 60;
           if( 0 <= value ) {
        	   if( value < bits_11 )
        		   start = 4;
        	   else if( value < bits_18 )
                   start = 11;
               else if( value < bits_32 )
                   start = 25;
           }
           
           for( int i = start; i >= 4; i -= 7) {
               bits = (int) (value >>> i) & BinaryXMLCodec.XML_REG_VAL_MASK;
               if( bits != 0 || writing ) {
                   ostream.write(bits);
                   count++;
                   writing = true;
               }
           }
       }
       
       // Explicit computation of the bottom byte
       bits = type & BinaryXMLCodec.XML_TT_MASK;
       final int bottom4 = (int) value & BinaryXMLCodec.XML_TT_VAL_MASK;
       bits |= bottom4 << BinaryXMLCodec.XML_TT_BITS;
       // the bottom byte always has the NO_MORE flag
       bits |= BinaryXMLCodec.XML_TT_NO_MORE;

       ostream.write(bits);
       count++;

//		byte [] encoding = encodeTypeAndVal(tag, val);
//		ostream.write(encoding);
		return count;
	}
	
	/**
	 * Decodes a type and value pair from an InputStream.
	 * @param istream stream to read from
	 * @return a decoded type and value
	 * @throws IOException if there is an error reading or decoding the type and value pair
	 */
	public static TypeAndVal decodeTypeAndVal(InputStream istream) throws IOException {
		
		int next;
		int type = -1;
		long val = 0;
		boolean more = true;

		do {
			next = istream.read();
			
			if (next < 0) {
				return null; // at EOF
			}
			
			// If leading byte is 0, we are at an end marker, not a start marker;
			// last byte of TV will have type and high bit set. Previous bytes
			// are packed number representation, so leading 0 not legal. Either
			// error or we're just peeking.
			if ((0 == next) && (0 == val)) {
				return null;
			}
			
			more = (0 == (next & XML_TT_NO_MORE));
			
			if  (more) {
				val = val << XML_REG_VAL_BITS;
				val |= (next & XML_REG_VAL_MASK);
			} else {
				// last byte
				type = next & XML_TT_MASK;
				val = val << XML_TT_VAL_BITS;
				val |= ((next >>> XML_TT_BITS) & XML_TT_VAL_MASK);
			}
			
		} while (more);
		
		return new TypeAndVal(type, val);
	}
	
	/**
	 * Decodes a type and value pair from an InputStream, and then resets that
	 * stream at its original position.
	 * @param istream stream to read from
	 * @return a decoded type and value
	 * @throws IOException if there is an error reading or decoding the type and value pair
	 */
	public static TypeAndVal peekTypeAndVal(InputStream istream) throws IOException {
		TypeAndVal tv = null;
		istream.mark(LONG_BYTES*2);		
		try {
			tv = decodeTypeAndVal(istream);
		} finally {
			istream.reset();
		}
		return tv;
	}
		
	/**
	 * Helper method, return the number of significant bits of x.
         *
         * Deprecated; unused here, but left since it is public.
         *
	 * @param x number we want to know bit length of
	 * @return bit length of x
	 */
	public static int numbits(long x) {
		if (0 == x)
			return 0;
		return (LONG_BITS - Long.numberOfLeadingZeros(x)); 
	}

	final static int ENCODING_LIMIT_1_BYTE = ((1 << (XML_TT_VAL_BITS)) - 1);
	final static int ENCODING_LIMIT_2_BYTES = ((1 << (XML_TT_VAL_BITS + XML_REG_VAL_BITS)) - 1);
	final static int ENCODING_LIMIT_3_BYTES = ((1 << (XML_TT_VAL_BITS + 2 * XML_REG_VAL_BITS)) - 1);
	
	public static int numEncodingBytes(long x) {
		if (x <= ENCODING_LIMIT_1_BYTE) return (1);
		if (x <= ENCODING_LIMIT_2_BYTES) return (2);
		if (x <= ENCODING_LIMIT_3_BYTES) return (3);
		
		int numbytes = 1;
		
		// Last byte gives you XML_TT_VAL_BITS
		// Remainder each give you XML_REG_VAL_BITS
		x = x >>> XML_TT_VAL_BITS;
		while (x != 0) {
            numbytes++;
			x = x >>> XML_REG_VAL_BITS;
		}
		return (numbytes);
	}
	
	/**
	 * Decodes a binary blob (encoded binary content) from an InputStream.
	 * Expects to read a XML_BLOB type marker, and then the data. Has to peek
	 * to cope with 0-length blob. Inline the peek to avoid unneeded resets.
	 * @param istream stream to read from
	 * @return returns decoded blob (binary content)
	 * @throws IOException if stream cannot be read, decoded or reset
	 */
	public static byte [] decodeBlob(InputStream istream) throws IOException {
		istream.mark(LONG_BYTES*2);
		
		TypeAndVal tv = decodeTypeAndVal(istream);
		if ((null == tv) || (XML_BLOB != tv.type())) { // if we just have closers left, will get back null
			if (Log.isLoggable(Log.FAC_ENCODING, Level.FINEST))
				Log.finest(Log.FAC_ENCODING, "Expected BLOB, got " + ((null == tv) ? " not a tag " : tv.type()) + ", assuming elided 0-length blob.");
			istream.reset();
			return new byte[0];
		}
		return decodeBlob(istream, (int)tv.val());
	}
	
	/**
	 * Decodes a binary blob (encoded binary content) from an InputStream
	 * when we have already read the BLOB tag and length, and just need to read the content.
	 * @param istream stream to read from
	 * @param blobLength the length of the binary content to read in bytes
	 * @return returns decoded blob (binary content)
	 * @throws IOException if stream cannot be read or decoded
	 */
	public static byte [] decodeBlob(InputStream istream, int blobLength) throws IOException {
		byte [] bytes = new byte[blobLength];
		int count = 0;
		while (true) {
			count += istream.read(bytes, count, (blobLength - count));
			//Library.info("read "+count+" bytes out of "+blobLength+" in decodeBlob");
			if (count < bytes.length) {
				//we couldn't read enough..  need to try to read all of the bytes
				//loop again...
				//should we add a max number of tries?
			} else if (count == bytes.length) {
				//we are done reading!  return now.
				return bytes;
			} else {
				//we somehow read more than we should have...

				throw new IOException("Expected to read " + bytes.length + 
						" bytes of data, read: " + count);
			}
		}
	}
	
	/**
	 * Encodes a binary BLOB (binary content) to an output stream.
	 * @param ostream the stream to write to
	 * @param blob the binary content to write
	 * @throws IOException if there is an error encoding or writing the data
	 */
	public static void encodeBlob(OutputStream ostream, byte [] blob) throws IOException {
		encodeBlob(ostream, blob, 0, ((null == blob) ? 0 : blob.length));
	}
	
	/**
	 * Encodes a binary BLOB (binary content) to an output stream.
	 * @param ostream the stream to write to
	 * @param blob the binary content to write
	 * @param offset the offset into blob at which to start encoding data
	 * @param length the number of bytes of blob to encode
	 * @throws IOException if there is an error encoding or writing the data
	 */
	public static void encodeBlob(OutputStream ostream, byte [] blob, int offset, int length) throws IOException {
		// We elide the encoding of a 0-length blob
		if ((null == blob) || (length == 0)) {
			if (Log.isLoggable(Log.FAC_ENCODING, Level.FINER))
				Log.finer(Log.FAC_ENCODING, "Eliding 0-length blob.");
			return;
		}
		
		encodeTypeAndVal(XML_BLOB, length, ostream);
		if (null != blob) {
			ostream.write(blob, offset, length);
		}
	}

	
	/**
	 * Decodes a UTF-8 string element's content from an InputStream.
	 * Expects to read a XML_UDATA type marker, and then the data. Has to peek
	 * to cope with 0-length ustring. Inline the peek to avoid unneeded resets.
	 * This will not decode a TAG or ATTR ustring.
	 * @param istream stream to read from
	 * @return returns decoded String
	 * @throws IOException if stream cannot be read, decoded or reset
	 */
	public static String decodeUString(InputStream istream) throws IOException {
		istream.mark(LONG_BYTES*2);
		
		TypeAndVal tv = decodeTypeAndVal(istream);
		if ((null == tv) || (XML_UDATA != tv.type())) { // if we just have closers left, will get back null
			if (Log.isLoggable(Log.FAC_ENCODING, Level.FINEST))
				Log.finest(Log.FAC_ENCODING, "Expected UDATA, got " + ((null == tv) ? " not a tag " : tv.type()) + ", assuming elided 0-length blob.");
			istream.reset();
			return "";
		}
		return decodeUString(istream, (int)tv.val());
	}

	/**
	 * Decodes a UTF-8 string element's content from an InputStream
	 * when we've read the type indicator (which could be UDATA, or TAG, or ATTR)
	 * and just need to get the data. Assumes caller will cope with the fact that
	 * TAGs and ATTRs have encoded lengths that are one byte shorter than their
	 * actual data length, and that the length passed in here is actually the
	 * length of data we should read.
	 * @param istream stream to read from
	 * @param byteLength length of element to read
	 * @return returns the decoded String
	 * @throws IOException if stream cannot be read or decoded
	 */
	public static String decodeUString(InputStream istream, int byteLength) throws IOException {
		
		byte [] stringBytes = decodeBlob(istream, byteLength);
		
		return DataUtils.getUTF8StringFromBytes(stringBytes);
	}
	
	/**
	 * Encode a non-TAG, non-ATTR UString (UTF-8 String).
	 * @param ostream stream to encode to
	 * @param ustring String to encode
	 * @throws IOException if there is an error encoding the data or writing to the stream
	 */
	public static void encodeUString(OutputStream ostream, String ustring) throws IOException {
		encodeUString(ostream, ustring, XML_UDATA);
	}
	
	/**
	 * Encode the special case the UStrings that represent TAG and ATTR.
	 * The lengths of these strings are represented as length-1, as they
	 * can never be 0 length. The decrement is done here, rather than
	 * in encodeTypeAndVal.
	 * @param ostream the stream to encode to
	 * @param ustring the String containing the TAG or ATTR value. If null or a zero length string is
	 * passed in then nothing is written to the output.
	 * @param type the type to encode (XML_TAG or XML_ATTR)
	 * @throws IOException if there is an error encoding or writing the data
	 **/
	public static void encodeUString(OutputStream ostream, String ustring, byte type) throws IOException {
		
		// We elide the encoding of a 0-length UString
		if ((null == ustring) || (ustring.length() == 0)) {
			if (Log.isLoggable(Log.FAC_ENCODING, Level.FINER))
				Log.finer(Log.FAC_ENCODING, "Eliding 0-length UString.");
			return;
		}
		
		byte [] strBytes = DataUtils.getBytesFromUTF8String(ustring);
		
		encodeTypeAndVal(type, 
							(((type == XML_TAG) || (type == XML_ATTR)) ?
									(strBytes.length-1) :
									strBytes.length), ostream);
		ostream.write(strBytes);
	}
}
