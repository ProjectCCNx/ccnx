/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008-2012 Palo Alto Research Center, Inc.
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

package org.ccnx.ccn.impl.support;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.nio.charset.Charset;
import java.nio.charset.UnsupportedCharsetException;
import java.util.ArrayList;

import org.bouncycastle.util.encoders.Base64;
import org.ccnx.ccn.config.SystemConfiguration;

/**
 * Miscellaneous utility routines for CCN, mostly data comparison and conversion.
 */
public final class DataUtils {

	public static final int BITS_PER_BYTE = 8;
	public static final String EMPTY = "";
	public static final String LINE_SEPARATOR = System.getProperty("line.separator");

	/**
	 * Useful when we move over to 1.6, and can avoid UnsupportedCharsetExceptions this way.
	 */
	public static Charset UTF8_CHARSET;

	static {
		try {
			UTF8_CHARSET = Charset.forName("UTF-8");
			if (null == UTF8_CHARSET) {
				// This shouldn't happen, but be noisy about it if it does...
				throw new UnsupportedCharsetException("Attempt to retrieve the UTF-8 charset returned null! Significant configuration error!");
			}
		} catch (Exception e) { // Should be UnsupportedCharsetException or IllegalCharsetNameException
			Log.severe("Unknown encoding UTF-8! This is a significant configuration problem.");
			throw new RuntimeException("Cannot find UTF-8 encoding. Significant configuration error");
		}
	}

	public static <T extends Comparable<T>> int compare(T left, T right) {
		int result = 0;
		if (null != left) {
			if (null == right)
				return 1; // sort nothing before something
			result = left.compareTo(right);
		} else {
			if (null != right)
				result = -1; // sort nothing before something
			// else fall through and compare publishers
			else
				result = 0; // null == null
		}
		return result;
	}

	/**
	 * Perform a shortlex comparison of byte arrays in canonical CCN ordering.
	 * Shortlex ordering is ordering by cardinality, then by lexigraphic.
	 *
	 * MM - This method should really be renamed to "shortlex" or something
	 * other than "compare", unless it is needed for an Override name.
	 *
	 * @param left
	 * @param right
	 * @return < 0 if left comes before right, 0 if they are equal, > 0 if left comes after right
	 */
	public static int compare(byte [] left, byte [] right) {
		if (null != left) {
			if (null == right) {
				return (1);
			} else {
				int leftLength = left.length;
				int rightLength = right.length;
				// If a is shorter than b then a comes before b
				if (leftLength < rightLength) {
					return (-1);
				} else if (leftLength > rightLength) {
					return (1);
				} else {
					// They have equal lengths - compare byte by byte
					for (int i=0; i < leftLength; ++i) {
						short leftSubI = (short)(left[i] & 0xff);
						short rightSubI = (short)(right[i] & 0xff);
						if (leftSubI < rightSubI) {
							return (-1);
						} else if (leftSubI > rightSubI) {
							return (1);
						}
					}
				}
			}
		} else {
			if (null != right)
				return (-1); // sort nothing before something
			// else fall through and compare publishers
			else
				return (0); // null == null
		}
		return (0);
	}

	/**
	 * This is not like compare(byte[], byte[]).  That is shortlex.  This
	 * is an actual lexigraphic ordering based on the shortlex compare
	 * of each byte array.
	 * @see compare(byte[], byte[])
	 */
	public static int compare(ArrayList<byte []> left, ArrayList<byte []> right) {

		int result = 0;
		if (null != left) {
			if (null == right) {
				result = 1;
			} else {
				// here we have the comparison.
				int leftSize = left.size();
				int rightSize = right.size();
				int minlen = (leftSize < rightSize) ? leftSize : rightSize;
				for (int i=0; i < minlen; ++i) {
					result = compare(left.get(i), right.get(i));
					if (0 != result) break;
				}
				if (result == 0) {
					// ok, they're equal up to the minimum length
					if (leftSize < rightSize) {
						result = -1;
					} else if (leftSize > rightSize) {
						result = 1;
					}
					// else they're equal, result = 0
				}
			}
		} else {
			if (null != right)
				result = -1; // sort nothing before something
			// else fall through and compare publishers
			else
				result = 0; // null == null
		}
		return result;
	}

