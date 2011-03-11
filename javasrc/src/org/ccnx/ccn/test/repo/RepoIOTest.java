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

package org.ccnx.ccn.test.repo;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.TreeMap;
import java.util.logging.Level;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.impl.CCNFlowControl.SaveType;
import org.ccnx.ccn.impl.repo.BasicPolicy;
import org.ccnx.ccn.impl.repo.PolicyXML;
import org.ccnx.ccn.impl.repo.PolicyXML.PolicyObject;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.CCNInputStream;
import org.ccnx.ccn.io.CCNOutputStream;
import org.ccnx.ccn.io.CCNVersionedInputStream;
import org.ccnx.ccn.io.RepositoryOutputStream;
import org.ccnx.ccn.io.content.CCNStringObject;
import org.ccnx.ccn.io.content.Link;
import org.ccnx.ccn.io.content.Link.LinkObject;
import org.ccnx.ccn.profiles.SegmentationProfile;
import org.ccnx.ccn.profiles.ccnd.CCNDCacheManager;
import org.ccnx.ccn.profiles.repo.RepositoryControl;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Interest;
import org.ccnx.ccn.protocol.KeyLocator;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;
import org.ccnx.ccn.test.CCNTestHelper;
import org.ccnx.ccn.test.Flosser;
import org.ccnx.ccn.utils.CreateUserData;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;



/**
 * Part of repository test infrastructure. Requires at least repository to be running,
 * and RFSTest to have been run.
 */
public class RepoIOTest extends RepoTestBase {
	
	// TODO - all the regular tests should probably use a namespace derived from this...
	// (it didn't exist when these tests were developed)
	protected static CCNTestHelper testHelper = new CCNTestHelper(RepoIOTest.class);
	
	protected static String _repoTestDir = "repotest";
	protected static byte [] data = new byte[4000];
	// Test stream and net object names for content written into repo before all test cases
	// Note these have random numbers added in initialization
	protected static String _testPrefix = "/testNameSpace/stream";
	protected static String _testPrefixObj = "/testNameSpace/obj";
	// Test stream and net object names for content not in repo before test cases
	protected static String _testNonRepo = "/testNameSpace/stream-nr";
	protected static String _testNonRepoObj = "/testNameSpace/obj-nr";
	protected static String _testLink = "/testNameSpace/link";
	
	protected static CCNDCacheManager _cacheManager = new CCNDCacheManager();
	protected static ContentName _keyNameForStream;
	protected static ContentName _keyNameForObj;
	
	static String USER_NAMESPACE = "TestRepoUser";
	
	private static class IOTestFlosser extends Flosser {
		public static int KEY_INTEREST_TIMEOUT = SystemConfiguration.SHORT_TIMEOUT;
		private TreeMap<ContentName, Long> keyCheck = new TreeMap<ContentName, Long>();

		public IOTestFlosser() throws ConfigurationException, IOException {
			super();
		}
		
		public void handleKeyNamespace(String name) throws IOException {
			Log.info("Called handleKeyNamespace for {0}", name);
			try {
				ContentName cn = ContentName.fromNative(name);
				keyCheck.put(cn, System.currentTimeMillis());
				super.handleNamespace(cn);
			} catch (MalformedContentNameStringException e) {}
		}
		
		protected void processContent(ContentObject result) {
			super.processContent(result);
			for (ContentName cn : keyCheck.keySet()) {
				if (cn.isPrefixOf(result.name())) {
					keyCheck.put(cn, System.currentTimeMillis());
					Log.info("Saw key data for {0}", cn);
					break;
				}
			}
		}
		
		public void waitForKeys() {
			long curTime = System.currentTimeMillis();
			long maxTime = 0;
			while (true) {
				for (ContentName cn : keyCheck.keySet()) {
					long t = keyCheck.get(cn);
					if (t > maxTime)
						maxTime = t;
				}
				long delta = curTime - maxTime;
				if (delta > KEY_INTEREST_TIMEOUT)
					return;
				try {
					Thread.sleep(delta);
				} catch (InterruptedException e) {}
			}
		}
	}
	
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		Log.setLevel(Log.FAC_NETMANAGER, Level.FINEST);
		Log.setLevel(Log.FAC_IO, Level.FINEST);
		_testPrefix += "-" + rand.nextInt(10000);
		_testPrefixObj += "-" + rand.nextInt(10000);
		RepoTestBase.setUpBeforeClass();
		byte value = 1;
		for (int i = 0; i < data.length; i++)
			data[i] = value++;
		RepositoryOutputStream ros = new RepositoryOutputStream(ContentName.fromNative(_testPrefix), putHandle); 
		ros.setBlockSize(100);
		ros.setTimeout(4000);
		ros.write(data, 0, data.length);
		ros.close();
		CCNStringObject so = new CCNStringObject(ContentName.fromNative(_testPrefixObj), "Initial string value", SaveType.REPOSITORY, putHandle);
		so.save();
		so.close();
		
