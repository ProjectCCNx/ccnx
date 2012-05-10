package org.ccnx.ccn.test.profiles.sync;

import java.io.IOException;
import java.lang.reflect.Array;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Random;
import java.util.Vector;

import junit.framework.Assert;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.CCNSync;
import org.ccnx.ccn.CCNSyncHandler;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.impl.security.crypto.CCNDigestHelper;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.RepositoryFileOutputStream;
import org.ccnx.ccn.io.content.ConfigSlice;
import org.ccnx.ccn.profiles.SegmentationProfile;
import org.ccnx.ccn.profiles.metadata.MetadataProfile;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;
import org.ccnx.ccn.test.CCNTestHelper;
import org.junit.Before;
import org.junit.Test;

public class CCNSyncTest implements CCNSyncHandler{
	
	public static CCNTestHelper testHelper = new CCNTestHelper(CCNSyncTest.class);
	
	ContentName prefix;
	ContentName topo;
	int BUF_SIZE = 1024;
	int maxBytes = 10 * BUF_SIZE;
	Vector<ContentName> callbackNames = new Vector<ContentName>();
	
	@Before
	public void setUpNameSpace() {
		prefix = testHelper.getTestNamespace("ccnSyncTest");
		topo = testHelper.getTestNamespace("topoPrefix");
		Log.fine(Log.FAC_TEST, "setting up namespace for sync test  data: {0} syncControlTraffic: {1}", prefix, topo);
	}
	
	@Test
	public void testSyncStartWithHandle() {
		Log.info(Log.FAC_TEST, "Starting testSyncStartWithHandle");
		ContentName prefix1;
		try {
			prefix1 = prefix.append("slice1");
			CCNHandle handle = CCNHandle.open();

			CCNSync sync1 = new CCNSync();
			ConfigSlice slice1 = sync1.startSync(handle, topo, prefix1, null, this);
			
			sync1.stopSync(this, slice1);
		} catch (MalformedContentNameStringException e) {
			Log.info(Log.FAC_TEST, "failed to create name for slice prefix: {0}", e.getMessage());
			Assert.fail();
		} catch (ConfigurationException e) {
			Log.info(Log.FAC_TEST, "failed to create handle for test: {0}", e.getMessage());
			Assert.fail();
		} catch (IOException e) {
			Log.info(Log.FAC_TEST, "failed to create slice for test: {0}", e.getMessage());
			Assert.fail();
		} 
		Log.info(Log.FAC_TEST, "Completed testSyncStartWithHandle");
	}
	
	@Test
	public void testSyncStartWithoutHandle() {
		Log.info(Log.FAC_TEST, "Starting testSyncStartWithoutHandle");
		
		ContentName prefix1;
		try {
			prefix1 = prefix.append("slice2");
			CCNSync sync1 = new CCNSync();
			ConfigSlice slice2 = sync1.startSync(topo, prefix1, null, this);
			
			sync1.stopSync(this, slice2);
		} catch (MalformedContentNameStringException e) {
			Log.info(Log.FAC_TEST, "failed to create name for slice prefix: {0}", e.getMessage());
			Assert.fail();
		} catch (IOException e) {
			Log.info(Log.FAC_TEST, "failed to create slice for test: {0}", e.getMessage());
			Assert.fail();
		} catch (ConfigurationException e){
			Log.info(Log.FAC_TEST, "failed to create slice for test: {0}", e.getMessage());
			Assert.fail();
		}
		Log.info(Log.FAC_TEST,"Finished running testSyncStartWithoutHandle");
	}

	@Test
	public void testSyncClose() {
		Log.info(Log.FAC_TEST, "Starting testSyncClose");
		
		ContentName prefix1;
		try {
			prefix1 = prefix.append("slice3");
			CCNSync sync1 = new CCNSync();
			ConfigSlice slice3 = sync1.startSync(topo, prefix1, null, this);
			
			//the slice should be written..  now save content and get a callback.
			System.out.println("writing out file: "+prefix1);
			int segments = writeFile(prefix1);
			int segmentCheck = checkCallbacks(prefix1, segments);
			if (segmentCheck!=0)
				Assert.fail("Did not receive all of the callbacks");
			else
				System.out.println("I got all the callbacks for part 1 of testSyncClose!");
			
			//now close the callback interface
			sync1.stopSync(this, slice3);

			
			//then close and make sure we don't get a callback
			prefix1 = prefix1.append("round2");
			System.out.println("writing out file: "+prefix1);
			segments = writeFile(prefix1);  //this should be a new version
			segmentCheck = checkCallbacks(prefix1, segments);
			if (segmentCheck != segments) {
				//we must have gotten callbacks...  bad.
				Assert.fail("received callbacks after interface was closed.  ERROR");
			}
			System.out.println("I didn't get callbacks after I stopped sync for myself!");
			
			
		} catch (MalformedContentNameStringException e) {
			Log.info(Log.FAC_TEST, "failed to create name for slice prefix: {0}", e.getMessage());
			Assert.fail();
		} catch (IOException e) {
			Log.info(Log.FAC_TEST, "failed to create slice for test: {0}", e.getMessage());
			Assert.fail();
		} catch (ConfigurationException e){
			Log.info(Log.FAC_TEST, "failed to create slice for test: {0}", e.getMessage());
			Assert.fail();
		}
		System.out.println("Finished running testSyncStop");
		Log.info(Log.FAC_TEST,"Finished running testSyncStop");
	}
	
