package test.ccn.library;

import java.io.IOException;
import java.util.ArrayList;

import org.junit.BeforeClass;
import org.junit.Test;

import com.parc.ccn.config.ConfigurationException;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.content.Collection;
import com.parc.ccn.data.content.CollectionData;
import com.parc.ccn.data.content.LinkReference;
import com.parc.ccn.data.query.BasicNameEnumeratorListener;
import com.parc.ccn.data.query.Interest;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.library.CCNNameEnumerator;

import junit.framework.Assert;

public class NameEnumeratorTest implements BasicNameEnumeratorListener{
	
	static CCNLibrary _library;
	static CCNNameEnumerator ne;
	static NameEnumeratorTest net;
	String prefix1String = "/parc.com/registerTest";
	String prefix1StringError = "/park.com/registerTest";
	ArrayList<LinkReference> names;
	ContentName prefix1;
	ContentName c1;
	ContentName c2;
	
	
	@Test
	public void testCreateEnumerator(){
		Assert.assertNotNull(_library);
		System.out.println("checking if we created a name enumerator");

		Assert.assertNotNull(ne);
	}
	
	@Test
	public void testRegisterPrefix(){
		Assert.assertNotNull(_library);
		Assert.assertNotNull(ne);
		
		try{
			net.prefix1 = ContentName.fromNative(prefix1String);
		}
		catch(Exception e){
			Assert.fail("Could not create ContentName from "+prefix1String);
		}
		
		System.out.println("registering prefix: "+net.prefix1.toString());
		
		ne.registerPrefix(net.prefix1);
		
	}
	
	@Test
	public void testGetCallback(){
		ne.handleContent(createNameList(), net.prefix1);
		
		for(LinkReference lr: net.names){
			System.out.println("got name: "+lr.targetName());
			Assert.assertTrue(lr.targetName().toString().equals("/name1") || lr.targetName().toString().equals("/name2"));
		}	
	}
	
		
	@Test
	public void testCancelPrefix(){
		Assert.assertNotNull(_library);
		Assert.assertNotNull(ne);
		
		//ContentName prefix1 = null;
		ContentName prefix1Error = null;
		
		try{
			//prefix1 = ContentName.fromNative(prefix1String);
			prefix1Error = ContentName.fromNative(prefix1StringError);
		}
		catch(Exception e){
			e.printStackTrace();
			Assert.fail("Could not create ContentName from "+prefix1String);
		}
		
		//try to remove a prefix not registered
		Assert.assertFalse(ne.cancelPrefix(prefix1Error));
		//remove the registered name
		Assert.assertTrue(ne.cancelPrefix(net.prefix1));
		//try to remove the registered name again
		Assert.assertFalse(ne.cancelPrefix(prefix1));
		
	}
	/*
	public static void nameEnumeratorSetup(){
		net.ne = new CCNNameEnumerator(_library, net);
	}
	*/
	
	public void setLibrary(CCNLibrary l){
		_library = l;
		ne = new CCNNameEnumerator(_library, net);
	}
	
	
	
	@BeforeClass
	public static void setup(){
		System.out.println("Starting CCNNameEnumerator Test");
		net = new NameEnumeratorTest();
		try {
			net.setLibrary(CCNLibrary.open());
			//net.nameEnumeratorSetup();
		} catch (ConfigurationException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public int handleNameEnumerator(ContentName p, ArrayList<LinkReference> n) {
		
		System.out.println("got a callback!");
		net.names = n;
		
		return 0;
	}
	
	public ArrayList<LinkReference> createNameList(){
		ArrayList<LinkReference> n = new ArrayList<LinkReference>();
		
		try{
			ContentName c1 = ContentName.fromNative("/name1");
			ContentName c2 = ContentName.fromNative("/name2");
			
			n.add(new LinkReference(c1));
			n.add(new LinkReference(c2));
		}
		catch(Exception e){
			e.printStackTrace();
			Assert.fail("Could not create ContentName from "+prefix1String);
		}
		return n;
	}
	
}
