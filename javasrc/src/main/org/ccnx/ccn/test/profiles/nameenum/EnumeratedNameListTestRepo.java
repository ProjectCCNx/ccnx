/*
 * A CCNx library test.
 *
 * Copyright (C) 2008-2012 Palo Alto Research Center, Inc.
 *
 * This work is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License version 2 as published by the
 * Free Software Foundation. 
 * This work is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License
 * for more details. You should have received a copy of the GNU General Public
 * License along with this program; if not, write to the
 * Free Software Foundation, Inc., 51 Franklin Street, Fifth Floor,
 * Boston, MA 02110-1301, USA.
 */

package org.ccnx.ccn.test.profiles.nameenum;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Random;
import java.util.SortedSet;
import java.util.logging.Level;

import junit.framework.Assert;

import org.bouncycastle.util.Arrays;
import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.impl.CCNFlowControl.SaveType;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.CCNStringObject;
import org.ccnx.ccn.profiles.nameenum.EnumeratedNameList;
import org.ccnx.ccn.protocol.Component;
import org.ccnx.ccn.protocol.ContentName;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;



/**
 * Test the synchronous interface to name enumeration.
 * Put a bunch of data in repo (in one directory)
 * Make a enumerate name list of directory (check that it works)
 * call update in background on enumerated name list (asynchronously)
 * add data using a different interface and see if it shows up on the lists
 */
public class EnumeratedNameListTestRepo { 
	
	EnumeratedNameList testList; //the enumeratednamelist object used to test the class
	
	static Random rand = new Random();
	
	static final String directoryString = "/test/parc" + "/directory-";
	static final String directoryString2 = "/test/parc" + "/directory2-";
	static final String directoryString3 = "/test/parc" + "/directory3-";
	static ContentName directory;
	static ContentName directory2;
	static ContentName directory3;

	static String nameString = "name-";
	static String name1String;
	static String name2String;
	static String name3String;
	static String name4String;
	static String name5String;
	static String name6String;

	static ContentName name1;
	static ContentName name2;
	static ContentName name3;
	
	// For thread test
	static ContentName name4;
	static ContentName name5;
	static ContentName name6;
	static ContentName name7;
	static ContentName name8;
	static ContentName name9;
	static CCNHandle putHandle;
		
	static String prefix1StringError = "/park.com/csl/ccn/repositories";
	ArrayList<ContentName> names;
	static ContentName brokenPrefix;
	ContentName c1;
	ContentName c2;
	
	static int contentSeenNoPool = 0;
	static int contentSeenPool = 0;

	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		//assign content names from strings to content objects
		
		// randomize names to minimize stateful effects of ccnd/repo caches.
		directory = ContentName.fromNative(directoryString + Integer.toString(rand.nextInt(10000)));
		directory2 = ContentName.fromNative(directoryString2 + Integer.toString(rand.nextInt(10000)));
		directory3 = ContentName.fromNative(directoryString3 + Integer.toString(rand.nextInt(10000)));
		
		// These must all be different
		int value = rand.nextInt(10000);
		name1String = nameString + Integer.toString(value);
		name2String = nameString + Integer.toString(value+1);
		name3String = nameString + Integer.toString(value+2);
		name4String = nameString + Integer.toString(value+3);

