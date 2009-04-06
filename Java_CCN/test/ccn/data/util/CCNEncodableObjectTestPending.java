package test.ccn.data.util;

import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InvalidObjectException;
import java.sql.Timestamp;
import java.util.logging.Level;

import javax.xml.stream.XMLStreamException;

import org.bouncycastle.util.Arrays;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import test.ccn.data.content.CCNEncodableCollectionData;
import test.ccn.util.Flosser;

import com.parc.ccn.Library;
import com.parc.ccn.config.ConfigurationException;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.content.CollectionData;
import com.parc.ccn.data.content.LinkReference;
import com.parc.ccn.data.security.LinkAuthenticator;
import com.parc.ccn.data.security.PublisherID;
import com.parc.ccn.data.security.SignedInfo;
import com.parc.ccn.data.security.PublisherID.PublisherType;
import com.parc.ccn.data.util.NullOutputStream;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.library.io.CCNVersionedInputStream;
import com.parc.security.crypto.DigestHelper;

public class CCNEncodableObjectTestPending {
	
	static final  String baseName = "test";
	static final  String subName = "smetters";
	static final  String document1 = "report";	
	static final  String document2 = "key";	
	static final String document3 = "cv.txt";
	static final String prefix = "drawing_";
	static ContentName namespace;
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
	
	static Level oldLevel;
	
	@AfterClass
	public static void tearDownAfterClass() throws Exception {
		Library.logger().setLevel(oldLevel);
	}

	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		oldLevel = Library.logger().getLevel();
	//	Library.logger().setLevel(Level.FINEST);
		
		library = CCNLibrary.open();
		namespace = ContentName.fromURI(new String[]{baseName, subName, document1});
		ns = new ContentName[NUM_LINKS];
		for (int i=0; i < NUM_LINKS; ++i) {
			ns[i] = ContentName.fromNative(namespace, prefix+Integer.toString(i));
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
		try {
			CCNEncodableCollectionData emptycoll = new CCNEncodableCollectionData();
			NullOutputStream nos = new NullOutputStream();
			emptycoll.save(nos);
		} catch (InvalidObjectException iox) {
			// this is what we expect to happen
			caught = true;
		} catch (IOException ie) {
			Assert.fail("Unexpected IOException!");
		} catch (XMLStreamException e) {
			Assert.fail("Unexpected XMLStreamException!");
		} catch (ConfigurationException e) {
			Assert.fail("Unexpected ConfigurationException!");
		}
		Assert.assertTrue("Failed to produce expected exception.", caught);
		
		Flosser flosser = null;
		try {
			CCNEncodableCollectionData ecd0 = new CCNEncodableCollectionData(namespace, empty, library);
			CCNEncodableCollectionData ecd1 = new CCNEncodableCollectionData(namespace, small1);
			CCNEncodableCollectionData ecd2 = new CCNEncodableCollectionData(namespace, small1);
			CCNEncodableCollectionData ecd3 = new CCNEncodableCollectionData(namespace, big, library);
			CCNEncodableCollectionData ecd4 = new CCNEncodableCollectionData(namespace, empty, library);

			flosser = new Flosser(namespace);
			flosser.logNamespaces();
			
			ecd0.save(ns[2]);
			System.out.println("Version for empty collection: " + ecd0.getVersion());
			ecd1.save(ns[1]);
			ecd2.save(ns[1]); 
			System.out.println("ecd1 name: " + ecd1.getName());
			System.out.println("ecd2 name: " + ecd2.getName());
			System.out.println("Versions for matching collection content: " + ecd1.getVersion() + " " + ecd2.getVersion());
			Assert.assertFalse(ecd1.equals(ecd2));
			Assert.assertTrue(ecd1.contentEquals(ecd2));
			CCNVersionedInputStream vis = new CCNVersionedInputStream(ecd1.getName());
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			byte [] buf = new byte[128];
			while (!vis.eof()) {
				int read = vis.read(buf);
				baos.write(buf, 0, read);
			}
			System.out.println("Read " + baos.toByteArray().length + " bytes, digest: " + 
					DigestHelper.printBytes(DigestHelper.digest(baos.toByteArray()), 16));

			CollectionData newData = new CollectionData();
			newData.decode(baos.toByteArray());
			System.out.println("Decoded collection data: " + newData);
			
			CCNVersionedInputStream vis3 = new CCNVersionedInputStream(ecd1.getName());
			ByteArrayOutputStream baos2 = new ByteArrayOutputStream();
			while (!vis3.eof()) {
				int val = vis3.read();
				if (val < 0)
					break;
				baos2.write((byte)val);
			}
			System.out.println("Read " + baos2.toByteArray().length + " bytes, digest: " + 
					DigestHelper.printBytes(DigestHelper.digest(baos2.toByteArray()), 16));

			CollectionData newData3 = new CollectionData();
			newData3.decode(baos2.toByteArray());
			System.out.println("Decoded collection data: " + newData3);

			CCNVersionedInputStream vis2 = new CCNVersionedInputStream(ecd1.getName());
			CollectionData newData2 = new CollectionData();
			newData2.decode(vis2);
			System.out.println("Decoded collection data from stream: " + newData);

			ecd0.update(ecd1.getName());
			Assert.assertEquals(ecd0, ecd1);
			System.out.println("Update works!");
			// latest version
			ecd0.update();
			Assert.assertEquals(ecd0, ecd2);
			System.out.println("Update really works!");

			ecd3.save(ns[2]);
			ecd0.update();
			ecd4.update(ns[2]);
			System.out.println("ns[2]: " + ns[2]);
			System.out.println("ecd3 name: " + ecd3.getName());
			System.out.println("ecd0 name: " + ecd0.getName());
			Assert.assertFalse(ecd0.equals(ecd3));
			Assert.assertEquals(ecd3, ecd4);
			System.out.println("Update really really works!");

		} catch (IOException e) {
			fail("IOException! " + e.getMessage());
		} catch (XMLStreamException e) {
			e.printStackTrace();

			fail("XMLStreamException! " + e.getMessage());
		} catch (ConfigurationException e) {
			fail("ConfigurationException! " + e.getMessage());
		} catch (Exception e) {
			e.printStackTrace();
			fail("Exception: " + e.getClass().getName() + ": " + e.getMessage());
		} finally {
			try {
				flosser.stop();
//				Thread.sleep(10000000);
				
			} catch (Exception e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
				Assert.fail("Exception " + e.getClass().getName() +": " + e.getMessage());
			}

		}
	}
}
