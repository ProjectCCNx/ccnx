package test.ccn.library;

import java.io.IOException;
import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.Queue;

import junit.framework.Assert;

import org.junit.Test;

import com.parc.ccn.config.ConfigurationException;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.MalformedContentNameStringException;
import com.parc.ccn.data.query.Interest;
import com.parc.ccn.data.security.Signature;
import com.parc.ccn.data.security.SignedInfo;
import com.parc.ccn.library.CCNFlowControl;
import com.parc.ccn.library.CCNLibraryTestHarness;
import com.parc.ccn.library.profiles.SegmentationProfile;
import com.parc.ccn.library.profiles.VersioningProfile;

/**
 * 
 * @author rasmusse
 *
 */

public class CCNFlowControlTest {
	static private CCNLibraryTestHarness _library ;
	
	static ContentName name1;
	static ContentName v1;
	static ContentName v2;	

	static {
		try {
			_library = new CCNLibraryTestHarness();
			
			name1 = ContentName.fromNative("/foo/bar");
			v1 = VersioningProfile.versionName(name1);
			// JDT TODO -- sleep is needed because no easy way yet to generate 
			// separate version numbers if generating names fast.
			Thread.sleep(2);
			v2 = VersioningProfile.versionName(name1);	

		} catch (ConfigurationException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		} catch (MalformedContentNameStringException e) {
			e.printStackTrace();
		} catch (InterruptedException e) {
			// no-op
		}
	}
	
	ContentObject obj1 = new ContentObject(name1, new SignedInfo(), "test".getBytes(), (Signature)null);
	ContentName v1s1 = SegmentationProfile.segmentName(v1, 1);
	ContentObject objv1s1 = new ContentObject(v1s1, new SignedInfo(), "v1s1".getBytes(), (Signature)null);		
	ContentName v1s2 = SegmentationProfile.segmentName(v1, 2);
	ContentObject objv1s2 = new ContentObject(v1s2, new SignedInfo(), "v1s2".getBytes(), (Signature)null);	
	ContentName v1s3 = SegmentationProfile.segmentName(v1, 3);
	ContentObject objv1s3 = new ContentObject(v1s3, new SignedInfo(), "v1s3".getBytes(), (Signature)null);	
	ContentName v1s4 = SegmentationProfile.segmentName(v1, 4);
	ContentObject objv1s4 = new ContentObject(v1s4, new SignedInfo(), "v1s4".getBytes(), (Signature)null);	
	ContentName v2s1 = SegmentationProfile.segmentName(v2, 1);
	ContentObject objv2s1 = new ContentObject(v2s1, new SignedInfo(), "v2s1".getBytes(), (Signature)null);	
	ContentName v2s2 = SegmentationProfile.segmentName(v2, 2);
	ContentObject objv2s2 = new ContentObject(v2s2, new SignedInfo(), "v2s2".getBytes(), (Signature)null);
	Queue<ContentObject> queue = _library.getOutputQueue();
	ArrayList<Interest> interestList = new ArrayList<Interest>();
	CCNFlowControl fc = new CCNFlowControl(_library);

	@Test
	public void testBasicControlFlow() throws Throwable {	
		
		System.out.println("Testing basic control flow functionality and errors");
		_library.reset();
		try {
			fc.put(obj1);
			Assert.fail("Put with no namespace succeeded");
		} catch (IOException e) {}
		fc.addNameSpace("/bar");
		try {
			fc.put(obj1);
			Assert.fail("Put with bad namespace succeeded");
		} catch (IOException e) {}
		fc.addNameSpace("/foo");
		try {
			fc.put(obj1);
		} catch (IOException e) {
			Assert.fail("Put with good namespace failed");
		}
		
	}
	
	@Test
	public void testInterestFirst() throws Throwable {	
		
		normalReset(name1);
		System.out.println("Testing interest arrives before a put");
		interestList.add(new Interest("/bar"));
		fc.handleInterests(interestList);
		fc.put(obj1);
		Assert.assertTrue(queue.poll() == null);
		interestList.add(new Interest("/foo"));
		fc.handleInterests(interestList);
		fc.put(obj1);
		testExpected(queue.poll(), obj1);
	}
	
	@Test
	public void testNextBeforePut() throws Throwable {	

		System.out.println("Testing \"next\" interest arrives before a put");
		normalReset(name1);
		interestList.add(Interest.next(v1s2));
		fc.handleInterests(interestList);
		fc.put(objv1s1);
		Assert.assertTrue(queue.poll() == null);
		fc.put(objv1s3);
		testExpected(queue.poll(), objv1s3);
		
	}
	
	@Test
	public void testLastBeforePut() throws Throwable {	

		System.out.println("Testing \"last\" interest arrives before a put");
		normalReset(name1);
		interestList.add(Interest.last(v1s2));
		fc.handleInterests(interestList);
		fc.put(objv1s1);
		Assert.assertTrue(queue.poll() == null);
		fc.put(objv1s3);
		testExpected(queue.poll(), objv1s3);
		
	}
	
