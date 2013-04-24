/*
 * A CCNx library test.
 *
 * Copyright (C) 2008-2012 Palo Alto Research Center, Inc.
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

import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import org.bouncycastle.util.Arrays;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.Collection;
import org.ccnx.ccn.io.content.ContentDecodingException;
import org.ccnx.ccn.io.content.ContentNotReadyException;
import org.ccnx.ccn.io.content.Link;
import org.ccnx.ccn.io.content.LinkAuthenticator;
import org.ccnx.ccn.protocol.CCNTime;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.PublisherID;
import org.ccnx.ccn.protocol.SignedInfo;
import org.ccnx.ccn.protocol.PublisherID.PublisherType;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Test low level (non-CCN) EncodableObject functionality, backing objects to ByteArrayOutputStreams.
 **/
public class EncodableObjectTest {
	
	static final  String baseName = "test";
	static final  String subName = "smetters";
	static final  String document1 = "intro.html";	
	static final  String document2 = "key";	
	static final String document3 = "cv.txt";
	static final String prefix = "drawing_";
	static ContentName name = null;
	static ContentName name2 = null; 
	static ContentName name3 = null;
	static ContentName name4 = null;
	static ContentName [] ns = null;
	static public byte [] contenthash1 = new byte[32];
	static public byte [] contenthash2 = new byte[32];
	static public byte [] publisherid1 = new byte[32];
	static public byte [] publisherid2 = new byte[32];
	static PublisherID pubID1 = null;	
	static PublisherID pubID2 = null;
	static int NUM_LINKS = 100;
	static LinkAuthenticator [] las = new LinkAuthenticator[NUM_LINKS];
	static Link [] lrs = null;
	
	static Collection small1;
	static Collection small2;
	static Collection empty;
	static Collection big;

	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		ns = new ContentName[NUM_LINKS];
		for (int i=0; i < NUM_LINKS; ++i) {
			ns[i] = new ContentName(baseName, subName, document1, 
										prefix+Integer.toString(i));
		}
		Arrays.fill(publisherid1, (byte)6);
		Arrays.fill(publisherid2, (byte)3);

		pubID1 = new PublisherID(publisherid1, PublisherType.KEY);
		pubID2 = new PublisherID(publisherid2, PublisherType.ISSUER_KEY);

		las[0] = new LinkAuthenticator(pubID1);
		las[1] = null;
		las[2] = new LinkAuthenticator(pubID2, null, null,
				SignedInfo.ContentType.DATA, contenthash1);
		las[3] = new LinkAuthenticator(pubID1, null, CCNTime.now(),
				null, contenthash1);
		
		for (int j=4; j < NUM_LINKS; ++j) {
			las[j] = new LinkAuthenticator(pubID2, null, CCNTime.now(),null, null);
 		}

		lrs = new Link[NUM_LINKS];
		for (int i=0; i < lrs.length; ++i) {
			lrs[i] = new Link(ns[i],las[i]);
		}
		
		empty = new Collection();
		small1 = new Collection();
		small2 = new Collection();
		for (int i=0; i < 5; ++i) {
			small1.add(lrs[i]);
			small2.add(lrs[i+5]);
		}
		big = new Collection();
		for (int i=0; i < NUM_LINKS; ++i) {
			big.add(lrs[i]);
		}
	}

	@Test
	public void testSave() {
		Log.info(Log.FAC_TEST, "Starting testSave");

		EncodableCollectionData ecd0 = new EncodableCollectionData(empty);
		EncodableCollectionData ecd1 = new EncodableCollectionData(small1);
		EncodableCollectionData ecd2 = new EncodableCollectionData(small1);
		EncodableCollectionData ecd3 = new EncodableCollectionData(big);

		ByteArrayOutputStream baos0 = new ByteArrayOutputStream();

		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		ByteArrayOutputStream baos2 = new ByteArrayOutputStream();
		ByteArrayOutputStream baos3 = new ByteArrayOutputStream();

		try {
			ecd0.save(baos0);
			Assert.assertFalse(baos0.toByteArray().length == 0);
			System.out.println("Saved empty Collection, length: " + baos0.toByteArray().length);
			ecd1.save(baos);
			ecd2.save(baos2); // will this save? currently not, should it?
			Assert.assertArrayEquals("Serializing two versions of same content should produce same output",
					baos.toByteArray(), baos2.toByteArray());
			ecd3.save(baos3);
			boolean be = Arrays.areEqual(baos.toByteArray(), baos3.toByteArray());
			Assert.assertFalse("Two different objects shouldn't have matching output.", be);
			System.out.println("Saved two collection datas, lengths " + baos.toByteArray().length + " and " + baos3.toByteArray().length);
		} catch (IOException e) {
			fail("IOException! " + e.getMessage());
		}
		
		Log.info(Log.FAC_TEST, "Completed testSave");
	}
	
	@Test
	public void testUpdate() {
		Log.info(Log.FAC_TEST, "Starting testUpdate");

		boolean caught = false;
		EncodableCollectionData emptycoll = new EncodableCollectionData();
		try {
			emptycoll.collection();
		} catch (ContentNotReadyException iox) {
			// this is what we expect to happen
			caught = true;
		} catch (IOException ie) {
			Assert.fail("Unexpectd IOException!");
		}
		Assert.assertTrue("Failed to produce expected exception.", caught);

		EncodableCollectionData ecd1 = new EncodableCollectionData(small1);
		EncodableCollectionData ecd2 = new EncodableCollectionData();
		EncodableCollectionData ecd3 = new EncodableCollectionData(small2);
		
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		ByteArrayOutputStream baos3 = new ByteArrayOutputStream();

		try {
			ecd1.save(baos);
			ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
			ecd2.update(bais); // will this save? currently not, should it?
			Assert.assertEquals("Writing content out and back in again should give matching object.",
					ecd1, ecd2);
			ecd3.save(baos3);
			boolean be = Arrays.areEqual(baos.toByteArray(), baos3.toByteArray());
			Assert.assertFalse("Two different objects shouldn't have matching output.", be);
			System.out.println("Saved two collection datas, lengths " + baos.toByteArray().length + " and " + baos3.toByteArray().length);
		} catch (ContentDecodingException e) {
			fail("ContentDecodingException! " + e.getMessage());
		} catch (IOException e) {
			fail("IOException! " + e.getMessage());
		}
		
		Log.info(Log.FAC_TEST, "Completed testUpdate");
	}
}
