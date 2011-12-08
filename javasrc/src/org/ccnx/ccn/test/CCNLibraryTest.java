/*
 * A CCNx library test.
 *
 * Copyright (C) 2008, 2009, 2011 Palo Alto Research Center, Inc.
 *
 * This work is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License version 2 as published by the
 * Free Software Foundation. 
 * This work is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License
 * for more details. You should have received a copy of the GNU General Public
 * License along with this program; if not, write to the
 * Free Software Foundation, Inc., 51 Franklin Street, Fifth Floor,
 * Boston, MA 02110-1301, USA.
 */

package org.ccnx.ccn.test;

import java.io.IOException;
import java.math.BigInteger;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SignatureException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Random;
import java.util.logging.Level;

import junit.framework.Assert;

import org.ccnx.ccn.BasicInterestListener;
import org.ccnx.ccn.CCNBase;
import org.ccnx.ccn.ContentVerifier;
import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.impl.CCNFlowControl;
import org.ccnx.ccn.impl.support.DataUtils;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.CCNReader;
import org.ccnx.ccn.io.CCNWriter;
import org.ccnx.ccn.io.content.ContentEncodingException;
import org.ccnx.ccn.profiles.SegmentationProfile;
import org.ccnx.ccn.profiles.VersionMissingException;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.protocol.CCNTime;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Interest;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;
import org.junit.Test;



/**
 * Part of older test infrastructure. Tests a set of library read functionality; some
 * of which could be aggregated into other tests.
 */
public class CCNLibraryTest extends LibraryTestBase {
	static final String contentString = "This is a very small amount of content";
	
	private class NameSeen {
		private ContentName name;
		private boolean seen = false;
		
		private NameSeen(ContentName name) {
			this.name = name;
		}
		
		private boolean setSeen(ContentObject co) {
			if (name.isPrefixOf(co.name())) {
				seen = true;
				return true;
			}
			return false;
		}
	}
	
	@Test
	public void testEnumerate() {
		Log.info(Log.FAC_TEST, "Starting testEnumerate");
		Assert.assertNotNull(putHandle);
		Assert.assertNotNull(getHandle);

		try {
			
			CCNWriter writer = new CCNWriter("/CPOF", putHandle);
			ArrayList<NameSeen> testNames = new ArrayList<NameSeen>(3);
			testNames.add(new NameSeen(ContentName.fromNative("/CPOF/foo")));
			testNames.add(new NameSeen(ContentName.fromNative("/CPOF/bar/lid")));
			testNames.add(new NameSeen(ContentName.fromNative("/CPOF/bar/jar")));
			
			for (int i = 0; i < testNames.size(); i++) {
				writer.put(testNames.get(i).name, Integer.toString(i).getBytes());
			}
			
			CCNReader reader = new CCNReader(getHandle);
			ArrayList<ContentObject> availableNames =
				reader.enumerate(new Interest("/CPOF"), SystemConfiguration.NO_TIMEOUT);

			Iterator<ContentObject> nameIt = availableNames.iterator();

			while (nameIt.hasNext()) {
				ContentObject theName = nameIt.next();

				// Just get by name, to test equivalent to current
				// ONC interface.
				ContentObject theObject = getHandle.get(theName.name(), 1000);

				if (null == theObject) {
					Log.info(Log.FAC_TEST, "Missing content: enumerated name: " + theName.name() + " not gettable.");

				} else {
					Log.info(Log.FAC_TEST, "Retrieved name: " + theName.name());
				}
				
				for (NameSeen nt : testNames) {
					if (nt.setSeen(theName))
						break;
				}
			}
			
			for (NameSeen nt : testNames) {
				if (!nt.seen)
					Assert.fail("Didn't see name " + nt.name.toString() + " in enumeration");
			}

		} catch (Exception e) {
			Log.warning(Log.FAC_TEST, "Got an exception in enumerate test: " + e.getClass().getName() + ": " + e.getMessage());
			Log.logStackTrace(Log.FAC_TEST, Level.WARNING, e);
			Assert.fail("Exception in testEnumerate: " + e.getMessage());
		}
		Log.info(Log.FAC_TEST, "Completed testEnumerate");
	}

