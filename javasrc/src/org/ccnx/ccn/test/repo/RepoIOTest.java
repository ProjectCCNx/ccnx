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
import java.util.logging.Level;

import org.ccnx.ccn.CCNHandle;
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
import org.ccnx.ccn.io.content.PublicKeyObject;
import org.ccnx.ccn.io.content.Link.LinkObject;
import org.ccnx.ccn.profiles.SegmentationProfile;
import org.ccnx.ccn.profiles.repo.RepositoryControl;
import org.ccnx.ccn.protocol.ContentName;
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
	protected static String _testLinkToKey = "/testNameSpace/link-to-key";
	protected static String _testLink = "/testNameSpace/link";
	
	static String USER_NAMESPACE = "TestRepoUser";
	
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		Log.setLevel(Level.FINEST);
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
		Flosser floss = new Flosser();
		
		// So we can test saving keys in the sync tests we build our first sync object (a stream) with
		// an alternate key and the second one (a CCNNetworkObject) with an alternate key locater that is
		// accessed through a link.
		CreateUserData testUsers = new CreateUserData(testHelper.getClassChildName(USER_NAMESPACE), 2, true, null, putHandle);
		String [] userNames = testUsers.friendlyNames().toArray(new String[2]);
		CCNHandle userHandle = testUsers.getHandleForUser(userNames[0]);
		
		KeyLocator userLocator = 
			userHandle.keyManager().getKeyLocator(userHandle.keyManager().getDefaultKeyID());
		floss.handleNamespace(userLocator.name().name());
		PublicKeyObject pko = userHandle.keyManager().publishSelfSignedKey(userLocator.name().name(), null,
						false);

		// Build the link and the key it links to. Floss these into ccnd.
		_testNonRepo += "-" + rand.nextInt(10000);
		_testNonRepoObj += "-" + rand.nextInt(10000);
		_testLinkToKey += "-" + rand.nextInt(10000);
		_testLink += "-" + rand.nextInt(10000);
		ContentName name = ContentName.fromNative(_testNonRepo);
		floss.handleNamespace(name);
		CCNOutputStream cos = new CCNOutputStream(name, userHandle);
		cos.setBlockSize(100);
		cos.setTimeout(4000);
		cos.write(data, 0, data.length);
		cos.close();
		
		ContentName linkToKeyName = ContentName.fromNative(_testLinkToKey);
		Link link = new Link(linkToKeyName);
		ContentName linkName = ContentName.fromNative(_testLink);
		LinkObject lo = new LinkObject(linkName, link, SaveType.RAW, putHandle);
		floss.handleNamespace(lo.getBaseName());
		lo.save();
		
		KeyLocator linkLocator = new KeyLocator(linkName);
		CCNHandle userHandle2 = testUsers.getHandleForUser(userNames[1]);
		userHandle2.keyManager().setKeyLocator(null, linkLocator);
		floss.handleNamespace(linkToKeyName);
		PublicKeyObject pko2 = userHandle2.keyManager().publishSelfSignedKey(linkToKeyName, null,
						false);
		
		name = ContentName.fromNative(_testNonRepoObj);
		floss.handleNamespace(name);
		so = new CCNStringObject(name, "String value for non-repo obj", SaveType.RAW, userHandle2);
		so.save();
		lo.close();
		waitForPutDrain(pko);
		pko.close();
		waitForPutDrain(pko2);
		so.close();
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
		input = new CCNInputStream(ContentName.fromNative(_testNonRepo), getHandle);
		Assert.assertFalse(RepositoryControl.localRepoSync(getHandle, input));
		
		Thread.sleep(2000);  // Give repo time to fetch TODO: replace with confirmation protocol
		Assert.assertTrue(RepositoryControl.localRepoSync(getHandle, input));
		
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
		so = new CCNStringObject(ContentName.fromNative(_testNonRepoObj), getHandle);
		Assert.assertFalse(RepositoryControl.localRepoSync(getHandle, so));

		Thread.sleep(2000);  // Give repo time to fetch TODO: replace with confirmation protocol
		Assert.assertTrue(RepositoryControl.localRepoSync(getHandle, so));
		so.close();
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
	
	/**
	 * Normally when we close an object which has been written, we will wait until all the outstanding 
	 * associated content objects have been output to ccnd. But the PublicKeyObjects use a special flow
	 * controller that does not wait. This is normally fine (presumably) but in order to test sync correctly
	 * we have to make sure that the objects are all written out before the close. So we fake waitForPutDrain
	 * here.
	 */
	private static void waitForPutDrain(PublicKeyObject obj) {
		long endTime = System.currentTimeMillis() + obj.getTimeout();
		while (obj.getFlowControl().size() > 0 && System.currentTimeMillis() < endTime) {
			try {
				Thread.sleep(200);
			} catch (InterruptedException e) {}
		}
	}
}
