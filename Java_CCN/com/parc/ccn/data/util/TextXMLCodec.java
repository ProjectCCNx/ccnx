package com.parc.ccn.data.util;

import java.io.IOException;
import java.sql.Timestamp;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

import sun.misc.BASE64Decoder;
import sun.misc.BASE64Encoder;

public class TextXMLCodec {

	public static final String CCN_NAMESPACE = "http://www.parc.com/ccn";
	public static final String CCN_PREFIX = "ccn";	
	public static final String CODEC_NAME = "Text";
	
	public static final String BINARY_ATTRIBUTE = "ccnbencoding";
	public static final String BINARY_ATTRIBUTE_VALUE = "base64Binary";
	
	public static String codecName() { return CODEC_NAME; }

	protected static DateFormat canonicalDateFormat = null;
	protected static final String PAD_STRING = "00000000";
	
	static {
		canonicalDateFormat = 
			new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.S'Z'");
		canonicalDateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
	}

	// Needs to handle null and 0-length elements.
	public static String encodeBinaryElement(byte [] element) {
		if ((null == element) || (0 == element.length)) 
			return new String("");
		return new BASE64Encoder().encode(element);
	}
	
	public static byte [] decodeBinaryElement(String element) throws IOException {
		if ((null == element) || (0 == element.length()))
			return new byte[0];
		return new BASE64Decoder().decodeBuffer(element);
	}

	/**
	 * Put our intput/output of Timestamps in one place as
	 * it seems tricky. Used by all classes to encode, so
	 * might want to go somewhere else.
	 * @param dateTime
	 * @return
	 */
	public static String formatDateTime(Timestamp dateTime) {
		// Need to put on fractional seconds (msec, nsec)
		// as bits after the second...
		// DKS TODO handle nanoseconds or give up on them.
		String date = canonicalDateFormat.format(dateTime);
		/* Nanoseconds are not handled by parser, so skip them here for now
		long nanos = dateTime.getNanos() % 1000000;
		if (nanos > 0) {
			// we have real nanos
			String nstr = Long.toString(nanos);
			if (nstr.length() < 8) {
				nstr = PAD_STRING.substring(0, (8-nstr.length())) + nstr + "Z";
			}
			date = date.replace("Z", nstr);
		}
		*/
		return date;
	}
	
	public static Timestamp parseDateTime(String strDateTime) throws ParseException {
		Date thisDate = canonicalDateFormat.parse(strDateTime);
		return new Timestamp(thisDate.getTime());
	}
}
