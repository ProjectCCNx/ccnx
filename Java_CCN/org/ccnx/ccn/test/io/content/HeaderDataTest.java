/**
 * 
 */
package org.ccnx.ccn.test.io.content;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import javax.xml.stream.XMLStreamException;

import junit.framework.Assert;

import org.ccnx.ccn.io.content.HeaderData;
import org.ccnx.ccn.profiles.SegmentationProfile;
import org.junit.Test;

import test.ccn.data.util.XMLEncodableTester;


/**
 * @author briggs, rasmusse, smetters
 *
 * DKS - Make this back into the HeaderData test it ought to be.
 */
public class HeaderDataTest {
	
	@Test
	public void testHeaderConstructor() throws Exception {
		byte [] digest = new byte[]{1,2,3,4,5,6,7,8,9,0,9,8,7,6,5,4,3,2,1};
		HeaderData seq = new HeaderData(1, 1, 8192, 2, digest, digest);
		assertNotNull(seq);
		assertEquals(1, seq.start());
		assertEquals(1, seq.count());
		assertEquals(seq.blockSize(), 8192);
		assertEquals(seq.length(), 2);
	}

	@Test
	public void testHeaderConstructor2() throws Exception {
		int length = 77295;
		byte [] digest = new byte[]{1,2,3,4,5,6,7,8,9,0,9,8,7,6,5,4,3,2,1};
		HeaderData seq = new HeaderData(length, digest, digest, SegmentationProfile.DEFAULT_BLOCKSIZE);
		assertNotNull(seq);
		assertEquals(SegmentationProfile.baseSegment(), seq.start());
		assertEquals(length, seq.length());
		assertEquals(SegmentationProfile.DEFAULT_BLOCKSIZE, seq.blockSize());
		assertEquals((length + SegmentationProfile.DEFAULT_BLOCKSIZE - 1) / SegmentationProfile.DEFAULT_BLOCKSIZE, seq.count());
	}
	@Test
	public void testHeaderConstructor3() throws Exception {
		int length = SegmentationProfile.DEFAULT_BLOCKSIZE;
		byte [] digest = new byte[]{1,2,3,4,5,6,7,8,9,0,9,8,7,6,5,4,3,2,1};
		HeaderData seq = new HeaderData(length, digest, digest, SegmentationProfile.DEFAULT_BLOCKSIZE);
		assertNotNull(seq);
		assertEquals(SegmentationProfile.baseSegment(), seq.start());
		assertEquals(length, seq.length());
		assertEquals(SegmentationProfile.DEFAULT_BLOCKSIZE, seq.blockSize());
		assertEquals(1, seq.count());
	}
	@Test
	public void testEncodeOutputStream() throws Exception {
		byte [] digest = new byte[]{1,2,3,4,5,6,7,8,9,0,9,8,7,6,5,4,3,2,1};
		HeaderData seq = new HeaderData(1, 37, 8192, 2, digest, digest);

		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		System.out.println("Encoding HeaderData...");
		try {
			seq.encode(baos);
		} catch (XMLStreamException e) {
			System.out.println("Exception " + e.getClass().getName() + ", message: " + e.getMessage());
			e.printStackTrace();
		}
		System.out.println("Encoded HeaderData: " );
		System.out.println(baos.toString());
		HeaderData dec = new HeaderData();
		dec.decode(baos.toByteArray());
		seq.equals(dec);
		Assert.assertEquals(seq, dec);
		
		HeaderData dt = new HeaderData();
		HeaderData db = new HeaderData();
		
		XMLEncodableTester.encodeDecodeTest("HeaderData", seq, dt, db);
	}

	@Test
	public void testDecodeInputStream() throws Exception {
		byte [] digest = new byte[]{1,2,3,4,5,6,7,8,9,0,9,8,7,6,5,4,3,2,1};
		HeaderData seqIn = new HeaderData(83545, digest, digest, SegmentationProfile.DEFAULT_BLOCKSIZE);
		HeaderData seqOut = new HeaderData();

		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		try {
			seqIn.encode(baos);
		} catch (XMLStreamException e) {
			System.out.println("Exception " + e.getClass().getName() + ", message: " + e.getMessage());
			e.printStackTrace();
		}
		System.out.println("Encoded header: " + baos.toString());

		System.out.println("Decoding header...");
		ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());

		try {
			seqOut.decode(bais);
		} catch (XMLStreamException e) {
			System.out.println("Exception " + e.getClass().getName() + ", message: " + e.getMessage());
			e.printStackTrace();
		}
		System.out.println("Decoded header: " + seqOut);
		assertEquals(seqIn, seqOut);
	}

}
