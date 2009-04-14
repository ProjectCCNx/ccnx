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
import com.parc.ccn.data.query.Interest;
import com.parc.ccn.data.security.Signature;
import com.parc.ccn.data.security.SignedInfo;
import com.parc.ccn.library.CCNFlowControl;
import com.parc.ccn.library.CCNLibraryTestHarness;
import com.parc.ccn.library.profiles.SegmentationProfile;
import com.parc.ccn.library.profiles.VersioningProfile;

public class CCNFlowControlTest {
	static private CCNLibraryTestHarness _library ;
	
	static {
		try {
			_library = new CCNLibraryTestHarness();
		} catch (ConfigurationException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	@Test
	public void testControlFlow() throws Throwable {
		
		System.out.println("Testing basic control flow functionality and errors");
		_library.reset();
		Queue<ContentObject> queue = _library.getOutputQueue();
		CCNFlowControl fc = new CCNFlowControl(_library);
		ContentName name1 = ContentName.fromNative("/foo/bar");
		ContentObject obj1 = new ContentObject(name1, new SignedInfo(), "test".getBytes(), (Signature)null);
		ArrayList<Interest> interestList = new ArrayList<Interest>();
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
			Assert.fail("Put with bad namespace succeeded");
		}
		
		_library.reset();
		System.out.println("Testing interest arrives before a put");
		interestList.add(new Interest("/bar"));
		fc.handleInterests(interestList);
		fc.put(obj1);
		Assert.assertTrue(queue.poll() == null);
		interestList.add(new Interest("/foo"));
		fc.handleInterests(interestList);
		fc.put(obj1);
		Assert.assertTrue(queue.poll() != null);
		
		System.out.println("Testing puts output in correct order");
		_library.reset();
		interestList.clear();
		fc = new CCNFlowControl(name1, _library);
		interestList.add(new Interest("/foo"));
		fc.handleInterests(interestList);
		fc.put(obj1);
		Assert.assertTrue(queue.poll() != null);
		
		_library.reset();
		interestList.clear();
		fc = new CCNFlowControl(name1, _library);
		ContentName v1 = VersioningProfile.versionName(name1);
		Thread.sleep(20);
		ContentName v2 = VersioningProfile.versionName(name1);	
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
		
		// Put these in in slightly random order. It would be nice to truly randomize this but am
		// not going to bother with that right now.
		fc.put(objv2s1);
		fc.put(objv1s4);
		fc.put(objv1s1);
		fc.put(objv1s2);
		fc.put(objv1s3);
		fc.put(objv2s2);
		ContentObject co = _library.get(v1, 0);
		Assert.assertTrue(co != null);
		Assert.assertEquals(co, objv1s1);
		co = testNext(co, objv1s2);
		co = testNext(co, objv1s3);
		co = testNext(co, objv1s4);
		co = testNext(co, objv2s1);
		
		_library.reset();
		fc = new CCNFlowControl(name1, _library);
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
			Assert.fail("WaitforPutdream threw unexpecxted exception");
		}
		fc.put(obj1);
		try {
			fc.waitForPutDrain();
			Assert.fail("WaitforPutdream succeeded when it should have failed");
		} catch (IOException ioe) {}
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
