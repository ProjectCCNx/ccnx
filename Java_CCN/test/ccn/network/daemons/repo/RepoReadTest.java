package test.ccn.network.daemons.repo;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.SignatureException;
import java.util.logging.Level;

import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import test.ccn.library.LibraryTestBase;

import com.parc.ccn.Library;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.MalformedContentNameStringException;
import com.parc.ccn.data.query.Interest;
import com.parc.ccn.data.security.PublisherID;
import com.parc.ccn.data.security.PublisherKeyID;
import com.parc.ccn.network.daemons.repo.RFSImpl;
import com.parc.ccn.network.daemons.repo.Repository;

/**
 * 
 * @author rasmusse
 * 
 * To run this test you must first unzip repotest.zip into the directory "repotest"
 * and then run the ccnd and the repository with that directory as its root.
 */

public class RepoReadTest extends LibraryTestBase {
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		// Set debug level: use for more FINE, FINER, FINEST for debug-level tracing
		Library.logger().setLevel(Level.INFO);
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
		PublisherKeyID pkid1 = new PublisherKeyID("141qt881s6o92i9fneidpvdsu5sm00cfu0g7u5bbk3o3o6l4s57v");
		PublisherKeyID pkid2 = new PublisherKeyID("12u9sl8h2oi5f132f97lon64l5al3bf7tj8p98lqgmdc5ovucahh");
		
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
		String hostName;
		try {
			hostName = InetAddress.getLocalHost().getHostName();
		} catch (UnknownHostException e) {
			hostName = InetAddress.getLocalHost().getHostAddress();
		}
		FileInputStream fis = new FileInputStream("test/ccn/network/daemons/repo/PolicyTest.xml");
		byte [] content = new byte[fis.available()];
		fis.read(content);
		fis.close();
		
		System.out.println("Testing namespace policy setting");
		checkNameSpace("/repoTest/data2", true);
		library.put(ContentName.fromNative("/" + hostName + Repository.REPO_POLICY), content);
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
		Assert.assertTrue(testContent.authenticator().publisherKeyID().equals(publisher));
	}
	
	private void checkNameSpace(String contentName, boolean expected) throws MalformedContentNameStringException, 
				SignatureException, IOException, InterruptedException {
		ContentName name = ContentName.fromNative(contentName);
		library.put(name, "Testing 1 2 3".getBytes());
		Thread.sleep(1000);
		File testFile = new File("repoTest" + contentName);
		if (expected)
			Assert.assertTrue(testFile.exists());
		else
			Assert.assertFalse(testFile.exists());
	}
}
