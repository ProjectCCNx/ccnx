package com.parc.ccn.data.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import com.parc.ccn.Library;

public class BinaryXMLCodec  {
	
	public static class TypeAndVal {
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
	 * Possible type tags:
	 *  - tag
	 *  - attribute
	 *  - dtag
	 *  - dattr
	 *  - utf8 string
	 *  - binary data
	 */ 
	public static byte XML_EXT = 0x00; // starts composite extension - numval is subtype
	public static byte XML_TAG = 0x01; // starts composite - numval is tagnamelen-1
	public static byte XML_DTAG = 0x02; // starts composite - numval is tagdict index
	public static byte XML_ATTR = 0x03; // attribute - numval is attrnamelen-1, value follows
	public static byte XML_DATTR = 0x04; // attribute numval is attrdict index
	public static byte XML_BLOB = 0x05; // opaque binary data - numval is byte count
	public static byte XML_UDATA = 0x06; // UTF-8 encoded character data - numval is byte count
	
	public static byte XML_CLOSE = 0x0; // end element

	public static byte XML_SUBTYPE_PROCESSING_INSTRUCTIONS = 16; // <?name:U value:U?>
	
	/**
	 * Masks for bitwise processing. Java's bitwise operations operate
	 * on ints, so save effort of promotion.
	 */
	public static int XML_TT_BITS = 3;
	public static int XML_TT_MASK = ((1 << XML_TT_BITS) - 1);
	public static int XML_TT_VAL_BITS = XML_TT_BITS + 1;
	public static int XML_TT_VAL_MASK = ((1 << (XML_TT_VAL_BITS)) - 1);
	public static int XML_REG_VAL_BITS = 7;
	public static int XML_REG_VAL_MASK = ((1 << XML_REG_VAL_BITS) - 1);
	public static int XML_TT_NO_MORE = (1 << XML_REG_VAL_BITS); // 0x80
	public static int BYTE_MASK = 0xFF;
	public static int LONG_BYTES = 8;
	public static int LONG_BITS = 64;
	
	public static String codecName() { return CODEC_NAME; }
	