	@Test
	public void testPut() {
		Log.info(Log.FAC_TEST, "Starting testPut");
		
		Assert.assertNotNull(putHandle);
		Assert.assertNotNull(getHandle);

		ContentName name = null;
		byte[] content = null;
//		SignedInfo.ContentType type = SignedInfo.ContentType.LEAF;
		PublisherPublicKeyDigest publisher = null;

		content = DataUtils.getBytesFromUTF8String(contentString);

		try {
			name = ContentName.fromNative("/test/briggs/foo.txt");
		} catch (MalformedContentNameStringException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		try {
			CCNWriter segmenter = new CCNWriter(name, putHandle);
			ContentName result = segmenter.put(name, content, publisher);
			Log.info(Log.FAC_TEST, "Resulting ContentName: " + result);
		} catch (SignatureException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
		Log.info(Log.FAC_TEST, "Completed testPut");
	}

	@Test
	public void testRevision() {
		Log.info(Log.FAC_TEST, "Starting testRevision");

		String key = "/test/key";
		byte[] data1 = "data".getBytes();
		byte[] data2 = "newdata".getBytes();
		ContentName revision1;
		ContentName revision2;

		try {
			ContentName keyName = ContentName.fromNative(key);
			CCNWriter segmenter = new CCNWriter(keyName, putHandle);
			revision1 = segmenter.newVersion(keyName, data1);
			revision2 = segmenter.newVersion(keyName, data2);
			long version1 = VersioningProfile.getLastVersionAsLong(revision1);
			long version2 = VersioningProfile.getLastVersionAsLong(revision2);
			Log.info(Log.FAC_TEST, "Version1: " + version1 + " version2: " + version2);
			Assert.assertTrue("Revisions are strange", 
					version2 > version1);
		} catch (Exception e) {
			Log.warning(Log.FAC_TEST, "Exception in updating versions: " + e.getClass().getName() + ": " + e.getMessage());
			Assert.fail(e.getMessage());
		}
		Log.info(Log.FAC_TEST, "Completed testRevision");
	}

	@Test
	public void testVersion() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testVersion");

		String name = "/test/smetters/stuff/versioned_name";
		ContentName cn = ContentName.fromNative(name);
		String name2 = "/test/smetters/stuff/.directory";
		ContentName cn2 = ContentName.fromNative(name2);
		String data = "The associated data.";
		String newdata = "The new associated data.";

		versionTest(cn, data.getBytes(), newdata.getBytes());
		versionTest(cn2, data.getBytes(), newdata.getBytes());
		Log.info(Log.FAC_TEST, "Completed testRevision");
	}

	@Test
	public void testGetLatestVersion() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testGetLatestVersion");

		String name = "/test/simon/versioned_name-" + new Random().nextInt(10000);
		// include a base object, who's digest can potentially confuse getLatestVersion
		ContentName base = ContentName.fromNative(name);
		
		// Don't do repeated get latest versions for now, not working.
		final int testCount = 2;
		final byte [][] data = new byte[testCount][1];
		for (int i=0; i < testCount; ++i) {
			data[i][0] = (byte)i;
		}
		CCNFlowControl f = new CCNFlowControl(base, putHandle);
		ContentObject [] cos = new ContentObject[testCount];
		cos[0] = ContentObject.buildContentObject(base, data[0]);
		for (int i=1; i < testCount; ++i) {
			ContentName versionedName = VersioningProfile.addVersion(base);
			Thread.sleep(3);
			cos[i] = ContentObject.buildContentObject(versionedName, data[i]);
		}
		f.put(cos[0]);
		f.put(cos[1]);
		// java lacks nested functions, so use a class here...
		class t {
			void check(ContentObject o, int i) throws InvalidKeyException, ContentEncodingException,
						SignatureException, NoSuchAlgorithmException, InterruptedException {
				Log.info(Log.FAC_TEST, "Got content: " + o.name());
				Log.info(Log.FAC_TEST, "Original value: " + i + " returned value: " + Byte.toString(o.content()[0]));
				Assert.assertTrue(o.verify(putHandle.keyManager()));
				Assert.assertTrue(DataUtils.arrayEquals(o.content(), data[i]));
			}
			/**
			 * Make sure the data is written to ccnd by reading it
			 * @throws InterruptedException 
			 * @throws NoSuchAlgorithmException 
			 * @throws SignatureException 
			 * @throws InvalidKeyException 
			 */
			void readAndCheck(ContentName name, int index) 
					throws ContentEncodingException,
						IOException, InvalidKeyException, 
						SignatureException, NoSuchAlgorithmException, InterruptedException {
				Log.info(Log.FAC_TEST, "Getting content: " + name);
				check(getHandle.get(name, 2000), index);
			}
		} t test = new t();
		test.readAndCheck(base, 0);
		test.readAndCheck(cos[1].name(), 1);
		ContentVerifier putVerifier = new ContentObject.SimpleVerifier(putHandle.getDefaultPublisher());
		test.check(VersioningProfile.getLatestVersion(base, putHandle.getDefaultPublisher(), 2000, putVerifier, getHandle), 1);
		// Beef this up a bit...
		for (int i=2; i < testCount; ++i) {
			f.put(cos[i]);
			Log.info(Log.FAC_TEST, "Wrote content: " + cos[i].name());
			test.check(VersioningProfile.getLatestVersion(cos[i-1].name(), putHandle.getDefaultPublisher(), 2000, putVerifier, getHandle), i);
		}
		Log.info(Log.FAC_TEST, "Completed testGetLatestVersion");
	}