		name1 = new ContentName(directory, name1String);
		name2 = new ContentName(directory, name2String);
		name3 = new ContentName(directory, name3String);
		name4 = new ContentName(directory2, name1String);
		name5 = new ContentName(directory2, name2String);
		name6 = new ContentName(directory2, name3String);
		name7 = new ContentName(directory2, name4String);
		name8 = new ContentName(directory3, name1String);
		name9 = new ContentName(directory3, name2String);
		brokenPrefix = ContentName.fromNative(prefix1StringError);
		putHandle = CCNHandle.open();
	}
	
	@AfterClass
	public static void tearDownAfterClass() {
		putHandle.close();
	}
	
	@Test
	public void testEnumeratedName() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testEnumeratedName");
		
		try {
			CCNHandle handle = CCNHandle.open();
		
			Assert.assertNotNull(directory);
			Assert.assertNotNull(name1);
			Assert.assertNotNull(name2);
			Assert.assertNotNull(name3);
			Assert.assertNotNull(brokenPrefix);

			Log.info(Log.FAC_TEST, "*****************Creating Enumerated Name List Object");
			//creates Enumerated Name List
			testList = new EnumeratedNameList(directory, putHandle);

			Log.info(Log.FAC_TEST, "*****************assert creation of handle and enumeratednamelist object");
			//verify that the class and handle is setup
			Assert.assertNotNull(putHandle);
			Assert.assertNotNull(testList);

			Log.info(Log.FAC_TEST, "*****************assert creation of prefix");
			//Verify that the object has been created with the right prefix
			ContentName prefixTest = testList.getName();
			Assert.assertNotNull(prefixTest);
			Log.info(Log.FAC_TEST, "***************** Prefix is "+ prefixTest.toString());
			Assert.assertEquals(prefixTest, directory);
			Assert.assertFalse(brokenPrefix.equals(prefixTest));

			//run it on a name that isn't there and make sure it is empty
			// DKS -- this won't work -- it will wait forever. The point is not to enumerate,
			// the point is to wait for an answer that says something, potentially forever. No repo
			// currently NACKs -- answers "nothing there", so this will simply wait until something
			// appears.
			//testList.waitForData();

			Log.info(Log.FAC_TEST, "****************** adding name1 to repo");

			// adding content to repo
			ContentName latestName = addContentToRepo(name1, handle);
			testList.waitForChildren();
			Log.info(Log.FAC_TEST, "Added data to repo: " + latestName);

			//testing that the enumerator has new data
			Assert.assertTrue(testList.hasNewData());

			//Testing that hasNewData returns true
			Assert.assertTrue(testList.hasNewData());

			//gets new data
			SortedSet<ContentName> returnedBytes = testList.getNewData();

			Assert.assertNotNull(returnedBytes);
			Assert.assertFalse(returnedBytes.isEmpty());
			
			// getNewData gets *new* data -- you got the last new data, there won't be any more
			// until you add something else to the repo. i.e. this next call would block
			// until there was new data for getNewData to return, and it *wouldn't* match the previous set.
			// Assert.assertEquals(testList.getNewData(), returnedBytes.size());
			System.out.println("Got " + returnedBytes.size() + " children: " + returnedBytes);
			//only one thing has been added, so we can only expect one name
			//System.out.println("Predicted strings " + name1String + ", " + name2String + ", " + name3String);
			System.out.println("Predicted strings " + name1String);
			// DKS -- previous version of this was failing:
			// Assert.assertEquals(name1String.getBytes(UTF8), returnedBytes.get(0)));
			// this will fail, because byte [] does not define equals (it's a native type, not an object), so
			// you get Object.equals -- which tests if the two pointers are the same.
			// If we run this more than once on the same repo, will get all the data back -- so won't know which child
			// is first. Don't try to test on the content.
			//Assert.assertTrue(Arrays.areEqual(name1String.getBytes(UTF8), returnedBytes.first().component(0)));

			
			System.out.print("names in list:");
			for(ContentName n: returnedBytes)
				System.out.print(" "+n);
			System.out.println();
			
			//testing that children exist
			// DKS -- if you're testing a boolean, use assertTrue, not assertNotNull
			Assert.assertTrue(testList.hasChildren());

			//Testing that Name1 Exists
			// only true if run on clean repo -- if not clean repo and clean ccnd cache, might be in second response
			Assert.assertTrue(testList.hasChild(name1String));
			
			// Only definite if run on fresh repo
			//as long as the EnumeratedNameList object isn't starting a new interest, this is correct.  a repo
			//  wouldn't return old names after the last response
			Assert.assertFalse(testList.hasNewData());
			// Now add some more data
			
			System.out.println("adding name2: "+name2);
			addContentToRepo(name2, handle);
			System.out.println("adding name3: "+name3);
			addContentToRepo(name3, handle);
			
			//both of these actions could generate a new response since there is an outstanding interest on the data.
			//this means the response can come a few different ways
			//1 - an interest.last request gets there and sets the interest flag.  then a save will generate a response
			//2 - an interest.last request gets there after the save, generating a new response
			//3 - same thing happens for the second object as 1
			//4 - same thing happens for the second object as 2
			//5 - after both things are added an interest.last arrives from the CCNNameEnumerator.
			
			
			SortedSet<ContentName> returnedBytes2 = testList.getNewData(); // will block for new data
			Assert.assertNotNull(returnedBytes2);
			
			System.out.print("names in list after adding name2 and name3:");
			for(ContentName n: returnedBytes2)
				System.out.print(" "+n);
			System.out.println();
			
			System.out.print("names in testlist after adding name2 and name3:");
			for(ContentName n: testList.getChildren())
				System.out.print(" "+n);
			System.out.println();
			
			// Might have older stuff from previous runs, so don't insist we get back only what we put in.
			System.out.println("Got new data, second round size: " + returnedBytes2.size() + " first round " + returnedBytes.size());
			//this is new data...  so comparing new data from one save to another doesn't really make sense
			Assert.assertTrue(returnedBytes2.size() >= 1);
			//since we have a new response, the first name has to be in there...
			Assert.assertTrue(testList.hasChild(name2String));
			//we might not have a response since the second name...  need to check again if it isn't in there yet 
			if(!testList.hasChild(name3String)){
				returnedBytes2 = testList.getNewData(); // will block for new data
			//now we have the third response...

				System.out.print("names in list after asking for new data again:");
				for(ContentName n: returnedBytes2)
					System.out.print(" "+n);
				System.out.println();
			}
			
			System.out.print("names in testlist after adding name2 and name3:");
			for(ContentName n: testList.getChildren())
				System.out.print(" "+n);
			System.out.println();
			
			Assert.assertTrue(testList.hasChild(name3String));

			
			// This will add new versions
			for (int i=0; i < 5; ++i) {
				latestName = addContentToRepo(name1, handle);
				Log.info(Log.FAC_TEST, "Added data to repo: " + latestName);
			}
			
			EnumeratedNameList versionList = new EnumeratedNameList(name1, handle);
			versionList.waitForChildren();
			Assert.assertTrue(versionList.hasNewData());
			// Even though the addition of versions above is blocking and the new EnumeratedNameList
			// is not created to start enumerating names until after the versions have been written,
			// we don't have a guarantee that the repository will have fully processed the writes 
			// before it answers the name enumeration request.  (At some point when full repository 
			// commitment is obtained before returning from write this may change).  There is a timing 
			// race with the last content written and the first name enumeration result.  For this reason
			// we must be prepared to wait a second time.
			// It could be possible that only waiting one more time is not sufficient...  if the writes are very slow,
			// the timing could work out that there is a response per object.  adding loop to account for that
			
			for(int attempts = 1; attempts < 5; attempts++){
				// 5 versions written just above plus 1 earlier addition under name1
				versionList.getNewData(); // ignore result, we want to look at entire set once available
				if(versionList.getChildren().size() >= 6)
					attempts = 5;
			}
			// Now we should have everything
			ContentName latestReturnName = versionList.getLatestVersionChildName();
			System.out.println("Got children: " + versionList.getChildren());
			System.out.println("Got latest name " + latestReturnName + " expected " + new ContentName(latestName.lastComponent()));
			Assert.assertTrue(Arrays.areEqual(latestName.lastComponent(), latestReturnName.lastComponent()));
			
		} catch (Exception e) {
			Log.logException(Log.FAC_TEST, Level.WARNING, "Failed test with exception " + e.getMessage(), e);
			Assert.fail("Failed test with exception " + e.getMessage());
		}
		
		Log.info(Log.FAC_TEST, "Completed testEnumeratedName");
	}
	
	@Test
	public void testEnumeratedNameListWithThreads() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testEnumeratedNameListWithThreads");

		EnumeratedNameList poolList = new EnumeratedNameList(directory3, putHandle);
		Thread poolThread = new Thread(new WaiterThreadForPool(poolList));
		poolThread.start();
		addContentToRepo(name8, putHandle);
		addContentToRepo(name9, putHandle);
		poolOps(poolList);
		Assert.assertEquals(2, contentSeenPool);
		EnumeratedNameList noPoolList = new EnumeratedNameList(directory2, putHandle);
		Thread noPoolThread = new Thread(new WaiterThread(noPoolList));
		noPoolThread.start();
		addContentToRepo(name4, putHandle);
		addContentToRepo(name5, putHandle);
		addContentToRepo(name6, putHandle);
		addContentToRepo(name7, putHandle);
		noPoolOps(noPoolList);
		Assert.assertEquals(4, contentSeenNoPool);
		
		Log.info(Log.FAC_TEST, "Completed testEnumeratedNameListWithThreads");
	}
	
	private class WaiterThreadForPool implements Runnable {
		private EnumeratedNameList myList = null;
		
		private WaiterThreadForPool(EnumeratedNameList list) {
			this.myList = list;
		}
		public void run() {
			poolOps(myList);
		}
	}
	
	public class WaiterThread implements Runnable {
		private EnumeratedNameList myList = null;
		
		private WaiterThread(EnumeratedNameList list) {
			this.myList = list;
		}
		public void run() {
			noPoolOps(myList);
		}
	}
	
	private static void poolOps(EnumeratedNameList list) {
		long currentTime = System.currentTimeMillis();
		long lastTime = currentTime + (2 * SystemConfiguration.MAX_TIMEOUT);
		while (contentSeenPool < 2 && currentTime < lastTime) {
			synchronized (list) {
				SortedSet<ContentName> names = list.getNewDataThreadPool(SystemConfiguration.MAX_TIMEOUT);
				if (null != names)
					contentSeenPool += names.size();
			}
			currentTime = System.currentTimeMillis();
		}
		list.shutdown();
	}
	
	private static void noPoolOps(EnumeratedNameList list) {
		long currentTime = System.currentTimeMillis();
		long lastTime = currentTime + (4 * SystemConfiguration.MAX_TIMEOUT);
		while (contentSeenNoPool < 4 && currentTime < lastTime) {
			synchronized (list) {
				SortedSet<ContentName> names = list.getNewData(SystemConfiguration.MAX_TIMEOUT);
				if (null != names)
					contentSeenNoPool += names.size();
			}
			currentTime = System.currentTimeMillis();
		}
		list.shutdown();
	}
	
	/*
	 * Adds data to the repo for testing
	 * */
	private ContentName addContentToRepo(ContentName name, CCNHandle handle) throws ConfigurationException, IOException {
		//method to load something to repo for testing
		CCNStringObject cso = 
			new CCNStringObject(name, Component.printNative(name.lastComponent()), SaveType.REPOSITORY, handle);
		cso.save();
		Log.info(Log.FAC_TEST, "Saved new object: " + cso.getVersionedName());
		return cso.getVersionedName();	
	}
}
