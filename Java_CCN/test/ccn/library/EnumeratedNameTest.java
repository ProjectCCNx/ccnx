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

import test.ccn.network.daemons.repo.RepoTestBase;

import com.parc.ccn.Library;
import com.parc.ccn.config.ConfigurationException;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.security.PublisherPublicKeyDigest;
import com.parc.ccn.data.util.CCNSerializableObject;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.library.EnumeratedNameList;

/**
 * Put a bunch of data in repo (in one directory)
 * Make a enumerate name list of directory (check that it works)
 * call update in background on enumerated name list (asynchronously)
 * add data using a different interface and see if it shows up on the lists
 */
public class EnumeratedNameTest extends RepoTestBase { 
	
	EnumeratedNameList testList; //the enumeratednamelist object used to test the class
	
	Random rand = new Random();
	static final String UTF8 = "UTF-8";
	
	static final String directoryString = _globalPrefix + "/directory1";
	static ContentName directory;
	static final String name1String = "name1";
	static ContentName name1;
	static final String name2String = "name2";
	static ContentName name2;
	static final String name3String = "name3";
	static ContentName name3;
		
	static String prefix1StringError = "/park.com/csl/ccn/repositories";
	ArrayList<ContentName> names;
	static ContentName brokenPrefix;
	ContentName c1;
	ContentName c2;

	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		//assign content names from strings to content objects
		
		directory = ContentName.fromNative(directoryString);
		name1 = ContentName.fromNative(directory, name1String);
		name2 = ContentName.fromNative(directory, name2String);
		name3 = ContentName.fromNative(directory, name3String);
		brokenPrefix = ContentName.fromNative(prefix1StringError);
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
			Assert.assertNotSame(brokenPrefix, prefixTest);

			//run it on a name that isn't there and make sure it is empty
			// DKS -- this won't work -- it will wait forever. The point is not to enumerate,
			// the point is to wait for an answer that says something, potentially forever. No repo
			// currently NACKs -- answers "nothing there", so this will simply wait until something
			// appears.
			//testList.waitForData();

			Library.logger().info("****************** adding name1 to repo");

			// adding content to repo
			addContentToRepo(name1, library);
			testList.waitForData();

			//testing that the enumerator has new data
			Assert.assertTrue(testList.hasNewData());

			//Testing that hasNewData returns true
			Assert.assertTrue(testList.hasNewData());

			//gets new data
			SortedSet<ContentName> returnedBytes = testList.getNewData();

			Assert.assertNotNull(returnedBytes);
			
			// getNewData gets *new* data -- you got the last new data, there won't be any more
			// until you add something else to the repo. i.e. this next call would block
			// until there was new data for getNewData to return, and it *wouldn't* match the previous set.
			// Assert.assertEquals(testList.getNewData(), returnedBytes.size());
			System.out.println("Got " + returnedBytes.size() + " children, first one is " + 
					ContentName.componentPrintNative(returnedBytes.first().component(0)));
			// DKS -- previous version of this was failing:
			// Assert.assertEquals(ame1String.getBytes(UTF8), returnedBytes.get(0)));
			// this will fail, because byte [] does not define equals (it's a native type, not an object), so
			// you get Object.equals -- which tests if the two pointers are the same.
			Assert.assertTrue(Arrays.areEqual(name1String.getBytes(UTF8), returnedBytes.first().component(0)));

			//testing that children exist
			// DKS -- if you're testing a boolean, use assertTrue, not assertNotNull
			Assert.assertTrue(testList.hasChildren());

			//Testing that Name1 Exists
			Assert.assertTrue(testList.hasChild(name1String));

			//Tests to see if that string name exists
			Assert.assertTrue(testList.hasChild(name1String));
			
			Assert.assertFalse(testList.hasNewData());
			// Now add some more data
			addContentToRepo(name2, library);
			addContentToRepo(name3, library);
			
			// DKS -- TODO fail: this next is not returning; repo isn't responding when data is updated, or
			// we're getting stuff out of ccnd's cache
			SortedSet<ContentName> returnedBytes2 = testList.getNewData(); // will block for new data
			
			System.out.println("Got new data, second round size: " + returnedBytes2 + " first round " + returnedBytes);
			Assert.assertTrue(returnedBytes2.size() > returnedBytes.size());
			
			// This will add new versions
			addContentToRepo(name1, library);
			addContentToRepo(name1, library);
			addContentToRepo(name1, library);
			ContentName latestName = addContentToRepo(name1, library); // 5 versions total
			
			EnumeratedNameList versionList = new EnumeratedNameList(name1, library);
			versionList.waitForData();
			Assert.assertTrue(versionList.hasNewData());
			ContentName latestReturnName = versionList.getLatestVersionChildName();
			System.out.println("Got latest name " + latestReturnName + " expected " + new ContentName(new byte [][]{latestName.lastComponent()}));
			Assert.assertTrue(Arrays.areEqual(latestName.lastComponent(), latestReturnName.lastComponent()));
			
		} catch (Exception e) {
			Library.logException("Failed test with exception " + e.getMessage(), e);
			Assert.fail("Failed test with exception " + e.getMessage());
		}			
	}
	
	class CCNStringObject extends CCNSerializableObject<String> {

		public CCNStringObject(ContentName name, String data, CCNLibrary library) throws ConfigurationException, IOException {
			super(String.class, name, data, library);
		}
		
		public CCNStringObject(ContentName name, PublisherPublicKeyDigest publisher,
				CCNLibrary library) throws ConfigurationException, IOException, XMLStreamException {
			super(String.class, name, publisher, library);
		}
		
		/**
		 * Read constructor -- opens existing object.
		 * @param type
		 * @param name
		 * @param library
		 * @throws XMLStreamException
		 * @throws IOException
		 * @throws ClassNotFoundException 
		 */
		public CCNStringObject(ContentName name, 
				CCNLibrary library) throws ConfigurationException, IOException, XMLStreamException {
			super(String.class, name, (PublisherPublicKeyDigest)null, library);
		}
		
		public CCNStringObject(ContentObject firstBlock,
				CCNLibrary library) throws ConfigurationException, IOException, XMLStreamException {
			super(String.class, firstBlock, library);
		}
		
		public String string() { return data(); }
	}
	
	
	/*
	 * Adds data to the repo for testing
	 * DKS -- previous version that used repo streams somehow wasn't getting data in.
	 * */
	private ContentName addContentToRepo(ContentName name, CCNLibrary library) throws ConfigurationException, IOException {
		//method to load something to repo for testing
		// DKS -- don't know why this wasn't working
		/*
		RepositoryOutputStream ros = putLibrary.repoOpen(name, null, putLibrary.getDefaultPublisher());
		ros.setTimeout(5000);
		byte [] data = "Testing 1 2 3".getBytes();
		ros.write(data, 0, data.length);
		ros.close();
		*/
		CCNStringObject cso = new CCNStringObject(name, ContentName.componentPrintNative(name.lastComponent()), library);
		cso.saveToRepository();
		System.out.println("Saved new object: " + cso.getName());
		return cso.getName();
	}
	
}