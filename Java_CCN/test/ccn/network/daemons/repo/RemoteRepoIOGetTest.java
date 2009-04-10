package test.ccn.network.daemons.repo;

import java.util.Arrays;

import org.junit.Assert;
import org.junit.Test;

import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.query.Interest;
import com.parc.ccn.library.profiles.SegmentationProfile;

/**
 * For now to run this you need to first run the RepoIoPutTest, then restart
 * the local ccnd, then run this
 * 
 * @author rasmusse
 *
 */

public class RemoteRepoIOGetTest extends RepoTestBase {
	
	@Test
	public void testPolicyViaCCN() throws Exception {
		checkContent("/repoTest/data3", false);
		checkContent("/testNameSpace/data1", true);
	}
	
	@Test
	public void testWriteToRepo() throws Exception {
		System.out.println("Testing writing streams to repo");
		byte [] data = new byte[4000];
		byte value = 1;
		for (int i = 0; i < data.length; i++)
			data[i] = value++;
		
		for (int i = 0; i < 40; i++) {
			byte [] testData = new byte[100];
			System.arraycopy(data, i * 100, testData, 0, 100);
			checkData(ContentName.fromNative("/testNameSpace/stream"), testData, i);
		}
	}
	
	private ContentObject checkContent(String contentName, boolean expected) throws Exception {
		return checkContent(ContentName.fromNative(contentName), expected);
	}
	
	private ContentObject checkContent(ContentName contentName, boolean expected) throws Exception {
		Interest interest = new Interest(contentName);
		ContentObject co = getLibrary.get(interest, 10000);
		if (expected)
			Assert.assertTrue(co != null);
		else
			Assert.assertTrue(co == null);
		return co;
	}
	
	private void checkData(ContentName currentName, byte[] testData, int i) throws Exception {
		ContentName segmentedName = SegmentationProfile.segmentName(currentName, i);
		ContentObject co = checkContent(segmentedName, true);
		Assert.assertTrue(Arrays.equals(testData, co.content()));
	}
}
