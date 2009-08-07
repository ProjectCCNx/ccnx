package test.ccn.data.content;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InvalidObjectException;
import java.sql.Timestamp;
import java.util.Random;
import java.util.logging.Level;

import javax.xml.stream.XMLStreamException;

import org.bouncycastle.util.Arrays;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import test.ccn.data.util.CCNStringObject;
import test.ccn.data.util.Flosser;

import com.parc.ccn.Library;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.content.CollectionData;
import com.parc.ccn.data.content.LinkReference;
import com.parc.ccn.data.content.CollectionData.CollectionObject;
import com.parc.ccn.data.security.LinkAuthenticator;
import com.parc.ccn.data.security.PublisherID;
import com.parc.ccn.data.security.SignedInfo;
import com.parc.ccn.data.security.PublisherID.PublisherType;
import com.parc.ccn.data.util.CCNNetworkObject;
import com.parc.ccn.data.util.DataUtils;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.library.io.CCNVersionedInputStream;
import com.parc.ccn.library.profiles.VersioningProfile;
import com.parc.security.crypto.DigestHelper;

/**
 * Works. Currently very slow, as it's timing
 * out lots of blocks. End of stream markers will help with that, as
 * will potentially better binary ccnb decoding.
 * @author smetters
 *
 */
public class CollectionObjectTest {
	
	static ContentName namespace;
	static ContentName stringObjName;
	static ContentName collectionObjName;
	static String prefix = "CollectionObject-";
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
	static String [] numbers = new String[]{"ONE", "TWO", "THREE", "FOUR", "FIVE", "SIX", "SEVEN", "EIGHT", "NINE", "TEN"};
	
	static Level oldLevel;
	
	static Flosser flosser = null;
	
	static void setupNamespace(ContentName name) throws IOException {
		flosser.handleNamespace(name);
	}
	
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
		Library.logger().setLevel(Level.INFO);
		
		library = CCNLibrary.open();
		namespace = ContentName.fromNative("/parc/test/data/CollectionObjectTest-" + + new Random().nextInt(10000));
		stringObjName = ContentName.fromNative(namespace, "StringObject");
		collectionObjName = ContentName.fromNative(namespace, "CollectionObject");
		
		ns = new ContentName[NUM_LINKS];
		for (int i=0; i < NUM_LINKS; ++i) {
			ns[i] = ContentName.fromNative(namespace, "Links", prefix+Integer.toString(i));
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
	}

	@Test
	public void testVersioning() throws Exception {
		// Testing problem of disappearing versions, inability to get latest. Use simpler
		// object than a collection.
		CCNLibrary lput = CCNLibrary.open();
		CCNLibrary lget = CCNLibrary.open();
		
		ContentName testName = ContentName.fromNative(stringObjName, "testVersioning");
		setupNamespace(testName);
		
		CCNStringObject so = new CCNStringObject(testName, "First value", lput);
		CCNStringObject ro = null;
		CCNStringObject ro2 = null;
		CCNStringObject ro3, ro4; // make each time, to get a new library.
		Timestamp soTime, srTime, sr2Time, sr3Time, sr4Time, so2Time;
		for (int i=0; i < numbers.length; ++i) {
			soTime = saveAndLog(numbers[i], so, null, numbers[i]);
			if (null == ro) {
				ro = new CCNStringObject(testName, lget);
				srTime = waitForDataAndLog(numbers[i], ro);
			} else {
				srTime = updateAndLog(numbers[i], ro, null);				
			}
			if (null == ro2) {
				ro2 = new CCNStringObject(testName, null);
				sr2Time = waitForDataAndLog(numbers[i], ro2);
			} else {
				sr2Time = updateAndLog(numbers[i], ro2, null);				
			}
			ro3 = new CCNStringObject(ro.getCurrentVersionName(), null); // read specific version
			sr3Time = waitForDataAndLog("UpdateToROVersion", ro3);
			// Save a new version and pull old
			so2Time = saveAndLog(numbers[i] + "-Update", so, null, numbers[i] + "-Update");
			ro4 = new CCNStringObject(ro.getCurrentVersionName(), null); // read specific version
			sr4Time = waitForDataAndLog("UpdateAnotherToROVersion", ro4);
			System.out.println("Update " + i + ": Times: " + soTime + " " + srTime + " " + sr2Time + " " + sr3Time + " different: " + so2Time);
			Assert.assertEquals("SaveTime doesn't match first read", soTime, srTime);
			Assert.assertEquals("SaveTime doesn't match second read", soTime, sr2Time);
			Assert.assertEquals("SaveTime doesn't match specific version read", soTime, sr3Time);
			Assert.assertFalse("UpdateTime isn't newer than read time", soTime.equals(so2Time));
			Assert.assertEquals("SaveTime doesn't match specific version read", soTime, sr4Time);
		}
	}

