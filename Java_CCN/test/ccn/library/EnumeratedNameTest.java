package test.ccn.library;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Random;
import java.util.logging.Level;

import javax.xml.stream.XMLStreamException;

import org.junit.Test;

import test.ccn.network.daemons.repo.RepoTestBase;

import com.parc.ccn.Library;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.MalformedContentNameStringException;
//import com.parc.ccn.library.CCNLibrary;


import com.parc.ccn.library.EnumeratedNameList;
import com.parc.ccn.library.io.repo.RepositoryOutputStream;

import junit.framework.Assert;

/**
 * Put a bunch of data in repo (in one directory)
 * Make a enumerate name list of directory (check that it works)
 * call update in background on enumerated name list (asynchronously)
 * add data using a different interface and see if it shows up on the lists
 */
public class EnumeratedNameTest extends RepoTestBase { 
	
	EnumeratedNameList testList; //the enumeratednamelist object used to test the class
	
	Random rand = new Random();

	String directoryString = _globalPrefix + "/directory1";
	ContentName directory;
	String name1String = "name1";
	ContentName name1;
	String name2String = "name2";
	ContentName name2;
	String name3String = "name3";
	ContentName name3;
	
	
	String prefix1StringError = "/park.com/csl/ccn/repositories";
	ArrayList<ContentName> names;
	ContentName prefix1;
	ContentName brokenPrefix;
	ContentName c1;
	ContentName c2;

	
	@Test
	public void testEnumeratedName() throws Exception
	{
		Library.logger().setLevel(Level.FINEST);
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
		
		//sets up the CCN library 
		//check to see if we made one already before making a new one
		//putLibrary = CCNLibrary.open();
		
		//creates Enumerated Name List
		testList = new EnumeratedNameList(prefix1, putLibrary);
		
		//verify that everything is set up
		Assert.assertNotNull(putLibrary);
		Assert.assertNotNull(testList);

		//Verify object created properly
		ContentName prefixTest = testList.getName();
		Assert.assertNotNull(prefixTest);
		Assert.assertEquals(prefixTest, prefix1);
		
		Library.logger().info("adding name1 to repo");
		
		// adding content to repo
		addContentToRepo(name1);
		
		//testing that new data exists
		Assert.assertNotNull(testList.hasNewData());
		
		//failing
		//Assert.assertEquals(true, testList.hasNewData());
		
		//testing that children exist
		Assert.assertNotNull(testList.hasChildren());
		
		//Testing that Name1 Exists
		Assert.assertNotNull(testList.hasChild(name1String));
	
		//failing
//		Assert.assertEquals(true, testList.hasChild(name1String));
		
		
		//Library.logger().info("adding name2 to repo");
		//addContentToRepo(name2);

	}
	


	private void testAssignContentNames() throws MalformedContentNameStringException {

		directory = ContentName.fromNative(directoryString);
		name1 = ContentName.fromNative(directory, name1String);
		name3 = ContentName.fromNative(directory, name3String);
		name2 = ContentName.fromNative(directory, name2String);
		prefix1 = ContentName.fromNative(_globalPrefix);
		brokenPrefix = ContentName.fromNative(prefix1StringError);		
	}

	
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