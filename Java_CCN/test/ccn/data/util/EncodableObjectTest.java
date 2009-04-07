package test.ccn.data.util;

import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InvalidObjectException;
import java.sql.Timestamp;

import javax.xml.stream.XMLStreamException;

import org.bouncycastle.util.Arrays;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.content.CollectionData;
import com.parc.ccn.data.content.LinkReference;
import com.parc.ccn.data.security.LinkAuthenticator;
import com.parc.ccn.data.security.PublisherID;
import com.parc.ccn.data.security.SignedInfo;
import com.parc.ccn.data.security.PublisherID.PublisherType;
import com.parc.ccn.data.util.NullOutputStream;

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
	static LinkReference [] lrs = null;
	
	static CollectionData small1;
	static CollectionData small2;
	static CollectionData empty;
	static CollectionData big;

	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		ns = new ContentName[NUM_LINKS];
		for (int i=0; i < NUM_LINKS; ++i) {
			ns[i] = ContentName.fromURI(new String[]{baseName, subName, document1, 
										prefix+Integer.toString(i)});
		}
		Arrays.fill(publisherid1, (byte)6);
		Arrays.fill(publisherid2, (byte)3);

		pubID1 = new PublisherID(publisherid1, PublisherType.KEY);
		pubID2 = new PublisherID(publisherid2, PublisherType.ISSUER_KEY);

		las[0] = new LinkAuthenticator(pubID1);
		las[1] = null;
		las[2] = new LinkAuthenticator(pubID2, null,
				SignedInfo.ContentType.LEAF, contenthash1);
		las[3] = new LinkAuthenticator(pubID1, new Timestamp(System.currentTimeMillis()),
				null, contenthash1);
		
		for (int j=4; j < NUM_LINKS; ++j) {
			las[j] = new LinkAuthenticator(pubID2, new Timestamp(System.currentTimeMillis()),null, null);
 		}

		lrs = new LinkReference[NUM_LINKS];
		for (int i=0; i < lrs.length; ++i) {
			lrs[i] = new LinkReference(ns[i],las[i]);
		}
		
		empty = new CollectionData();
		small1 = new CollectionData();
		small2 = new CollectionData();
		for (int i=0; i < 5; ++i) {
			small1.add(lrs[i]);
			small2.add(lrs[i+5]);
		}
		big = new CollectionData();
		for (int i=0; i < NUM_LINKS; ++i) {
			big.add(lrs[i]);
		}
	}

	@Test
	public void testSave() {
		boolean caught = false;
		EncodableCollectionData emptycoll = new EncodableCollectionData();
		try {
			NullOutputStream nos = new NullOutputStream();
			emptycoll.save(nos);
		} catch (InvalidObjectException iox) {
			// this is what we expect to happen
			caught = true;
		} catch (IOException ie) {
			Assert.fail("Unexpectd IOException!");
		} catch (XMLStreamException e) {
			Assert.fail("Unexpectd IOException!");
		}
		Assert.assertTrue("Failed to produce expected exception.", caught);
		
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
			System.out.println("Saved empty CollectionData, length: " + baos0.toByteArray().length);
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
		} catch (XMLStreamException e) {
			fail("XMLStreamException! " + e.getMessage());
		}
	}
	
	@Test
	public void testUpdate() {
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
		} catch (IOException e) {
			fail("IOException! " + e.getMessage());
		} catch (XMLStreamException e) {
			fail("XMLStreamException! " + e.getMessage());
		} catch (ClassNotFoundException e) {
			fail("XMLStreamException! " + e.getMessage());
		}
	}
}
