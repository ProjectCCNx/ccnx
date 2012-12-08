/*
 * A CCNx library test.
 *
 * Copyright (C) 2008, 2009, 2011, 2012 Palo Alto Research Center, Inc.
 *
 * This work is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License version 2 as published by the
 * Free Software Foundation. 
 * This work is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License
 * for more details. You should have received a copy of the GNU General Public
 * License along with this program; if not, write to the
 * Free Software Foundation, Inc., 51 Franklin Street, Fifth Floor,
 * Boston, MA 02110-1301, USA.
 */

package org.ccnx.ccn.test.impl.encoding;

import java.text.ParseException;

import junit.framework.Assert;

import org.ccnx.ccn.impl.encoding.CCNProtocolDTags;
import org.ccnx.ccn.impl.encoding.TextXMLCodec;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.protocol.CCNTime;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Test portions of the encoder/decoder infrastructure related to encoding dates
 * and times. Largely superseded by CCNTime.
 */
public class TextXMLCodecTest {

	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
	}
	
	@Test
	public void testTagMap() {
		Log.info(Log.FAC_TEST, "Starting testTagMap");

		String name;
		Long tag;
		for (int i=1; i <= CCNProtocolDTags.Parameters; ++i) {
			name = CCNProtocolDTags.tagToString(i);
			if (name != null) {
				tag = CCNProtocolDTags.stringToTag(name);
				Assert.assertEquals(tag.longValue(), i);
			}
		}
		name = CCNProtocolDTags.tagToString(CCNProtocolDTags.Interest);
		Assert.assertEquals("Interest", name);
		
		name = CCNProtocolDTags.tagToString(CCNProtocolDTags.ExtOpt);
		Assert.assertEquals("ExtOpt", name);

		name = CCNProtocolDTags.tagToString(CCNProtocolDTags.RootDigest);
		Assert.assertEquals("RootDigest", name);

		name = CCNProtocolDTags.tagToString(CCNProtocolDTags.Nonce);
		Assert.assertEquals("Nonce", name);

		name = CCNProtocolDTags.tagToString(CCNProtocolDTags.AnswerOriginKind);
		Assert.assertEquals("AnswerOriginKind", name);
		
		name = CCNProtocolDTags.tagToString(CCNProtocolDTags.Witness);
		Assert.assertEquals("Witness", name);

		name = CCNProtocolDTags.tagToString(CCNProtocolDTags.FinalBlockID);
		Assert.assertEquals("FinalBlockID", name);
		
		name = CCNProtocolDTags.tagToString(CCNProtocolDTags.EncryptedKey);
		Assert.assertEquals("EncryptedKey", name);
		
		name = CCNProtocolDTags.tagToString(CCNProtocolDTags.BinaryValue);
		Assert.assertEquals("BinaryValue", name);
		
		name = CCNProtocolDTags.tagToString(CCNProtocolDTags.ProfileName);
		Assert.assertEquals("ProfileName", name);

		name = CCNProtocolDTags.tagToString(CCNProtocolDTags.Parameters);
		Assert.assertEquals("Parameters", name);
		
		Log.info(Log.FAC_TEST, "Completed testTagMap");
	}
	
	@Test
	public void testParseDateTime() {
		Log.info(Log.FAC_TEST, "Starting testParseDateTime");

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
		
		Log.info(Log.FAC_TEST, "Completed testParseDateTime");
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