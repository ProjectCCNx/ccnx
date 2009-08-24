package org.ccnx.ccn.impl.encoding;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

import org.ccnx.ccn.impl.support.DataUtils;
import org.ccnx.ccn.impl.support.Log;



public class TextXMLCodec {

	public static final String CCN_NAMESPACE = "http://www.parc.com/ccn";
	public static final String CCN_PREFIX = "ccn";	
	public static final String CODEC_NAME = "Text";
	
	public static final String BINARY_ATTRIBUTE = "ccnbencoding";
	public static final String BINARY_ATTRIBUTE_VALUE = "base64Binary";
	
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

	// Needs to handle null and 0-length elements.
	public static String encodeBinaryElement(byte [] element) {
		if ((null == element) || (0 == element.length)) 
			return new String("");
		return new String(DataUtils.base64Encode(element));
	}
	
	public static String encodeBinaryElement(byte [] element, int offset, int length) {
		if ((null == element) || (0 == element.length)) 
			return new String("");
		ByteBuffer bbuf = ByteBuffer.wrap(element, offset, length);
		return new String(DataUtils.base64Encode(bbuf.array()));
	}

	public static byte [] decodeBinaryElement(String element) throws IOException {
		if ((null == element) || (0 == element.length()))
			return new byte[0];
		return DataUtils.base64Decode(element.getBytes());
	}

	/**
	 * Put our intput/output of Timestamps in one place as
	 * it seems tricky. Used by all classes to encode, so
	 * might want to go somewhere else.
	 * @param dateTime
	 * @return
	 */
	public static String formatDateTime(Timestamp dateTime) {
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
		//Library.logger().finest("Timestamp: " + dateTime + " formatted timestamp: " + date);
		return date;
	}
	
	public static Timestamp parseDateTime(String strDateTime) throws ParseException {
		
		// no . but has the Z 
		if (strDateTime.indexOf('.') < 0) {
			Date noNsDate = ((SimpleDateFormat)canonicalWriteDateFormat.clone()).parse(strDateTime);
			return new Timestamp(noNsDate.getTime());
		}
		
		// Split on the .
		String [] dateParts = strDateTime.split("\\.");
		// Not sure whether we really need the clone here, but we're running into some
		// odd parsing behavior...
		Date thisDate = ((SimpleDateFormat)canonicalReadDateFormat.clone()).parse(dateParts[0]);
		
		Timestamp ts =  new Timestamp(thisDate.getTime());
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
				Log.logger().finest("Nanostr: " + nanostr + " originally: " + dateParts[1] + " nanos: " + nanos + " pre-nano ts parse: " + ts);
			} catch (NumberFormatException nfe) {
				Log.logger().info("Exception in parsing nanoseconds from time: " + strDateTime);
			}
		}
		//Library.logger().finest("Parsed timestamp: " + ts + " from string: " + strDateTime);
		
		return ts;
	}
}
