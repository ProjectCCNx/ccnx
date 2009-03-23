/**
 * 
 */
package test.ccn.data.content;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;

import javax.xml.stream.XMLStreamException;

import org.junit.BeforeClass;
import org.junit.Test;

import test.ccn.data.XMLEncodableTester;

import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.content.Header;
import com.parc.ccn.data.security.KeyLocator;
import com.parc.ccn.data.security.PublisherKeyID;
import com.parc.ccn.library.profiles.SegmentationProfile;

/**
 * @author briggs, rasmusse
 *
 */
public class HeaderTest {
	
	static PublisherKeyID pubKey = null;
	static public byte [] publisherid1 = new byte[32];
	static public PrivateKey signingKey;
	static public KeyLocator locator;
	
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
		kpg.initialize(512); // go for fast
		KeyPair pair = kpg.generateKeyPair();
		signingKey = pair.getPrivate();
		pubKey = new PublisherKeyID(publisherid1);
		locator = new KeyLocator(ContentName.fromNative("/headerTest1"));
	}

	@Test
	public void testHeaderConstructor() throws Exception {
		byte [] digest = new byte[]{1,2,3,4,5,6,7,8,9,0,9,8,7,6,5,4,3,2,1};
		Header seq = new Header(ContentName.fromNative("/headerTest1"), 1, 1, 8192, 2, digest, digest,
				pubKey, locator, signingKey);
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
		Header seq = new Header(ContentName.fromNative("/headerTest1"), length, digest, digest, SegmentationProfile.DEFAULT_BLOCKSIZE,
				pubKey, locator, signingKey);
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
		Header seq = new Header(ContentName.fromNative("/headerTest1"), length, digest, digest, SegmentationProfile.DEFAULT_BLOCKSIZE,
				pubKey, locator, signingKey);
		assertNotNull(seq);
		assertEquals(SegmentationProfile.baseSegment(), seq.start());
		assertEquals(length, seq.length());
		assertEquals(SegmentationProfile.DEFAULT_BLOCKSIZE, seq.blockSize());
		assertEquals(1, seq.count());
	}
	@Test
	public void testEncodeOutputStream() throws Exception {
		byte [] digest = new byte[]{1,2,3,4,5,6,7,8,9,0,9,8,7,6,5,4,3,2,1};
		Header seq = new Header(ContentName.fromNative("/headerTest1"), 1, 1, 8192, 2, digest, digest,
				pubKey, locator, signingKey);

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
	public void testDecodeInputStream() throws Exception {
		byte [] digest = new byte[]{1,2,3,4,5,6,7,8,9,0,9,8,7,6,5,4,3,2,1};
		Header seqIn = new Header(ContentName.fromNative("/headerTest1"), 83545, digest, digest, SegmentationProfile.DEFAULT_BLOCKSIZE,
				pubKey, locator, signingKey);
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
