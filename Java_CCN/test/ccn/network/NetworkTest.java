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
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.query.CCNInterestListener;
import com.parc.ccn.data.query.Interest;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.library.CCNSegmenter;

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
	protected static CCNLibrary library = null;
	private Semaphore sema = new Semaphore(0);
	private boolean gotData = false;
	Interest testInterest = null;
	
	static {
		try {
			library = CCNLibrary.open();
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
		CCNSegmenter segmenter = new CCNSegmenter("/networkTest", library);
		testInterest = new Interest("/networkTest/aaa");
		TestListener tl = new TestListener();
		library.expressInterest(testInterest, tl);
		// Sleep long enough that the interest must be re-expressed
		Thread.sleep(8000);  
		segmenter.put("/networkTest/aaa", "aaa");
		sema.tryAcquire(4000, TimeUnit.MILLISECONDS);
		Assert.assertTrue(gotData);
	}
	
	class TestListener implements CCNInterestListener {

		public Interest handleContent(ArrayList<ContentObject> results,
				Interest interest) {
			Assert.assertFalse(results == null);
			for (ContentObject co : results) {
				Assert.assertEquals("aaa", new String(co.content()));
				gotData = true;
			}
			sema.release();
			
			/*
			 * Test call of cancel in handler doesn't hang
			 */
			library.cancelInterest(testInterest, this);
			return null;
		}
	}
	
}
