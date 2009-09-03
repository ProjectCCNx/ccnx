package org.ccnx.ccn.impl.support;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.util.ArrayList;

import org.bouncycastle.util.encoders.Base64;
import org.ccnx.ccn.config.SystemConfiguration;


public class DataUtils {
	
	public static class Tuple<A, B> {
		
		A _first;
		B _second;
		
		public Tuple(A first, B second) {
			_first = first;
			_second = second;
		}
		
		public A first() { return _first; }
		public B second() { return _second; }
		public void setFirst(A first) { _first = first; }
		public void setSecond(B second) { _second = second; }
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
	 * Perform a lexigraphical comparison
	 * @param left
	 * @param right
	 * @return < 0 if left comes before right, 0 if they are equal, > 0 if left comes after right
	 */
	public static int compare(byte [] left, byte [] right) {
		int result = 0;
		if (null != left) {
			if (null == right) {
				result = 1;
			} else {
				// If a is shorter than b then a comes before b
				if (left.length < right.length) {
					result = -1;
				} else if (left.length > right.length) {
					result = 1;
				} else {
					// They have equal lengths - compare byte by byte
					for (int i=0; i < left.length; ++i) {
						if ((short)(left[i] & 0xff) < (short)(right[i] & 0xff)) {
							result = -1;
							break;
						} else if ((short)(left[i] & 0xff) > (short)(right[i] & 0xff)) {
							result = 1;
							break;
						}
					}
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
	 * @see #compare(byte[], byte[])
	 */
	public static int compare(ArrayList<byte []> left, ArrayList<byte []> right) {

		int result = 0;
		if (null != left) {
			if (null == right) {
				result = 1;
			} else {
				// here we have the comparison.
				int minlen = (left.size() < right.size()) ? left.size() : right.size();
				for (int i=0; i < minlen; ++i) {
					result = compare(left.get(i), right.get(i));
					if (0 != result) break;
				}
				if (result == 0) {
					// ok, they're equal up to the minimum length
					if (left.size() < right.size()) {
						result = -1;
					} else if (left.size() > right.size()) {
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

	public static String printBytes(byte [] bytes) {
		BigInteger bi = new BigInteger(1, bytes);
		return bi.toString(SystemConfiguration.DEBUG_RADIX);
	}
	
	public static String printHexBytes(byte [] bytes) {
		BigInteger bi = new BigInteger(1, bytes);
		return bi.toString(16);
	}
	
	/*
	 * A place to centralize interfaces to base64 encoding/decoding, as the classes
	 * we use change depending on what ships with Java.
	 */
	public static byte [] base64Decode(byte [] input) throws IOException {
		return Base64.decode(input);
	}
	
	public static byte [] base64Encode(byte [] input) {
		return Base64.encode(input);
	}

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
	
	public static boolean arrayEquals(byte[] left, byte[] right, int length) {
		if (left == null) {
			return ((right == null) ? true : false);
		}
		if (right == null) {
			return ((left == null) ? true : false);
		}
		if (left.length < length || right.length < length)
			return false;
		for (int i = 0; i < length; i++) {
			if (left[i] != right[i])
				return false;
		}
		return true;
	}
	
	public static boolean isBinaryPrefix(byte [] prefix,
										 byte [] data) {
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
	 * @throws IOException
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

	public static byte[] getBytesFromFile(File file) throws IOException {
	    InputStream is = new FileInputStream(file);
	
	    // Get the size of the file
	    long length = file.length();
	
	    if (length > Integer.MAX_VALUE) {
	        // File is too large
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
	 * @param count Lexicographically compare two byte arrays, looking at at most count bytes.
	 * @return
	 */
	public static int bytencmp(byte[] arr1, byte[] arr2, int count) {
		if (null == arr1) {
			if (null == arr2)
				return 0;
			return 1;
		}
		if (null == arr2)
			return -1;
		
		int cmpcount = Math.min(Math.min(count, arr1.length), arr2.length);
		for (int i=0; i < cmpcount; ++i) {
			if (arr1[i] < arr2[i])
				return -1;
			if (arr2[i] > arr1[i])
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
}
