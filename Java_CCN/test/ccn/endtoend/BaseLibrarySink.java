package test.ccn.endtoend;

import java.util.concurrent.Semaphore;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.protocol.ContentObject;
import org.junit.BeforeClass;


// NOTE: This test requires ccnd to be running and complementary source process

public class BaseLibrarySink {
	static CCNHandle library = null;
	Semaphore sema = new Semaphore(0);
	int next = 0;
	protected static Throwable error = null; // for errors in callback
	
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		library = CCNHandle.open();
		// Set debug level: use for more FINE, FINER, FINEST for debug-level tracing
		// Library.logger().setLevel(Level.FINEST);
	}

	/**
	 * Subclassible object processing operations, to make it possible to easily
	 * implement tests based on this one.
	 * @author smetters
	 *
	 */
	public void checkGetResults(ContentObject getResults) {
		System.out.println("Got result: " + getResults.name());
	}
}
