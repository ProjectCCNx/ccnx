package test.ccn.network.daemons.repo;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Random;

import javax.xml.stream.XMLStreamException;

import org.junit.Assert;
import org.junit.Test;

import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.library.CCNNameEnumerator;
import com.parc.ccn.library.io.repo.RepositoryOutputStream;
import com.parc.ccn.config.ConfigurationException;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.MalformedContentNameStringException;
import com.parc.ccn.data.query.BasicNameEnumeratorListener;


//test is currently hardcoded and only works for Rebecca with a remote ccnd and repo running  

public class RepoNameEnumeratorTest implements BasicNameEnumeratorListener{
	CCNLibrary getLibrary;
	CCNNameEnumerator getne;
	
	String prefix1String = "/parc.com/test";
	ContentName prefix1;
	
	Random rand = new Random();
	
	CCNLibrary putLibrary;
	
	ArrayList<ContentName> names1 = null;
	ArrayList<ContentName> names2 = null;
	ArrayList<ContentName> names3 = null;
	
	@Test
	public void repoNameEnumerationTest(){
		setLibraries();
		
		startRepo();
		
		addContentToRepo(prefix1String+"/name1");
		
		testRegisterPrefix();
		
		testGetResponse(1);
		
		addContentToRepo(prefix1String+"/name2");
		
		//make sure we get the new thing
		testGetResponse(2);
		
		//make sure nothing new came in
		testGetResponse(3);
		
		testCancelPrefix();
		
		addContentToRepo(prefix1String+"/name3");
		
		//make sure we don't hear about this one
		testGetResponse(3);
		
		cleanRepo();
		
	}
	
	
	private void addContentToRepo(String contentName){
		//method to load something to repo for testing
		ContentName name;
		try {
			name = ContentName.fromNative(contentName);

			RepositoryOutputStream ros = putLibrary.repoOpen(name, null, putLibrary.getDefaultPublisher());
			byte [] data = "Testing 1 2 3".getBytes();
			ros.write(data, 0, data.length);
			ContentName baseName = ros.getBaseName();
			ros.close();
			Thread.sleep(1000);
			File testFile = new File("/home/rbraynar/repocache" + baseName);
			Assert.assertTrue(testFile.exists());
		}
		catch(IOException ex){
			Assert.fail("could not put the content into the repo.");
		}
		catch (MalformedContentNameStringException e) {
			e.printStackTrace();
			Assert.fail("Could not create content name from String.");
		} catch (XMLStreamException e) {
			e.printStackTrace();
			Assert.fail("Could not open repo output stream for test.");
		} catch (InterruptedException e) {
			e.printStackTrace();
			Assert.fail("error while sleeping during put");
		}

		
	}
	
	
	@Override
	public int handleNameEnumerator(ContentName prefix, ArrayList<ContentName> names) {
		System.out.println("I got a response!!!!");
		if(names1 == null)
			names1 = names;
		else if(names2 == null)
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
		try{
			getLibrary = CCNLibrary.open();
			getne = new CCNNameEnumerator(getLibrary, this);
			
			putLibrary = CCNLibrary.open();
		}
		catch(ConfigurationException e){
			e.printStackTrace();
			Assert.fail("Failed to open libraries for tests");
		}
		catch(IOException e){
			e.printStackTrace();
			Assert.fail("Failed to open libraries for tests");
		}
	}
	
	public void startRepo(){
		
		
	}
	
	public void testRegisterPrefix(){

		try{
			prefix1 = ContentName.fromNative(prefix1String);
		}
		catch(Exception e){
			Assert.fail("Could not create ContentName from "+prefix1String);
		}
		
		System.out.println("registering prefix: "+prefix1.toString());
		
		try{
			getne.registerPrefix(prefix1);
		}
		catch(IOException e){
			System.err.println("error registering prefix");
			e.printStackTrace();
			Assert.fail();
		}
		
	}
	
	public void testCancelPrefix(){
		getne.cancelPrefix(prefix1);
	}
	
	
	public void testGetResponse(int count){
		try{
			int i = 0;
			while(i < 200){
				Thread.sleep(rand.nextInt(50));
				i++;
			}
			
			//the names are registered...
			System.out.println("done waiting for response");
		}
		catch(InterruptedException e){
			System.err.println("error waiting for names to be registered by name enumeration responder");
			Assert.fail();
		}
		
		if(count == 1){
			Assert.assertTrue(names1.size()==1);
			Assert.assertTrue(names1.get(0).toString().equals("/name1"));
			Assert.assertNull(names2);
			Assert.assertNull(names3);
		}
		else if(count == 2){
			Assert.assertTrue(names2.size()==2);
			Assert.assertTrue(names2.get(0).toString().equals("/name1"));
			Assert.assertTrue(names2.get(1).toString().equals("/name2"));
			Assert.assertNull(names3);
		}
		else if(count == 3){
			Assert.assertNull(names3);
		}
	}
	
	private void cleanRepo(){
		//need to remove the files I added...
		File testFile = new File("/home/rbraynar/repocache/parc.com/test");
		ArrayList<File> filesToDelete = new ArrayList<File>();
		for(File f1: testFile.listFiles()){
			for(File f2: f1.listFiles()){
				for(File f3: f2.listFiles())
					filesToDelete.add(f3);
				filesToDelete.add(f2);
			}
			filesToDelete.add(f1);
		}
		filesToDelete.add(testFile);
		
		while(filesToDelete.size()>0)
			filesToDelete.remove(0).delete();
		
			

		
	}
	
}
