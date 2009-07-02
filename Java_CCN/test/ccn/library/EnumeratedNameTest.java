package test.ccn.library;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Random;

import javax.xml.stream.XMLStreamException;

import org.junit.Test;

import com.parc.ccn.Library;
import com.parc.ccn.config.ConfigurationException;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.MalformedContentNameStringException;
import com.parc.ccn.library.CCNLibrary;


import com.parc.ccn.library.EnumeratedNameList;
import com.parc.ccn.library.io.repo.RepositoryOutputStream;

import junit.framework.Assert;

/**
 * Put a bunch of data in repo (in one directory)
 * Make a enumerate name list of directory (check that it works)
 * call update in background on enumerated name list (asynchronously)
 * add data using a different interface and see if it shows up on the lists
 */
public class EnumeratedNameTest{ 
//implements BasicNameEnumeratorListener{
	CCNLibrary putLibrary; //the ccn library object
	
	EnumeratedNameList testList; //the enumeratednamelist object used to test the class
	
	Random rand = new Random();

	String namespaceString = "/parc.com";
	ContentName namespace;
	String directoryString = "/parc.com/enumeratorTest/directory1";
	ContentName directory;
	String name1String = "/parc.com/enumeratorTest/directory1/name1";
	ContentName name1;
	String name2String = "/parc.com/enumeratorTest/directory1/name2";
	ContentName name2;
	String name3String = "/parc.com/enumeratorTest/directory1/name3";
	ContentName name3;
	
	String prefix1String = "/parc.com/enumeratorTest";
	String prefix1StringError = "/park.com/enumeratorTest";
	ArrayList<ContentName> names;
	ContentName prefix1;
	ContentName brokenPrefix;
	ContentName c1;
	ContentName c2;

/*
	//setup method
	@BeforeClass
	
	//cleanup
	@AfterClass
*/
	
	@Test
	public void testEnumeratedName() throws Exception
	{
		
		System.out.println("Starting Enumerated Name Test");
		
		//assign content names from strings to content objects
		testAssignContentNames();
	
		Assert.assertNotNull(directory);
		Assert.assertNotNull(name1);
		Assert.assertNotNull(name2);
		Assert.assertNotNull(name3);
		
		//set up CCN libraries for testing
		// creates the name enumerator object
		// and the enumerated name object
		setLibraries();
		
		//verify that everything is set up
		Assert.assertNotNull(putLibrary);
		Assert.assertNotNull(testList);

		
		Library.logger().info("adding name1 to repo");
		
		//addContentToRepo(directory); //don't think I need to do this, double check 
		addContentToRepo(name1);
		Library.logger().info("adding name2 to repo");
		addContentToRepo(name2);
		//testRegisterPrefix();
		
		//testRegisterName();
		
		//testList.hasChild("");		

		//Make a enumerate name list of directory (check that it works)
		testDataExists(directory);		
		testDataExists(name1);
		testDataExists(name2);
		
		//Add an additional item to the repo
		addContentToRepo(name3);
		
		//see if it exists
		testDataExists(name3);
		
		//get children from prefix
		//test has children
		testHasChildren();
	}
	
	private void testHasChildren() {
		Assert.assertTrue(testList.hasChildren());
	}


	private void testAssignContentNames() throws MalformedContentNameStringException {
		namespace = ContentName.fromNative(namespaceString);
		directory = ContentName.fromNative(directoryString);
		name1 = ContentName.fromNative(name1String);
		name3 = ContentName.fromNative(name3String);
		name2 = ContentName.fromNative(name2String);
		prefix1 = ContentName.fromNative(prefix1String);
		brokenPrefix = ContentName.fromNative(prefix1StringError);		
	}

	private void testDataExists(ContentName name) throws IOException {
		//Seems to be returning true for most stuff
		//need to look into this more
		
		EnumeratedNameList testEnumerator;
		testEnumerator = EnumeratedNameList.exists(name, prefix1, putLibrary);
		Assert.assertNotNull(testEnumerator);
	}

	private void createEnumeratedNameList() throws IOException {
		if (null == testList)
			testList = new EnumeratedNameList(prefix1, putLibrary);
	}

		
	//now add new name
//	public void registerAdditionalName() throws Exception {
//		name1Dirty = ContentName.fromNative(name1StringDirty);
//		putne.registerNameForResponses(name1Dirty);
//	}

	/* 
	 * method that creates a new CCNnameEnumerator object
	 * with a reference to put library
	 * and creates a new enumerated name list 
	 * (which also creates a CCNNameEnumerator)
	 * which points to the same ccn library
	 * 
	 */
	public void setLibraries() throws ConfigurationException, IOException{
		putLibrary = CCNLibrary.open();
		createEnumeratedNameList();
		
	}

//	public int handleNameEnumerator(ContentName prefix,
//			ArrayList<ContentName> names) {
//		System.out.println("Call back in the enumerated name test");
//		return 0;
//	}
	
	/*
	 * Adds data to the repo for testing
	 * */
	private void addContentToRepo(ContentName name) throws MalformedContentNameStringException, IOException, XMLStreamException{
		//method to load something to repo for testing
		RepositoryOutputStream ros = putLibrary.repoOpen(name, null, putLibrary.getDefaultPublisher());
		ros.setTimeout(5000);
		byte [] data = "Testing 1 2 3".getBytes();
		ros.write(data, 0, data.length);
		ros.close();
	}
	
}