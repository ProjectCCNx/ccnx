package test.ccn.endtoend;


import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.Semaphore;
import java.util.logging.Level;

import org.junit.BeforeClass;

import com.parc.ccn.Library;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.util.BinaryXMLCodec;
import com.parc.ccn.library.CCNLibrary;

//NOTE: This test requires ccnd to be running and complementary sink process 

public class BaseLibrarySource {
	public static int count = 43;
	protected static CCNLibrary library = null;
	ContentName name = null;
	int next = 0;
	protected static Throwable error = null; // for errors in callback
	Semaphore sema = new Semaphore(0);
	protected static Random rand;
	private static ArrayList<Integer> currentSet;
	
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		library = CCNLibrary.open();
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
	public void checkPutResults(ContentName putResult) {
		System.out.println("Put data: " + putResult);
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
	
	public int getRandomFromSet(int length, boolean reset) {
		int result = -1;
		if (reset || currentSet == null)
			currentSet = new ArrayList<Integer>(length);
		if (currentSet.size() >= length)
			return result;
		while (true) {
			result = rand.nextInt(length);
			boolean found = false;
			for (int used : currentSet) {
				if (used == result) {
					found = true;
					break;
				}
			}
			if (!found)
				break;
		}
		currentSet.add(result);
		return result;
	}
}
