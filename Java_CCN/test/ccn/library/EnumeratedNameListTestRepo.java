package test.ccn.library;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Random;
import java.util.SortedSet;

import javax.xml.stream.XMLStreamException;

import junit.framework.Assert;

import org.bouncycastle.util.Arrays;
import org.junit.BeforeClass;
import org.junit.Test;

import test.ccn.data.util.CCNStringObject;

import com.parc.ccn.Library;
import com.parc.ccn.config.ConfigurationException;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.library.EnumeratedNameList;

/**
 * Put a bunch of data in repo (in one directory)
 * Make a enumerate name list of directory (check that it works)
 * call update in background on enumerated name list (asynchronously)
 * add data using a different interface and see if it shows up on the lists
 */
public class EnumeratedNameListTestRepo { 
	
	EnumeratedNameList testList; //the enumeratednamelist object used to test the class
	
	static Random rand = new Random();
	
	static final String directoryString = "/test/parc" + "/directory-";
	static ContentName directory;
	static String nameString = "name-";
	static String name1String;
	static String name2String;
	static String name3String;
	static ContentName name1;
	static ContentName name2;
	static ContentName name3;
	static CCNLibrary putLibrary;
		
	static String prefix1StringError = "/park.com/csl/ccn/repositories";
	ArrayList<ContentName> names;
	static ContentName brokenPrefix;
	ContentName c1;
	ContentName c2;

	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		//assign content names from strings to content objects
		
		// randomize names to minimize stateful effects of ccnd/repo caches.
		directory = ContentName.fromNative(directoryString + Integer.toString(rand.nextInt(10000)));
		name1String = nameString + Integer.toString(rand.nextInt(10000));
		name2String = nameString + Integer.toString(rand.nextInt(10000));
		name3String = nameString + Integer.toString(rand.nextInt(10000));

		name1 = ContentName.fromNative(directory, name1String);
		name2 = ContentName.fromNative(directory, name2String);
		name3 = ContentName.fromNative(directory, name3String);
		brokenPrefix = ContentName.fromNative(prefix1StringError);
		putLibrary = CCNLibrary.open();
	}
	
	@Test
	public void testEnumeratedName() throws Exception {
		//Library.logger().setLevel(Level.FINEST);
		System.out.println("Starting Enumerated Name Test");
		
		try {
			CCNLibrary library = CCNLibrary.open();
		
			Library.logger().info("*****************Starting Enumerated Name Test");

			Assert.assertNotNull(directory);
			Assert.assertNotNull(name1);
			Assert.assertNotNull(name2);
			Assert.assertNotNull(name3);
			Assert.assertNotNull(brokenPrefix);

			Library.logger().info("*****************Creating Enumerated Name List Object");
			//creates Enumerated Name List
			testList = new EnumeratedNameList(directory, putLibrary);

			Library.logger().info("*****************assert creation of library and enumeratednamelist object");
			//verify that the class and library is setup
			Assert.assertNotNull(putLibrary);
			Assert.assertNotNull(testList);

			Library.logger().info("*****************assert creation of prefix");
			//Verify that the object has been created with the right prefix
			ContentName prefixTest = testList.getName();
			Assert.assertNotNull(prefixTest);
			Library.logger().info("***************** Prefix is "+ prefixTest.toString());
			Assert.assertEquals(prefixTest, directory);
			Assert.assertFalse(brokenPrefix.equals(prefixTest));

			//run it on a name that isn't there and make sure it is empty
			// DKS -- this won't work -- it will wait forever. The point is not to enumerate,
			// the point is to wait for an answer that says something, potentially forever. No repo
			// currently NACKs -- answers "nothing there", so this will simply wait until something
			// appears.
			//testList.waitForData();

			Library.logger().info("****************** adding name1 to repo");

			// adding content to repo
			ContentName latestName = addContentToRepo(name1, library);
			testList.waitForData();
			Library.logger().info("Added data to repo: " + latestName);

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
			addContentToRepo(name2, library);
			System.out.println("adding name3: "+name3);
			addContentToRepo(name3, library);
			
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
				latestName = addContentToRepo(name1, library);
				Library.logger().info("Added data to repo: " + latestName);
			}
			
			EnumeratedNameList versionList = new EnumeratedNameList(name1, library);
			versionList.waitForData();
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
			System.out.println("Got latest name " + latestReturnName + " expected " + new ContentName(new byte [][]{latestName.lastComponent()}));
			Assert.assertTrue(Arrays.areEqual(latestName.lastComponent(), latestReturnName.lastComponent()));
			
		} catch (Exception e) {
			Library.logException("Failed test with exception " + e.getMessage(), e);
			Assert.fail("Failed test with exception " + e.getMessage());
		}			
	}
	
	/*
	 * Adds data to the repo for testing
	 * DKS -- previous version that used repo streams somehow wasn't getting data in.
	 * */
	private ContentName addContentToRepo(ContentName name, CCNLibrary library) throws ConfigurationException, IOException, XMLStreamException {
		//method to load something to repo for testing
		// DKS -- don't know why this wasn't working
		/*
		RepositoryOutputStream ros = putLibrary.repoOpen(name, null, putLibrary.getDefaultPublisher());
		ros.setTimeout(5000);
		byte [] data = "Testing 1 2 3".getBytes();
		ros.write(data, 0, data.length);
		ros.close();
		return name;
		*/
		
		CCNStringObject cso = new CCNStringObject(name, ContentName.componentPrintNative(name.lastComponent()), library);
		cso.saveToRepository();
		System.out.println("Saved new object: " + cso.getCurrentVersionName());
		return cso.getCurrentVersionName();
		
	}
	
}