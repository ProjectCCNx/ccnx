/*
 * A CCNx library test.
 *
 * Copyright (C) 2008-2012 Palo Alto Research Center, Inc.
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

import static org.ccnx.ccn.profiles.CommandMarker.COMMAND_MARKER_BASIC_ENUMERATION;

import java.io.File;
import java.security.KeyPair;
import java.security.KeyPairGenerator;

import org.ccnx.ccn.config.UserConfiguration;
import org.ccnx.ccn.impl.repo.LogStructRepoStore;
import org.ccnx.ccn.impl.repo.RepositoryException;
import org.ccnx.ccn.impl.repo.RepositoryStore;
import org.ccnx.ccn.impl.repo.LogStructRepoStore.LogStructRepoStoreProfile;
import org.ccnx.ccn.impl.support.DataUtils;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.profiles.CommandMarker;
import org.ccnx.ccn.profiles.SegmentationProfile;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.profiles.nameenum.NameEnumerationResponse;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Exclude;
import org.ccnx.ccn.protocol.Interest;
import org.ccnx.ccn.protocol.KeyLocator;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;
import org.ccnx.ccn.protocol.PublisherID;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;
import org.ccnx.ccn.protocol.SignedInfo;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;


/**
 * Test repository backend implementation(s) using filesystem (FS) as stable storage.  In principle,
 * there could be multiple FS-backed implementations exercised by these tests.
 * 
 * Because it uses the default KeyManager, this test must be run
 * with ccnd running.
 *
 */
public class RFSTest extends RepoTestBase {
	
	RepositoryStore repolog; // Instance of simple log-based repo implementation under test
	
	private final String Repository2 = "TestRepository2";
	