		// Need to save key also for first time sync test. Actually we need this for the policy
		// test too since the repo needs to locate the key to verify the policy test file
		KeyLocator locator = 
			putHandle.keyManager().getKeyLocator(putHandle.keyManager().getDefaultKeyID()); 
		putHandle.keyManager().publishSelfSignedKeyToRepository(
		               locator.name().name(), 
		               putHandle.keyManager().getDefaultPublicKey(), null, 
		               SystemConfiguration.getDefaultTimeout());
		

		// Floss content into ccnd for tests involving content not already in repo when we start
		IOTestFlosser floss = new IOTestFlosser();
		
		// Floss user based keys from CreateUserData
		floss.handleKeyNamespace(testHelper.getClassChildName(USER_NAMESPACE) + "/" + CreateUserData.USER_NAMES[0]);
		floss.handleKeyNamespace(testHelper.getClassChildName(USER_NAMESPACE) + "/" + CreateUserData.USER_NAMES[1]);
		
		// So we can test saving keys in the sync tests we build our first sync object (a stream) with
		// an alternate key and the second one (a CCNNetworkObject) with an alternate key locater that is
		// accessed through a link.
		CreateUserData testUsers = new CreateUserData(testHelper.getClassChildName(USER_NAMESPACE), 2, false, null, putHandle);
		String [] userNames = testUsers.friendlyNames().toArray(new String[2]);
		CCNHandle userHandle = testUsers.getHandleForUser(userNames[0]);
				
		// Build the link and the key it links to. Floss these into ccnd.
		_testNonRepo += "-" + rand.nextInt(10000);
		_testNonRepoObj += "-" + rand.nextInt(10000);
		_testLink += "-" + rand.nextInt(10000);
		ContentName name = ContentName.fromNative(_testNonRepo);
		floss.handleNamespace(name);
		CCNOutputStream cos = new CCNOutputStream(name, userHandle);
		cos.setBlockSize(100);
		cos.setTimeout(4000);
		cos.write(data, 0, data.length);
		_keyNameForStream = cos.getFirstSegment().signedInfo().getKeyLocator().name().name();
		cos.close();
		
		CCNHandle userHandle2 = testUsers.getHandleForUser(userNames[1]);
		KeyLocator userLocator = 
			userHandle2.keyManager().getKeyLocator(userHandle2.keyManager().getDefaultKeyID());
		Link link = new Link(userLocator.name().name());
		ContentName linkName = ContentName.fromNative(_testLink);
		LinkObject lo = new LinkObject(linkName, link, SaveType.RAW, putHandle);
		floss.handleNamespace(lo.getBaseName());
		lo.save();
		
