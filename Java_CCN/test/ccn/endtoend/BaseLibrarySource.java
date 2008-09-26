package test.ccn.endtoend;


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.Semaphore;
import java.util.logging.Level;

import org.junit.BeforeClass;
import org.junit.Test;

import com.parc.ccn.Library;
import com.parc.ccn.data.CompleteName;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.query.CCNFilterListener;
import com.parc.ccn.data.query.Interest;
import com.parc.ccn.data.util.BinaryXMLCodec;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.library.StandardCCNLibrary;

//NOTE: This test requires ccnd to be running and complementary sink process 

public class BaseLibrarySource implements CCNFilterListener {
	public static int count = 43;
	protected CCNLibrary library = null;
	ContentName name = null;
	int next = 0;
	protected static Throwable error = null; // for errors in callback
	Semaphore sema = new Semaphore(0);
	static Random rand;

	public BaseLibrarySource() throws Throwable {
		library = StandardCCNLibrary.open();
	}
	
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		// Set debug level: use for more FINE, FINER, FINEST for debug-level tracing
		Library.logger().setLevel(Level.INFO);
		rand = new Random();
	}

	/**
	 * Subclassible object processing operations, to make it possible to easily
	 * implement tests based on this one.
	 * @author smetters
	 *
	 */
	public void checkPutResults(CompleteName putResult) {
		System.out.println("Put data: " + putResult.name());
	}

	/**
	 * getRandomString returns a random string (all digits) of random
	 * length so that different packets will have varying sizes of content
	 * as a test of buffer handling.  We try to carefully construct 
	 * content that will do maximum damage if interpreted as the start 
	 * of a message (i.e. if you read this as 'leftover' data in a buffer
	 * previously used for a larger packet, because you read past the end
	 * of what you are supposed to be handling)
	 */
	public byte[] getRandomContent(int item) {
		// Initially keep this small so we don't engage fragmentation,
		// which may not yet be working and is not supported by this test code
		byte valbyte = new Integer(BinaryXMLCodec.XML_REG_VAL_MASK).byteValue(); // max value
		byte typebyte = new Integer(BinaryXMLCodec.XML_TT_NO_MORE | BinaryXMLCodec.XML_TAG).byteValue();
		int size = 1 + (rand.nextBoolean() ? rand.nextInt(42) * 12 : rand.nextInt(250) * 12 + 504);
		byte[] result = new byte[size];
		result[0] = new Integer(item).byteValue();
		for (int i = 1; i < result.length; i++) {
			result[i] = (i % 12 == 0) ? typebyte : valbyte; 
			
		}
		return result;
	}
	
	@Test
	public void puts() throws Throwable {
		assert(count <= Byte.MAX_VALUE);
		System.out.println("Put sequence started");
		for (int i = 0; i < count; i++) {
			Thread.sleep(rand.nextInt(50));
			byte[] content = getRandomContent(i);
			CompleteName putName = library.put(ContentName.fromNative("/BaseLibraryTest/gets/" + new Integer(i).toString()), content);
			System.out.println("Put " + i + " done: " + content.length + " content bytes");
			checkPutResults(putName);
		}
		System.out.println("Put sequence finished");
	}
	
	@Test
	public void server() throws Throwable {
		System.out.println("PutServer started");
		// Register filter
		name = ContentName.fromNative("/BaseLibraryTest/");
		library.setInterestFilter(name, this);
		// Block on semaphore until enough data has been received
		sema.acquire();
		library.cancelInterestFilter(name, this);
		if (null != error) {
			throw error;
		}
	}

	public synchronized int handleInterests(ArrayList<Interest> interests) {
		try {
			if (next >= count) {
				return 0;
			}
			for (Interest interest : interests) {
				assertTrue(name.isPrefixOf(interest.name()));
				byte[] content = getRandomContent(next);
				CompleteName putName = library.put(ContentName.fromNative("/BaseLibraryTest/server/" + new Integer(next).toString()), content);
				System.out.println("Put " + next + " done: " + content.length + " content bytes");
				checkPutResults(putName);
				next++;
			}
			if (next >= count) {
				sema.release();
			}
		} catch (Throwable e) {
			error = e;
		}
		return 0;
	}
}
