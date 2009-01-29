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
		Interest interest = new Interest("/networkTest/aaa");
		TestListener tl = new TestListener();
		library.expressInterest(interest, tl);
		// Sleep long enough that the interest must be re-expressed
		Thread.sleep(8000);  
		library.put("/networkTest/aaa", "aaa");
		sema.tryAcquire(1000, TimeUnit.MILLISECONDS);
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
			return null;
		}
	}
	
}