	@Test
	public void testRecall() {
		Log.info(Log.FAC_TEST, "Starting testRecall");
		
		String key = "/test/smetters/values/data";
		CCNTime time = CCNTime.now();
		try {
			ContentName keyName = ContentName.fromNative(key);
			CCNWriter writer = new CCNWriter(keyName, putHandle);
			ContentName name = writer.put(keyName, BigInteger.valueOf(time.getTime()).toByteArray());
			Log.info(Log.FAC_TEST, "Put under name: " + name);
			ContentObject result = getHandle.get(name, SystemConfiguration.NO_TIMEOUT);

			Log.info(Log.FAC_TEST, "Querying for returned name, Got back: " + (result == null ? "0"  : "1") + " results.");

			if (result == null) {
				Log.info(Log.FAC_TEST, "Didn't get back content we just put in.");
				Log.info(Log.FAC_TEST, "Put under name: " + keyName);
				Log.info(Log.FAC_TEST, "Final name: " + name);
				//Assert.fail("Didn't get back content we just put!");

				result = getHandle.get(name, SystemConfiguration.NO_TIMEOUT);

				Log.info(Log.FAC_TEST, "Recursive querying for returned name, Got back: " + (result == null ? "0"  : "1") + " results.");

				ContentName parentName = name.parent();
				Log.info(Log.FAC_TEST, "Inserted name's parent same as key name? " + parentName.equals(keyName));

			} else {
				byte [] content = result.content();
				Log.info(Log.FAC_TEST, "Got content: " + result.name());
				Log.info(Log.FAC_TEST, "Original time: " + time + " returned time: " + new Timestamp(new BigInteger(1, content).longValue()));
				Assert.assertNotNull("No content associated with name we just put!", content);
				Assert.assertTrue("didn't get back same data", 
						time.equals(new Timestamp(new BigInteger(1, content).longValue())));
			}

			result = getHandle.get(keyName, SystemConfiguration.NO_TIMEOUT);

			Log.info(Log.FAC_TEST, "Querying for inserted name, Got back: " 
							+ (result == null ? "0"  : "1") + " results.");

			if (result == null)
				Assert.fail("Didn't get back content we just put!");

			if (SegmentationProfile.segmentRoot(result.name()).equals(name) &&
					time.equals(new Timestamp(new BigInteger(1, result.content()).longValue()))) {
				Log.info(Log.FAC_TEST, "Got back name we inserted.");
			} else {
				Log.warning(Log.FAC_TEST, "Didn't get back data we just inserted:\n  result: " + result.name() + 
								" (write time: " + result.signedInfo().getTimestamp() + 
								   " content time: " + new Timestamp(new BigInteger(1, result.content()).longValue()) +
										")\n   orig: " + name + 
										" (time: " + time + ")");
				Assert.fail("Didn't get back data we just inserted - result name: " + 
						SegmentationProfile.segmentRoot(result.name()) + 
						", auth: " + result.signedInfo() + " - orig name: " + name + "\n, orig time: " +
						time + " time content: " + 
						new Timestamp(new BigInteger(1, result.content()).longValue()));
			}
		} catch (Exception e) {
			System.out.println("Exception in testing recall: " + e.getClass().getName() + ": " + e.getMessage());
			Assert.fail(e.getMessage());
		}
		Log.info(Log.FAC_TEST, "Completed testRecall");
	}