	/**
	 * Value is encoded in the first several bytes; with the tag encoded in the
	 * last three bits. The encoding of value is variable length in the bottom
	 * 7 bits of every byte except for the last one, where it is in the next to top
	 * 4 bits; the high order bit is set on every byte where there are more bytes
	 * to follow.
	 *
	 * @param tag
	 * @param val Positive integer, potentially of any length, allow only longs
	 * 	   here.
	 * @return the number of bytes used to encode.
	 */
	public static int encodeTypeAndVal(int tag, long val, byte [] buf, int offset) {
		
		if ((tag > XML_UDATA) || (tag < 0) || (val < 0)) {
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
						(((XML_TT_MASK & tag) | 
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
			Library.logger().info("This should not happen: miscalculated encoding length, have " + val + " left.");
		}
		
		return numEncodingBytes;
	}
	
	public static byte [] encodeTypeAndVal(int tag, long val) {
		byte [] buf = new byte[numEncodingBytes(val)];
		
		encodeTypeAndVal(tag, val, buf, 0);
		return buf;
	}
	
	public static int encodeTypeAndVal(int tag, long val, OutputStream ostream) throws IOException {
		byte [] encoding = encodeTypeAndVal(tag, val);
		ostream.write(encoding);
		return encoding.length;
	}
	
	public static TypeAndVal decodeTypeAndVal(InputStream istream) throws IOException {
		
		int next;
		int type = -1;
		long val = 0;
		
		do {
			next = istream.read();
			
			if (next < 0) {
				if (istream instanceof com.parc.ccn.library.io.CCNInputStream) {
					Library.logger().info("Reached EOF in decodeTypeAndVal.");
				}
				return null; // at EOF
			}
			
			// If leading byte is 0, we are at an end marker, not a start marker;
			// last byte of TV will have type and high bit set. Previous bytes
			// are packed number representation, so leading 0 not legal. Either
			// error or we're just peeking.
			if ((0 == next) && (0 == val)) {
				return null;
			}
			
			if (0 == (next & XML_TT_NO_MORE)) {
				val = val << XML_REG_VAL_BITS;
				val |= (next & XML_REG_VAL_MASK);
			} else {
				// last byte
				type = next & XML_TT_MASK;
				val = val << XML_TT_VAL_BITS;
				val |= ((next >>> XML_TT_BITS) & XML_TT_VAL_MASK);
			}
			
		} while (0 == (next & XML_TT_NO_MORE));
		
		return new TypeAndVal(type, val);
	}
	
	public static TypeAndVal peekTypeAndVal(InputStream istream) throws IOException {
		if (!istream.markSupported()) {
			Library.logger().info("Cannot peek -- stream without marking ability!");
			throw new IOException("No lookahead in stream!");
		}

		istream.mark(LONG_BYTES*2);
		
		TypeAndVal tv = null;
		
		try {
			tv = decodeTypeAndVal(istream);
		} finally {
			istream.reset();
		}
		return tv;
	}
			
	public static int numbits(long x) {
		if (0 == x)
			return 0;
		return (LONG_BITS - Long.numberOfLeadingZeros(x)); 
	}
	
	public static int numEncodingBytes(long x) {
		int numbits = numbits(x);
		
		// Last byte gives you XML_TT_VAL_BITS
		// Remainder each give you XML_REG_VAL_BITS
		numbits -= XML_TT_VAL_BITS;
		// If numbits < 0 here, ceil brings it up to 0 which gives the correct behavior.
		return (((int)Math.ceil((double)numbits/XML_REG_VAL_BITS) + 1));
	}
	
	/**
	 * Expects to read a XML_BLOB type marker, and then the data.
	 */
	public static byte [] decodeBlob(InputStream istream) throws IOException {
		TypeAndVal tv = decodeTypeAndVal(istream);
		
		if ((null == tv) || (XML_BLOB != tv.type())) {
			throw new IOException("Unexpected type, expected XML_BLOB " + XML_BLOB + "," +
					" got " + ((null != tv) ? tv.type() : "not a tag."));
		}
		return decodeBlob(istream, (int)tv.val());
	}
	
	/**
	 * If we've already read the tag, and just need to get the data.
	 */
	public static byte [] decodeBlob(InputStream istream, int blobLength) throws IOException {
		byte [] bytes = new byte[blobLength];
		
		int count = istream.read(bytes);
		
		if (count != bytes.length) {
			throw new IOException("Expected to read " + bytes.length + 
					" bytes of data, only read: " + count);
		}
		
		return bytes;
	}
	
	/**
	 * Expects to read a XML_UDATA type marker, and then the data.
	 * This will not decode a TAG or ATTR ustring.
	 */
	public static String decodeUString(InputStream istream) throws IOException {
		TypeAndVal tv = decodeTypeAndVal(istream);
		
		if ((null == tv) || (XML_UDATA != tv.type())) {
			throw new IOException("Unexpected type, expected XML_USTRING " + XML_UDATA + "," +
					" got " + ((null != tv) ? tv.type() : "not a tag."));
		}
		return decodeUString(istream, (int)tv.val());
	}

	/**
	 * If we've read the type indicator (which could be UDATA, or TAG, or ATTR)
	 * and just need to get the data. Assumes caller will cope with the fact that
	 * TAGs and ATTRs have encoded lengths that are one byte shorter than their
	 * actual data length, and that the length passed in here is actually the
	 * length of data we should read.
	 * @param istream
	 * @param byteLength
	 * @return
	 * @throws IOException
	 */
	public static String decodeUString(InputStream istream, int byteLength) throws IOException {
		
		byte [] stringBytes = decodeBlob(istream, byteLength);
		
		String ustring = new String(stringBytes, "UTF-8");
		return ustring;
	}
	
	/**
	 * Encode as UDATA element.
	 * @param istream
	 * @param ustring
	 * @throws IOException
	 */
	public static void encodeUString(OutputStream ostream, String ustring) throws IOException {
		encodeUString(ostream, ustring, XML_UDATA);
	}
	
	/**
	 * We have to special case the UStrings that represent TAG and ATTR.
	 * The lengths of these strings are represented as length-1, as they
	 * can never be 0 length. The decrement is done here, rather than
	 * in encodeTypeAndVal. Alternatively, we could make this generic, and
	 * either provide another encoder specifically for tags, or allow
	 * caller to give us a length.
	 **/
	public static void encodeUString(OutputStream ostream, String ustring, byte type) throws IOException {
		byte [] strBytes = ustring.getBytes("UTF-8");
		
		encodeTypeAndVal(type, 
							(((type == XML_TAG) || (type == XML_ATTR)) ?
									(strBytes.length-1) :
									strBytes.length), ostream);
		ostream.write(strBytes);
	}

	public static void encodeBlob(OutputStream ostream, byte [] blob) throws IOException {
		encodeBlob(ostream, blob, 0, ((null == blob) ? 0 : blob.length));
	}
	
	public static void encodeBlob(OutputStream ostream, byte [] blob, int offset, int length) throws IOException {
		encodeTypeAndVal(XML_BLOB, length, ostream);
		if (null != blob) {
			ostream.write(blob, offset, length);
		}
	}
}
