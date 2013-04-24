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

package org.ccnx.ccn.test.repo;

import static org.ccnx.ccn.profiles.CommandMarker.COMMAND_MARKER_BASIC_ENUMERATION;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Random;

import org.ccnx.ccn.CCNContentHandler;
import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.RepositoryOutputStream;
import org.ccnx.ccn.profiles.CommandMarker;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.profiles.nameenum.BasicNameEnumeratorListener;
import org.ccnx.ccn.profiles.nameenum.CCNNameEnumerator;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Exclude;
import org.ccnx.ccn.protocol.Interest;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;
import org.ccnx.ccn.test.AssertionCCNHandle;
import org.junit.Assert;
import org.junit.Test;


/**
 * Part of repository test infrastructure. Test repository side of name enumeration.
 */
public class RepoNameEnumeratorTest implements BasicNameEnumeratorListener, CCNContentHandler {
	
	static final long WAIT_TIME = 500;

	CCNHandle getLibrary;
	CCNNameEnumerator getne;
	
	String prefix1String = RepoTestBase._globalPrefix+"/nameEnumerate";
	//String prefix1String = "/repoTest/nameEnumerate";
	ContentName prefix1;
	
	Random rand = new Random();
	
	CCNHandle putLibrary;
	AssertionCCNHandle explicitExcludeHandle;
	
	ArrayList<ContentName> names1 = null;
	ArrayList<ContentName> names2 = null;
	ArrayList<ContentName> names3 = null;
	