	private ContentName longName;
	private byte[] longNameDigest;
	private ContentName badCharName;
	private ContentName badCharLongName;
	private ContentName versionedName;
	private ContentName segmentedName1;
	private ContentName segmentedName223;
	private ContentName versionedNameNormal;
	
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		RepoTestBase.setUpBeforeClass();
		_fileTest = new File(_fileTestDir);
		DataUtils.deleteDirectory(_fileTest);
		_fileTest.mkdirs();
	}
				
	public void initRepoLog() throws Exception {
		repolog = new LogStructRepoStore();
		repolog.initialize(_fileTestDir, null, _repoName, _globalPrefix, null, null);
	}
	
	/**
	 * This is the basic data test for the repo store without networking
	 */
	@Test
	public void testRepo() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testRepo");

		initRepoLog();
		test(repolog);
		initRepoLog();
		// Having initialized a new instance on the same stable storage stage produced by the
		// test() method, now run testReinitialization to check consistency.
		testReinitialization(repolog);
		repolog.shutDown();
		
		Log.info(Log.FAC_TEST, "Completed testRepo");
	}
	
	@Test
	public void testBulkImport() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testBulkImport");

		initRepoLog();
		RepositoryStore repolog2 = new LogStructRepoStore();
		repolog2.initialize(_fileTestDir2, null, Repository2, _globalPrefix, null, null);
		ContentName name = ContentName.fromNative("/repoTest/testAddData");
		ContentObject content = ContentObject.buildContentObject(name, "Testing bulk import".getBytes());
		repolog2.saveContent(content);
		checkData(repolog2, name, "Testing bulk import");
		repolog2.shutDown();
		File importDir = new File(_fileTestDir + UserConfiguration.FILE_SEP + LogStructRepoStoreProfile.REPO_IMPORT_DIR);
		Assert.assertTrue(importDir.mkdir());
		File importFile = new File(_fileTestDir2, LogStructRepoStoreProfile.CONTENT_FILE_PREFIX + "1");
		importFile.renameTo(new File(importDir, "BulkImportTest"));
		repolog.bulkImport("BulkImportTest");
		checkData(repolog, name, "Testing bulk import");
		repolog.shutDown();
		
		Log.info(Log.FAC_TEST, "Completed testBulkImport");
	}
	
	/**
	 * Tests policy file parsing
	 */
	@Test
	public void testPolicy() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testPolicy");

		RepositoryStore repo = new LogStructRepoStore();
		try {	// Test no version
			repo.initialize(_fileTestDir, new File(_topdir + "/org/ccnx/ccn/test/repo/badPolicyTest1.xml"), null, null, null, null);
			Assert.fail("Bad policy file succeeded");
		} catch (RepositoryException re) {}
		try {	// Test bad version
			repo.initialize(_fileTestDir, new File(_topdir + "/org/ccnx/ccn/test/repo/badPolicyTest2.xml"), null, null, null, null);
			Assert.fail("Bad policy file succeeded");
		} catch (RepositoryException re) {}
		// Make repository using repo's keystore, not user's
		repo.initialize(_fileTestDir,  
					new File(_topdir + "/org/ccnx/ccn/test/repo/policyTest.xml"), _repoName, _globalPrefix, null, null);
		repo.shutDown();
		
		Log.info(Log.FAC_TEST, "Completed testPolicy");
	}
	
	/**
	 * Various tests for storing and retrieving data from the store. Called via testRepo
	 * after repo is set up
	 */
	public void test(RepositoryStore repo) throws Exception{		
		System.out.println("Repotest - Testing basic data");
		ContentName name = ContentName.fromNative("/repoTest/data1");
		ContentObject content = ContentObject.buildContentObject(name, "Here's my data!".getBytes());
		repo.saveContent(content);
		checkData(repo, name, "Here's my data!");
		
		// TODO - Don't know how to check that multiple data doesn't result in multiple copies
		// Do it just to make sure the mechanism doesn't break (but result is not tested).
		repo.saveContent(content);
				
		System.out.println("Repotest - Testing multiple digests for same data");
		ContentObject digest2 = ContentObject.buildContentObject(name, "Testing2".getBytes());
		repo.saveContent(digest2);
		ContentName digestName = new ContentName(name, digest2.digest());
		checkDataWithDigest(repo, digestName, "Testing2");
		
		System.out.println("Repotest - Testing same digest for different data and/or publisher");
		KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
		kpg.initialize(512); // go for fast
		KeyPair pair1 = kpg.generateKeyPair();
		PublisherPublicKeyDigest pubKey1 = new PublisherPublicKeyDigest(pair1.getPublic());
		KeyLocator kl = new KeyLocator(new ContentName(keyprefix, pubKey1.digest()));
		repo.saveContent(ContentObject.buildContentObject(kl.name().name(), pubKey1.digest()));
		SignedInfo si = new SignedInfo(pubKey1, kl);
		ContentObject digestSame1 = new ContentObject(name, si, "Testing2".getBytes(), pair1.getPrivate());
		repo.saveContent(digestSame1);
		KeyPair pair2 = kpg.generateKeyPair();
		PublisherPublicKeyDigest pubKey2 = new PublisherPublicKeyDigest(pair2.getPublic());
		kl = new KeyLocator(new ContentName(keyprefix, pubKey2.digest()));
		repo.saveContent(ContentObject.buildContentObject(kl.name().name(), pubKey2.digest()));
		SignedInfo si2 = new SignedInfo(pubKey2, kl);
		ContentObject digestSame2 = new ContentObject(name, si2, "Testing2".getBytes(), pair2.getPrivate());
		repo.saveContent(digestSame2);
		checkDataAndPublisher(repo, name, "Testing2", pubKey1);
		checkDataAndPublisher(repo, name, "Testing2", pubKey2);
		
		System.out.println("Repotest - Testing too long data");
		String tooLongName = "0123456789";
		for (int i = 0; i < 30; i++)
			tooLongName += "0123456789";
		longName = ContentName.fromNative("/repoTest/" + tooLongName);
		ContentObject co = ContentObject.buildContentObject(longName, "Long name!".getBytes());
		longNameDigest = co.digest();
		repo.saveContent(co);
		checkData(repo, longName, "Long name!");
		digest2 = ContentObject.buildContentObject(longName, "Testing2".getBytes());
		repo.saveContent(digest2);
		digestName = new ContentName(longName, digest2.digest());
		checkDataWithDigest(repo, digestName, "Testing2");
		String wayTooLongName = tooLongName;
		for (int i = 0; i < 30; i++)
			wayTooLongName += "0123456789";
		ContentName reallyLongName = ContentName.fromNative("/repoTest/" + wayTooLongName);
		repo.saveContent(ContentObject.buildContentObject(reallyLongName, "Really Long name!".getBytes()));
		checkData(repo, reallyLongName, "Really Long name!");
		byte[] longNonASCIIBytes = new byte[300];
		
		for (int i = 0; i < 30; i++) {
			rand.nextBytes(longNonASCIIBytes);
			ContentName lnab = new ContentName("repoTest", longNonASCIIBytes);
			repo.saveContent(ContentObject.buildContentObject(lnab, ("Long and Non ASCII " + i).getBytes()));
			checkData(repo, lnab, "Long and Non ASCII " + i);
		}
		
		System.out.println("Repotest - Testing invalid characters in name");
		badCharName = ContentName.fromNative("/repoTest/" + "*x?y<z>u");
		repo.saveContent(ContentObject.buildContentObject(badCharName, "Funny characters!".getBytes()));
		checkData(repo, badCharName, "Funny characters!");
		badCharLongName = ContentName.fromNative("/repoTest/" + tooLongName + "*x?y<z>u");
		repo.saveContent(ContentObject.buildContentObject(badCharLongName, "Long and funny".getBytes()));
		checkData(repo, badCharLongName, "Long and funny");
		
		System.out.println("Repotest - Testing different kinds of interests");
		String prefix1 = "/repoTest/nextTest";
		ContentName name1 = ContentName.fromNative(prefix1 + "/aaa");
		ContentObject content1 = addRelativeTestContent(repo, prefix1, "");
		checkData(repo, Interest.next(new ContentName(name1, content1.digest()), 2, null), "bbb");
		checkData(repo, Interest.last(new ContentName(name1, content1.digest()), 2, null), "fff");
		checkData(repo, Interest.next(new ContentName(name1, content1.digest()), 
				new Exclude(new byte [][] {"bbb".getBytes(), "ccc".getBytes()}), 2, null, null, null), "ddd");
		
		System.out.println("Repotest - Testing different kinds of interests in a mixture of encoded/standard data");
		ContentName nonLongName = ContentName.fromNative("/repoTestLong/nextTestLong/aaa");
		ContentObject nonLongContent = addRelativeTestContent(repo, "/repoTestLong/nextTestLong", "/" + tooLongName);
		checkData(repo, Interest.next(new ContentName(nonLongName, nonLongContent.digest()), 2, null), "bbb");
		checkData(repo, Interest.last(new ContentName(nonLongName, nonLongContent.digest()), 2, null), "fff");
		checkData(repo, Interest.next(new ContentName(nonLongName, nonLongContent.digest()), 
				new Exclude(new byte [][] {"bbb".getBytes(), "ccc".getBytes()}), 2, null, null, null), "ddd");
		
		System.out.println("Test some unusual left and right searches that could break the prescanner");
		Exclude excludeEandF = new Exclude(new byte [][] {"eee".getBytes(), "fff".getBytes()});
		checkData(repo, Interest.last(new ContentName(name1, content1.digest()), 
				excludeEandF, 2, null, null, null), "ddd");
		Interest handInterest = Interest.constructInterest(ContentName.fromNative("/repoTest/nextTest"), 
				excludeEandF, Interest.CHILD_SELECTOR_RIGHT, null, null, null);	
		checkData(repo, handInterest, "ddd");
		String prefix2 = "/repoTest/nextTest/bbb";
		ContentName name2 = ContentName.fromNative(prefix2 + "/aaa");
		
		// Make sure exclude prescan is at correct level
		ContentObject content2= addRelativeTestContent(repo, prefix2, "");
		checkData(repo, Interest.next(new ContentName(name2, content2.digest()), 3, null), "bbb");
		checkData(repo, Interest.next(new ContentName(name2, content2.digest()), 
				new Exclude(new byte [][] {"bbb".getBytes(), "ccc".getBytes()}), 3, null, null, null), "ddd");
		String prefix3 = "/repoTest/nextTest/ddd";
		ContentName name3 = ContentName.fromNative(prefix3 + "/aaa");
		ContentObject content3 = addRelativeTestContent(repo, prefix3, "");
		checkData(repo, Interest.last(new ContentName(name3, content3.digest()), 
				excludeEandF, 3, null, null, null), "ddd");
		Interest handInterest1 = Interest.constructInterest(ContentName.fromNative(prefix3), 
				excludeEandF, Interest.CHILD_SELECTOR_RIGHT, null, null, null);	
		checkData(repo, handInterest1, "ddd");
		Exclude excludeAll = Exclude.uptoFactory("fff".getBytes());
		Interest excludeLeftInterest = Interest.next(name3, excludeAll, 2, null, null, null);
		ContentObject testScreenOut = repo.getContent(excludeLeftInterest);
		Assert.assertTrue(testScreenOut == null);
		
		System.out.println("Repotest - test that rightSearch iterates backwards through objects");
		repo.saveContent(new ContentObject(ContentName.fromNative(prefix1 + "/bbb"), si, "funnyRightSearch".getBytes(), pair1.getPrivate()));
		repo.saveContent(new ContentObject(ContentName.fromNative(prefix1 + "/aaa"), si, "wrongRightSearch".getBytes(), pair1.getPrivate()));
		ContentName name4 = ContentName.fromNative(prefix1 + "/aa");
		Interest rightSearch = Interest.last(name4, null, 2, null, null, pubKey1);
		checkData(repo, rightSearch, "funnyRightSearch");
		
		System.out.println("Repotest - test that rightSearch gives left branch of rightMost object");
		String prefix4 = "/repoTest/nextTest/fff";
		addRelativeTestContent(repo, prefix4, "");
		Interest rightInterest = Interest.last(name1, null, null);
		checkData(repo, rightInterest, "aaa");
			
		System.out.println("Repotest - testing version and segment files");
		versionedName = ContentName.fromNative("/repoTest/testVersion");
		versionedName = VersioningProfile.addVersion(versionedName);
		repo.saveContent(ContentObject.buildContentObject(versionedName, "version".getBytes()));
		checkData(repo, versionedName, "version");
		segmentedName1 = SegmentationProfile.segmentName(versionedName, 1);
		repo.saveContent(ContentObject.buildContentObject(segmentedName1, "segment1".getBytes()));
		checkData(repo, segmentedName1, "segment1");
		segmentedName223 = SegmentationProfile.segmentName(versionedName, 223);
		repo.saveContent(ContentObject.buildContentObject(segmentedName223, "segment223".getBytes()));
		checkData(repo, segmentedName223, "segment223");
		
		System.out.println("Repotest - storing sequence of objects for versioned stream read testing");
		versionedNameNormal = ContentName.fromNative("/testNameSpace/testVersionNormal");
		versionedNameNormal = VersioningProfile.addVersion(versionedNameNormal);
		repo.saveContent(ContentObject.buildContentObject(versionedNameNormal, "version-normal".getBytes()));
		checkData(repo, versionedNameNormal, "version-normal");
		byte[] finalBlockID = SegmentationProfile.getSegmentNumberNameComponent(4);
		for (Long i=SegmentationProfile.baseSegment(); i<5; i++) {
			ContentName segmented = SegmentationProfile.segmentName(versionedNameNormal, i);
			String segmentContent = "segment"+ new Long(i).toString();
			repo.saveContent(ContentObject.buildContentObject(segmented, segmentContent.getBytes(), null, null, finalBlockID));
			checkData(repo, segmented, segmentContent);
		}
		
		System.out.println("Repotest - testing min and max in retrieval");
		ContentName shortName = ContentName.fromNative("/repoTest/1/2");
		ContentName longName = ContentName.fromNative("/repoTest/1/2/3/4/5/6");
		ContentName middleName = ContentName.fromNative("/repoTest/1/2/3/4");
		repo.saveContent(ContentObject.buildContentObject(shortName, "short".getBytes()));
		repo.saveContent(ContentObject.buildContentObject(longName, "long".getBytes()));
		repo.saveContent(ContentObject.buildContentObject(middleName, "middle".getBytes()));
		Interest minInterest = new Interest(ContentName.fromNative("/repoTest/1"));
		minInterest.minSuffixComponents(4);
		checkData(repo, minInterest, "long");
		Interest maxInterest = new Interest(ContentName.fromNative("/repoTest/1"));
		maxInterest.maxSuffixComponents(3);
		checkData(repo, maxInterest, "short");
		Interest middleInterest = new Interest(ContentName.fromNative("/repoTest/1"));
		middleInterest.maxSuffixComponents(4);
		middleInterest.minSuffixComponents(3);
		checkData(repo, middleInterest, "middle");

		//adding in fast name enumeration response tests
		System.out.println("Repotest - testing fast name enumeration response");

		//building names for tests
		ContentName nerpre = ContentName.fromNative("/testFastNameEnumeration");
		ContentName ner = new ContentName(nerpre, "name1");
		ContentName nername1 = ContentName.fromNative("/name1");
		ContentName ner2 = new ContentName(nerpre, "name2");
		ContentName nername2 = ContentName.fromNative("/name2");
		ContentName ner3 = new ContentName(nerpre, "longer");
		ner3 = new ContentName(ner3, "name3");
		ContentName nername3 = ContentName.fromNative("/longer");
		NameEnumerationResponse neresponse = null;

		//send initial interest to make sure namespace is empty
		//interest flag will not be set for a fast response since there isn't anything in the index yet
		
		Interest interest = new Interest(new ContentName(nerpre, COMMAND_MARKER_BASIC_ENUMERATION));
		ContentName responseName = ContentName.ROOT;
		Log.info("RFSTEST: Name enumeration prefix:{0}", interest.name());
		neresponse = repo.getNamesWithPrefix(interest, responseName);
		Assert.assertTrue(neresponse == null || neresponse.hasNames()==false);
		//now saving the first piece of content in the repo.  interest flag not set, so it should not get an object back
		neresponse = repo.saveContent(ContentObject.buildContentObject(ner, "FastNameRespTest".getBytes()));
		Assert.assertTrue(neresponse==null || neresponse.hasNames()==false);
		//now checking with the prefix that the first name is in
		neresponse = repo.getNamesWithPrefix(interest, responseName);
		Assert.assertTrue(neresponse.getNames().contains(nername1));

		Assert.assertTrue(neresponse.getPrefix().contains(CommandMarker.COMMAND_MARKER_BASIC_ENUMERATION.getBytes()));
		Assert.assertTrue(neresponse.getTimestamp()!=null);
		//now call get names with prefix again to set interest flag
		//have to use the version from the last response (or at least a version after the last write
		interest = Interest.last(new ContentName(neresponse.getPrefix(), neresponse.getTimestamp()), null, null);
		//the response should be null and the flag set
		neresponse = repo.getNamesWithPrefix(interest, responseName);
		Assert.assertTrue(neresponse==null || neresponse.hasNames()==false);
		//save content.  if the flag was set, we should get an enumeration response
		neresponse = repo.saveContent(ContentObject.buildContentObject(ner2, "FastNameRespTest".getBytes()));
		Assert.assertTrue(neresponse.getNames().contains(nername1));
		Assert.assertTrue(neresponse.getNames().contains(nername2));
		Assert.assertTrue(neresponse.getPrefix().contains(CommandMarker.COMMAND_MARKER_BASIC_ENUMERATION.getBytes()));
		Assert.assertTrue(neresponse.getTimestamp()!=null);
		
		//need to reconstruct the interest again
		interest = Interest.last(new ContentName(neresponse.getPrefix(), neresponse.getTimestamp()), null, null);
		//another interest to set interest flag, response should be null
		neresponse = repo.getNamesWithPrefix(interest, responseName);
		Assert.assertTrue(neresponse == null || neresponse.hasNames()==false);
		//interest flag should now be set, so when i add something - this is a longer name, i should be handed back an object
		neresponse = repo.saveContent(ContentObject.buildContentObject(ner3, "FastNameRespTest".getBytes()));
		Assert.assertTrue(neresponse.getNames().contains(nername1));
		Assert.assertTrue(neresponse.getNames().contains(nername2));
		Assert.assertTrue(neresponse.getNames().contains(nername3));
		Assert.assertTrue(neresponse.getPrefix().contains(CommandMarker.COMMAND_MARKER_BASIC_ENUMERATION.getBytes()));
		Assert.assertTrue(neresponse.getTimestamp()!=null);
		
		repo.shutDown();
	}
	
	public void testReinitialization(RepositoryStore repo) throws Exception {
		
		System.out.println("Repotest - Testing reinitialization of repo");
		// Since we have 2 pieces of data with the name "longName" we need to compute the
		// digest to make sure we get the right data.
		longName = new ContentName(longName, longNameDigest);
		checkDataWithDigest(repo, longName, "Long name!");
		checkData(repo, badCharName, "Funny characters!");
		checkData(repo, badCharLongName, "Long and funny");
		Interest vnInterest = new Interest(versionedName);
		vnInterest.maxSuffixComponents(1);
		checkData(repo, vnInterest, "version");
		checkData(repo, segmentedName1, "segment1");
		checkData(repo, segmentedName223, "segment223");
		vnInterest = new Interest(versionedNameNormal);
		vnInterest.maxSuffixComponents(1);
		checkData(repo, vnInterest, "version-normal");
		for (Long i=SegmentationProfile.baseSegment(); i<5; i++) {
			ContentName segmented = SegmentationProfile.segmentName(versionedNameNormal, i);
			String segmentContent = "segment"+ new Long(i).toString();
			checkData(repo, segmented, segmentContent);
		}
	}
	
	private void checkData(RepositoryStore repo, ContentName name, String data) throws RepositoryException {
		checkData(repo, new Interest(name), data);
	}
	
	private void checkDataWithDigest(RepositoryStore repo, ContentName name, String data) throws RepositoryException {
		// When generating an Interest for the exact name with content digest, need to set maxSuffixComponents
		// to 0, signifying that name ends with explicit digest
		Interest interest = new Interest(name);
		interest.maxSuffixComponents(0);
		checkData(repo, interest, data);
	}

	private void checkData(RepositoryStore repo, Interest interest, String data) throws RepositoryException {
		ContentObject testContent = repo.getContent(interest);
		Assert.assertFalse(testContent == null);
		Assert.assertEquals(data, new String(testContent.content()));		
	}
	
	private void checkDataAndPublisher(RepositoryStore repo, ContentName name, String data, PublisherPublicKeyDigest publisher) 
				throws RepositoryException {
		Interest interest = new Interest(name, new PublisherID(publisher));
		ContentObject testContent = repo.getContent(interest);
		Assert.assertFalse(testContent == null);
		Assert.assertEquals(data, new String(testContent.content()));
		Assert.assertTrue(testContent.signedInfo().getPublisherKeyID().equals(publisher));
	}
	
	private ContentObject addRelativeTestContent(RepositoryStore repo, String prefix, String name) throws RepositoryException, 
				MalformedContentNameStringException {
		ContentName name1 = ContentName.fromNative(prefix + "/aaa" + name);
		ContentObject content1 = ContentObject.buildContentObject(name1, "aaa".getBytes());
		repo.saveContent(content1);
		ContentName name2 = ContentName.fromNative(prefix + "/bbb" + name);
		repo.saveContent(ContentObject.buildContentObject(name2, "bbb".getBytes()));
		ContentName name3= ContentName.fromNative(prefix + "/ccc" + name);
		repo.saveContent(ContentObject.buildContentObject(name3, "ccc".getBytes()));
		ContentName name4= ContentName.fromNative(prefix + "/ddd" + name);
		repo.saveContent(ContentObject.buildContentObject(name4, "ddd".getBytes()));
		ContentName name5= ContentName.fromNative(prefix + "/eee" + name);
		repo.saveContent(ContentObject.buildContentObject(name5, "eee".getBytes()));
		ContentName name6= ContentName.fromNative(prefix + "/fff" + name);
		repo.saveContent(ContentObject.buildContentObject(name6, "fff".getBytes()));
		return content1;
	}
}
