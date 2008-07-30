package test.ccn.library;


import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SignatureException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
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
	public static int count = 55;
	public static Random rand = new Random();
	
	protected static final String BASE_NAME = "/test/BaseLibraryTest/";
	protected static ContentName PARENT_NAME = null;
	
	protected static final boolean DO_TAP = true;
	
	protected HashSet<Integer> _resultSet = new HashSet<Integer>();

	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
	
		// Set debug level: use for more FINE, FINER, FINEST for debug-level tracing
		Library.logger().setLevel(Level.INFO);
	}

	@Before
	public void setUp() throws Exception {
		if (null == PARENT_NAME)
			PARENT_NAME = new ContentName(BASE_NAME);
	}

	public void genericGetPut(Thread putter, Thread getter) throws Throwable {
		try {
			putter.start();
			Thread.sleep(20);
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
		int id = rand.nextInt(1000);
		Thread putter = new Thread(new PutThread(count, id));
		Thread getter = new Thread(new GetThread(count, id));
		genericGetPut(putter, getter);
	}
	
	@Test
	public void testGetServPut() throws Throwable {
		System.out.println("TEST: PutThread/GetServer");
		int id = rand.nextInt(1000);

		Thread putter = new Thread(new PutThread(count, id));
		Thread getter = new Thread(new GetServer(count, id));
		genericGetPut(putter, getter);
	}

	@Test
	public void testGetPutServ() throws Throwable {
		System.out.println("TEST: PutServer/GetThread");
		int id = rand.nextInt(1000);
		Thread putter = new Thread(new PutServer(count, id));
		Thread getter = new Thread(new GetThread(count, id));
		genericGetPut(putter, getter);
	}
	
	/**
	 * Subclassible object processing operations, to make it possible to easily
	 * implement tests based on this one.
	 * @author smetters
	 *
	 */
	public void checkGetResults(ArrayList<ContentObject> getResults) {
		if (0 < getResults.size())
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
	public void getResults(ContentName baseName, int count, CCNLibrary library) throws InterruptedException, MalformedContentNameStringException, IOException, InvalidKeyException, SignatureException, NoSuchAlgorithmException, XMLStreamException {
		Random rand = new Random();
	//	boolean done = false;
		System.out.println("getResults: getting children of " + baseName);
		for (int i = 0; i < count; i++) {
	//	while (!done) {
			Thread.sleep(rand.nextInt(50));
			System.out.println("getResults getting " + baseName + " subitem " + i);
			ArrayList<ContentObject> contents = library.get(new ContentName(baseName, Integer.toString(i)));
			if (1 != contents.size()) {
				Library.logger().info("Got " + contents.size() + " results at once!");
			}
			//assertEquals(1, contents.size());
			for (int j=0; j < contents.size(); j++) {
				if (contents.size() > 1) {
					Library.logger().info("Content item: " + j + " name: " + contents.get(j).name());
				}
				try {
					int val = Integer.parseInt(new String(contents.get(j).content()));
					if (_resultSet.contains(val)) {
						System.out.println("Got " + val + " again.");
					} else {
						System.out.println("Got " + val);
					}
					_resultSet.add(val);

				} catch (NumberFormatException nfe) {
					Library.logger().info("BaseLibraryTest: unexpected content - not integer. Name: " + contents.get(j).content());
				}
			}
			//assertEquals(i, Integer.parseInt(new String(contents.get(0).content())));
			checkGetResults(contents);
			
			if (_resultSet.size() == count) {
				System.out.println("We have everything!");
//				done = true; 
			}
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
	public void doPuts(ContentName baseName, int count, CCNLibrary library) throws InterruptedException, SignatureException, MalformedContentNameStringException, IOException, XMLStreamException, InvalidKeyException, NoSuchAlgorithmException {
		Random rand = new Random();
		for (int i = 0; i < count; i++) {
			Thread.sleep(rand.nextInt(50));
			CompleteName putName = library.put(new ContentName(baseName,new Integer(i).toString()), new Integer(i).toString().getBytes());
			System.out.println("Put " + i + " done");
			checkPutResults(putName);
		}
	}
	
	public class GetThread implements Runnable {
		protected CCNLibrary library = StandardCCNLibrary.open();
		int count = 0;
		int id = 0;
		public GetThread(int n, int id) {
			count = n;
			this.id = id;
			if (DO_TAP) {
				try {
					((StandardCCNLibrary)library).getNetworkManager().setTap("CCN_DEBUG_DATA/LibraryTestDebug_" + Integer.toString(id) + "_get");
				} catch (IOException ie) {
				}
			}
		}
		public void run() {
			try {
				System.out.println("Get thread started");
				getResults(new ContentName(PARENT_NAME, Integer.toString(id)), count, library);
				System.out.println("Get thread finished");
				((StandardCCNLibrary)library).getNetworkManager().shutdown();
			} catch (Throwable ex) {
				error = ex;
			}
		}
	}
	
	public class PutThread implements Runnable {
		protected CCNLibrary library = StandardCCNLibrary.open();
		int count = 0;
		int id = 0;
		public PutThread(int n, int id) {
			count = n;
			this.id = id;
			if (DO_TAP) {
				try {
					((StandardCCNLibrary)library).getNetworkManager().setTap("CCN_DEBUG_DATA/LibraryTestDebug_" + Integer.toString(id) + "_put");
				} catch (IOException ie) {
				}
			}
		}
		public void run() {
			try {
				System.out.println("Put thread started");
				doPuts(new ContentName(PARENT_NAME, Integer.toString(id)), count, library);
				System.out.println("Put thread finished");
				((StandardCCNLibrary)library).getNetworkManager().shutdown();
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
		HashSet<Integer> accumulatedResults = new HashSet<Integer>();
		int id;
		
		public GetServer(int n, int id) {
			count = n;
			this.id = id;
			if (DO_TAP) {
				try {
					((StandardCCNLibrary)library).getNetworkManager().setTap("CCN_DEBUG_DATA/LibraryTestDebug_" + Integer.toString(id) + "_get");
				} catch (IOException ie) {
				}
			}
		}
		public void run() {
			try {
				System.out.println("GetServer started");
				Interest interest = new Interest(new ContentName(PARENT_NAME, Integer.toString(id)));
				// Register interest
				library.expressInterest(interest, this);
				// Block on semaphore until enough data has been received
				sema.acquire();
				library.cancelInterest(interest, this);
				((StandardCCNLibrary)library).getNetworkManager().shutdown();

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
				try {
					int val = Integer.parseInt(new String(contentObject.content()));
					if (!accumulatedResults.contains(val)) {
						accumulatedResults.add(val);
						System.out.println("Got " + val);	
					}
				} catch (NumberFormatException nfe) {
					Library.logger().info("Unexpected content, " + contentObject.name() + " is not an integer!");
				}
			}
			checkGetResults(results);
			
			if (accumulatedResults.size() >= count) {
				System.out.println("GetServer got all content: " + accumulatedResults.size() + ". Releasing semaphore.");
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
		HashSet<Integer> accumulatedResults = new HashSet<Integer>();
		int id;
		
		public PutServer(int n, int id) {
			count = n;
			this.id = id;
			if (DO_TAP) {
				try {
					((StandardCCNLibrary)library).getNetworkManager().setTap("CCN_DEBUG_DATA/LibraryTestDebug_" + Integer.toString(id) + "_put");
				} catch (IOException ie) {
				}
			}
		}
		
		public void run() {
			try {
				System.out.println("PutServer started");
				// Register filter
				name = new ContentName(PARENT_NAME, Integer.toString(id));
				library.setInterestFilter(name, this);
				// Block on semaphore until enough data has been received
				sema.acquire();
				library.cancelInterestFilter(name, this);
				System.out.println("PutServer finished.");
				((StandardCCNLibrary)library).getNetworkManager().shutdown();

			} catch (Throwable ex) {
				error = ex;
			}
		}

		public synchronized int handleInterests(ArrayList<Interest> interests) {
			try {
				for (Interest interest : interests) {
					assertTrue(name.isPrefixOf(interest.name()));
					try {
						int val = Integer.parseInt(new String(interest.name().component(interest.name().count()-1)));
						System.out.println("Got interest in " + val);
						if (!accumulatedResults.contains(val)) {
							CompleteName putName = library.put(new ContentName(name, Integer.toString(val)), Integer.toString(next).getBytes());
							System.out.println("Put " + val + " done");
							checkPutResults(putName);
							next++;
							accumulatedResults.add(val);
						}
					} catch (NumberFormatException nfe) {
						Library.logger().info("Unexpected interest, " + interest.name() + " does not end in an integer!");
					}
				}
				if (accumulatedResults.size() >= count) {
					sema.release();
				}
			} catch (Throwable e) {
				error = e;
			}
			return 0;
		}
		
	}
}