	@Test
	public void repoNameEnumerationTest(){
		Log.info(Log.FAC_TEST, "Starting repoNameEnumerationTest");

		setHandles();
		
		prefix1String += "-" + rand.nextInt(10000);
		
		Log.info(Log.FAC_TEST, "adding name1 to repo");
		addContentToRepo(prefix1String+"/name1");
		
		Log.info(Log.FAC_TEST, "test register prefix");
		testRegisterPrefix();
		
		Log.info(Log.FAC_TEST, "checking for first response");
		testGetResponse(1);
		
		Log.info(Log.FAC_TEST, "adding second name to repo");
		addContentToRepo(prefix1String+"/name2");
		
		//make sure we get the new thing
		Log.info(Log.FAC_TEST, "checking for second name added");
		testGetResponse(2);
		
		//make sure nothing new came in
		Log.info(Log.FAC_TEST, "check to make sure nothing new came in");
		testGetResponse(3);
		
		Log.info(Log.FAC_TEST, "test a cancelPrefix");
		testCancelPrefix();
		
		Log.info(Log.FAC_TEST, "now add third thing");
		addContentToRepo(prefix1String+"/name3");
		
		//make sure we don't hear about this one
		Log.info(Log.FAC_TEST, "called cancel, shouldn't hear about the third item");
		testGetResponse(3);
		
		Log.info(Log.FAC_TEST, "now testing with a version in the prefix");
		ContentName versionedName = getVersionedName(prefix1String);
		addContentToRepo(new ContentName(versionedName, "versionNameTest"));
		registerPrefix(versionedName);
		testGetResponse(4);
		closeHandles();
		
		Log.info(Log.FAC_TEST, "Completed repoNameEnumerationTest");
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
			RepositoryOutputStream ros = new RepositoryOutputStream(name, putLibrary);
			ros.setTimeout(5000);
			byte [] data = "Testing 1 2 3".getBytes();
			ros.write(data, 0, data.length);
			ros.close();
		} catch (IOException ex) {
			Log.warningStackTrace(Log.FAC_TEST, ex);
			Assert.fail("could not put the content into the repo ("+name+"); " + ex.getMessage());
		} 
	}
	
	private void addContentToRepo(String contentName){
		//method to load something to repo for testing
		ContentName name;
		try {
			name = ContentName.fromNative(contentName);
			addContentToRepo(name);
		} catch (MalformedContentNameStringException e) {
			Log.warningStackTrace(Log.FAC_TEST, e);
			Assert.fail("Could not create content name from String.");
		}
	}
	
	
	public int handleNameEnumerator(ContentName prefix, ArrayList<ContentName> names) {
		Log.info("I got a response from the name enumerator, with " + names.size() + " entries.");
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
	public void setHandles(){
		try {
			getLibrary = CCNHandle.open();
			getne = new CCNNameEnumerator(getLibrary, this);
			
			putLibrary = CCNHandle.open();
		} catch (ConfigurationException e) {
			e.printStackTrace();
			Assert.fail("Failed to open libraries for tests");
		} catch (IOException e) {
			Log.warningStackTrace(Log.FAC_TEST, e);
			Assert.fail("Failed to open libraries for tests");
		}
	}
	
	public void closeHandles() {
		getLibrary.close();
		putLibrary.close();
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
			Log.warningStackTrace(Log.FAC_TEST, e);
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
			while (i < 500) {
				Thread.sleep(rand.nextInt(50));
				i++;
				//break out early if possible
				if ( (count == 1 && names1!=null) || (count == 2 && names2!=null) || (count == 4 && names3!=null) )
					break;
			}
			
			//the names are registered...
			System.out.println("done waiting for response: count is "+count+" i="+i);
		} catch (InterruptedException e) {
			Log.warning(Log.FAC_TEST, "error waiting for names to be registered by name enumeration responder");
			Assert.fail();
		}
		
		if (count == 1) {
			Assert.assertNotNull(names1);
			Log.info(Log.FAC_TEST, "names1 size = "+names1.size());
			for (ContentName s: names1)
				Log.info(s.toString());
				
			Assert.assertTrue(names1.size()==1);
			Assert.assertTrue(names1.get(0).toString().equals("/name1"));
			Assert.assertNull(names2);
			Assert.assertNull(names3);
		} else if (count == 2) {
			Assert.assertNotNull(names2);
			Log.info(Log.FAC_TEST, "names2 size = "+names2.size());
			for (ContentName s: names2)
				Log.info(Log.FAC_TEST, s.toString());
			Assert.assertTrue(names2.size()==2);
			Assert.assertTrue((names2.get(0).toString().equals("/name1") && names2.get(1).toString().equals("/name2")) || (names2.get(0).toString().equals("/name2") && names2.get(1).toString().equals("/name1")));
			//not guaranteed to be in this order!
			//Assert.assertTrue(names2.get(0).toString().equals("/name1"));
			//Assert.assertTrue(names2.get(1).toString().equals("/name2"));
			Assert.assertNull(names3);
		} else if (count == 3) {
			if (names3 != null) {
				Assert.assertTrue(names2.size()==2);
				Assert.assertTrue((names2.get(0).toString().equals("/name1") && names2.get(1).toString().equals("/name2")) || (names2.get(0).toString().equals("/name2") && names2.get(1).toString().equals("/name1")));
			}
		} else if (count==4) {
			Assert.assertNotNull(names3);
			Log.info(Log.FAC_TEST, "names3 size = "+names3.size());
			for (ContentName s: names3)
				Log.info(Log.FAC_TEST, s.toString());
		}
	}
	
	
	int contentReceived = 0;
	ContentName repoID = null;
	
	@Test
	public void explicitExcludeFastResponseTest(){
		Log.info(Log.FAC_TEST, "Completed explicitExcludeFastResponseTest");

		ContentName prefixMarked = new ContentName(COMMAND_MARKER_BASIC_ENUMERATION);

		//we have minSuffixComponents to account for sig, version, seg and digest
		Interest pi = Interest.constructInterest(prefixMarked, null, null, null, 4, null);

		try {
			explicitExcludeHandle = AssertionCCNHandle.open();
			explicitExcludeHandle.expressInterest(pi, this);
		} catch (IOException e) {
			Assert.fail("Error expressing explicit interest: "+e.getMessage());
		} catch (ConfigurationException e) {
			Assert.fail("could not create handle for test");
		}
		
		try {
			int i = 0;
			while (i < 500) {
				Thread.sleep(50);
				i++;
				explicitExcludeHandle.checkError(0);
				//break out early if possible
				if ( contentReceived > 0)
					break;
			}
			
			//the names are registered...
			System.out.println("received a response");
		} catch (InterruptedException e) {
			System.err.println("error waiting for explicit NE response.");
			Assert.fail();
		}
		
		//now express interest excluding the repo
		//interest expressed in handleContent...
		try {
			int i = 0;
			while (i < 500) {
				Thread.sleep(rand.nextInt(50));
				i++;
				explicitExcludeHandle.checkError(0);
				//break out early if possible
				if ( contentReceived == 2)
					Assert.fail("received a response from the repo!");
			}
			
			//no response when I shouldn't have gotten one!
		} catch (InterruptedException e) {
			System.err.println("error waiting for explicit NE response.");
			Assert.fail();
		}
		
		explicitExcludeHandle.close();
		
		Log.info(Log.FAC_TEST, "Completed explicitExcludeFastResponseTest");	
	}

	boolean firstResponse = true;
	
	public Interest handleContent(ContentObject data, Interest interest) {
		
		ContentName responseName = null;
		ContentName name = data.name();
		
		if (interest.exclude()!=null) {
			Assert.assertFalse(firstResponse);
			Assert.fail("responseName is null, this is not the first response and it should not be null");
		} else {
			firstResponse = false;
		}
		
		//for now, this is copied from CCNNameEnumerator.getIDFromName  should be refactored so it isn't manually copied.
		try {
			int index = name.containsWhere(CommandMarker.COMMAND_MARKER_BASIC_ENUMERATION.getBytes());
			ContentName prefix = name.subname(index+1, name.count());
			if(VersioningProfile.hasTerminalVersion(prefix))
				responseName = VersioningProfile.cutLastVersion(prefix);
			else
				responseName = prefix;
			Log.finest(Log.FAC_TEST, "NameEnumeration response ID: {0}", responseName);
		} catch(Exception e) {
			Assert.fail("failed to get repo id from NE response: "+e.getMessage());
		}
		
		Exclude excludes = interest.exclude();
		if(excludes==null)
			excludes = new Exclude();
		excludes.add(new byte[][]{responseName.component(0)});
		Interest newInterest = Interest.constructInterest(interest.name(), excludes, null, null, 4, null); 
		
		contentReceived++;
		
		return newInterest;
	}
}
