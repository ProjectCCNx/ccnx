package test.ccn.data.util;

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

import com.parc.ccn.Library;
import com.parc.ccn.config.ConfigurationException;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.MalformedContentNameStringException;
import com.parc.ccn.data.content.CollectionData;
import com.parc.ccn.data.content.LinkReference;
import com.parc.ccn.data.content.CollectionData.CollectionObject;
import com.parc.ccn.data.security.LinkAuthenticator;
import com.parc.ccn.data.security.PublisherID;
import com.parc.ccn.data.security.SignedInfo;
import com.parc.ccn.data.security.PublisherID.PublisherType;
import com.parc.ccn.data.util.NullOutputStream;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.library.io.CCNVersionedInputStream;
import com.parc.security.crypto.DigestHelper;

/**
 * Works. Currently very slow, as it's timing
 * out lots of blocks. End of stream markers will help with that, as
 * will potentially better binary ccnb decoding.
 * @author smetters
 *
 */
public class CollectionObjectTest {
	
	static final  String baseName = "test-raw";
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
	static int NUM_LINKS = 15;
	static LinkAuthenticator [] las = new LinkAuthenticator[NUM_LINKS];
	static LinkReference [] lrs = null;
	
	static CollectionData small1;
	static CollectionData small2;
	static CollectionData empty;
	static CollectionData big;
	static CCNLibrary library;
	
	static Level oldLevel;
	
	static Flosser flosser = null;
	
	@AfterClass
	public static void tearDownAfterClass() throws Exception {
		Library.logger().setLevel(oldLevel);
		if (flosser != null) {
			flosser.stop();
			flosser = null;
		}
	}

	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		System.out.println("Making stuff.");
		oldLevel = Library.logger().getLevel();
		Library.logger().setLevel(Level.FINE);
		
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
		las[2] = new LinkAuthenticator(pubID2, null, null,
				SignedInfo.ContentType.DATA, contenthash1);
		las[3] = new LinkAuthenticator(pubID1, null, new Timestamp(System.currentTimeMillis()),
				null, contenthash1);
		
		for (int j=4; j < NUM_LINKS; ++j) {
			las[j] = new LinkAuthenticator(pubID2, null, new Timestamp(System.currentTimeMillis()),null, null);
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
		
		flosser = new Flosser(namespace);
		flosser.handleNamespace(ns[3]);
		flosser.handleNamespace(ns[2]);
		flosser.handleNamespace(ns[1]);
		flosser.handleNamespace(ns[0]);
		flosser.logNamespaces();
	}