	@Test
	public void testSaveToVersion() throws Exception {
		// Testing problem of disappearing versions, inability to get latest. Use simpler
		// object than a collection.
		CCNLibrary lput = CCNLibrary.open();
		CCNLibrary lget = CCNLibrary.open();
		ContentName testName = ContentName.fromNative(stringObjName, "testSaveToVersion");
		setupNamespace(testName);
		
		Timestamp desiredVersion = DataUtils.roundTimestamp(SignedInfo.now());
		
		CCNStringObject so = new CCNStringObject(testName, "First value", lput);
		saveAndLog("SpecifiedVersion", so, desiredVersion, "Time: " + desiredVersion);
		Assert.assertEquals("Didn't write correct version", desiredVersion, so.getCurrentVersion());
		
		CCNStringObject ro = new CCNStringObject(testName, lget);
		ro.waitForData(); 
		Assert.assertEquals("Didn't read correct version", desiredVersion, ro.getCurrentVersion());
		ContentName versionName = ro.getCurrentVersionName();
		
		saveAndLog("UpdatedVersion", so, null, "ReplacementData");
		updateAndLog("UpdatedData", ro, null);
		Assert.assertTrue("New version " + so.getCurrentVersion() + " should be later than old version " + desiredVersion, (desiredVersion.before(so.getCurrentVersion())));
		Assert.assertEquals("Didn't read correct version", so.getCurrentVersion(), ro.getCurrentVersion());
		
		CCNStringObject ro2 = new CCNStringObject(versionName, null);
		ro2.waitForData();
		Assert.assertEquals("Didn't read correct version", desiredVersion, ro2.getCurrentVersion());
	}

	@Test
	public void testEmptySave() throws Exception {
		boolean caught = false;
		ContentName testName = ContentName.fromNative(stringObjName, "testEmptySave");
		setupNamespace(testName);
		CollectionObject emptycoll = 
			new CollectionObject(testName, (CollectionData)null, library);
		try {
			emptycoll.save();
		} catch (InvalidObjectException iox) {
			// this is what we expect to happen
			caught = true;
		}
		Assert.assertTrue("Failed to produce expected exception.", caught);		
	}
	
