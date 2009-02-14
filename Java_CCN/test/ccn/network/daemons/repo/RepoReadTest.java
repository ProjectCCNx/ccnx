package test.ccn.network.daemons.repo;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

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
import com.parc.ccn.library.io.repo.RepositoryOutputStream;
import com.parc.ccn.network.daemons.repo.RFSImpl;
import com.parc.ccn.network.daemons.repo.Repository;

/**
 * 
 * @author rasmusse
 * 
 * To run this test you must first unzip repotest.zip into the directory "repotest"
 * and then run the ccnd and the repository with that directory as its root.
 */

public class RepoReadTest extends RepoTestBase {
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		RepoTestBase.setUpBeforeClass();
	}
	
	@AfterClass
	public static void cleanup() throws Exception {
	}
	
	@Before
	public void setUp() throws Exception {
	}
	
	@Test
	public void testReadViaRepo() throws Throwable {
		ContentName name = ContentName.fromNative("/repoTest/data1");
		ContentName clashName = ContentName.fromNative("/" + RFSImpl.META_DIR + "/repoTest/data1");
		ContentName digestName = new ContentName(name, ContentObject.contentDigest("Testing2"));
		String tooLongName = "0123456789";
		for (int i = 0; i < 30; i++)
			tooLongName += "0123456789";
		ContentName longName = ContentName.fromNative("/repoTest/" + tooLongName);
		ContentName badCharName = ContentName.fromNative("/repoTest/" + "*x?y<z>u");
		ContentName badCharLongName = ContentName.fromNative("/repoTest/" + tooLongName + "*x?y<z>u");
		PublisherKeyID pkid1 = new PublisherKeyID("9s4o5j263snpdl59phc5vf0jhpqgdtghg155smuo2gbnk5ui8f3");
		//PublisherKeyID pkid2 = new PublisherKeyID("1paij2p7setof6ognk3b34q0hesnv1jpov07h9qvqnveqahv9ml2");
		PublisherKeyID pkid2 = new PublisherKeyID("-6ldct6o3h27gp7f8bsksr5veh380uc670voem50580h5le0m9au");
		
		checkData(name, "Here's my data!");
		checkData(clashName, "Clashing Name");
		checkData(digestName, "Testing2");
		checkData(longName, "Long name!");
		checkData(badCharName, "Funny characters!");
		checkData(badCharLongName, "Long and funny");
		checkDataAndPublisher(name, "Testing2", pkid1);
		checkDataAndPublisher(name, "Testing2", pkid2);
	}
	
	@Test
	public void testPolicyViaCCN() throws Exception {
		System.out.println("Testing namespace policy setting");
		checkNameSpace("/repoTest/data2", true);
		FileInputStream fis = new FileInputStream(_topdir + "/test/ccn/network/daemons/repo/policyTest.xml");
		byte [] content = new byte[fis.available()];
		fis.read(content);
		fis.close();
		RepositoryOutputStream ros = new RepositoryOutputStream(ContentName.fromNative(_globalPrefix + '/' + 
				_repoName + '/' + Repository.REPO_POLICY), null, null, null, library);
		ros.write(content, 0, content.length);
		ros.close();
		Thread.sleep(1000);
		checkNameSpace("/repoTest/data3", false);
		checkNameSpace("/testNameSpace/data1", true);
	}
	
	private void checkData(ContentName name, String data) throws IOException, InterruptedException{
		checkData(new Interest(name), data);
	}
	private void checkData(Interest interest, String data) throws IOException, InterruptedException{
		ContentObject testContent = library.get(interest, 10000);
		Assert.assertFalse(testContent == null);
		Assert.assertEquals(data, new String(testContent.content()));		
	}
	private void checkDataAndPublisher(ContentName name, String data, PublisherKeyID publisher) 
				throws IOException, InterruptedException {
		Interest interest = new Interest(name, new PublisherID(publisher));
		ContentObject testContent = library.get(interest, 10000);
		Assert.assertFalse(testContent == null);
		Assert.assertEquals(data, new String(testContent.content()));
		Assert.assertTrue(testContent.signedInfo().publisherKeyID().equals(publisher));
	}
	
	private void checkNameSpace(String contentName, boolean expected) throws Exception {
		ContentName name = ContentName.fromNative(contentName);
		RepositoryOutputStream ros = new RepositoryOutputStream(name, null, null, null, library);
		byte [] data = "Testing 1 2 3".getBytes();
		ros.write(data, 0, data.length);
		ContentName baseName = ros.getBaseName();
		ros.close();
		Thread.sleep(4000);
		File testFile = new File("repoTest" + baseName);
		if (expected)
			Assert.assertTrue(testFile.exists());
		else
			Assert.assertFalse(testFile.exists());
	}
}