	@Test
	public void testCallbackRegistration() {
		Log.info(Log.FAC_TEST, "Starting testCallbackRegistration");
		
		ContentName prefix1;
		ContentName prefix2;
		try {
			prefix1 = prefix.append("slice4");
			CCNSync sync1 = new CCNSync();
			ConfigSlice slice4 = sync1.startSync(topo, prefix1, null, this);
			prefix2 = prefix.append("slice5");
			ConfigSlice slice5 = sync1.startSync(topo, prefix2, null, this);
			
			//the slice should be written..  now save content and get a callback.
			System.out.println("writing out file: "+prefix1);
			int segments = writeFile(prefix1);
			int segments2 = writeFile(prefix2);
			int segmentCheck = checkCallbacks(prefix1, segments);
			if (segmentCheck!=0)
				Assert.fail("Did not receive all of the callbacks");
			segmentCheck = checkCallbacks(prefix2, segments2);
			if (segmentCheck!=0)
				Assert.fail("Did not receive all of the callbacks");
			
			//now close the callback interface
			sync1.stopSync(this, slice4);
			sync1.stopSync(this, slice5);
			
		} catch (MalformedContentNameStringException e) {
			Log.info(Log.FAC_TEST, "failed to create name for slice prefix: {0}", e.getMessage());
			Assert.fail();
		} catch (IOException e) {
			Log.info(Log.FAC_TEST, "failed to create slice for test: {0}", e.getMessage());
			Assert.fail();
		} catch (ConfigurationException e){
			Log.info(Log.FAC_TEST, "failed to create slice for test: {0}", e.getMessage());
			Assert.fail();
		}
		Log.info(Log.FAC_TEST,"Finished running testSyncStop");
	}
	

	@Test
	public void testSyncRestart() {
		Log.info(Log.FAC_TEST, "Starting testSyncRestart");
		
		ContentName prefix1;
		try {
			prefix1 = prefix.append("slice6");
			CCNSync sync1 = new CCNSync();
			ConfigSlice slice6 = sync1.startSync(topo, prefix1, null, this);
			
			//the slice should be written..  now save content and get a callback.
			System.out.println("writing out file: "+prefix1);
			int segments = writeFile(prefix1);
			int segmentCheck = checkCallbacks(prefix1, segments);
			if (segmentCheck!=0)
				Assert.fail("Did not receive all of the callbacks");
			else
				System.out.println("I got all the callbacks for part 1 of testSyncRestart!");
			
			//now close the callback interface
			sync1.stopSync(this, slice6);

			
			//then close and make sure we don't get a callback
			ContentName prefix1a = prefix1.append("round2");
			System.out.println("writing out file: "+prefix1a);
			segments = writeFile(prefix1a);  //this should be a new version
			segmentCheck = checkCallbacks(prefix1a, segments);
			if (segmentCheck != segments) {
				//we must have gotten callbacks...  bad.
				Assert.fail("received callbacks after interface was closed.  ERROR");
			}
			System.out.println("I didn't get callbacks after I stopped sync for myself!");
			
			//now restart sync and make sure i get everything
			ContentName prefix1b = prefix1.append("round3");
			//ConfigSlice slice3 = sync1.startSync(topo, prefix1, null, this);
			ConfigSlice slice6b = sync1.startSync(topo, prefix1, null, this);
			
			System.out.println("check if slice 6 == slice 6b, they should be equal!");
			if (slice6.equals(slice6b)) {
				System.out.println("the slices are equal!");
			} else {
				System.out.println("XXXXXXXXX  the slices are not equal!!!!");
				
				//what makes them different?
				System.out.println("slice6: "+slice6);
				System.out.println("slice6b: "+slice6b);
			}
			
			//the slice should be written..  now save content and get a callback.
			System.out.println("writing out file: "+prefix1b);
			segments = writeFile(prefix1b);
			segmentCheck = checkCallbacks(prefix1b, segments);
			if (segmentCheck!=0)
				Assert.fail("Did not receive all of the callbacks");
			else
				System.out.println("I got all the callbacks for part 1 of testSyncRestart!");
			
			//now close the callback interface
			sync1.stopSync(this, slice6b);
			
		} catch (MalformedContentNameStringException e) {
			Log.info(Log.FAC_TEST, "failed to create name for slice prefix: {0}", e.getMessage());
			Assert.fail();
		} catch (IOException e) {
			Log.info(Log.FAC_TEST, "failed to create slice for test: {0}", e.getMessage());
			Assert.fail();
		} catch (ConfigurationException e){
			Log.info(Log.FAC_TEST, "failed to create slice for test: {0}", e.getMessage());
			Assert.fail();
		}
		System.out.println("Finished running testSyncRestart");
		Log.info(Log.FAC_TEST,"Finished running testSyncRestart");
	}
	