	/**
	 * Used to print non ASCII components for logging, etc.
	 *
	 * @param bytes
	 * @return the data as a BigInteger String
	 */
	public static String printBytes(byte [] bytes) {
		if (bytes == null) {
			return "";
		}
		BigInteger bi = new BigInteger(1, bytes);
		return bi.toString(SystemConfiguration.DEBUG_RADIX);
	}

	/**
	 * Used to print components to be interpreted as hexadecimal such as segments
	 * @param bytes
	 * @return the data as a Hexadecimal String
	 */
	public static String printHexBytes(byte [] bytes) {
		if ((null == bytes) || (bytes.length == 0)) {
			return "<empty>";
		}
		BigInteger bi = new BigInteger(1, bytes);
		return bi.toString(16);
	}

	/**
	 * A place to centralize interfaces to base64 encoding/decoding, as the classes
	 * we use change depending on what ships with Java.
	 */

	public static byte [] base64Decode(byte [] input) throws IOException {
		return Base64.decode(input);
	}

	public static byte [] base64Encode(byte [] input) {
		return Base64.encode(input);
	}

	public static final int LINELEN = 64;

	public static String base64Encode(byte [] input, Integer lineLength) {
		byte [] encodedBytes = base64Encode(input);
		return lineWrap(DataUtils.getUTF8StringFromBytes(encodedBytes), LINELEN);
	}
	/**
	 * @deprecated not used in CCNx, candidate for removal in future release.
	 * @param input
	 * @param lineLength
	 * @return the byte array with added CRLF line-breaks and null termination.
	 */
	@Deprecated
	public static byte [] lineWrapBase64(byte [] input, int lineLength) {
		int finalLen = input.length + 2*(input.length/lineLength) + 3;
		byte output[] = new byte[finalLen];
		// add line breaks
		int outidx = 0;
		int inidx = 0;
		while (inidx < input.length) {
			output[outidx] = input[inidx];
			outidx++;
			inidx++;
			if ((inidx % lineLength) == 0) {
				output[outidx++] = (byte)0x0D;
				output[outidx++] = (byte)0x0A;
			}
		}
		output[outidx]='\0';
		return (output);

	}

	/**
	 * @param inputString
	 * @param lineLength
	 * @return
	 */
	public static String lineWrap(String inputString, int lineLength) {
		if ((null == inputString) || (inputString.length() <= lineLength)) {
			return inputString;
		}

		StringBuffer line = new StringBuffer(inputString);

		int length = inputString.length();
		int sepLen = LINE_SEPARATOR.length();
		int index = lineLength - sepLen;
		while (index < length - sepLen) {
			line.insert(index, LINE_SEPARATOR);
			index += lineLength;
			length += sepLen;
		}
		return line.toString();
	}

	/**
	 * byte array compare
	 * @param left
	 * @param right
	 * @return true if equal
	 */
	public static boolean arrayEquals(byte[] left, byte[] right) {
		if (left == null) {
			return ((right == null) ? true : false);
		}
		if (right == null) {
			return ((left == null) ? true : false);
		}
		if (left.length != right.length)
			return false;
		for (int i = 0; i < left.length; i++) {
			if (left[i] != right[i])
				return false;
		}
		return true;
	}

	/**
	 * byte array compare
	 * @param left
	 * @param right
	 * @param length
	 * @return true if equal
	 */
	public static boolean arrayEquals(byte[] left, byte[] right, int length) {
		if (left == null) {
			return ((right == null) ? true : false);
		}
		if (right == null) {
			return ((left == null) ? true : false);
		}

		// If one of left or right is shorter than length, arrays
		// must be same length to be equal.
		if( left.length < length || right.length < length )
			if( left.length != right.length )
				return false;

		int minarray = (left.length < right.length) ? left.length : right.length;
		int minlen   = (length < minarray) ? length : minarray;

		for (int i = 0; i < minlen; i++) {
			if (left[i] != right[i])
				return false;
		}
		return true;
	}

	/**
	 * Check if a byte array starts with a certain prefix.
	 *
	 * Used to check for binary prefixes used to mark certain ContentName components for special purposes.
	 *
	 * @param prefix bytes to look for, if null this method always returns true.
	 * @param data data to inspect. If null this method always returns false.
	 * @return true if data starts with prefix.
	 */
	public static boolean isBinaryPrefix(byte [] prefix, byte [] data) {
		if ((null == prefix) || (prefix.length == 0))
			return true;
		if ((null == data) || (data.length < prefix.length))
			return false;
		for (int i=0; i < prefix.length; ++i) {
			if (prefix[i] != data[i])
				return false;
		}
		return true;
	}

