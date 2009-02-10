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

import com.parc.ccn.CCNBase;
import com.parc.ccn.Library;
import com.parc.ccn.config.ConfigurationException;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.MalformedContentNameStringException;
import com.parc.ccn.data.query.CCNFilterListener;
import com.parc.ccn.data.query.CCNInterestListener;
import com.parc.ccn.data.query.Interest;
import com.parc.ccn.library.CCNLibrary;

public class LibraryTestBase {

	protected static boolean exit = false;
	protected static Throwable error = null; // for errors from other threads
	public static int count = 55;
	public static Random rand = new Random();
	
	protected static final String BASE_NAME = "/test/BaseLibraryTest/";
	protected static ContentName PARENT_NAME = null;
	
	protected static final boolean DO_TAP = true;
	
	protected HashSet<Integer> _resultSet = new HashSet<Integer>();
	
	protected static CCNLibrary library = null;

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
	
		// Set debug level: use for more FINE, FINER, FINEST for debug-level tracing
		Library.logger().setLevel(Level.INFO);
	}

	@Before
	public void setUp() throws Exception {
		if (null == PARENT_NAME)
			PARENT_NAME = ContentName.fromNative(BASE_NAME);
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
	
	/**
	 * Subclassible object processing operations, to make it possible to easily
	 * implement tests based on this one.
	 * @author smetters
	 *
	 */
	public void checkGetResults(ContentObject getResults) {
		System.out.println("Got result: " + getResults.name());
	}
	
	public void checkPutResults(ContentObject putResult) {
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
			ContentObject contents = library.get(ContentName.fromNative(baseName, Integer.toString(i)), CCNBase.NO_TIMEOUT);
		
			try {
				int val = Integer.parseInt(new String(contents.content()));
				if (_resultSet.contains(val)) {
					System.out.println("Got " + val + " again.");
				} else {
					System.out.println("Got " + val);
				}
				_resultSet.add(val);

			} catch (NumberFormatException nfe) {
				Library.logger().info("BaseLibraryTest: unexpected content - not integer. Name: " + contents.content());
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
			ContentObject putResult = library.put(ContentName.fromNative(baseName, Integer.toString(i)), new Integer(i).toString().getBytes());
			System.out.println("Put " + i + " done");
			checkPutResults(putResult);
		}
	}
	
	public class GetThread implements Runnable {
		protected CCNLibrary library = null;
		int count = 0;
		int id = 0;
		public GetThread(int n, int id) throws ConfigurationException, IOException {
			library = CCNLibrary.open();
			count = n;
			this.id = id;
			if (DO_TAP) {
				try {
					((CCNLibrary)library).getNetworkManager().setTap("CCN_DEBUG_DATA/LibraryTestDebug_" + Integer.toString(id) + "_get");
				} catch (IOException ie) {
				}
			}
		}
		public void run() {
			try {
				System.out.println("Get thread started");
				getResults(ContentName.fromNative(PARENT_NAME, Integer.toString(id)), count, library);
				System.out.println("Get thread finished");
				((CCNLibrary)library).getNetworkManager().shutdown();
			} catch (Throwable ex) {
				error = ex;
			}
		}
	}
	
	public class PutThread implements Runnable {
		protected CCNLibrary library = null;
		int count = 0;
		int id = 0;
		public PutThread(int n, int id) throws ConfigurationException, IOException {
			library = CCNLibrary.open();
			count = n;
			this.id = id;
			if (DO_TAP) {
				try {
					((CCNLibrary)library).getNetworkManager().setTap("CCN_DEBUG_DATA/LibraryTestDebug_" + Integer.toString(id) + "_put");
				} catch (IOException ie) {
				}
			}
		}
		public void run() {
			try {
				System.out.println("Put thread started");
				doPuts(ContentName.fromNative(PARENT_NAME, Integer.toString(id)), count, library);
				System.out.println("Put thread finished");
				((CCNLibrary)library).getNetworkManager().shutdown();
			} catch (Throwable ex) {
				error = ex;
				Library.logger().warning("Exception in run: " + ex.getClass().getName() + " message: " + ex.getMessage());
				Library.logStackTrace(Level.WARNING, ex);
			}
		}
	}
	
	public class GetServer implements Runnable, CCNInterestListener {
		protected CCNLibrary library = null;
		int count = 0;
		int next = 0;
		Semaphore sema = new Semaphore(0);
		HashSet<Integer> accumulatedResults = new HashSet<Integer>();
		int id;
		
		public GetServer(int n, int id) throws ConfigurationException, IOException {
			library = CCNLibrary.open();
			count = n;
			this.id = id;
			if (DO_TAP) {
				try {
					((CCNLibrary)library).getNetworkManager().setTap("CCN_DEBUG_DATA/LibraryTestDebug_" + Integer.toString(id) + "_get");
				} catch (IOException ie) {
				}
			}
		}
		public void run() {
			try {
				System.out.println("GetServer started");
				Interest interest = new Interest(ContentName.fromNative(PARENT_NAME, Integer.toString(id)));
				// Register interest
				library.expressInterest(interest, this);
				// Block on semaphore until enough data has been received
				sema.acquire();
				library.cancelInterest(interest, this);
				((CCNLibrary)library).getNetworkManager().shutdown();

			} catch (Throwable ex) {
				error = ex;
			}
		}
		public synchronized Interest handleContent(ArrayList<ContentObject> results, Interest interest) {
			Interest newInterest = null;
			for (ContentObject contentObject : results) {
				try {
					int val = Integer.parseInt(new String(contentObject.content()));
					if (!accumulatedResults.contains(val)) {
						accumulatedResults.add(val);
						System.out.println("Got " + val);
					}
					newInterest = Interest.next(contentObject, null);
				} catch (NumberFormatException nfe) {
					Library.logger().info("Unexpected content, " + contentObject.name() + " is not an integer!");
				}
			}
			checkGetResults(results.get(0));
			
			if (accumulatedResults.size() >= count) {
				System.out.println("GetServer got all content: " + accumulatedResults.size() + ". Releasing semaphore.");
				sema.release();
			}
			return  newInterest;
		}
	}
	
	public class PutServer implements Runnable, CCNFilterListener {
		protected CCNLibrary library = null;
		int count = 0;
		int next = 0;
		Semaphore sema = new Semaphore(0);
		ContentName name = null;
		HashSet<Integer> accumulatedResults = new HashSet<Integer>();
		int id;
		
		public PutServer(int n, int id) throws ConfigurationException, IOException {
			library = CCNLibrary.open();
			count = n;
			this.id = id;
			if (DO_TAP) {
				try {
					((CCNLibrary)library).getNetworkManager().setTap("CCN_DEBUG_DATA/LibraryTestDebug_" + Integer.toString(id) + "_put");
				} catch (IOException ie) {
				}
			}
		}
		
		public void run() {
			try {
				System.out.println("PutServer started");
				// Register filter
				name = ContentName.fromNative(PARENT_NAME, Integer.toString(id));
				library.registerFilter(name, this);
				// Block on semaphore until enough data has been received
				sema.acquire();
				library.unregisterFilter(name, this);
				System.out.println("PutServer finished.");
				((CCNLibrary)library).getNetworkManager().shutdown();

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
							ContentObject putResult = library.put(ContentName.fromNative(name, Integer.toString(val)), Integer.toString(next).getBytes());
							System.out.println("Put " + val + " done");
							checkPutResults(putResult);
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