	@Override
	public void handleContentName(ConfigSlice syncSlice, ContentName syncedContent) {
		System.out.println("Callback for name: " +syncedContent);
		synchronized (callbackNames) {
			callbackNames.add(syncedContent);
		}
	}
	
	private int writeFile(ContentName name) {
		int segmentsToWrite = 0;
		try {
			RepositoryFileOutputStream rfos = new RepositoryFileOutputStream(name.append("randomFile"), CCNHandle.getHandle());
			DigestOutputStream dos = new DigestOutputStream(rfos, MessageDigest.getInstance(CCNDigestHelper.DEFAULT_DIGEST_ALGORITHM));

			byte [] buf = new byte[BUF_SIZE];
			int count = 0;
			int towrite = 0;
			Random rand = new Random();
			int bytes = rand.nextInt(maxBytes) + 1;
			double block = (double)bytes/(double)SystemConfiguration.BLOCK_SIZE;
			segmentsToWrite = (int) (Math.ceil(block) + 1);
			System.out.println("bytes: "+bytes+" block size: "+SystemConfiguration.BLOCK_SIZE+" div: "+block +" ceil: "+(int)Math.ceil(block));
			System.out.println("will write out a "+bytes+" byte file, will have "+segmentsToWrite+" segments (1 is a header)");
			while (count < bytes) {
				rand.nextBytes(buf);
				towrite = ((bytes - count) > buf.length) ? buf.length : (bytes - count);
				dos.write(buf, 0, towrite);
				count += towrite;
			}
			dos.flush();
			dos.close();
			System.out.println("Wrote file to repository: " + rfos.getBaseName()+ " with "+segmentsToWrite+" segments");
			Log.info(Log.FAC_TEST, "Wrote file to repository: " + rfos.getBaseName());
		} catch (NoSuchAlgorithmException e) {
			Assert.fail("Cannot find digest algorithm: " + CCNDigestHelper.DEFAULT_DIGEST_ALGORITHM);
		} catch (MalformedContentNameStringException e) {
			Assert.fail("Malformed content name when creating random test file: " + CCNDigestHelper.DEFAULT_DIGEST_ALGORITHM);
		} catch (IOException e) {
			Assert.fail("Exception while writing test file to repo: " + CCNDigestHelper.DEFAULT_DIGEST_ALGORITHM);
		}
		return segmentsToWrite;

	}

	
	private int checkCallbacks(ContentName prefix, int segments) {
		System.out.println("checking for callbacks:  "+ prefix + " segments: "+segments);
		boolean[] received = (boolean[]) Array.newInstance(boolean.class, segments);
		Arrays.fill(received, false);
		boolean[]finished = (boolean[]) Array.newInstance(boolean.class, segments);
		Arrays.fill(finished, true);
		int loopsToTry = 5;
		while (segments != 0 && loopsToTry > 0) {
			try {
				Thread.sleep(500);
			} catch (InterruptedException e) {
				Log.warning(Log.FAC_TEST, "interrupted while waiting for names on callback");
			}
			synchronized(callbackNames) {
				for (ContentName n: callbackNames) {
					if (prefix.isPrefixOf(n)) {
						//this is one of our names
						if ( MetadataProfile.isHeader(n)) {
							//this is the header!
							received[segments-1] = true;
							System.out.println("got the header");
						} else {
							//this is not the header...  get the segment number
							received[(int) SegmentationProfile.getSegmentNumber(n)] = true;
							System.out.println("got segment "+SegmentationProfile.getSegmentNumber(n));
						}
						System.out.println("received: "+Arrays.toString(received)+" finished: "+Arrays.toString(finished));
						if (Arrays.equals(received, finished)) {
							//all done!
							segments = 0;
							System.out.println("got all the segments!");
							break;
						}
					}
				}
			}
			loopsToTry = loopsToTry - 1;
			System.out.println("trying to loop again looking for segments");
		}
		System.out.println("done looping, returning.  outstanding segments = "+segments);
		return segments;
	}
}
