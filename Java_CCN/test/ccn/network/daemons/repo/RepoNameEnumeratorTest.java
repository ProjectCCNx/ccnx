package test.ccn.network.daemons.repo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Random;

import javax.xml.stream.XMLStreamException;

import org.junit.Assert;
import org.junit.Test;

import com.parc.ccn.Library;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.library.CCNNameEnumerator;
import com.parc.ccn.library.io.repo.RepositoryOutputStream;
import com.parc.ccn.library.profiles.VersioningProfile;
import com.parc.ccn.config.ConfigurationException;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.MalformedContentNameStringException;
import com.parc.ccn.data.query.BasicNameEnumeratorListener;


public class RepoNameEnumeratorTest implements BasicNameEnumeratorListener{
	CCNLibrary getLibrary;
	CCNNameEnumerator getne;
	
	String prefix1String = RepoTestBase._globalPrefix+"/nameEnumerate";
	//String prefix1String = "/repoTest/nameEnumerate";
	ContentName prefix1;
	
	Random rand = new Random();
	
	CCNLibrary putLibrary;
	
	ArrayList<ContentName> names1 = null;
	ArrayList<ContentName> names2 = null;
	ArrayList<ContentName> names3 = null;
	
	@Test
	public void repoNameEnumerationTest(){
		setLibraries();
		
		Library.logger().info("adding name1 to repo");
		addContentToRepo(prefix1String+"/name1");
		
		Library.logger().info("test register prefix");
		testRegisterPrefix();
		
		Library.logger().info("checking for first response");
		testGetResponse(1);
		
		Library.logger().info("adding second name to repo");
		addContentToRepo(prefix1String+"/name2");
		
		//make sure we get the new thing
		Library.logger().info("checking for second name added");
		testGetResponse(2);
		
		//make sure nothing new came in
		Library.logger().info("check to make sure nothing new came in");
		testGetResponse(3);
		
		Library.logger().info("test a cancelPrefix");
		testCancelPrefix();
		
		Library.logger().info("now add third thing");
		addContentToRepo(prefix1String+"/name3");
		
		//make sure we don't hear about this one
		Library.logger().info("called cancel, shouldn't hear about the third item");
		testGetResponse(3);
		
		Library.logger().info("now testing with a version in the prefix");
		ContentName versionedName = getVersionedName(prefix1String);
		addContentToRepo(new ContentName(versionedName, "versionNameTest".getBytes()));
		registerPrefix(versionedName);
		//uncomment this line when the VersioningProfile code is updated
		//testGetResponse(4);
		
	}
	
	private ContentName getVersionedName(String name){
		try {
			return VersioningProfile.addVersion(ContentName.fromNative(name));
		} catch (MalformedContentNameStringException e) {
			Assert.fail("could not create versioned name for prefix: "+name);
		}
		return null;
	}
	
	private void addContentToRepo(ContentName name){
		try{
			RepositoryOutputStream ros = putLibrary.repoOpen(name, null, putLibrary.getDefaultPublisher());
			ros.setTimeout(5000);
			byte [] data = "Testing 1 2 3".getBytes();
			ros.write(data, 0, data.length);
			ros.close();
		} catch (IOException ex) {
			ex.printStackTrace();
			Assert.fail("could not put the content into the repo ("+name+"); " + ex.getMessage());
		} catch (XMLStreamException e) {
			e.printStackTrace();
			Assert.fail("Could not open repo output stream for test.");
		}
	}
	
	private void addContentToRepo(String contentName){
		//method to load something to repo for testing
		ContentName name;
		try {
			name = ContentName.fromNative(contentName);
			addContentToRepo(name);
		} catch (MalformedContentNameStringException e) {
			e.printStackTrace();
			Assert.fail("Could not create content name from String.");
		}
	}
	
	
	public int handleNameEnumerator(ContentName prefix, ArrayList<ContentName> names) {
		Library.logger().info("I got a response from the name enumerator, with " + names.size() + " entries.");
		if (names1 == null)
			names1 = names;
		else if (names2 == null)
			names2 = names;
		else
			names3 = names;
		return 0;
	}
	
	
	/* 
	 * function to open and set the put and get libraries
	 * also creates and sets the Name Enumerator Objects
	 * 
	 */
	public void setLibraries(){
		try {
			getLibrary = CCNLibrary.open();
			getne = new CCNNameEnumerator(getLibrary, this);
			
			putLibrary = CCNLibrary.open();
		} catch (ConfigurationException e) {
			e.printStackTrace();
			Assert.fail("Failed to open libraries for tests");
		} catch (IOException e) {
			e.printStackTrace();
			Assert.fail("Failed to open libraries for tests");
		}
	}
	
	
	public void testRegisterPrefix(){
		//adding a second prefix...  should never get a response,
		prefix1 = registerPrefix(prefix1String);
		registerPrefix(prefix1String+"/doesnotexist");
	}
	
	public void registerPrefix(ContentName prefix){
		try {
			getne.registerPrefix(prefix);
		} catch (IOException e) {
			System.err.println("error registering prefix");
			e.printStackTrace();
			Assert.fail("error registering prefix");
		}
	}
	
	public ContentName registerPrefix(String pre){
		try {
			ContentName p = ContentName.fromNative(pre);
			registerPrefix(p);
			return p;
		} catch (Exception e) {
			Assert.fail("Could not create ContentName from "+prefix1String);
		}
		return null;
	}
	
	
	public void testCancelPrefix(){
		getne.cancelPrefix(prefix1);
	}
	
	
	public void testGetResponse(int count){
		try {
			int i = 0;
			while (i < 200) {
				Thread.sleep(rand.nextInt(50));
				i++;
			}
			
			//the names are registered...
			System.out.println("done waiting for response: count is "+count);
		} catch (InterruptedException e) {
			System.err.println("error waiting for names to be registered by name enumeration responder");
			Assert.fail();
		}
		
		if (count == 1) {
			Assert.assertNotNull(names1);
			Library.logger().info("names1 size = "+names1.size());
			for (ContentName s: names1)
				Library.logger().info(s.toString());
				
			Assert.assertTrue(names1.size()==1);
			Assert.assertTrue(names1.get(0).toString().equals("/name1"));
			Assert.assertNull(names2);
			Assert.assertNull(names3);
		} else if(count == 2) {
			Assert.assertNotNull(names2);
			Library.logger().info("names2 size = "+names2.size());
			for (ContentName s: names2)
				Library.logger().info(s.toString());
			Assert.assertTrue(names2.size()==2);
			Assert.assertTrue((names2.get(0).toString().equals("/name1") && names2.get(1).toString().equals("/name2")) || (names2.get(0).toString().equals("/name2") && names2.get(1).toString().equals("/name1")));
			//not guaranteed to be in this order!
			//Assert.assertTrue(names2.get(0).toString().equals("/name1"));
			//Assert.assertTrue(names2.get(1).toString().equals("/name2"));
			Assert.assertNull(names3);
		} else if (count == 3) {
			Assert.assertNull(names3);
		} else if (count==4) {
			Assert.assertNotNull(names3);
			Library.logger().info("names3 size = "+names3.size());
			for (ContentName s: names3)
				Library.logger().info(s.toString());
		}
	}
}