	/**
	 * Recursively delete a directory and all its contents.
	 * If given File does not exist, this method returns with no error
	 * but if it exists as a file not a directory, an exception will be thrown.
	 * Similar to org.apache.commons.io.FileUtils.deleteDirectory
	 * but avoids dependency on that library for minimal use.
	 * @param directory
	 * @throws IOException if "directory" is a file
	 */
	public static void deleteDirectory(File directory) throws IOException {
		if (!directory.exists()) {
			return;
		}
		if (!directory.isDirectory()) {
			throw new IOException(directory.getPath() + " is not a directory");
		}
		for (File child : directory.listFiles()) {
			if (child.isDirectory()) {
				deleteDirectory(child);
			} else {
				child.delete();
			}
		}
		directory.delete();
	}

	/**
	 * This was used in early content demos; keep it around as it may be generally useful.
	 * @param file
	 * @return
	 * @throws IOException
	 */
	public static byte[] getBytesFromFile(File file) throws IOException {
		InputStream is = new FileInputStream(file);

		// Get the size of the file
		long length = file.length();

		if (length > Integer.MAX_VALUE) {
			throw new IOException("File is too large: " + file.getName());
		}

		// Create the byte array to hold the data
		byte[] bytes = new byte[(int)length];

		// Read in the bytes
		int offset = 0;
		int numRead = 0;
		while (offset < bytes.length
				&& (numRead=is.read(bytes, offset, bytes.length-offset)) >= 0) {
			offset += numRead;
		}

		// Ensure all the bytes have been read in
		if (offset < bytes.length) {
			throw new IOException("Could not completely read file "+file.getName());
		}

		// Close the input stream and return bytes
		is.close();
		return bytes;
	}

