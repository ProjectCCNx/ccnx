/**
 * 
 */
package test.ccn.data.content;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import javax.xml.stream.XMLStreamException;


import org.junit.Test;

import test.ccn.data.XMLEncodableTester;

import com.parc.ccn.data.content.*;

/**
 * @author briggs
 *
 */
public class HeaderTest {

	@Test
	public void testHeaderConstructor() {
		byte [] digest = new byte[]{1,2,3,4,5,6,7,8,9,0,9,8,7,6,5,4,3,2,1};
		Header seq = new Header(1, 1, 8192, 2, digest, digest);
		assertNotNull(seq);
		assertEquals(1, seq.start());
		assertEquals(1, seq.count());
		assertEquals(seq.blockSize(), 8192);
		assertEquals(seq.length(), 2);
	}

	@Test
	public void testHeaderConstructor2() {
		int length = 77295;
		byte [] digest = new byte[]{1,2,3,4,5,6,7,8,9,0,9,8,7,6,5,4,3,2,1};
		Header seq = new Header(length, digest, digest);
		assertNotNull(seq);
		assertEquals(Header.DEFAULT_START, seq.start());
		assertEquals(length, seq.length());
		assertEquals(Header.DEFAULT_BLOCKSIZE, seq.blockSize());
		assertEquals((length + Header.DEFAULT_BLOCKSIZE - 1) / Header.DEFAULT_BLOCKSIZE, seq.count());
	}
	@Test
	public void testHeaderConstructor3() {
		int length = Header.DEFAULT_BLOCKSIZE;
		byte [] digest = new byte[]{1,2,3,4,5,6,7,8,9,0,9,8,7,6,5,4,3,2,1};
		Header seq = new Header(length, digest, digest);
		assertNotNull(seq);
		assertEquals(Header.DEFAULT_START, seq.start());
		assertEquals(length, seq.length());
		assertEquals(Header.DEFAULT_BLOCKSIZE, seq.blockSize());
		assertEquals(1, seq.count());
	}
	@Test
	public void testEncodeOutputStream() {
		byte [] digest = new byte[]{1,2,3,4,5,6,7,8,9,0,9,8,7,6,5,4,3,2,1};
		Header seq = new Header(1, 1, 8192, 2, digest, digest);

		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		System.out.println("Encoding header...");
		try {
			seq.encode(baos);
		} catch (XMLStreamException e) {
			System.out.println("Exception " + e.getClass().getName() + ", message: " + e.getMessage());
			e.printStackTrace();
		}
		System.out.println("Encoded header: " );
		System.out.println(baos.toString());
		
		Header dt = new Header();
		Header db = new Header();
		
		XMLEncodableTester.encodeDecodeTest("Header", seq, dt, db);
	}

	@Test
	public void testDecodeInputStream() {
		byte [] digest = new byte[]{1,2,3,4,5,6,7,8,9,0,9,8,7,6,5,4,3,2,1};
		Header seqIn = new Header(83545, digest, digest);
		Header seqOut = new Header();


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