	public void versionTest(ContentName docName,
			byte [] content1,
			byte [] content2) throws Exception {

		CCNWriter writer = new CCNWriter(docName, putHandle);
		ContentName version1 = writer.newVersion(docName, content1);
		Log.info(Log.FAC_TEST, "Inserted first version as: " + version1);
		Assert.assertNotNull("New version is null!", version1);

		ContentVerifier putVerifier = new ContentObject.SimpleVerifier(putHandle.getDefaultPublisher());
		ContentObject latestVersion =
			VersioningProfile.getLatestVersion(docName, null, SystemConfiguration.NO_TIMEOUT, putVerifier, getHandle);
		Assert.assertTrue(latestVersion.verify(getHandle.keyManager()));
		Assert.assertNotNull("Retrieved latest version of " + docName + " got null!", latestVersion);
		Log.info(Log.FAC_TEST, "Latest version name: " + latestVersion.name());

		ContentName version2 = 
			writer.newVersion(docName, content2);

		Assert.assertNotNull("New version is null!", version2);
		Log.info(Log.FAC_TEST, "Inserted second version as: " + version2);

		ContentObject newLatestVersion = 
			VersioningProfile.getLatestVersion(docName, null, SystemConfiguration.NO_TIMEOUT, putVerifier, getHandle);
		Assert.assertTrue(newLatestVersion.verify(getHandle.keyManager()));
		Assert.assertNotNull("Retrieved new latest version of " + docName + " got null!", newLatestVersion);
		Log.info(Log.FAC_TEST, "Latest version name: " + newLatestVersion.name());

		Assert.assertTrue("Version is not a version of the parent name!", VersioningProfile.isVersionOf(version1, docName));
		Assert.assertTrue("Version is not a version of the parent name!", VersioningProfile.isVersionOf(version2, docName));
		Assert.assertTrue("Version numbers don't increase!", VersioningProfile.getLastVersionAsLong(version2) > VersioningProfile.getLastVersionAsLong(version1));
	}

	@Test
	public void testNotFound() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testNotFound");

		try {
			String key = "/some_strange_key_we_should_never_find";
			ContentObject result = getHandle.get(ContentName.fromNative(key), 1000);
			Assert.assertTrue("found something when there shouldn't have been anything", result == null);
		} catch (Exception e) {
			Log.info(Log.FAC_TEST, "Exception in testing recall: " + e.getClass().getName() + ": " + e.getMessage());
			Assert.fail(e.getMessage());
		}
		Log.info(Log.FAC_TEST, "Completed testNotFound");
	}

	class TestListener extends BasicInterestListener {
		int _count = 0;
		Thread _mainThread;

		public TestListener(CCNBase queryProvider,
				Interest initialInterest,
				Thread mainThread) {
			super(queryProvider);
			_mainThread = mainThread;
		}

		public Interest handleContent(ContentObject co, Interest interest) {
			byte[] content = null;
			try {
				if (null != co) {
						Log.info(Log.FAC_TEST, "handleContent: got " + co.name());

						content = co.content();
						String strContent = new String(content);
						
						if (VersioningProfile.hasTerminalVersion(co.name())) {
							// We're writing this content using CCNWriter.put. That interface
							// does *not* version content for you, at least at the moment. 
							// TODO We need to decide whether we expect it to. So don't require
							// versioning here yet. 
							Log.info(Log.FAC_TEST, "Got update for " + co.name() + ": " + strContent + 
								" (revision " + VersioningProfile.getLastVersionAsLong(co.name()) + ")");
						} else {
							Log.info(Log.FAC_TEST, "Got update for " + co.name() + ": " + strContent);
						}
						_count++;
						switch(_count) {
						case 1:
							Assert.assertEquals("data1", strContent);
							Log.info(Log.FAC_TEST, "Got data1 back!");
							_mainThread.interrupt();
							break;
						case 2: 
							Assert.assertEquals("data2", strContent);
							Log.info(Log.FAC_TEST, "Got data2 back!");
							_mainThread.interrupt();
							break;
						default:
							Assert.fail("Somehow got a third update");
						}
				}
			} catch (VersionMissingException vex) {
				Assert.fail("No version when expecting one -- though be careful to make sure we should have been.");
			}
			return null;
		}
	}

	@Test
	public void testInterest() {
		Log.info(Log.FAC_TEST, "Starting testInterest");

		String key = "/test/interest";
		final Thread mainThread = Thread.currentThread();

		byte[] data1 = "data1".getBytes();
		byte[] data2 = "data2".getBytes();

		try {
			CCNWriter writer = new CCNWriter(key, putHandle);
			Interest ik = new Interest(key);
			TestListener tl = new TestListener(getHandle, ik, mainThread);
			getHandle.expressInterest(ik, 
					tl);
			writer.put(ContentName.fromNative(key), data1);
			// wait a little bit before we move on...
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
			}

			writer.put(ContentName.fromNative(key), data2);

			// wait a little bit before we move on...
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
			}

			getHandle.cancelInterest(ik, tl);

		} catch (Exception e) {
			Log.warning(Log.FAC_TEST, "Exception in testing interests: " + e.getClass().getName() + ": " + e.getMessage());
			Assert.fail(e.getMessage());
		}
		Log.info(Log.FAC_TEST, "Completed testInterest");
	}

}
