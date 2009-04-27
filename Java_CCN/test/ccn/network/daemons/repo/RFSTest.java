package test.ccn.network.daemons.repo;

import java.io.File;
import java.security.InvalidParameterException;

import org.apache.commons.io.FileUtils;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.query.Interest;
import com.parc.ccn.data.security.PublisherID;
import com.parc.ccn.data.security.PublisherPublicKeyDigest;
import com.parc.ccn.library.profiles.SegmentationProfile;
import com.parc.ccn.library.profiles.VersioningProfile;
import com.parc.ccn.network.daemons.repo.RFSImpl;
import com.parc.ccn.network.daemons.repo.RFSLocks;
import com.parc.ccn.network.daemons.repo.Repository;
import com.parc.ccn.network.daemons.repo.RepositoryException;

/**
 * 
 * @author rasmusse
 * 
 * Because it uses the default KeyManager, this test must be run
 * with ccnd running.
 *
 */

public class RFSTest extends RepoTestBase {
	
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		RepoTestBase.setUpBeforeClass();
		_fileTest = new File(_fileTestDir);
		FileUtils.deleteDirectory(_fileTest);
		_fileTest.mkdirs();
	}
	
	// Purposely don't delete the directory so we can build the zip file
	// for the IO test in ant and also can examine it in case of failure
	//@AfterClass
	//public static void cleanup() throws Exception {
	//	FileUtils.deleteDirectory(_fileTest);
	//}
	
	@Before
	public void setUp() throws Exception {
		super.setUp();
	}
	
	@Test
	public void testLocks() throws Exception {
		String testLockDir = _fileTestDir + File.separator + RFSImpl.META_DIR;
		RFSLocks locker = new RFSLocks(testLockDir);
		String testFileName = _fileTestDir + File.separator + "testFile";
		File testFile = new File(testFileName);
		locker.lock(testFileName);
		testFile.createNewFile();
		locker = new RFSLocks(testLockDir);
		Assert.assertFalse(testFile.exists());
		
		locker.lock(testFileName);
		testFile.createNewFile();
		locker.unLock(testFileName);
		locker = new RFSLocks(testLockDir);
		Assert.assertTrue(testFile.exists());
	}
	
	@Test
	public void testRepo() throws Exception {
		Repository repo = new RFSImpl();
		repo.initialize(new String[] {"-root", _fileTestDir, "-local", _repoName, "-global", _globalPrefix});
		
		System.out.println("Repotest - Testing basic data");
		ContentName name = ContentName.fromNative("/repoTest/data1");
		ContentObject content = ContentObject.buildContentObject(name, "Here's my data!".getBytes());
		repo.saveContent(content);
		checkData(repo, name, "Here's my data!");
		
		/**
		 * TODO - need to figure out some way to test that saving identical content more
		 * than once doesn't result in multiple copies of the data
		 */
		
		System.out.println("Repotest - Testing clashing data");
		ContentName clashName = ContentName.fromNative("/" + RFSImpl.META_DIR + "/repoTest/data1");
		repo.saveContent(ContentObject.buildContentObject(clashName, "Clashing Name".getBytes()));
		checkData(repo, clashName, "Clashing Name");
		
		System.out.println("Repotest - Testing multiple digests for same data");
		ContentObject digest2 = ContentObject.buildContentObject(name, "Testing2".getBytes());
		repo.saveContent(digest2);
		ContentName digestName = new ContentName(name, digest2.contentDigest());
		checkData(repo, digestName, "Testing2");
		
		/*
		 * Broken - commented out until I can figure out how to fix it..
		 * 
		 * 
		System.out.println("Repotest - Testing same digest for different data and/or publisher");
		KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
		kpg.initialize(512); // go for fast
		KeyPair pair1 = kpg.generateKeyPair();
		PublisherPublicKeyDigest pubKey1 = new PublisherPublicKeyDigest(pair1.getPublic());
		ContentObject digestSame1 = ContentObject.buildContentObject(name, "Testing2".getBytes(), pubKey1);
		repo.saveContent(digestSame1);
		KeyPair pair2 = kpg.generateKeyPair();
		PublisherPublicKeyDigest pubKey2 = new PublisherPublicKeyDigest(pair2.getPublic());
		ContentObject digestSame2 = ContentObject.buildContentObject(name, "Testing2".getBytes(), pubKey2);
		repo.saveContent(digestSame2);
		checkDataAndPublisher(repo, name, "Testing2", pubKey1);
		checkDataAndPublisher(repo, name, "Testing2", pubKey2);  */
		
		System.out.println("Repotest - Testing too long data");
		String tooLongName = "0123456789";
		for (int i = 0; i < 30; i++)
			tooLongName += "0123456789";
		ContentName longName = ContentName.fromNative("/repoTest/" + tooLongName);
		repo.saveContent(ContentObject.buildContentObject(longName, "Long name!".getBytes()));
		checkData(repo, longName, "Long name!");
		digest2 = ContentObject.buildContentObject(longName, "Testing2".getBytes());
		repo.saveContent(digest2);
		digestName = new ContentName(longName, digest2.contentDigest());
		checkData(repo, digestName, "Testing2");
		
		System.out.println("Repotest - Testing invalid characters in name");
		ContentName badCharName = ContentName.fromNative("/repoTest/" + "*x?y<z>u");
		repo.saveContent(ContentObject.buildContentObject(badCharName, "Funny characters!".getBytes()));
		checkData(repo, badCharName, "Funny characters!");
		ContentName badCharLongName = ContentName.fromNative("/repoTest/" + tooLongName + "*x?y<z>u");
		repo.saveContent(ContentObject.buildContentObject(badCharLongName, "Long and funny".getBytes()));
		checkData(repo, badCharLongName, "Long and funny");
		
		System.out.println("Repotest - Testing different kinds of interests");
		ContentName name1 = ContentName.fromNative("/repoTest/nextTest/aaa");
		ContentObject content1 = ContentObject.buildContentObject(name1, "aaa".getBytes());
		repo.saveContent(content1);
		ContentName name2 = ContentName.fromNative("/repoTest/nextTest/bbb");
		repo.saveContent(ContentObject.buildContentObject(name2, "bbb".getBytes()));
		ContentName name3= ContentName.fromNative("/repoTest/nextTest/ccc");
		repo.saveContent(ContentObject.buildContentObject(name3, "ccc".getBytes()));
		ContentName name4= ContentName.fromNative("/repoTest/nextTest/ddd");
		repo.saveContent(ContentObject.buildContentObject(name4, "ddd".getBytes()));
		ContentName name5= ContentName.fromNative("/repoTest/nextTest/eee");
		repo.saveContent(ContentObject.buildContentObject(name5, "eee".getBytes()));
		checkData(repo, Interest.next(new ContentName(name1, content1.contentDigest()), 2), "bbb");
		checkData(repo, Interest.last(new ContentName(name1, content1.contentDigest()), 2), "eee");
		checkData(repo, Interest.next(new ContentName(name1, content1.contentDigest()), 
				new byte [][] {"bbb".getBytes(), "ccc".getBytes()}, 2), "ddd");
		
		System.out.println("Repotest - Testing different kinds of interests in a mixture of encoded/standard data");
		ContentName nonLongName = ContentName.fromNative("/repoTestLong/nextTestLong/aaa");
		ContentObject nonLongContent = ContentObject.buildContentObject(nonLongName, "aaa".getBytes());
		repo.saveContent(nonLongContent);
		ContentName longName2 = ContentName.fromNative("/repoTestLong/nextTestLong/bbb/" + tooLongName);
		repo.saveContent(ContentObject.buildContentObject(longName2, "bbb".getBytes()));
		ContentName nonLongName2 = ContentName.fromNative("/repoTestLong/nextTestLong/ccc");
		repo.saveContent(ContentObject.buildContentObject(nonLongName2, "ccc".getBytes()));
		ContentName longName3 = ContentName.fromNative("/repoTestLong/nextTestLong/ddd/" + tooLongName);
		repo.saveContent(ContentObject.buildContentObject(longName3, "ddd".getBytes()));
		ContentName longName4 = ContentName.fromNative("/repoTestLong/nextTestLong/eee/" + tooLongName);
		repo.saveContent(ContentObject.buildContentObject(longName4, "eee".getBytes()));
		checkData(repo, Interest.next(new ContentName(nonLongName, nonLongContent.contentDigest()), 2), "bbb");
		checkData(repo, Interest.last(new ContentName(nonLongName, nonLongContent.contentDigest()), 2), "eee");
		checkData(repo, Interest.next(new ContentName(nonLongName, nonLongContent.contentDigest()), 
				new byte [][] {"bbb".getBytes(), "ccc".getBytes()}, 2), "ddd");
		
		System.out.println("Repotest - testing version and segment files");
		ContentName versionedName = ContentName.fromNative("/repoTest/testVersion");
		versionedName = VersioningProfile.versionName(versionedName);
		repo.saveContent(ContentObject.buildContentObject(versionedName, "version".getBytes()));
		checkData(repo, versionedName, "version");
		ContentName segmentedName1 = SegmentationProfile.segmentName(versionedName, 1);
		repo.saveContent(ContentObject.buildContentObject(segmentedName1, "segment1".getBytes()));
		checkData(repo, segmentedName1, "segment1");
		ContentName segmentedName223 = SegmentationProfile.segmentName(versionedName, 223);
		repo.saveContent(ContentObject.buildContentObject(segmentedName223, "segment223".getBytes()));
		checkData(repo, segmentedName223, "segment223");
		
		System.out.println("Repotest - Testing reinitialization of repo");
		repo = new RFSImpl();
		repo.initialize(new String[] {"-root", _fileTestDir, "-local", _repoName, "-global", _globalPrefix});
		checkData(repo, clashName, "Clashing Name");
		// Since we have 2 pieces of data with the name "longName" we need to compute the
		// digest to make sure we get the right data.
		longName = new ContentName(longName, ContentObject.contentDigest("Long name!"));
		checkData(repo, longName, "Long name!");
		checkData(repo, badCharName, "Funny characters!");
		checkData(repo, badCharLongName, "Long and funny");
		checkData(repo, versionedName, "version");
		checkData(repo, segmentedName1, "segment1");
		checkData(repo, segmentedName223, "segment223");
		//checkDataAndPublisher(repo, name, "Testing2", pubKey1);
		//checkDataAndPublisher(repo, name, "Testing2", pubKey2);
	}
	
	@Test
	public void testPolicy() throws Exception {
		Repository repo = new RFSImpl();
		try {	// Test no version
			repo.initialize(new String[] {"-root", _fileTestDir, "-policy", _topdir + "/test/ccn/network/daemons/repo/badPolicyTest1.xml"});
			Assert.fail("Bad policy file succeeded");
		} catch (InvalidParameterException ipe) {}
		try {	// Test bad version
			repo.initialize(new String[] {"-root", _fileTestDir, "-policy", _topdir + "/test/ccn/network/daemons/repo/badPolicyTest2.xml"});
			Assert.fail("Bad policy file succeeded");
		} catch (InvalidParameterException ipe) {}
		repo.initialize(new String[] {"-root", _fileTestDir, "-policy", 
					_topdir + "/test/ccn/network/daemons/repo/policyTest.xml", "-local", _repoName, "-global", _globalPrefix});
		ContentName name = ContentName.fromNative("/testNameSpace/data1");
		ContentObject content = ContentObject.buildContentObject(name, "Here's my data!".getBytes());
		repo.saveContent(content);
		checkData(repo, name, "Here's my data!");
		ContentName outOfNameSpaceName = ContentName.fromNative("/anotherNameSpace/data1");
		ContentObject oonsContent = ContentObject.buildContentObject(name, "Shouldn't see this".getBytes());
		repo.saveContent(oonsContent);
		ContentObject testContent = repo.getContent(new Interest(outOfNameSpaceName));
		Assert.assertTrue(testContent == null);
	}
	
	private void checkData(Repository repo, ContentName name, String data) throws RepositoryException {
		checkData(repo, new Interest(name), data);
	}
	private void checkData(Repository repo, Interest interest, String data) throws RepositoryException {
		ContentObject testContent = repo.getContent(interest);
		Assert.assertFalse(testContent == null);
		Assert.assertEquals(data, new String(testContent.content()));		
	}
	private void checkDataAndPublisher(Repository repo, ContentName name, String data, PublisherPublicKeyDigest publisher) 
				throws RepositoryException {
		Interest interest = new Interest(name, new PublisherID(publisher));
		ContentObject testContent = repo.getContent(interest);
		Assert.assertFalse(testContent == null);
		Assert.assertEquals(data, new String(testContent.content()));
		Assert.assertTrue(testContent.signedInfo().getPublisherKeyID().equals(publisher));
	}
}