	@Test
	public void testPutsOrdered() throws Throwable {	

		System.out.println("Testing puts output in correct order");
		normalReset(name1);
		interestList.add(new Interest("/foo"));
		fc.handleInterests(interestList);
		fc.put(obj1);
		testExpected(queue.poll(), obj1);
		
	} 
	
	@Test
	public void testRandomOrderPuts() throws Throwable {	

		normalReset(name1);
		
		// Put these in slightly random order. It would be nice to truly randomize this but am
		// not going to bother with that right now.
		fc.put(objv2s1);
		fc.put(objv1s4);
		fc.put(objv1s1);
		fc.put(objv1s2);
		fc.put(objv1s3);
		fc.put(objv2s2);
		ContentObject co = testExpected(_library.get(v1, 0), objv1s1);
		co = testNext(co, objv1s2);
		co = testNext(co, objv1s3);
		co = testNext(co, objv1s4);
		co = testNext(co, objv2s1);
		
	}

	/**
	 * Test method for an order case that failed in practice for 
	 * RepoIOTest when matching was broken.
	 * @throws Throwable
	 */
	@Test
	public void testMixedOrderInterestPut() throws Throwable {	

		normalReset(name1);
		
		// First one normal order exchange: put first, interest next
		fc.put(objv1s1);
		ContentObject co = testExpected(_library.get(v1, 0), objv1s1);

		// Next we get the interest for the next segment before the data
		interestList.add(Interest.next(co,3));
		fc.handleInterests(interestList);

		// Data arrives for the waiting interest, should be sent out
		fc.put(objv1s2);
		testExpected(queue.poll(), objv1s2);

		// Remainder in order, puts first
		fc.put(objv1s3);
		co = testNext(co, objv1s3);
		fc.put(objv1s4);
		co = testNext(co, objv1s4);
	}

	@Test
	public void testWaitForPutDrain() throws Throwable {	

		normalReset(name1);
		fc.put(objv1s2);
		fc.put(objv2s1);
		fc.put(objv1s4);
		fc.put(objv1s1);
		fc.put(objv1s3);
		fc.put(objv2s2);
		testLast(objv1s1, objv2s2);
		testLast(objv1s1, objv2s1);
		testLast(objv1s1, objv1s4);
		testLast(objv1s1, objv1s3);
		testLast(objv1s1, objv1s2);
		testLast(objv1s1, objv1s1);
		
		System.out.println("Testing \"waitForPutDrain\"");
		try {
			fc.waitForPutDrain();
		} catch (IOException ioe) {
			Assert.fail("WaitforPutDrain threw unexpecxted exception");
		}
		fc.put(obj1);
		try {
			fc.waitForPutDrain();
			Assert.fail("WaitforPutDrain succeeded when it should have failed");
		} catch (IOException ioe) {}
	}
	
	@Test
	public void testHighwaterWait() throws Throwable {
		
		// Test that put over highwater fails with nothing draining
		// the buffer
		normalReset(name1);
		fc.setHighwater(4);
		fc.put(objv1s1);
		fc.put(objv1s2);
		fc.put(objv1s3);
		try {
			fc.put(objv1s4);
			Assert.fail("Put over highwater mark succeeded");
		} catch (IOException ioe) {}
		
		// Test that put over highwater succeeds when buffer is
		// drained
		normalReset(name1);
		fc.setHighwater(4);
		fc.setHighwater(4);
		fc.put(objv1s1);
		fc.put(objv1s2);
		fc.put(objv1s3);

		HighWaterHelper hwh = new HighWaterHelper();
		hwh.start();
		fc.put(objv1s4);
	}
	
	public class HighWaterHelper extends Thread {

		public void run() {
			synchronized (this) {
				try {
					Thread.sleep(500);
					_library.get(objv1s1.name(), 0);
				} catch (Exception e) {
					Assert.fail("Caught exception: " + e.getMessage());
				}
			}
		}
		
	}
	
	private void normalReset(ContentName n) {
		_library.reset();
		interestList.clear();
		fc = new CCNFlowControl(n, _library);
	}
	
	private ContentObject testNext(ContentObject co, ContentObject expected) throws InvalidParameterException, IOException {
		co = _library.getNext(co.name(), 2, 0);
		return testExpected(co, expected);
	}
	
	private void testLast(ContentObject co, ContentObject expected) throws InvalidParameterException, IOException {
		co = _library.getLatest(co.name(), 2, 0);
		testExpected(co, expected);
	}
	
	private ContentObject testExpected(ContentObject co, ContentObject expected) {
		Assert.assertTrue(co != null);
		Assert.assertEquals(co, expected);
		return co;
	}
}
