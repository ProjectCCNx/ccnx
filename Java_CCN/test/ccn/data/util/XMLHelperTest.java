package test.ccn.data.util;

import java.sql.Timestamp;
import java.text.ParseException;

import junit.framework.Assert;

import org.junit.Test;

import com.parc.ccn.data.util.XMLHelper;

public class XMLHelperTest {

	@Test
	public void testParseDateTime() {
		Timestamp now = new Timestamp(System.currentTimeMillis());
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
	}
	
	public void testDateTime(Timestamp testDateTime) {
		String strDateTime = XMLHelper.formatDateTime(testDateTime);
		System.out.println("DateTime: " + testDateTime + " XML version: " + strDateTime);
		Timestamp parsedDateTime = null;
		try {
			parsedDateTime = XMLHelper.parseDateTime(strDateTime);
		} catch (ParseException e) {
			System.out.println("Exception parsing date time: " + e.getMessage());
			e.printStackTrace();
			Assert.fail("Failed to parse date time: " + strDateTime);
		}
		System.out.println("Parsed version: " + parsedDateTime);		
	}

}
