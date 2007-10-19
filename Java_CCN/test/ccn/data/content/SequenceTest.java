/**
 * 
 */
package test.ccn.data.content;


import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import javax.xml.stream.XMLStreamException;


import org.junit.Test;

import com.parc.ccn.data.content.*;



/**
 * @author briggs
 *
 */
public class SequenceTest {
	
@Test
public void testSequenceConstructor() {
	Sequence seq = new Sequence(1, 1, 8192, 2);
	assertNotNull(seq);
	assertEquals(1, seq.start());
	assertEquals(1, seq.count());
	assertEquals(seq.blockSize(), 8192);
	assertEquals(seq.length(), 2);
}

@Test
public void testSequenceConstructor2() {
		int length = 77295;
		Sequence seq = new Sequence(length);
		assertNotNull(seq);
		assertEquals(Sequence.DEFAULT_START, seq.start());
		assertEquals(length, seq.length());
		assertEquals(Sequence.DEFAULT_BLOCKSIZE, seq.blockSize());
		assertEquals((length + Sequence.DEFAULT_BLOCKSIZE - 1) / Sequence.DEFAULT_BLOCKSIZE, seq.count());
}
@Test
public void testSequenceConstructor3() {
		int length = Sequence.DEFAULT_BLOCKSIZE;
		Sequence seq = new Sequence(length);
		assertNotNull(seq);
		assertEquals(Sequence.DEFAULT_START, seq.start());
		assertEquals(length, seq.length());
		assertEquals(Sequence.DEFAULT_BLOCKSIZE, seq.blockSize());
		assertEquals(1, seq.count());
}
@Test
public void testEncodeOutputStream() {
	Sequence seq = new Sequence(1, 1, 8192, 2);

	ByteArrayOutputStream baos = new ByteArrayOutputStream();
	System.out.println("Encoding sequence...");
	try {
		seq.encode(baos);
	} catch (XMLStreamException e) {
		System.out.println("Exception " + e.getClass().getName() + ", message: " + e.getMessage());
		e.printStackTrace();
	}
	System.out.println("Encoded sequence: " );
	System.out.println(baos.toString());
}

@Test
public void testDecodeInputStream() {
	Sequence seqIn = new Sequence(83545);
	Sequence seqOut = new Sequence();

	
	ByteArrayOutputStream baos = new ByteArrayOutputStream();
	try {
		seqIn.encode(baos);
	} catch (XMLStreamException e) {
		System.out.println("Exception " + e.getClass().getName() + ", message: " + e.getMessage());
		e.printStackTrace();
	}
	System.out.println("Encoded sequence: " + baos.toString());

	System.out.println("Decoding sequence...");
	ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());

	try {
		seqOut.decode(bais);
	} catch (XMLStreamException e) {
		System.out.println("Exception " + e.getClass().getName() + ", message: " + e.getMessage());
		e.printStackTrace();
	}
	System.out.println("Decoded sequence: " + seqOut);
	assertEquals(seqIn, seqOut);
}

}
