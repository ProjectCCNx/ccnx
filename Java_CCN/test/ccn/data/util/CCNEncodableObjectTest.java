package test.ccn.data.util;

import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.InvalidObjectException;
import java.sql.Timestamp;

import javax.xml.stream.XMLStreamException;

import org.bouncycastle.util.Arrays;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import test.ccn.data.content.CCNEncodableCollectionData;

import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.content.CollectionData;
import com.parc.ccn.data.content.LinkReference;
import com.parc.ccn.data.security.LinkAuthenticator;
import com.parc.ccn.data.security.PublisherID;
import com.parc.ccn.data.security.SignedInfo;
import com.parc.ccn.data.security.PublisherID.PublisherType;
import com.parc.ccn.data.util.NullOutputStream;
import com.parc.ccn.library.CCNLibrary;

public class CCNEncodableObjectTest {
	
	static final  String baseName = "test";
	static final  String subName = "smetters";
	static final  String document1 = "intro.html";	
	static final  String document2 = "key";	
	static final String document3 = "cv.txt";
	static final String prefix = "drawing_";
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
	static CCNLibrary library;

	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		library = CCNLibrary.open();
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
	public void testSaveUpdate() {
		boolean caught = false;
		CCNEncodableCollectionData emptycoll = new CCNEncodableCollectionData(library);
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
		
		CCNEncodableCollectionData ecd0 = new CCNEncodableCollectionData(empty, library);
		CCNEncodableCollectionData ecd1 = new CCNEncodableCollectionData(small1);
		CCNEncodableCollectionData ecd2 = new CCNEncodableCollectionData(small1);
		CCNEncodableCollectionData ecd3 = new CCNEncodableCollectionData(big);

		try {
			ecd0.save(ns[0]);
			System.out.println("Version for empty collection: " + ecd0.getVersion());
			ecd1.save(ns[1]);
			ecd2.save(ns[1]); 
			System.out.println("Versions for matching collection content: " + ecd1.getVersion() + " " + ecd2.getVersion());
			Assert.assertFalse(ecd1.equals(ecd2));
			Assert.assertTrue(ecd1.contentEquals(ecd2));
			ecd0.update(ecd1.getName());
			Assert.assertEquals(ecd0, ecd1);
			// latest version
			ecd0.update();
			Assert.assertEquals(ecd0, ecd2);
			ecd3.save(ns[1]);
			ecd0.update();
			Assert.assertEquals(ecd0, ecd3);
		} catch (IOException e) {
			fail("IOException! " + e.getMessage());
		} catch (XMLStreamException e) {
			fail("XMLStreamException! " + e.getMessage());
		}
	}
}