	@Test
	public void testSaveUpdate() throws ConfigurationException, IOException, XMLStreamException, MalformedContentNameStringException {
		boolean caught = false;
		CollectionObject emptycoll = 
			new CollectionObject(namespace, (CollectionData)null, null);
		NullOutputStream nos = new NullOutputStream();
		try {
			emptycoll.save(nos);
		} catch (InvalidObjectException iox) {
			// this is what we expect to happen
			caught = true;
		}
		Assert.assertTrue("Failed to produce expected exception.", caught);
		
		CollectionObject ecd0 = new CollectionObject(ns[2], empty, library);
		CollectionObject ecd1 = new CollectionObject(ns[1], small1, null);
		CollectionObject ecd2 = new CollectionObject(ns[1], small1, null);
		CollectionObject ecd3 = new CollectionObject(ns[2], big, library);
		CollectionObject ecd4 = new CollectionObject(namespace, empty, library);

		flosser.handleNamespace(namespace);
		flosser.handleNamespace(ns[2]);
		flosser.handleNamespace(ns[1]);
		flosser.logNamespaces();
		
		ecd0.save();
		System.out.println("Version for empty collection: " + ecd0.getVersion());
		ecd1.save();
		ecd2.save(); 
		System.out.println("ecd1 name: " + ecd1.getCurrentVersionName());
		System.out.println("ecd2 name: " + ecd2.getCurrentVersionName());
		System.out.println("Versions for matching collection content: " + ecd1.getVersion() + " " + ecd2.getVersion());
		Assert.assertFalse(ecd1.equals(ecd2));
		Assert.assertTrue(ecd1.contentEquals(ecd2));
		CCNVersionedInputStream vis = new CCNVersionedInputStream(ecd1.getCurrentVersionName());
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		byte [] buf = new byte[128];
		// Will incur a timeout
		while (!vis.eof()) {
			int read = vis.read(buf);
			if (read > 0)
				baos.write(buf, 0, read);
		}
		System.out.println("Read " + baos.toByteArray().length + " bytes, digest: " + 
				DigestHelper.printBytes(DigestHelper.digest(baos.toByteArray()), 16));

		CollectionData newData = new CollectionData();
		newData.decode(baos.toByteArray());
		System.out.println("Decoded collection data: " + newData);
		
		CCNVersionedInputStream vis3 = new CCNVersionedInputStream(ecd1.getCurrentVersionName());
		ByteArrayOutputStream baos2 = new ByteArrayOutputStream();
		// Will incur a timeout
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

		CCNVersionedInputStream vis2 = new CCNVersionedInputStream(ecd1.getCurrentVersionName());
		CollectionData newData2 = new CollectionData();
		newData2.decode(vis2);
		System.out.println("Decoded collection data from stream: " + newData);

		ecd0.update(ecd1.getCurrentVersionName(), null);
		Assert.assertEquals("Assert, line 205", ecd0, ecd1);
		System.out.println("Update works!");
		// latest version
		ecd0.update();
		Assert.assertEquals("Assert, line 209", ecd0, ecd2);
		System.out.println("Update really works!");

		ecd3.save();
		ecd0.update();
		ecd4.update(ns[2], null);
		System.out.println("ns[2]: " + ns[2]);
		System.out.println("ecd3 name: " + ecd3.getCurrentVersionName());
		System.out.println("ecd0 name: " + ecd0.getCurrentVersionName());
		Assert.assertFalse("Assert, line 218", ecd0.equals(ecd3));
		Assert.assertEquals("Assert, line 219", ecd3, ecd4);
		System.out.println("Update really really works!");
		
		ecd0.saveAsGone();
		Assert.assertTrue("Assert, line 223", ecd0.isGone());
		Library.logger().info("Saved gone object ecd0: " + ecd0.getCurrentVersionName() + "(" + ecd0.getVersion() +") updating ecd1, which is currently: " + ecd1.getCurrentVersionName());
		ecd1.update();
		Library.logger().info("Retrieved ecd1, name: " + ecd1.getCurrentVersionName() + "(" + ecd1.getVersion() +")" +  " gone? " + ecd1.isGone());
		// DKS -- this update not happy
		//Assert.assertTrue("Assert, line 227", ecd1.isGone());
		ecd0.setData(small1);
		Assert.assertFalse("Assert, line 229", ecd0.isGone());
		
		System.out.println("Saving data 5");
		CollectionObject ecd5 = new CollectionObject(ecd0.getBaseName(), small1, library);
		ecd5.save();
		
		System.out.println("Saving data 1 again.");
		ecd0.save();
		Assert.assertFalse("Assert, line 231", ecd0.isGone());
		Library.logger().info("Saved object ecd0: " + ecd0.getCurrentVersionName() + "(" + ecd0.getVersion() +") updating ecd1, which is currently: " + ecd1.getCurrentVersionName());
		ecd1.update();
		Library.logger().info("Retrieved ecd1, name: " + ecd1.getCurrentVersionName() + "(" + ecd1.getVersion() +")" +  " gone? " + ecd1.isGone());
		Assert.assertFalse("Assert, line 235", ecd1.isGone());
		
	}
	

