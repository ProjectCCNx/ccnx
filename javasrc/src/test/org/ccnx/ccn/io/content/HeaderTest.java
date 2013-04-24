/*
 * A CCNx library test.
 *
 * Copyright (C) 2008, 2009, 2011 Palo Alto Research Center, Inc.
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

package org.ccnx.ccn.test.io.content;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import junit.framework.Assert;

import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.ContentDecodingException;
import org.ccnx.ccn.io.content.ContentEncodingException;
import org.ccnx.ccn.io.content.Header;
import org.ccnx.ccn.profiles.SegmentationProfile;
import org.ccnx.ccn.test.impl.encoding.XMLEncodableTester;
import org.junit.Test;

/**
 * Test the Header data structure.
 **/
public class HeaderTest {
	
	@Test
	public void testHeaderConstructor() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testHeaderConstructor");

		byte [] digest = new byte[]{1,2,3,4,5,6,7,8,9,0,9,8,7,6,5,4,3,2,1};
		Header seq = new Header(1, 1, 8192, 2, digest, digest);
		assertNotNull(seq);
		assertEquals(1, seq.start());
		assertEquals(1, seq.count());
		assertEquals(seq.blockSize(), 8192);
		assertEquals(seq.length(), 2);
		
		Log.info(Log.FAC_TEST, "Completed testHeaderConstructor");
	}

	@Test
	public void testHeaderConstructor2() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testHeaderConstructor2");

		int length = 77295;
		byte [] digest = new byte[]{1,2,3,4,5,6,7,8,9,0,9,8,7,6,5,4,3,2,1};
		Header seq = new Header(length, digest, digest, SegmentationProfile.DEFAULT_BLOCKSIZE);
		assertNotNull(seq);
		assertEquals(SegmentationProfile.baseSegment(), seq.start());
		assertEquals(length, seq.length());
		assertEquals(SegmentationProfile.DEFAULT_BLOCKSIZE, seq.blockSize());
		assertEquals((length + SegmentationProfile.DEFAULT_BLOCKSIZE - 1) / SegmentationProfile.DEFAULT_BLOCKSIZE, seq.count());
		
		Log.info(Log.FAC_TEST, "Completed testHeaderConstructor2");
	}
	
	@Test
	public void testHeaderConstructor3() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testHeaderConstructor3");

		int length = SegmentationProfile.DEFAULT_BLOCKSIZE;
		byte [] digest = new byte[]{1,2,3,4,5,6,7,8,9,0,9,8,7,6,5,4,3,2,1};
		Header seq = new Header(length, digest, digest, SegmentationProfile.DEFAULT_BLOCKSIZE);
		assertNotNull(seq);
		assertEquals(SegmentationProfile.baseSegment(), seq.start());
		assertEquals(length, seq.length());
		assertEquals(SegmentationProfile.DEFAULT_BLOCKSIZE, seq.blockSize());
		assertEquals(1, seq.count());
		
		Log.info(Log.FAC_TEST, "Completed testHeaderConstructor3");
	}
	
	@Test
	public void testEncodeOutputStream() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testEncodeOutputStream");

		byte [] digest = new byte[]{1,2,3,4,5,6,7,8,9,0,9,8,7,6,5,4,3,2,1};
		Header seq = new Header(1, 37, 8192, 2, digest, digest);

		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		Log.info(Log.FAC_TEST, "Encoding Header...");
		try {
			seq.encode(baos);
		} catch (ContentEncodingException e) {
			Log.warning(Log.FAC_TEST, "Exception " + e.getClass().getName() + ", message: " + e.getMessage());
			Log.warningStackTrace(Log.FAC_TEST, e);
		}
		System.out.println("Encoded Header: " );
		System.out.println(baos.toString());
		Header dec = new Header();
		dec.decode(baos.toByteArray());
		seq.equals(dec);
		Assert.assertEquals(seq, dec);
		
		Header dt = new Header();
		Header db = new Header();
		
		XMLEncodableTester.encodeDecodeTest("Header", seq, dt, db);
		
		Log.info(Log.FAC_TEST, "Completed testEncodeOutputStream");
	}

	@Test
	public void testDecodeInputStream() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testDecodeInputStream");

		byte [] digest = new byte[]{1,2,3,4,5,6,7,8,9,0,9,8,7,6,5,4,3,2,1};
		Header seqIn = new Header(83545, digest, digest, SegmentationProfile.DEFAULT_BLOCKSIZE);
		Header seqOut = new Header();

		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		try {
			seqIn.encode(baos);
		} catch (ContentEncodingException e) {
			Log.warning(Log.FAC_TEST, "Exception " + e.getClass().getName() + ", message: " + e.getMessage());
			Log.warningStackTrace(Log.FAC_TEST, e);
		}
		System.out.println("Encoded header: " + baos.toString());

		System.out.println("Decoding header...");
		ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());

		try {
			seqOut.decode(bais);
		} catch (ContentDecodingException e) {
			Log.warning(Log.FAC_TEST, "Exception " + e.getClass().getName() + ", message: " + e.getMessage());
			Log.warningStackTrace(Log.FAC_TEST, e);
		}
		System.out.println("Decoded header: " + seqOut);
		assertEquals(seqIn, seqOut);
		
		Log.info(Log.FAC_TEST, "Completed testDecodeInputStream");
	}

}