		KeyLocator linkLocator = new KeyLocator(linkName);
		userHandle2.keyManager().setKeyLocator(null, linkLocator);
		name = ContentName.fromNative(_testNonRepoObj);
		floss.handleNamespace(name);
		so = new CCNStringObject(name, "String value for non-repo obj", SaveType.RAW, userHandle2);
		so.save();
		_keyNameForObj = so.getFirstSegment().signedInfo().getKeyLocator().name().name();
		lo.close();
		so.close();
		floss.waitForKeys();
		floss.stop();
	}
	
	@AfterClass
	public static void cleanup() throws Exception {
	}
	
	@Before
	public void setUp() throws Exception {
	}
	
	@Test
	public void testPolicyViaCCN() throws Exception {
		System.out.println("Testing namespace policy setting");
		checkNameSpace("/repoTest/data2", true);
		changePolicy("/org/ccnx/ccn/test/repo/policyTest.xml");
		checkNameSpace("/repoTest/data3", false);
		checkNameSpace("/testNameSpace/data1", true);
		changePolicy("/org/ccnx/ccn/test/repo/origPolicy.xml");
		checkNameSpace("/repoTest/data4", true);
	}
	
	@Test
	public void testReadFromRepo() throws Exception {
		System.out.println("Testing reading a stream from the repo");
		Thread.sleep(5000);
		CCNInputStream input = new CCNInputStream(ContentName.fromNative(_testPrefix), getHandle);
		byte[] testBytes = new byte[data.length];
		input.read(testBytes);
		Assert.assertArrayEquals(data, testBytes);
	}
	
	@Test
	// The purpose of this test is to do versioned reads from repo
	// of data not already in the ccnd cache, thus testing 
	// what happens if we pull latest version and try to read
	// content in order
	public void testVersionedRead() throws InterruptedException, IOException, MalformedContentNameStringException {
		System.out.println("Testing reading a versioned stream");
		Thread.sleep(5000);
		ContentName versionedNameNormal = ContentName.fromNative("/testNameSpace/testVersionNormal");
		CCNVersionedInputStream vstream = new CCNVersionedInputStream(versionedNameNormal);
		InputStreamReader reader = new InputStreamReader(vstream);
		for (long i=SegmentationProfile.baseSegment(); i<5; i++) {
			String segmentContent = "segment"+ new Long(i).toString();
			char[] cbuf = new char[8];
			int count = reader.read(cbuf, 0, 8);
			System.out.println("for " + i + " got " + count + " (eof " + vstream.eof() + "): " + new String(cbuf));
			Assert.assertEquals(segmentContent, new String(cbuf));
		}
		Assert.assertEquals(-1, reader.read());
	}
	
	@Test
	public void testLocalSyncInputStream() throws Exception {
		// This test should run all on single handle, just as client would do
		System.out.println("Testing local repo sync request for input stream");
		CCNInputStream input = new CCNInputStream(ContentName.fromNative(_testPrefix), getHandle);
		// Ignore data in this case, just trigger repo confirmation
		// Setup of this test writes the stream into repo, so we know it is already there --
		// should get immediate confirmation from repo, which means no new repo read starts
		boolean confirm = RepositoryControl.localRepoSync(getHandle, input);
		Assert.assertTrue(confirm);
		input.close();
		
		// Test case of content not already in repo
		Log.info("About to do first sync for stream");
		ContentName name = ContentName.fromNative(_testNonRepo);
		input = new CCNInputStream(name, getHandle);
		Assert.assertFalse(RepositoryControl.localRepoSync(getHandle, input));
		
		Thread.sleep(2000);  // Give repo time to fetch TODO: replace with confirmation protocol
		Log.info("About to do second sync for stream");
		Assert.assertTrue(RepositoryControl.localRepoSync(getHandle, input));	
		input.close();
		
		_cacheManager.clearCache(name, getHandle, 10000);
		_cacheManager.clearCache(_keyNameForStream, getHandle, 1000);
		byte[] testBytes = new byte[data.length];
		input = new CCNInputStream(name, getHandle);
		input.read(testBytes);
		Assert.assertArrayEquals(data, testBytes);
		input.close();
	}
	
	@Test
	public void testLocalSyncNetObj() throws Exception {
		// This test should run all on single handle, just as client would do
		System.out.println("Testing local repo sync request for network object");	
		CCNStringObject so = new CCNStringObject(ContentName.fromNative(_testPrefixObj), getHandle);
		// Ignore data in this case, just trigger repo confirmation
		// Setup of this test writes the object into repo, so we know it is already there --
		// should get immediate confirmation from repo, which means no new repo read starts
		boolean confirm = RepositoryControl.localRepoSync(getHandle, so);
		Assert.assertTrue(confirm);
		so.close();
		
		// Test case of content not already in repo
		ContentName name = ContentName.fromNative(_testNonRepoObj);
		so = new CCNStringObject(name, getHandle);
		Log.info("About to do first sync for object {0}", so.getBaseName());
		Assert.assertFalse(RepositoryControl.localRepoSync(getHandle, so));

		Thread.sleep(2000);  // Give repo time to fetch TODO: replace with confirmation protocol
		Log.info("About to do second sync for object {0}", so.getBaseName());
		Assert.assertTrue(RepositoryControl.localRepoSync(getHandle, so));
		so.close();
		
		_cacheManager.clearCache(name, getHandle, 10000);
		_cacheManager.clearCache(_keyNameForStream, getHandle, 1000);
		_cacheManager.clearCache(ContentName.fromNative(_testLink), getHandle, 1000);
		so = new CCNStringObject(name, getHandle);
		assert(so.string().equals("String value for non-repo obj"));
	}

	private void changePolicy(String policyFile) throws Exception {
		FileInputStream fis = new FileInputStream(_topdir + policyFile);
		PolicyXML pxml = BasicPolicy.createPolicyXML(fis);
		fis.close();
		ContentName basePolicy = BasicPolicy.getPolicyName(ContentName.fromNative(_globalPrefix), _repoName);
		ContentName policyName = new ContentName(basePolicy, Interest.generateNonce());
		PolicyObject po = new PolicyObject(policyName, pxml, SaveType.REPOSITORY, putHandle);
		po.save();
		Thread.sleep(4000);
	}
}