	/**
	 * Read a stream (usually small) completely in to a byte array. Used to get all of the
	 * bytes out of one or more content objects for decoding or other processing, where the
	 * content needs to be handed to something else as a unit.
	 */
	public static byte [] getBytesFromStream(InputStream input) throws IOException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		byte [] buf = new byte[1024];
		int byteCount = 0;
		byteCount = input.read(buf);
		while (byteCount > 0) {
			baos.write(buf, 0, byteCount);
			byteCount = input.read(buf);
		}
		return baos.toByteArray();
	}

	/**
	 * Wrap up handling of UTF-8 encoding in one place (as much as possible), because
	 * an UnsupportedEncodingException in response to a request for UTF-8 signals
	 * a significant configuration error; we should catch it and signal a RuntimeException
	 * in one place and let the rest of the code not worry about it.
	 */
	public static String getUTF8StringFromBytes(byte [] stringBytes) {
		try {
			// Version taking a Charset not available till 1.6.
			return new String(stringBytes, "UTF-8");
		} catch (UnsupportedEncodingException e) {
			Log.severe("Unknown encoding UTF-8! This is a significant configuration problem.");
			throw new RuntimeException("Unknown encoding UTF-8! This is a significant configuration problem.");
		}
	}

	/**
	 * Wrap up handling of UTF-8 encoding in one place (as much as possible), because
	 * an UnsupportedEncodingException in response to a request for UTF-8 signals
	 * a significant configuration error; we should catch it and signal a RuntimeException
	 * in one place and let the rest of the code not worry about it.
	 */
	public static byte [] getBytesFromUTF8String(String stringData) {
		try {
			// Version taking a Charset not available till 1.6.
			return stringData.getBytes("UTF-8");
		} catch (UnsupportedEncodingException e) {
			Log.severe("Unknown encoding UTF-8! This is a significant configuration problem.");
			throw new RuntimeException("Unknown encoding UTF-8! This is a significant configuration problem.");
		}
	}

	/**
	 * Lexicographically compare two byte arrays, looking at a limited number of bytes.
	 * @param arr1
	 * @param arr2
	 * @param count Maximum number of bytes to inspect.
	 * @return < 0 if left comes before right, 0 if they are equal, > 0 if left comes after right
	 */
	public static int bytencmp(byte[] arr1, int offset1, byte[] arr2, int offset2, int count) {
		if (null == arr1) {
			if (null == arr2)
				return 0;
			return 1;
		}
		if (null == arr2)
			return -1;

		int cmpcount = Math.min(Math.min(count, (arr1.length-offset1)), (arr2.length-offset2));
		for (int i=offset1, j=offset2; i < cmpcount; ++i, ++j) {
			if (arr1[i] < arr2[j])
				return -1;
			if (arr1[i] > arr2[j])
				return 1;
		}
		if (cmpcount == count)
			return 0;
		// OK, they match up to the length of the shortest one, which is shorter
		// than count. Whichever is shorter is less.
		if (arr1.length > arr2.length)
			return 1;
		if (arr1.length < arr2.length)
			return -1;
		return 0;
	}

	public static int bytencmp(byte [] arr1, byte [] arr2, int count) {
		return bytencmp(arr1, 0, arr2, 0, count);
	}

	/**
	 * Finds the index of the first occurrence of byteToFind in array starting at given
	 * offset, returns 01 if not found.
	 * @param array array to search
	 * @param startingOffset offset into array to start at
	 * @param byteToFind byte to seek
	 * @return position in array containing first occurrence of byteToFind, or array.length if not found
	 */
	public static int byteindex(byte [] array, int startingOffset, byte byteToFind) {
		int byteindex;
		for (byteindex = startingOffset; byteindex < array.length; byteindex++) {
			if (array[byteindex] == byteToFind)
				break;
		}
		return (byteindex == array.length) ? -1 : byteindex;
	}

	/**
	 * Finds the index of the first occurrence of byteToFind in array, returns -1 if not found.
	 * @param array array to search
	 * @param byteToFind byte to seek
	 * @return position in array containing first occurrence of byteToFind, or array.length if not found
	 */
	public static int byteindex(byte [] array, byte byteToFind) {
		return byteindex(array, 0, byteToFind);
	}

	/**
	 * Finds the index of the last occurrence of byteToFind in array starting at given
	 * offset, returns -1 if not found.
	 * @param array array to search
	 * @param startingOffset offset into array to start at
	 * @param byteToFind byte to seek
	 * @return position in array containing first occurrence of byteToFind, or array.length if not found
	 */
	public static int byterindex(byte [] array, int startingOffset, byte byteToFind) {
		int byteindex;
		for (byteindex = startingOffset; byteindex >= 0; byteindex--) {
			if (array[byteindex] == byteToFind)
				break;
		}
		return byteindex;
	}

	/**
	 * Finds the last of the first occurrence of byteToFind in array, returns -1 if not found.
	 * @param array array to search
	 * @param byteToFind byte to seek
	 * @return position in array containing first occurrence of byteToFind, or array.length if not found
	 */
	public static int byterindex(byte [] array, byte byteToFind) {
		return byterindex(array, (array != null) ? array.length : 0, byteToFind);
	}


	/**
	 * Count how may times a given byte occurs in an array.
	 */
	public static int occurcount(byte [] array, int startingOffset, int length, byte byteToFind) {
		int count = 0;
		if (array == null)
			return 0;

		for (int i=startingOffset; i < length; ++i) {
			if (array[i] == byteToFind) {
				count++;
			}
		}
		return count;
	}

	public static int occurcount(byte [] array, int length, byte byteToFind) {
		return occurcount(array, 0, (null != array) ? array.length : -1, byteToFind);
	}

	public static int occurcount(byte [] array, byte byteToFind) {
		return occurcount(array, 0, byteToFind);
	}

	/**
	 * Akin to String.split for binary arrays; splits on a given byte value.
	 */
	public static byte [][] binarySplit(byte [] array, int startingOffset, byte splitValue) {
		int index = 0;
		int offset = 0;
		int lastoffset = startingOffset;
		int count = occurcount(array, startingOffset, splitValue) + 1;
		if (count == 1) {
			// no split values; just return the original array
			return new byte [][]{array};
		}
		byte [][] components = new byte[count][];
		while (index < count) {
			offset = byteindex(array, lastoffset, splitValue);
			if (offset < 0) {
				// last one
				offset = array.length;
			}
			components[index] = new byte[offset - lastoffset];
			System.arraycopy(array, lastoffset, components[index], 0, components[index].length);
			lastoffset = offset + 1;
			index++;
		}
		return components;
	}

	public static byte [][] binarySplit(byte [] array, byte splitValue) {
		return binarySplit(array, 0, splitValue);
	}

	public static byte [] subarray(byte [] array, int offset, int len) {
		byte [] newarray = new byte [len];
		System.arraycopy(array, offset, newarray, 0, len);
		return newarray;
	}

	/**
	 * Convert a BigEndian byte array in to a long assuming unsigned values.
	 * No bounds checking is done on the array -- caller should make sure
	 * it is 8 or fewer bytes.
	 *
	 * Should operate like BigInteger(1, bytes).longValue().
	 */
	public final static long byteArrayToUnsignedLong(final byte [] src) {
		long value = 0;
		for(int i = 0; i < src.length; i++) {
			value = value << 8;
			// Java will assume the byte is signed, so extend it and trim it.
			int b = (src[i]) & 0xFF;
			value |= b;
		}
		return value;
	}

	/**
	 * Like byteArrayToUnsignedLong, excpet we begin at byte position @start, not
	 * at position 0.  This is commonly used to skip the 1st byte of a CommandMarker.
	 * If @start is 0, works exactly like byteArrayToUnsignedLong(src).
	 * @param src
	 * @param start
	 * @return
	 */
	public final static long byteArrayToUnsignedLong(final byte [] src, int start) {
		long value = 0;
		for(int i = start; i < src.length; i++) {
			value = value << 8;
			// Java will assume the byte is signed, so extend it and trim it.
			int b = (src[i]) & 0xFF;
			value |= b;
		}
		return value;
	}

	/**
	 * Convert a long value to a Big Endian byte array.  Assume
	 * the long is not signed.
	 *
	 * This should be the equivalent of:
	 *		byte [] b = BigInteger.valueOf(toBinaryTimeAsLong()).toByteArray();
			if( 0 == b[0] && b.length > 1 ) {
				byte [] bb = new byte[b.length - 1];
				System.arraycopy(b, 1, bb, 0, bb.length);
				b = bb;
			}

	 */
	private final static byte [] _byte0 = {0};

	public final static byte [] unsignedLongToByteArray(final long value) {
		if( 0 == value )
			return _byte0;

		if( 0 <= value && value <= 0x00FF ) {
			byte [] bb = new byte[1];
			bb[0] = (byte) (value & 0x00FF);
			return bb;
		}


		byte [] out = null;
		int offset = -1;
		for(int i = 7; i >=0; --i) {
			byte b = (byte) ((value >> (i * 8)) & 0xFF);
			if( out == null && b != 0 ) {
				out = new byte[i+1];
				offset = i;
			}
			if( out != null )
				out[ offset - i ] = b;
		}
		return out;
	}

	/**
	 * Like unsignedLongToByteArray, except we specify what the first byte should be, so the
	 * array is 1 byte longer than normal.  This is used by things that need a CommandMarker.
	 *
	 * If the value is 0, then the array will be 1 byte with only @fistByte.  The 0x00 byte
	 * will not be included.
	 */
	public final static byte [] unsignedLongToByteArray(final long value, final byte firstByte) {
		// A little bit of unwinding for common cases.
		// These hit a lot of the SegmentationProfile cases

		if( 0 == value ) {
			byte [] bb = new byte[1];
			bb[0] = firstByte;
			return bb;
		}

		if( 0 <= value && value <= 0x00FF ) {
			byte [] bb = new byte[2];
			bb[0] = firstByte;
			bb[1] = (byte) (value & 0x00FF);
			return bb;
		}

		if( 0 <= value && value <= 0x0000FFFFL ) {
			byte [] bb = new byte[3];
			bb[0] = firstByte;
			bb[1] = (byte) ((value >>> 8) & 0x00FF);
			bb[2] = (byte) (value & 0x00FF);
			return bb;
		}

		byte [] out = null;
		int offset = -1;
		for(int i = 7; i >=0; --i) {
			byte b = (byte) ((value >> (i * 8)) & 0xFF);
			if( out == null && b != 0 ) {
				out = new byte[i+2];
				offset = i;
			}
			if( out != null )
				out[ offset - i + 1 ] = b;
		}
		out[0] = firstByte;
		return out;
	}

}