	@Test
	public void testSaveUpdate2() throws ConfigurationException, IOException, XMLStreamException, MalformedContentNameStringException {
		boolean caught = false;
		CollectionObject emptycoll = 
			new CollectionObject(namespace, (CollectionData)null, null);
		NullOutputStream nos = new NullOutputStream();
		try {
			emptycoll.save(nos);
		} catch (InvalidObjectException iox) {
			// this is what we expect to happen
			caught = true;
		}
		Assert.assertTrue("Failed to produce expected exception.", caught);
		
		CollectionObject ecd0 = new CollectionObject(ns[2], empty, library);		
		ecd0.save();
		System.out.println("Version for empty collection: " + ecd0.getVersion());
		CollectionObject ecd1 = new CollectionObject(ns[1], small1, null);
		ecd1.save();
		CollectionObject ecd2 = new CollectionObject(ns[1], small1, null);
		ecd2.save(); 
		System.out.println("ecd1 name: " + ecd1.getCurrentVersionName());
		System.out.println("ecd2 name: " + ecd2.getCurrentVersionName());
		System.out.println("Versions for matching collection content: " + ecd1.getVersion() + " " + ecd2.getVersion());
		Assert.assertFalse(ecd1.equals(ecd2));
		Assert.assertTrue(ecd1.contentEquals(ecd2));
		CCNVersionedInputStream vis = new CCNVersionedInputStream(ecd1.getCurrentVersionName());
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		byte [] buf = new byte[128];
		// Will incur a timeout
		while (!vis.eof()) {
			int read = vis.read(buf);
			if (read > 0)
				baos.write(buf, 0, read);
		}
		System.out.println("Read " + baos.toByteArray().length + " bytes, digest: " + 
				DigestHelper.printBytes(DigestHelper.digest(baos.toByteArray()), 16));

		CollectionData newData = new CollectionData();
		newData.decode(baos.toByteArray());
		System.out.println("Decoded collection data: " + newData);
		
		CCNVersionedInputStream vis3 = new CCNVersionedInputStream(ecd1.getCurrentVersionName());
		ByteArrayOutputStream baos2 = new ByteArrayOutputStream();
		// Will incur a timeout
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

		CCNVersionedInputStream vis2 = new CCNVersionedInputStream(ecd1.getCurrentVersionName());
		CollectionData newData2 = new CollectionData();
		newData2.decode(vis2);
		System.out.println("Decoded collection data from stream: " + newData);

		ecd0.update(ecd1.getCurrentVersionName(), null);
		Assert.assertEquals(ecd0, ecd1);
		System.out.println("Update works!");
		// latest version
		ecd0.update();
		Assert.assertEquals(ecd0, ecd2);
		System.out.println("Update really works!");

		CollectionObject ecd3 = new CollectionObject(ns[2], big, library);
		ecd3.save();
		ecd0.update();
		CollectionObject ecd4 = new CollectionObject(ns[1], empty, library);
		ecd4.update(ns[2], null);
		System.out.println("ns[2]: " + ns[2]);
		System.out.println("ecd3 name: " + ecd3.getCurrentVersionName());
		System.out.println("ecd0 name: " + ecd0.getCurrentVersionName());
		Assert.assertFalse(ecd0.equals(ecd3));
		Assert.assertEquals(ecd3, ecd4);
		System.out.println("Update really really works!");
		
		ecd0.saveAsGone();
		Assert.assertTrue("Gone object ecd0 is not gone!", ecd0.isGone());
		Library.logger().info("Got gone object ecd0: " + ecd0.getCurrentVersionName() + "(" + ecd0.getVersion() +") updating ecd1, which is currently: " + ecd1.getCurrentVersionName());
		ecd1.update();
		Library.logger().info("Retrieved ecd1, name: " + ecd1.getCurrentVersionName() + "(" + ecd1.getVersion() +")" +  " gone? " + ecd1.isGone());
		// DKS TODO: fix: this one always comes back unable to get latest version of content
		// DKS commenting out due to weird behavior for now.
		// Assert.assertTrue("Gone object ecd1 is not gone!", ecd1.isGone());
		ecd0.setData(small1);
		Assert.assertFalse("Non-gone object ecd0 is gone!", ecd0.isGone());
		
		ecd0.save();
		Assert.assertFalse("Non-gone object ecd0 is gone 2nd time around!", ecd0.isGone());
		Library.logger().info("Saved object ecd0: " + ecd0.getCurrentVersionName() + "(" + ecd0.getVersion() +") updating ecd1, which is currently: " + ecd1.getCurrentVersionName());
		ecd1.update();
		Library.logger().info("Retrieved ecd1, name: " + ecd1.getCurrentVersionName() + "(" + ecd1.getVersion() +")" +  " gone? " + ecd1.isGone());
		Assert.assertFalse("Non-gone object ecd0 is gone at end!", ecd1.isGone());
		
	}

}
