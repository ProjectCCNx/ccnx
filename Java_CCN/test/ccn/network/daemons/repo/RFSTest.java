package test.ccn.network.daemons.repo;

import java.io.File;

import org.apache.commons.io.FileUtils;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.query.Interest;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.network.daemons.repo.RFSImpl;
import com.parc.ccn.network.daemons.repo.RFSLocks;
import com.parc.ccn.network.daemons.repo.Repository;
import com.parc.ccn.network.daemons.repo.RepositoryException;

/**
 * 
 * @author rasmusse
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
		repo.saveContent(CCNLibrary.getContent(name, "Here's my data!".getBytes()));
		checkData(repo, name, "Here's my data!");
		
		System.out.println("Repotest - Testing clashing data");
		ContentName clashName = ContentName.fromNative("/" + RFSImpl.META_DIR + "/repoTest/data1");
		repo.saveContent(CCNLibrary.getContent(clashName, "Clashing Name".getBytes()));
		checkData(repo, clashName, "Clashing Name");
		
		System.out.println("Repotest - Testing multiple digests for same data");
		ContentObject digest2 = CCNLibrary.getContent(name, "Testing2".getBytes());
		repo.saveContent(digest2);
		ContentName digestName = new ContentName(name, digest2.contentDigest());
		checkData(repo, digestName, "Testing2");
		
		System.out.println("Repotest - Testing too long data");
		String tooLongName = "0123456789";
		for (int i = 0; i < 30; i++)
			tooLongName += "0123456789";
		ContentName longName = ContentName.fromNative("/repoTest/" + tooLongName);
		repo.saveContent(CCNLibrary.getContent(longName, "Long name!".getBytes()));
		checkData(repo, longName, "Long name!");
	}
	
	private void checkData(Repository repo, ContentName name, String data) throws RepositoryException {
		Interest interest = new Interest(name);
		ContentObject testContent = repo.getContent(interest);
		Assert.assertFalse(testContent == null);
		Assert.assertEquals(new String(testContent.content()), data);		
	}
}
