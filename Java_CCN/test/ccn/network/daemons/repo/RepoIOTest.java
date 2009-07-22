package test.ccn.network.daemons.repo;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;

import javax.xml.stream.XMLStreamException;

import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.MalformedContentNameStringException;
import com.parc.ccn.library.io.CCNDescriptor;
import com.parc.ccn.library.io.CCNVersionedInputStream;
import com.parc.ccn.library.io.repo.RepositoryOutputStream;
import com.parc.ccn.library.profiles.SegmentationProfile;
import com.parc.ccn.network.daemons.repo.Repository;

/**
 * 
 * @author rasmusse
 * 
 * To run this test you must first unzip repotest.zip into the directory "repotest"
 * and then run the ccnd and the repository with that directory as its root.
 */

public class RepoIOTest extends RepoTestBase {
	
	protected static String _repoTestDir = "repotest";
	protected static byte [] data = new byte[4000];
	
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		//Library.logger().setLevel(Level.FINEST);
		RepoTestBase.setUpBeforeClass();
		byte value = 1;
		for (int i = 0; i < data.length; i++)
			data[i] = value++;
		RepositoryOutputStream ros = putLibrary.repoOpen(ContentName.fromNative("/testNameSpace/stream"), 
														null, putLibrary.getDefaultPublisher());
		ros.setBlockSize(100);
		ros.setTimeout(4000);
		ros.write(data, 0, data.length);
		ros.close();
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
		FileInputStream fis = new FileInputStream(_topdir + "/test/ccn/network/daemons/repo/policyTest.xml");
		byte [] content = new byte[fis.available()];
		fis.read(content);
		fis.close();
		RepositoryOutputStream ros = putLibrary.repoOpen(ContentName.fromNative(_globalPrefix + '/' + 
				_repoName + '/' + Repository.REPO_DATA + '/' + Repository.REPO_POLICY), null,
				putLibrary.getDefaultPublisher());
		ros.write(content, 0, content.length);
		ros.close();
		Thread.sleep(4000);
		checkNameSpace("/repoTest/data3", false);
		checkNameSpace("/testNameSpace/data1", true);
	}
	
	@Test
	public void testReadFromRepo() throws Exception {
		System.out.println("Testing reading a stream from the repo");
		Thread.sleep(5000);
		CCNDescriptor input = new CCNDescriptor(ContentName.fromNative("/testNameSpace/stream"), null, getLibrary);
		byte[] testBytes = new byte[data.length];
		input.read(testBytes);
		Assert.assertArrayEquals(data, testBytes);
	}
	
	@Test
	// The purpose of this test is to do versioned reads from repo
	// of data not already in the ccnd cache, thus testing 
	// what happens if we pull latest version and try to read
	// content in order
	public void testVersionedRead() throws InterruptedException, MalformedContentNameStringException, XMLStreamException, IOException {
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
}