	@Test
	public void testStreamUpdate() throws Exception {
		
		ContentName testName = ContentName.fromNative(collectionObjName, "testStreamUpdate");
		setupNamespace(testName);
		CollectionObject testCollectionObject = new CollectionObject(testName, small1, CCNLibrary.open());
		
		testCollectionObject.save();
		System.out.println("testCollectionObject name: " + testCollectionObject.getCurrentVersionName());
				
		CCNVersionedInputStream vis = new CCNVersionedInputStream(testCollectionObject.getCurrentVersionName());
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

		CollectionData decodedData = new CollectionData();
		decodedData.decode(baos.toByteArray());
		System.out.println("Decoded collection data: " + decodedData);
		Assert.assertEquals("Decoding via stream fails to give expected result!", decodedData, small1);
		
		CCNVersionedInputStream vis2 = new CCNVersionedInputStream(testCollectionObject.getCurrentVersionName());
		ByteArrayOutputStream baos2 = new ByteArrayOutputStream();
		// Will incur a timeout
		while (!vis2.eof()) {
			int val = vis2.read();
			if (val < 0)
				break;
			baos2.write((byte)val);
		}
		System.out.println("Read " + baos2.toByteArray().length + " bytes, digest: " + 
				DigestHelper.printBytes(DigestHelper.digest(baos2.toByteArray()), 16));
		Assert.assertArrayEquals("Reading same object twice gets different results!", baos.toByteArray(), baos2.toByteArray());

		CollectionData decodedData2 = new CollectionData();
		decodedData2.decode(baos2.toByteArray());
		Assert.assertEquals("Decoding via stream byte read fails to give expected result!", decodedData2, small1);

		CCNVersionedInputStream vis3 = new CCNVersionedInputStream(testCollectionObject.getCurrentVersionName());
		CollectionData decodedData3 = new CollectionData();
		decodedData3.decode(vis3);
		Assert.assertEquals("Decoding via stream full read fails to give expected result!", decodedData3, small1);
	}
	
	@Test
	public void testVersionOrdering() throws Exception {
		ContentName testName = ContentName.fromNative(collectionObjName, "testVersionOrdering", "name1");
		setupNamespace(testName);
		ContentName testName2 = ContentName.fromNative(collectionObjName, "testVersionOrdering", "name2");
		setupNamespace(testName2);
		
		CollectionObject c0 = new CollectionObject(testName, empty, library);
		Timestamp t0 = saveAndLog("Empty", c0, null, empty);
		
		CollectionObject c1 = new CollectionObject(testName2, small1, CCNLibrary.open());
		Timestamp t1 = saveAndLog("Small", c1, null, small1);
		Assert.assertTrue("First version should come before second", t0.before(t1));
		
		CollectionObject c2 = new CollectionObject(testName2, small1, null);
		Timestamp t2 = saveAndLog("Small2ndWrite", c2, null, small1);
		Assert.assertTrue("Third version should come after second", t1.before(t2));
		Assert.assertTrue(c2.contentEquals(c1));
		Assert.assertFalse(c2.equals(c1));
		Assert.assertTrue(VersioningProfile.isLaterVersionOf(c2.getCurrentVersionName(), c1.getCurrentVersionName()));
	}
	
