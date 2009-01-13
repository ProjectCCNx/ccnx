package test.ccn.network.daemons.repo;

import java.io.IOException;
import java.util.Arrays;

import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import test.ccn.library.LibraryTestBase;

import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.query.Interest;
import com.parc.ccn.data.security.PublisherID;
import com.parc.ccn.data.security.PublisherKeyID;

/**
 * 
 * @author rasmusse
 * 
 * To run this test you must first unzip repotest.zip into a
 * directory and then run the ccnd and the repository with that
 * directory as its root.
 */

public class RepoReadTest extends LibraryTestBase {
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
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
		String tooLongName = "0123456789";
		for (int i = 0; i < 30; i++)
			tooLongName += "0123456789";
		ContentName longName = ContentName.fromNative("/repoTest/" + tooLongName);
		ContentName badCharName = ContentName.fromNative("/repoTest/" + "*x?y<z>u");
		ContentName badCharLongName = ContentName.fromNative("/repoTest/" + tooLongName + "*x?y<z>u");
		byte [] publisher1 = new byte[32];
		Arrays.fill(publisher1, (byte)1);
		PublisherKeyID pkid1 = new PublisherKeyID(publisher1);
		PublisherID pubID1 = new PublisherID(pkid1.id(), PublisherID.PublisherType.KEY);
		byte [] publisher2 = new byte[32];
		Arrays.fill(publisher2, (byte)2);
		PublisherKeyID pkid2 = new PublisherKeyID(publisher2);
		PublisherID pubID2 = new PublisherID(pkid2.id(), PublisherID.PublisherType.KEY);
		checkData(longName, "Long name!");
		checkData(badCharName, "Funny characters!");
		checkData(badCharLongName, "Long and funny");
		checkDataAndPublisher(name, "Testing2", pubID1);
		checkDataAndPublisher(name, "Testing2", pubID2);
	}
	
	private void checkData(ContentName name, String data) throws IOException, InterruptedException{
		checkData(new Interest(name), data);
	}
	private void checkData(Interest interest, String data) throws IOException, InterruptedException{
		ContentObject testContent = library.get(interest, 10000);
		Assert.assertFalse(testContent == null);
		Assert.assertEquals(data, new String(testContent.content()));		
	}
	private void checkDataAndPublisher(ContentName name, String data, PublisherID publisher) 
				throws IOException, InterruptedException {
		Interest interest = new Interest(name, publisher);
		ContentObject testContent = library.get(interest, 10000);
		Assert.assertFalse(testContent == null);
		Assert.assertEquals(data, new String(testContent.content()));
		Assert.assertTrue(Arrays.equals(testContent.authenticator().publisher(), publisher.id()));
	}
}
