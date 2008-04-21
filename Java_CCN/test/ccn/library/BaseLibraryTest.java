package test.ccn.library;


import static org.junit.Assert.*;

import java.lang.Thread.UncaughtExceptionHandler;
import java.util.ArrayList;
import java.util.logging.Level;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.parc.ccn.Library;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.library.StandardCCNLibrary;

public class BaseLibraryTest {

	protected static CCNLibrary library = null;
	protected static boolean exit = false;
	protected static Throwable error = null; // for errors from other threads

	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		library = StandardCCNLibrary.getLibrary();
		
		// Uncomment the following line for more debug-level tracing
//		Library.logger().setLevel(Level.FINER);
	}

	@Before
	public void setUp() throws Exception {
	}

	@Test
	public void testGetPut() throws Throwable {
		try {
			Thread putter = new Thread(new PutThread(5));
			Thread getter = new Thread(new GetThread(5));
			putter.start();
			Thread.sleep(200);
			getter.start();
			putter.join(10000);
			boolean good = true;
			exit = true;
			if (getter.getState() != Thread.State.TERMINATED) {
				getter.interrupt();
				System.out.println("Get Thread has not finished!");
				good = false;
			}
			if (putter.getState() != Thread.State.TERMINATED) {
				putter.interrupt();
				System.out.println("Put Thread has not finished!");
				good = false;
			}
			if (null != error) {
				System.out.println("Error in test thread: " + error.getClass().toString());
				throw error;
			}
			if (!good) {
				fail();
			}
			System.out.println("Get/Put test done");
		} catch (InterruptedException e) {
			e.printStackTrace();
			fail("InterruptedException");
		}
	}
	
	public class GetThread implements Runnable {
		int count = 0;
		public GetThread(int n) {
			count = n;
		}
		public void run() {
			try {
				System.out.println("Get thread started");
				for (int i = 0; i < count; i++) {
					ArrayList<ContentObject> contents = library.get("/BaseLibraryTest/" + new Integer(i).toString());
					assertEquals(1, contents.size());
					assertEquals(i, Integer.parseInt(new String(contents.get(0).content())));
					System.out.println("Got " + i);
				}
				System.out.println("Get thread finished");
			} catch (Throwable ex) {
				error = ex;
			}
		}
	}
	
	public class PutThread implements Runnable {
		int count = 0;
		public PutThread(int n) {
			count = n;
		}
		public void run() {
			try {
				System.out.println("Put thread started");
				for (int i = 0; i < count; i++) {
					library.put("/BaseLibraryTest/" + new Integer(i).toString(), new Integer(i).toString());
					System.out.println("Put " + i);
				}
				System.out.println("Put thread finished");
			} catch (Throwable ex) {
				error = ex;
			}
		}
	}
}