	@Test
	public void testUpdateOtherName() throws Exception {
		ContentName testName = ContentName.fromNative(collectionObjName, "testUpdateOtherName", "name1");
		setupNamespace(testName);
		ContentName testName2 = ContentName.fromNative(collectionObjName, "testUpdateOtherName", "name2");
		setupNamespace(testName2);

		CollectionObject c0 = new CollectionObject(testName, empty, library);
		Timestamp t0 = saveAndLog("Empty", c0, null, empty);
		
		CollectionObject c1 = new CollectionObject(testName2, small1, CCNLibrary.open());
		Timestamp t1 = saveAndLog("Small", c1, null, small1);
		Assert.assertTrue("First version should come before second", t0.before(t1));
		
		CollectionObject c2 = new CollectionObject(testName2, small1, null);
		Timestamp t2 = saveAndLog("Small2ndWrite", c2, null, small1);
		Assert.assertTrue("Third version should come after second", t1.before(t2));
		Assert.assertTrue(c2.contentEquals(c1));
		Assert.assertFalse(c2.equals(c1));
		
		Timestamp t3 = updateAndLog(c0.getCurrentVersionName().toString(), c0, testName2);
		Assert.assertTrue(VersioningProfile.isVersionOf(c0.getCurrentVersionName(), testName2));
		Assert.assertEquals(t3, t2);
		Assert.assertTrue(c0.contentEquals(c2));
		
		t3 = updateAndLog(c0.getCurrentVersionName().toString(), c0, c1.getCurrentVersionName());
		Assert.assertTrue(VersioningProfile.isVersionOf(c0.getCurrentVersionName(), testName2));
		Assert.assertEquals(t3, t1);
		Assert.assertTrue(c0.contentEquals(c1));	
	}

	
	@Test
	public void testSaveAsGone() throws Exception {
		ContentName testName = ContentName.fromNative(collectionObjName, "testSaveAsGone");
		setupNamespace(testName);

		CollectionObject c0 = new CollectionObject(testName, empty, library);
		Timestamp t0 = saveAsGoneAndLog("Gone", c0);
		Assert.assertTrue("Should be gone", c0.isGone());
		ContentName goneVersionName = c0.getCurrentVersionName();
		
		Timestamp t1 = saveAndLog("NotGone", c0, null, small1);
		Assert.assertFalse("Should not be gone", c0.isGone());
		Assert.assertTrue(t1.after(t0));
		
		CollectionObject c1 = new CollectionObject(testName, CCNLibrary.open());
		Timestamp t2 = waitForDataAndLog(testName.toString(), c1);
		Assert.assertFalse("Read back should not be gone", c1.isGone());
		Assert.assertEquals(t2, t1);
		
		Timestamp t3 = updateAndLog(goneVersionName.toString(), c1, goneVersionName);
		Assert.assertTrue(VersioningProfile.isVersionOf(c1.getCurrentVersionName(), testName));
		Assert.assertEquals(t3, t0);
		Assert.assertTrue("Read back should be gone.", c1.isGone());

		t0 = saveAsGoneAndLog("GoneAgain", c0);
		Assert.assertTrue("Should be gone", c0.isGone());
		CollectionObject c2 = new CollectionObject(testName, CCNLibrary.open());
		Timestamp t4 = waitForDataAndLog(testName.toString(), c2);
		Assert.assertTrue("Read back of " + c0.getCurrentVersionName() + " should be gone, got " + c2.getCurrentVersionName(), c2.isGone());
		Assert.assertEquals(t4, t0);

	}
	
	public <T> Timestamp saveAndLog(String name, CCNNetworkObject<T> ecd, Timestamp version, T data) throws XMLStreamException, IOException {
		Timestamp oldVersion = ecd.getCurrentVersion();
		ecd.save(version, data);
		Library.logger().info("Saved: " + ecd.getCurrentVersionName() + " (" + ecd.getVersion() + ", updated from " + oldVersion + ")" +  " gone? " + ecd.isGone() + " data: " + ecd);
		return ecd.getCurrentVersion();
	}
	
	public <T> Timestamp saveAsGoneAndLog(String name, CCNNetworkObject<T> ecd) throws XMLStreamException, IOException {
		Timestamp oldVersion = ecd.getCurrentVersion();
		ecd.saveAsGone();
		Library.logger().info("Saved: " + ecd.getCurrentVersionName() + " (" + ecd.getVersion() + ", updated from " + oldVersion + ")" +  " gone? " + ecd.isGone() + " data: " + ecd);
		return ecd.getCurrentVersion();
	}
	
	public Timestamp waitForDataAndLog(String name, CCNNetworkObject<?> ecd) throws XMLStreamException, IOException {
		ecd.waitForData();
		Library.logger().info("Initial read " + name + ", name: " + ecd.getCurrentVersionName() + " (" + ecd.getVersion() +")" +  " gone? " + ecd.isGone() + " data: " + ecd);
		return ecd.getCurrentVersion();
	}

	public Timestamp updateAndLog(String name, CCNNetworkObject<?> ecd, ContentName updateName) throws XMLStreamException, IOException {
		if (((null == updateName) && ecd.update()) || (ecd.update(updateName, null)))
			Library.logger().info("Updated " + name + ", to name: " + ecd.getCurrentVersionName() + " (" + ecd.getVersion() +")" +  " gone? " + ecd.isGone() + " data: " + ecd);
		else 
			Library.logger().info("No update found for " + name + ((null != updateName) ? (" at name " + updateName) : "") + ", still: " + ecd.getCurrentVersionName() + " (" + ecd.getVersion() +")" +  " gone? " + ecd.isGone() + " data: " + ecd);
		return ecd.getCurrentVersion();
	}

}
