package test.ccn.network;

import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.parc.ccn.config.ConfigurationException;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.query.CCNInterestListener;
import com.parc.ccn.data.query.Interest;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.library.io.CCNWriter;
import com.parc.ccn.library.profiles.SegmentationProfile;

/**
 * This should eventually have more tests but for now at least we will
 * test the re-expression of interests
 * 
 * Note - this test requires ccnd to be running
 * 
 * @author rasmusse
 *
 */

public class NetworkTest {
	protected static CCNLibrary putLibrary = null;
	protected static CCNLibrary getLibrary = null;
	private Semaphore sema = new Semaphore(0);
	private boolean gotData = false;
	Interest testInterest = null;
	
	static {
		try {
			putLibrary = CCNLibrary.open();
			getLibrary = CCNLibrary.open();
		} catch (ConfigurationException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
	}

	@Before
	public void setUp() throws Exception {
	}

	@Test
	public void testNetworkManager() throws Exception {
		
		/*
		 * Test re-expression of interest
		 */
		CCNWriter writer = new CCNWriter("/networkTest", putLibrary);
		testInterest = new Interest("/networkTest/aaa");
		TestListener tl = new TestListener();
		getLibrary.expressInterest(testInterest, tl);
		Thread.sleep(80);  
		writer.put("/networkTest/aaa", "aaa");
		sema.tryAcquire(4000, TimeUnit.MILLISECONDS);
		Assert.assertTrue(gotData);
	}
	
	@Test
	public void testNetworkManagerFixedPrefix() throws Exception {
		
		/*
		 * Test re-expression of interest
		 */
		CCNWriter writer = new CCNWriter(putLibrary);
		testInterest = new Interest("/networkTest/ddd");
		TestListener tl = new TestListener();
		getLibrary.expressInterest(testInterest, tl);
		Thread.sleep(80);  
		writer.put("/networkTest/ddd", "ddd");
		sema.tryAcquire(4000, TimeUnit.MILLISECONDS);
		Assert.assertTrue(gotData);
	}
	
	@Test
	public void testNetworkManagerBackwards() throws Exception {
		
		/*
		 * Test re-expression of interest
		 */
		CCNWriter writer = new CCNWriter("/networkTest", putLibrary);
		testInterest = new Interest("/networkTest/bbb");
		TestListener tl = new TestListener();
		writer.put("/networkTest/bbb", "bbb");
		Thread.sleep(80);  
		getLibrary.expressInterest(testInterest, tl);
		sema.tryAcquire(4000, TimeUnit.MILLISECONDS);
		Assert.assertTrue(gotData);
	}
	
	@Test
	public void testFreshnessSeconds() throws Exception {
		CCNWriter writer = new CCNWriter("/networkTest", putLibrary);
		writer.put("/networkTest/freshnessTest", "freshnessTest", 3);
		Thread.sleep(80);
		ContentObject co = getLibrary.get(ContentName.fromNative("/networkTest/freshnessTest"), 1000);
		Assert.assertFalse(co == null);
		Thread.sleep(4000);
		co = getLibrary.get(ContentName.fromNative("/networkTest/freshnessTest"), 1000);
		Assert.assertTrue(co == null);
	}

	@Test
	public void testInterestReexpression() throws Exception {
		
		/*
		 * Test re-expression of interest
		 */
		CCNWriter writer = new CCNWriter("/networkTest", putLibrary);
		testInterest = new Interest("/networkTest/ccc");
		TestListener tl = new TestListener();
		getLibrary.expressInterest(testInterest, tl);
		// Sleep long enough that the interest must be re-expressed
		Thread.sleep(8000);  
		writer.put("/networkTest/ccc", "ccc");
		sema.tryAcquire(4000, TimeUnit.MILLISECONDS);
		Assert.assertTrue(gotData);
	}
	
	class TestListener implements CCNInterestListener {

		public Interest handleContent(ArrayList<ContentObject> results,
				Interest interest) {
			Assert.assertFalse(results == null);
			for (ContentObject co : results) {
				ContentName nameBase = SegmentationProfile.segmentRoot(co.name());
				Assert.assertEquals(nameBase.stringComponent(nameBase.count()-1), new String(co.content()));
				gotData = true;
			}
			sema.release();
			
			/*
			 * Test call of cancel in handler doesn't hang
			 */
			getLibrary.cancelInterest(testInterest, this);
			return null;
		}
	}
	
}
