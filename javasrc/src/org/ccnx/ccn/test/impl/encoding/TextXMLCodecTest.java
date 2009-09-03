package org.ccnx.ccn.test.impl.encoding;

import java.text.ParseException;
import java.util.logging.Level;

import junit.framework.Assert;

import org.ccnx.ccn.impl.encoding.TextXMLCodec;
import org.ccnx.ccn.impl.support.CCNTime;
import org.ccnx.ccn.impl.support.Log;
import org.junit.BeforeClass;
import org.junit.Test;


public class TextXMLCodecTest {

	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
	
		// Set debug level: use for more FINE, FINER, FINEST for debug-level tracing
		Log.setLevel(Level.INFO);
	}
	
	@Test
	public void testParseDateTime() {
		CCNTime now = CCNTime.now();
		testDateTime(now);
		
		now.setNanos(384);
		testDateTime(now);

		now.setNanos(1105384);
		testDateTime(now);
		now.setNanos(550105384);
		testDateTime(now);
		now.setNanos(550000000);
		
		testDateTime(now);
		now.setNanos(953405384);
		testDateTime(now);
		
		now.setNanos(110672800);
		testDateTime(now);
	}
	
	public void testDateTime(CCNTime testDateTime) {
		String strDateTime = TextXMLCodec.formatDateTime(testDateTime);
		System.out.println("DateTime: " + testDateTime + " XML version: " + strDateTime);
		CCNTime parsedDateTime = null;
		try {
			parsedDateTime = TextXMLCodec.parseDateTime(strDateTime);
		} catch (ParseException e) {
			System.out.println("Exception parsing date time: " + e.getMessage());
			e.printStackTrace();
			Assert.fail("Failed to parse date time: " + strDateTime);
		}
		System.out.println("Parsed version: " + parsedDateTime);
		if (!parsedDateTime.equals(testDateTime)) {
			System.out.println("Time : " + parsedDateTime + "(long: " + parsedDateTime.getTime() + ") does not equal " + testDateTime + "(long: " + testDateTime.getTime() + ")");
		}
		Assert.assertTrue(parsedDateTime.equals(testDateTime));
	}

}
