package test.ccn.endtoend;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.Semaphore;
import java.util.logging.Level;

import org.junit.BeforeClass;
import org.junit.Test;

import com.parc.ccn.Library;
import com.parc.ccn.data.CompleteName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.query.CCNInterestListener;
import com.parc.ccn.data.query.Interest;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.library.StandardCCNLibrary;

// NOTE: This test requires ccnd to be running and complementary source process

public class BaseLibrarySink implements CCNInterestListener {
	static int count = 25;
	CCNLibrary library = StandardCCNLibrary.open();
	Semaphore sema = new Semaphore(0);
	int next = 0;
	protected static Throwable error = null; // for errors in callback

	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
	
		// Set debug level: use for more FINE, FINER, FINEST for debug-level tracing
		Library.logger().setLevel(Level.FINEST);
	}

	/**
	 * Subclassible object processing operations, to make it possible to easily
	 * implement tests based on this one.
	 * @author smetters
	 *
	 */
	public void checkGetResults(ArrayList<ContentObject> getResults) {
		System.out.println("Got result: " + getResults.get(0).name());
	}

	@Test
	public void gets() throws Throwable {
		System.out.println("Get sequence started");
		Random rand = new Random();
		for (int i = 0; i < count; i++) {
			Thread.sleep(rand.nextInt(50));
			ArrayList<ContentObject> contents = library.get("/BaseLibraryTest/gets");
			assertEquals(1, contents.size());
			String sc = new String(contents.get(0).content());
			int value = Integer.parseInt(sc.split(" ")[0]);
			// Note that we cannot be guaranteed to pick up every value:
			// due to timing we may miss a value that arrives while we are not
			// in the get()
			assertEquals(true, value >= i);
			i = value;
			System.out.println("Got " + i);
			checkGetResults(contents);
		}
		System.out.println("Get sequence finished");
	}
	
	@Test
	public void server() throws Throwable {
		System.out.println("GetServer started");
		Interest interest = new Interest("/BaseLibraryTest/server");
		// Register interest
		library.expressInterest(interest, this);
		// Block on semaphore until enough data has been received
		sema.acquire();
		library.cancelInterest(interest, this);
		if (null != error) {
			throw error;
		}
	}
	
	public synchronized int handleContent(ArrayList<ContentObject> results) {
		try {
			for (ContentObject contentObject : results) {
				assertEquals(next, Integer.parseInt(new String(contentObject
						.content()).split(" ")[0]));
				System.out.println("Got " + next);
				next++;
			}
			checkGetResults(results);
			if (next >= count) {
				sema.release();
			}
		} catch (Throwable e) {
			error = e;
		}
		return 0;
	}

	public void addInterest(Interest interest) {
	}

	public void cancelInterests() {
	}

	public Interest[] getInterests() {
		return null;
	}

	public void interestTimedOut(Interest interest) {
	}

	public boolean matchesInterest(CompleteName name) {
		return false;
	}
}
