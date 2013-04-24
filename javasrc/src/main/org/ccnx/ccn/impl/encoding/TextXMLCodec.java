/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008, 2009, 2011 Palo Alto Research Center, Inc.
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
import java.nio.ByteBuffer;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;
import java.util.logging.Level;

import org.ccnx.ccn.impl.support.DataUtils;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.protocol.CCNTime;

/**
 * A text-based XML codec.
 * 
 * Close to standard text XML, though with limited support for things like namespaces.
 * This class contains utility functions used by TextXMLEncoder and TextXMLDecoder
 * as well as setup to use this codec with XMLCodecFactory.
 */
public class TextXMLCodec implements XMLCodec {

	public static final String CCN_NAMESPACE = "http://www.parc.com/ccn";
	public static final String CCN_PREFIX = "ccn";	
	public static final String CODEC_NAME = "Text";
	public static final String BINARY_ATTRIBUTE = "ccnbencoding";
	public static final String BINARY_ATTRIBUTE_VALUE = "base64Binary";
	
	/**
	 * The name of this codec. Used to generate XMLEncoder and XMLDecoder instances with XMLCodecFactory.
	 * @return the codec name.
	 */
	public static String codecName() { return CODEC_NAME; }

	protected static DateFormat canonicalWriteDateFormat = null;
	protected static DateFormat canonicalReadDateFormat = null;
	protected static final String PAD_STRING = "000000000";
	protected static final int NANO_LENGTH = 9;
	
	static {
		canonicalWriteDateFormat = 
			new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
		/* new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.S'Z'"); // writing ns doesn't format leading 0's correctly */
		canonicalWriteDateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
		canonicalReadDateFormat = 
			new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
			// new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.S'Z'");
		canonicalReadDateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
	}

	/**
	 * Encodes a binary element as base 64.
	 * @param element the element data to encode. Needs to handle null and 0-length elements
	 * @return the binary data base64 encoded into a String
	 */
	public static String encodeBinaryElement(byte [] element) {
		if ((null == element) || (0 == element.length)) 
			return new String("");
		return new String(DataUtils.base64Encode(element));
	}
	
	/**
	 * Encodes a binary element as base 64.
	 * @param element the element data to encode. Needs to handle null and 0-length elements
	 * @param offset the offset into element at which to start encoding
	 * @param length how many bytes of element to encode
	 * @return the binary data base64 encoded into a String
	 */
	public static String encodeBinaryElement(byte [] element, int offset, int length) {
		if ((null == element) || (0 == element.length)) 
			return new String("");
		ByteBuffer bbuf = ByteBuffer.wrap(element, offset, length);
		return new String(DataUtils.base64Encode(bbuf.array()));
	}

	/**
	 * Decodes a base64-encoded binary element back into a byte array.
	 * @param element base64-encoded element content
	 * @return the decoded byte array
	 * @throws IOException if element is not valid base64
	 */
	public static byte [] decodeBinaryElement(String element) throws IOException {
		if ((null == element) || (0 == element.length()))
			return new byte[0];
		return DataUtils.base64Decode(element.getBytes());
	}

	/**
	 * Encapsulate our timestamp formatting/parsing for consistency. Use a simple
	 * standard format for outputing a quantized CCNTime.
	 * @param dateTime the timestamp to encode
	 * @return the formatted timestamp
	 */
	public static String formatDateTime(CCNTime dateTime) {
		// Handles nanoseconds
		String date = ((SimpleDateFormat)canonicalWriteDateFormat.clone()).format(dateTime);
		if (dateTime.getNanos() > 0) {
			String ns = String.format(".%09d", dateTime.getNanos());
			// now need to truncate trailing 0's
			if (ns.endsWith("0")) {
				// we know this has at least 1 non-0 character before the last 0's
				int trailerEnd = ns.length()-2;
				while ((ns.charAt(trailerEnd) == '0') && (trailerEnd > 0)) {
					--trailerEnd;
				}
				ns = ns.substring(0,trailerEnd+1);
			}
			date = date.replace("Z", ns) + "Z";
		}
		//Library.finest("Timestamp: " + dateTime + " formatted timestamp: " + date);
		return date;
	}
	
	/**
	 * Encapsulate our timestamp formatting/parsing for consistency. Use a simple
	 * standard format for outputing a quantized CCNTime.
	 * @param strDateTime the string-encoded timestamp
	 * @return the parsed timestamp as a CCNTime
	 */
	public static CCNTime parseDateTime(String strDateTime) throws ParseException {
		
		// no . but has the Z 
		if (strDateTime.indexOf('.') < 0) {
			Date noNsDate = ((SimpleDateFormat)canonicalWriteDateFormat.clone()).parse(strDateTime);
			return new CCNTime(noNsDate);
		}
		
		// Split on the .
		String [] dateParts = strDateTime.split("\\.");
		// Not sure whether we really need the clone here, but we're running into some
		// odd parsing behavior...
		Date thisDate = ((SimpleDateFormat)canonicalReadDateFormat.clone()).parse(dateParts[0]);
		
		CCNTime ts =  new CCNTime(thisDate);
		// Deal with nanos. Parser ignores them, so don't have to pull them out.
		int index = strDateTime.indexOf('.');
		int nanos = 0;
		if (index >= 0) {
			try {
				String nanostr = dateParts[1].substring(0, dateParts[1].length()-1); // remove trailing Z
				nanostr = 
					(nanostr.length() < NANO_LENGTH) ? (nanostr + PAD_STRING.substring(0, (NANO_LENGTH - nanostr.length()))) :
															(nanostr);
				nanos = Integer.valueOf(nanostr.toString());
				ts.setNanos(nanos);
				if (Log.isLoggable(Log.FAC_ENCODING, Level.FINEST))
					Log.finest(Log.FAC_ENCODING, "Nanostr: " + nanostr + " originally: " + dateParts[1] + " nanos: " + nanos + " pre-nano ts parse: " + ts);
			} catch (NumberFormatException nfe) {
				Log.info("Exception in parsing nanoseconds from time: " + strDateTime);
			}
		}
		//Library.finest("Parsed timestamp: " + ts + " from string: " + strDateTime);
		
		return ts;
	}
}
