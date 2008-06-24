package test.ccn.library;


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SignatureException;
import java.util.ArrayList;
import java.util.Date;
import java.util.Random;
import java.util.concurrent.Semaphore;
import java.util.logging.Level;

import javax.xml.stream.XMLStreamException;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.parc.ccn.Library;
import com.parc.ccn.data.CompleteName;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.MalformedContentNameStringException;
import com.parc.ccn.data.query.CCNFilterListener;
import com.parc.ccn.data.query.CCNInterestListener;
import com.parc.ccn.data.query.Interest;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.library.StandardCCNLibrary;

public class BaseLibraryTest {

	protected static boolean exit = false;
	protected static Throwable error = null; // for errors from other threads
	
	protected static final String BASE_NAME = "/test/BaseLibraryTest/";

	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
	
		// Set debug level: use for more FINE, FINER, FINEST for debug-level tracing
		Library.logger().setLevel(Level.INFO);
	}

	@Before
	public void setUp() throws Exception {
	}

	public void genericGetPut(Thread putter, Thread getter) throws Throwable {
		try {
			putter.start();
			Thread.sleep(200);
			Date start = new Date();
			getter.start();
			putter.join(50000);
			getter.join(50000);
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
			System.out.println("Get/Put test in " + (new Date().getTime() - start.getTime()) + " ms");
		} catch (InterruptedException e) {
			e.printStackTrace();
			fail("InterruptedException");
		}
	}
	
	@Test
	public void testGetPut() throws Throwable {
		System.out.println("TEST: PutThread/GetThread");
		Thread putter = new Thread(new PutThread(25));
		Thread getter = new Thread(new GetThread(25));
		genericGetPut(putter, getter);
	}
	
	@Test
	public void testGetServPut() throws Throwable {
		System.out.println("TEST: PutThread/GetServer");
		Thread putter = new Thread(new PutThread(25));
		Thread getter = new Thread(new GetServer(25));
		genericGetPut(putter, getter);
	}

	@Test
	public void testGetPutServ() throws Throwable {
		System.out.println("TEST: PutServer/GetThread");
		Thread putter = new Thread(new PutServer(25));
		Thread getter = new Thread(new GetThread(25));
		genericGetPut(putter, getter);
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
	
	public void checkPutResults(CompleteName putResult) {
		System.out.println("Put data: " + putResult.name());
	}
	
	/**
	 * Expects this method to call checkGetResults on each set of content returned...
	 * @param baseName
	 * @param count
	 * @param library
	 * @return
	 * @throws InterruptedException
	 * @throws MalformedContentNameStringException
	 * @throws IOException
	 * @throws NoSuchAlgorithmException 
	 * @throws SignatureException 
	 * @throws InvalidKeyException 
	 * @throws XMLStreamException 
	 */
	public void getResults(String baseName, int count, CCNLibrary library) throws InterruptedException, MalformedContentNameStringException, IOException, InvalidKeyException, SignatureException, NoSuchAlgorithmException, XMLStreamException {
		Random rand = new Random();
		for (int i = 0; i < count; i++) {
			Thread.sleep(rand.nextInt(50));
			ArrayList<ContentObject> contents = library.get(baseName);
			assertEquals(1, contents.size());
			assertEquals(i, Integer.parseInt(new String(contents.get(0).content())));
			System.out.println("Got " + i);
			checkGetResults(contents);
		}
		return;
	}
	
	/**
	 * Responsible for calling checkPutResults on each put. (Could return them all in
	 * a batch then check...)
	 * @throws InterruptedException 
	 * @throws IOException 
	 * @throws MalformedContentNameStringException 
	 * @throws SignatureException 
	 * @throws XMLStreamException 
	 * @throws NoSuchAlgorithmException 
	 * @throws InvalidKeyException 
	 */
	public void doPuts(String baseName, int count, CCNLibrary library) throws InterruptedException, SignatureException, MalformedContentNameStringException, IOException, XMLStreamException, InvalidKeyException, NoSuchAlgorithmException {
		Random rand = new Random();
		for (int i = 0; i < count; i++) {
			Thread.sleep(rand.nextInt(50));
			CompleteName putName = library.put(baseName + new Integer(i).toString(), new Integer(i).toString());
			System.out.println("Put " + i + " done");
			checkPutResults(putName);
		}
	}
	
	public class GetThread implements Runnable {
		protected CCNLibrary library = StandardCCNLibrary.open();
		int count = 0;
		public GetThread(int n) {
			count = n;
		}
		public void run() {
			try {
				System.out.println("Get thread started");
				getResults(BASE_NAME, count, library);
				System.out.println("Get thread finished");
			} catch (Throwable ex) {
				error = ex;
			}
		}
	}
	
	public class PutThread implements Runnable {
		protected CCNLibrary library = StandardCCNLibrary.open();
		int count = 0;
		public PutThread(int n) {
			count = n;
		}
		public void run() {
			try {
				System.out.println("Put thread started");
				doPuts(BASE_NAME, count, library);
				System.out.println("Put thread finished");
			} catch (Throwable ex) {
				error = ex;
				Library.logger().finer("Exception in run: " + ex.getClass().getName() + " message: " + ex.getMessage());
				Library.logStackTrace(Level.FINEST, ex);
			}
		}
	}
	
	public class GetServer implements Runnable, CCNInterestListener {
		protected CCNLibrary library = StandardCCNLibrary.open();
		int count = 0;
		int next = 0;
		Semaphore sema = new Semaphore(0);
		public GetServer(int n) {
			count = n;
		}
		public void run() {
			try {
				System.out.println("GetServer started");
				Interest interest = new Interest(BASE_NAME);
				// Register interest
				library.expressInterest(interest, this);
				// Block on semaphore until enough data has been received
				sema.acquire();
				library.cancelInterest(interest, this);
			} catch (Throwable ex) {
				error = ex;
			}
		}
		public void addInterest(Interest interest) {
		}
		public void cancelInterests() {
		}
		public Interest[] getInterests() {
			return null;
		}
		public synchronized int handleContent(ArrayList<ContentObject> results) {
			for (ContentObject contentObject : results) {
				assertEquals(next, Integer.parseInt(new String(contentObject.content())));
				System.out.println("Got " + next);	
				next++;
			}
			checkGetResults(results);
			
			if (next >= count) {
				sema.release();
			}
			return 0;
		}
		public void interestTimedOut(Interest interest) {
		}
		public boolean matchesInterest(CompleteName name) {
			return false;
		}
	}
	
	public class PutServer implements Runnable, CCNFilterListener {
		protected CCNLibrary library = StandardCCNLibrary.open();
		int count = 0;
		int next = 0;
		Semaphore sema = new Semaphore(0);
		ContentName name = null;
		
		public PutServer(int n) {
			count = n;
		}
		
		public void run() {
			try {
				System.out.println("PutServer started");
				// Register filter
				name = new ContentName(BASE_NAME);
				library.setInterestFilter(name, this);
				// Block on semaphore until enough data has been received
				sema.acquire();
				library.cancelInterestFilter(name, this);
			} catch (Throwable ex) {
				error = ex;
			}
		}

		public synchronized int handleInterests(ArrayList<Interest> interests) {
			try {
				assertEquals(1, interests.size());
				for (Interest interest : interests) {
					assertTrue(name.isPrefixOf(interest.name()));
					CompleteName putName = library.put(BASE_NAME + new Integer(next).toString(), new Integer(next).toString());
					System.out.println("Put " + next + " done");
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
}
