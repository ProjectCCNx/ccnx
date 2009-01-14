package test.ccn.network.daemons.repo;

import java.io.File;
import java.security.KeyPair;
import java.security.KeyPairGenerator;

import org.apache.commons.io.FileUtils;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.query.Interest;
import com.parc.ccn.data.security.PublisherID;
import com.parc.ccn.data.security.PublisherKeyID;
import com.parc.ccn.library.CCNLibrary;
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

public class RFSTest {
	
	private static String _fileTestDir = "fileTestDir";
	private static File _fileTest;
	
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		_fileTest = new File(_fileTestDir);
		FileUtils.deleteDirectory(_fileTest);
		_fileTest.mkdirs();
	}
	
	@AfterClass
	public static void cleanup() throws Exception {
		FileUtils.deleteDirectory(_fileTest);
	}
	
	@Before
	public void setUp() throws Exception {
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
		repo.initialize(new String[] {"-root", _fileTestDir});
		
		System.out.println("Repotest - Testing basic data");
		ContentName name = ContentName.fromNative("/repoTest/data1");
		ContentObject content = CCNLibrary.getContent(name, "Here's my data!".getBytes());
		repo.saveContent(content);
		checkData(repo, name, "Here's my data!");
		
		/**
		 * TODO - need to figure out some way to test that saving identical content more
		 * than once doesn't result in multiple copies of the data
		 */
		
		System.out.println("Repotest - Testing clashing data");
		ContentName clashName = ContentName.fromNative("/" + RFSImpl.META_DIR + "/repoTest/data1");
		repo.saveContent(CCNLibrary.getContent(clashName, "Clashing Name".getBytes()));
		checkData(repo, clashName, "Clashing Name");
		
		System.out.println("Repotest - Testing multiple digests for same data");
		ContentObject digest2 = CCNLibrary.getContent(name, "Testing2".getBytes());
		repo.saveContent(digest2);
		ContentName digestName = new ContentName(name, digest2.contentDigest());
		checkData(repo, digestName, "Testing2");
		
		System.out.println("Repotest - Testing same digest for different data and/or publisher");
		KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
		kpg.initialize(512); // go for fast
		KeyPair pair1 = kpg.generateKeyPair();
		PublisherKeyID pubKey1 = new PublisherKeyID(pair1.getPublic());
		ContentObject digestSame1 = CCNLibrary.getContent(name, "Testing2".getBytes(), pubKey1);
		repo.saveContent(digestSame1);
		KeyPair pair2 = kpg.generateKeyPair();
		PublisherKeyID pubKey2 = new PublisherKeyID(pair2.getPublic());
		ContentObject digestSame2 = CCNLibrary.getContent(name, "Testing2".getBytes(), pubKey2);
		repo.saveContent(digestSame2);
		checkDataAndPublisher(repo, name, "Testing2", pubKey1);
		checkDataAndPublisher(repo, name, "Testing2", pubKey2);
		
		System.out.println("Repotest - Testing too long data");
		String tooLongName = "0123456789";
		for (int i = 0; i < 30; i++)
			tooLongName += "0123456789";
		ContentName longName = ContentName.fromNative("/repoTest/" + tooLongName);
		repo.saveContent(CCNLibrary.getContent(longName, "Long name!".getBytes()));
		checkData(repo, longName, "Long name!");
		digest2 = CCNLibrary.getContent(longName, "Testing2".getBytes());
		repo.saveContent(digest2);
		digestName = new ContentName(longName, digest2.contentDigest());
		checkData(repo, digestName, "Testing2");
		
		System.out.println("Repotest - Testing invalid characters in name");
		ContentName badCharName = ContentName.fromNative("/repoTest/" + "*x?y<z>u");
		repo.saveContent(CCNLibrary.getContent(badCharName, "Funny characters!".getBytes()));
		checkData(repo, badCharName, "Funny characters!");
		ContentName badCharLongName = ContentName.fromNative("/repoTest/" + tooLongName + "*x?y<z>u");
		repo.saveContent(CCNLibrary.getContent(badCharLongName, "Long and funny".getBytes()));
		checkData(repo, badCharLongName, "Long and funny");
		
		System.out.println("Repotest - Testing different kinds of interests");
		ContentName name1 = ContentName.fromNative("/repoTest/nextTest/aaa");
		ContentObject content1 = CCNLibrary.getContent(name1, "aaa".getBytes());
		repo.saveContent(content1);
		ContentName name2 = ContentName.fromNative("/repoTest/nextTest/bbb");
		repo.saveContent(CCNLibrary.getContent(name2, "bbb".getBytes()));
		ContentName name3= ContentName.fromNative("/repoTest/nextTest/ccc");
		repo.saveContent(CCNLibrary.getContent(name3, "ccc".getBytes()));
		ContentName name4= ContentName.fromNative("/repoTest/nextTest/ddd");
		repo.saveContent(CCNLibrary.getContent(name4, "ddd".getBytes()));
		ContentName name5= ContentName.fromNative("/repoTest/nextTest/eee");
		repo.saveContent(CCNLibrary.getContent(name5, "eee".getBytes()));
		checkData(repo, Interest.next(new ContentName(name1, content1.contentDigest(), 2)), "bbb");
		checkData(repo, Interest.last(new ContentName(name1, content1.contentDigest(), 2)), "eee");
		checkData(repo, Interest.next(new ContentName(name1, content1.contentDigest(), 2), 
				new byte [][] {"bbb".getBytes(), "ccc".getBytes()}), "ddd");
		
		System.out.println("Repotest - Testing different kinds of interests in a mixture of encoded/standard data");
		ContentName nonLongName = ContentName.fromNative("/repoTestLong/nextTestLong/aaa");
		ContentObject nonLongContent = CCNLibrary.getContent(nonLongName, "aaa".getBytes());
		repo.saveContent(nonLongContent);
		ContentName longName2 = ContentName.fromNative("/repoTestLong/nextTestLong/bbb/" + tooLongName);
		repo.saveContent(CCNLibrary.getContent(longName2, "bbb".getBytes()));
		ContentName nonLongName2 = ContentName.fromNative("/repoTestLong/nextTestLong/ccc");
		repo.saveContent(CCNLibrary.getContent(nonLongName2, "ccc".getBytes()));
		ContentName longName3 = ContentName.fromNative("/repoTestLong/nextTestLong/ddd/" + tooLongName);
		repo.saveContent(CCNLibrary.getContent(longName3, "ddd".getBytes()));
		ContentName longName4 = ContentName.fromNative("/repoTestLong/nextTestLong/eee/" + tooLongName);
		repo.saveContent(CCNLibrary.getContent(longName4, "eee".getBytes()));
		checkData(repo, Interest.next(new ContentName(nonLongName, nonLongContent.contentDigest(), 2)), "bbb");
		checkData(repo, Interest.last(new ContentName(nonLongName, nonLongContent.contentDigest(), 2)), "eee");
		checkData(repo, Interest.next(new ContentName(nonLongName, nonLongContent.contentDigest(), 2), 
				new byte [][] {"bbb".getBytes(), "ccc".getBytes()}), "ddd");
		
		System.out.println("Repotest - Testing reinitialization of repo");
		repo = new RFSImpl();
		repo.initialize(new String[] {"-root", _fileTestDir});
		checkData(repo, clashName, "Clashing Name");
		checkData(repo, longName, "Long name!");
		checkData(repo, badCharName, "Funny characters!");
		checkData(repo, badCharLongName, "Long and funny");
		checkDataAndPublisher(repo, name, "Testing2", pubKey1);
		checkDataAndPublisher(repo, name, "Testing2", pubKey2);
	}
	
	private void checkData(Repository repo, ContentName name, String data) throws RepositoryException {
		checkData(repo, new Interest(name), data);
	}
	private void checkData(Repository repo, Interest interest, String data) throws RepositoryException {
		ContentObject testContent = repo.getContent(interest);
		Assert.assertFalse(testContent == null);
		Assert.assertEquals(data, new String(testContent.content()));		
	}
	private void checkDataAndPublisher(Repository repo, ContentName name, String data, PublisherKeyID publisher) 
				throws RepositoryException {
		Interest interest = new Interest(name, new PublisherID(publisher));
		ContentObject testContent = repo.getContent(interest);
		Assert.assertFalse(testContent == null);
		Assert.assertEquals(data, new String(testContent.content()));
		Assert.assertTrue(testContent.authenticator().publisherKeyID().equals(publisher));
	}
}
