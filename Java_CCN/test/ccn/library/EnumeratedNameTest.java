package test.ccn.library;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Random;

import org.junit.Test;

import com.parc.ccn.config.ConfigurationException;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.MalformedContentNameStringException;
import com.parc.ccn.data.query.BasicNameEnumeratorListener;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.library.CCNNameEnumerator;

import com.parc.ccn.library.EnumeratedNameList;

import junit.framework.Assert;

/**
 * Put a bunch of data in repo (in one directory)
 * Make a enumerate name list of directory (check that it works)
 * call update in background on enumerated name list (asynchronously)
 * add data using a different interface and see if it shows up on the lists
 */
public class EnumeratedNameTest implements BasicNameEnumeratorListener{
	CCNLibrary putLibrary;
	CCNLibrary getLibrary;
	CCNNameEnumerator putne;
	CCNNameEnumerator getne;

	EnumeratedNameList testList;
	
	Random rand = new Random();

	String namespaceString = "/parc.com";
	ContentName namespace;
	String name1String = "/parc.com/enumeratorTest/directory1";
	ContentName name1;
	String name2String = "/parc.com/enumeratorTest/directory1/name1";
	ContentName name2;
	String name2aString = "/parc.com/enumeratorTest/directory1/name2";
	ContentName name2a;
	String name3String = "/parc.com/enumeratorTest/directory1/name3";
	ContentName name3;
	String name1StringDirty = "/parc.com/enumeratorTest/directory1/DirtyName";
	ContentName name1Dirty;
	
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
		System.out.println("Starting CCNNameEnumerator Test");
		
		//assign content names
		testAssignContentNames();
		
		//set up CCN libraries for testing
		setLibraries();
		
		//verify that everything is set up
		Assert.assertNotNull(putLibrary);
		Assert.assertNotNull(getLibrary);
		Assert.assertNotNull(putne);
		Assert.assertNotNull(getne);
		Assert.assertNotNull(testList);
		
		testRegisterPrefix();
		
		testRegisterName();
		
		//testList.hasChild("");		

		//Make a enumerate name list of directory (check that it works)
		testDataExists(name1);		
	
		//Add an additional Name
	    registerAdditionalName();
		
		//see if it exists
		testDataExists(name1Dirty);
		
		//get children from prefix
		//test has children
//		testHasChildren();
	}

	public void testRegisterPrefix() throws Exception{
		prefix1 = ContentName.fromNative(prefix1String);

		System.out.println("registering prefix: "+prefix1.toString());

		getne.registerPrefix(prefix1);
	}
	
/*
	private void testHasChildren() {
		Assert.assertTrue(testList.hasChildren());
	}
*/

	private void testAssignContentNames() throws MalformedContentNameStringException {
		namespace = ContentName.fromNative(namespaceString);
		name1 = ContentName.fromNative(name1String);
		name2 = ContentName.fromNative(name2String);
		name2a = ContentName.fromNative(name2aString);
		name3 = ContentName.fromNative(name3String);
		prefix1 = ContentName.fromNative(prefix1String);
		brokenPrefix = ContentName.fromNative(prefix1StringError);		
	}

	private void testDataExists(ContentName name) throws IOException {
		//Seems to be returning true for most stuff
		//need to look into this more
		
		EnumeratedNameList testEnumerator;
		testEnumerator = EnumeratedNameList.exists(name, prefix1, getLibrary);
		Assert.assertNotNull(testEnumerator);
	}

	private void createEnumeratedNameList() throws IOException {
		if (null == testList)
			testList = new EnumeratedNameList(prefix1, putLibrary);
	}

	public void testRegisterName() throws InterruptedException {
		putne.registerNameSpace(namespace);
		putne.registerNameForResponses(name1);
		putne.registerNameForResponses(name2);
		putne.registerNameForResponses(name2a);
		ContentName nullName = null;
		putne.registerNameForResponses(nullName);
		
		while(!putne.containsRegisteredName(name2a)){
			Thread.sleep(rand.nextInt(50));
		}
		
		//the names are registered...
		System.out.println("the names are now registered");
	}
	
	//now add new name
	public void registerAdditionalName() throws Exception {
		name1Dirty = ContentName.fromNative(name1StringDirty);
		putne.registerNameForResponses(name1Dirty);
	}

	/* 
	 * function to open and set the put and get libraries
	 * also creates and sets the Name Enumerator Objects
	 * 
	 */
	public void setLibraries() throws ConfigurationException, IOException{
		putLibrary = CCNLibrary.open();
		getLibrary = CCNLibrary.open();
		createEnumeratedNameList();
		putne = new CCNNameEnumerator(putLibrary, this);
		getne = new CCNNameEnumerator(getLibrary, testList);
	}

	public int handleNameEnumerator(ContentName prefix,
			ArrayList<ContentName> names) {
		// TODO Auto-generated method stub
		
		System.out.println("Call back in the enumerated name test");
		return 0;
	}
}