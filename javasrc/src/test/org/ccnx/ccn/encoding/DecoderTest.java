/*
 * A CCNx library test.
 *
 * Copyright (C) 2011, 2013 Palo Alto Research Center, Inc.
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
package org.ccnx.ccn.encoding;

import java.io.ByteArrayInputStream;
import java.util.Random;

import junit.framework.Assert;

import org.ccnx.ccn.KeyManager;
import org.ccnx.ccn.KeyManagerScaffold;
import org.ccnx.ccn.SecurityBaseNoCcnd;
import org.ccnx.ccn.impl.encoding.BinaryXMLDecoder;
import org.ccnx.ccn.impl.encoding.XMLEncodable;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.ContentDecodingException;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Interest;
import org.junit.Test;

public class DecoderTest {
	public final String badTest = "/test/bad";
	public final String goodTest = "/test/good";
	public final String interestTest = "/test/interest";
	public final String contentTest = "/test/content";

	BinaryXMLDecoder _decoder = new BinaryXMLDecoder();

	@Test
	public void testBinaryDecoding() throws Exception {
		ContentName interestName = ContentName.fromNative(interestTest);
		Interest interest = new Interest(interestName);
		byte[] interestBytes = interest.encode();
		ByteArrayInputStream bais = new ByteArrayInputStream(interestBytes);
		_decoder.beginDecoding(bais);
		XMLEncodable packet = _decoder.getPacket();
		Assert.assertTrue("Packet has incorrect type", packet instanceof Interest);
		Assert.assertEquals(((Interest)packet).name(), interestName);

		ContentName contentName = ContentName.fromNative(contentTest);
		KeyManager keyManager = new KeyManagerScaffold();
		ContentObject co = ContentObject.buildContentObject(contentName, 
				null, "test decoder".getBytes(), 
				SecurityBaseNoCcnd.publishers[0], keyManager, null);
		byte[] contentBytes = co.encode();
		bais = new ByteArrayInputStream(contentBytes);
		_decoder.beginDecoding(bais);
		packet = _decoder.getPacket();
		Assert.assertTrue("Packet has incorrect type", packet instanceof ContentObject);
		Assert.assertEquals(((ContentObject)packet).name(), contentName);
	}

	@Test
	public void testResync() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testResync");
		Random random = new Random();
		_decoder.setResyncable(true);

		ContentName badName = ContentName.fromNative(badTest);
		Interest badInterest = new Interest(badName);
		byte[] badBytes = badInterest.encode();

		ContentName name = ContentName.fromNative(goodTest);
		Interest goodInterest = new Interest(name);
		byte[] goodBytes = goodInterest.encode();

		for (int i = 0; i < 4; i++) {
			testChopped(random.nextInt(badBytes.length - 1), badBytes, goodBytes, goodInterest, name);
		}

		KeyManager keyManager = new KeyManagerScaffold();
		ContentObject co = ContentObject.buildContentObject(badName, 
				null, "test decoder".getBytes(), 
				SecurityBaseNoCcnd.publishers[0], keyManager, null);
		badBytes = co.encode();

		for (int i = 1; i < 4; i++) {
			testChopped(random.nextInt(badBytes.length - 1), badBytes, goodBytes, goodInterest, name);
		}

		Log.info(Log.FAC_TEST, "Completed testResync");
	}

	private void testChopped(int size, byte[] toChop, byte[] good, Object kind, ContentName name) throws ContentDecodingException {
		if (size == 0)
			return;
		byte [] bytes = new byte[toChop.length + good.length - size];
		System.arraycopy(toChop, 0, bytes, 0, toChop.length - size);
		System.arraycopy(good, 0, bytes, toChop.length - size, good.length);
		ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
		_decoder.beginDecoding(bais);

		try {
			XMLEncodable packet = _decoder.getPacket();
			Assert.assertTrue("Packet has incorrect type", packet.getClass().isInstance(kind));
			Assert.assertEquals(((Interest)packet).name(), name);
		} catch (ContentDecodingException cde) {
			Log.info(Log.FAC_TEST, "Resync failed with size " + size);
		}
	}
}
