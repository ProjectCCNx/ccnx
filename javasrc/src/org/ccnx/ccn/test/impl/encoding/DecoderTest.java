package org.ccnx.ccn.test.impl.encoding;

import java.io.ByteArrayInputStream;

import junit.framework.Assert;

import org.ccnx.ccn.impl.encoding.BinaryXMLDecoder;
import org.ccnx.ccn.impl.encoding.XMLEncodable;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Interest;
import org.junit.Test;

public class DecoderTest {
	public final int CHOPOFF_SIZE = 10;
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
		ContentObject co = ContentObject.buildContentObject(contentName, "test decoder".getBytes());
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

		ContentName badName = ContentName.fromNative(badTest);
		Interest badInterest = new Interest(badName);
		byte[] badBytes = badInterest.encode();
		Assert.assertTrue(badBytes.length > CHOPOFF_SIZE);

		ContentName name = ContentName.fromNative(goodTest);
		Interest goodInterest = new Interest(name);
		byte[] goodBytes = goodInterest.encode();

		byte [] bytes = new byte[badBytes.length + goodBytes.length - CHOPOFF_SIZE];
		System.arraycopy(badBytes, 0, bytes, 0, badBytes.length - CHOPOFF_SIZE);
		System.arraycopy(goodBytes, 0, bytes, badBytes.length - CHOPOFF_SIZE, goodBytes.length);
		ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
		_decoder.beginDecoding(bais);
		XMLEncodable packet = _decoder.getPacket();
		Assert.assertTrue("Packet has incorrect type", packet instanceof Interest);
		Assert.assertEquals(((Interest)packet).name(), name);

		Log.info(Log.FAC_TEST, "Completed testResync");
	}
}
