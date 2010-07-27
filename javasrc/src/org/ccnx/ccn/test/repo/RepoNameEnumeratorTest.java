/*
 * A CCNx library test.
 *
 * Copyright (C) 2008, 2009 Palo Alto Research Center, Inc.
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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Random;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.RepositoryOutputStream;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.profiles.nameenum.BasicNameEnumeratorListener;
import org.ccnx.ccn.profiles.nameenum.CCNNameEnumerator;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;
import org.junit.Assert;
import org.junit.Test;


/**
 * Part of repository test infrastructure. Test repository side of name enumeration.
 */
public class RepoNameEnumeratorTest implements BasicNameEnumeratorListener{
	CCNHandle getLibrary;
	CCNNameEnumerator getne;
	
	String prefix1String = RepoTestBase._globalPrefix+"/nameEnumerate";
	//String prefix1String = "/repoTest/nameEnumerate";
	ContentName prefix1;
	
	Random rand = new Random();
	
	CCNHandle putLibrary;
	
	ArrayList<ContentName> names1 = null;
	ArrayList<ContentName> names2 = null;
	ArrayList<ContentName> names3 = null;
	
	@Test
	public void repoNameEnumerationTest(){
		setLibraries();
		
		prefix1String += "-" + rand.nextInt(10000);
		
		Log.info("adding name1 to repo");
		addContentToRepo(prefix1String+"/name1");
		
		Log.info("test register prefix");
		testRegisterPrefix();
		
		Log.info("checking for first response");
		testGetResponse(1);
		
		Log.info("adding second name to repo");
		addContentToRepo(prefix1String+"/name2");
		
		//make sure we get the new thing
		Log.info("checking for second name added");
		testGetResponse(2);
		
		//make sure nothing new came in
		Log.info("check to make sure nothing new came in");
		testGetResponse(3);
		
		Log.info("test a cancelPrefix");
		testCancelPrefix();
		
		Log.info("now add third thing");
		addContentToRepo(prefix1String+"/name3");
		
		//make sure we don't hear about this one
		Log.info("called cancel, shouldn't hear about the third item");
		testGetResponse(3);
		
		Log.info("now testing with a version in the prefix");
		ContentName versionedName = getVersionedName(prefix1String);
		addContentToRepo(new ContentName(versionedName, "versionNameTest".getBytes()));
		registerPrefix(versionedName);
		testGetResponse(4);
		cleanupLibraries();
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
			ex.printStackTrace();
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
			e.printStackTrace();
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
	public void setLibraries(){
		try {
			getLibrary = CCNHandle.open();
			getne = new CCNNameEnumerator(getLibrary, this);
			
			putLibrary = CCNHandle.open();
		} catch (ConfigurationException e) {
			e.printStackTrace();
			Assert.fail("Failed to open libraries for tests");
		} catch (IOException e) {
			e.printStackTrace();
			Assert.fail("Failed to open libraries for tests");
		}
	}
	
	public void cleanupLibraries() {
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
			System.err.println("error waiting for names to be registered by name enumeration responder");
			Assert.fail();
		}
		
		if (count == 1) {
			Assert.assertNotNull(names1);
			Log.info("names1 size = "+names1.size());
			for (ContentName s: names1)
				Log.info(s.toString());
				
			Assert.assertTrue(names1.size()==1);
			Assert.assertTrue(names1.get(0).toString().equals("/name1"));
			Assert.assertNull(names2);
			Assert.assertNull(names3);
		} else if (count == 2) {
			Assert.assertNotNull(names2);
			Log.info("names2 size = "+names2.size());
			for (ContentName s: names2)
				Log.info(s.toString());
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
			Log.info("names3 size = "+names3.size());
			for (ContentName s: names3)
				Log.info(s.toString());
		}
	}
}
