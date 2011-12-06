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

package org.ccnx.ccn.test.profiles.nameenum;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Random;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.profiles.nameenum.BasicNameEnumeratorListener;
import org.ccnx.ccn.profiles.nameenum.CCNNameEnumerator;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;
import org.junit.Test;


import junit.framework.Assert;

/**
 * Test the asynchronous interface to name enumeration.
 */
public class NameEnumeratorTest implements BasicNameEnumeratorListener{
	
	CCNHandle putLibrary;
	CCNHandle getLibrary;
	CCNNameEnumerator putne;
	CCNNameEnumerator getne;

	Random rand = new Random();

	String namespaceString = "/parc.com";
	ContentName namespace;
	String name1String = "/parc.com/registerTest/name1";
	ContentName name1;
	String name2String = "/parc.com/registerTest/name2";
	ContentName name2;
	String name2aString = "/parc.com/registerTest/name2/namea";
	ContentName name2a;
	String name1StringDirty = "/parc.com/registerTest/name1TestDirty";
	ContentName name1Dirty;
	
	String prefix1String = "/parc.com/registerTest";
	String prefix1StringError = "/park.com/registerTest";
	ArrayList<ContentName> names;
	ContentName prefix1;
	ContentName c1;
	ContentName c2;
	
	@Test
	public void testNameEnumerator() throws Exception {
		
		System.out.println("Starting CCNNameEnumerator Test");
		
		//set up CCN libraries for testing
		setLibraries();
		
		//verify that everything is set up
		Assert.assertNotNull(putLibrary);
		Assert.assertNotNull(getLibrary);
		Assert.assertNotNull(putne);
		Assert.assertNotNull(getne);
		
		//tests that the names are properly registered (namespace and object names)
		testRegisterName();
		
		//tests that prefixes are properly registered for exploring the namespace
		testRegisterPrefix();
		
		//tests that an ArrayList of ContentNames was received for the registered prefix
		testGetCallback();
		
		//register additional name to trigger (set responser dirty flag) a new response by the responder
		registerAdditionalName();
		
		//verify that the new response with an updated list of names was received.
		testGetCallbackDirty();
		
		//test that registered prefixes and their interests are canceled
		testCancelPrefix();
		
		//verify that we only get a response for names with the correct prefix and that are active
		testGetCallbackNoResponse();
		closeLibraries();
	}
	
	
	public void testRegisterName() throws IOException{
		
		try{
			namespace = ContentName.fromNative(namespaceString);
			name1 = ContentName.fromNative(name1String);
			name2 = ContentName.fromNative(name2String);
			name2a = ContentName.fromNative(name2aString);
		}
		catch(Exception e){
			Assert.fail("Could not create ContentName from "+name1String +" or "+name2String);
		}
		
		putne.registerNameSpace(namespace);
		putne.registerNameForResponses(name1);
		putne.registerNameForResponses(name2);
		putne.registerNameForResponses(name2a);
		ContentName nullName = null;
		putne.registerNameForResponses(nullName);
		
		try{
			while(!putne.containsRegisteredName(name2a)){
				Thread.sleep(rand.nextInt(50));
			}
			
			//the names are registered...
			System.out.println("the names are now registered");
		}
		catch(InterruptedException e){
			System.err.println("error waiting for names to be registered by name enumeration responder");
			Assert.fail();
		}
		
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
	
	
	public void testGetCallback(){

		
		int attempts = 0;
		try{
			while (names==null && attempts < 500){
				Thread.sleep(rand.nextInt(50));
				attempts++;
			}
			
			//we either broke out of loop or the names are here
			System.out.println("done waiting for results to arrive: attempts " + attempts);
		} catch(InterruptedException e){
			System.err.println("error waiting for names to be registered by name enumeration responder");
			Assert.fail();
		}
		
		Assert.assertNotNull(names);
		
		for (ContentName cn: names){
			System.out.println("got name: "+cn.toString());
			Assert.assertTrue(cn.toString().equals("/name1") || cn.toString().equals("/name2"));
		}
		
		names = null;
			
	}
	
	public void registerAdditionalName(){
		//now add new name
		try{
			name1Dirty = ContentName.fromNative(name1StringDirty);
			putne.registerNameForResponses(name1Dirty);
			
			while(!putne.containsRegisteredName(name1Dirty)){
				Thread.sleep(rand.nextInt(50));
			}
				
			//the names are registered...
			System.out.println("the new name is now registered to trigger the dirty flag");
		}
		catch(InterruptedException e){
			System.err.println("error waiting for names to be registered by name enumeration responder");
			Assert.fail();
		}
		catch (MalformedContentNameStringException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
						
	}

	
	public void testGetCallbackDirty(){

		int attempts = 0;
		try{
			while(names==null && attempts < 1000){
				Thread.sleep(rand.nextInt(50));
				attempts++;
			}
			
			//we either broke out of loop or the names are here
			System.out.println("done waiting for results to arrive: attempts " + attempts);
		}
		catch(InterruptedException e){
			System.err.println("error waiting for names to be registered by name enumeration responder");
			Assert.fail();
		}

		Assert.assertTrue(names.size()==3);
		for(ContentName cn: names){
			System.out.println("got name: "+cn.toString());
			Assert.assertTrue(cn.toString().equals("/name1") || cn.toString().equals("/name2") || cn.toString().equals("/name1TestDirty"));
		}
		names = null;
	}
	

	public void testCancelPrefix(){

		System.out.println("testing prefix cancel");
		
		ContentName prefix1Error = null;
		
		try{
			prefix1Error = ContentName.fromNative(prefix1StringError);
		}
		catch(Exception e){
			e.printStackTrace();
			Assert.fail("Could not create ContentName from "+prefix1String);
		}
		
		//try to remove a prefix not registered
		Assert.assertFalse(getne.cancelPrefix(prefix1Error));
		//remove the registered name
		Assert.assertTrue(getne.cancelPrefix(prefix1));
		//try to remove the registered name again
		Assert.assertFalse(getne.cancelPrefix(prefix1));

	}
	
	
	public void testGetCallbackNoResponse(){

		ContentName p1 = null;
		
		try{
			p1 = ContentName.fromNative(prefix1String+"NoNames");
		}
		catch(Exception e){
			Assert.fail("Could not create ContentName from "+prefix1String+"NoNames");
		}
		
		System.out.println("registering prefix: "+p1.toString());
		
		try{
			getne.registerPrefix(p1);
		}
		catch(IOException e){
			System.err.println("error registering prefix");
			e.printStackTrace();
			Assert.fail();
		}
	
		
		int attempts = 0;
		try{
			while(names==null && attempts < 100){
				Thread.sleep(rand.nextInt(50));
				attempts++;
			}
			//we either broke out of loop or the names are here
			System.out.println("done waiting for results to arrive");
			Assert.assertNull(names);
			getne.cancelPrefix(p1);
		}
		catch(InterruptedException e){
			System.err.println("error waiting for names to be registered by name enumeration responder");
			Assert.fail();
		}
		
	}
	
	public void testGetCallbackAfterCancel(){
		//assert names are still null in case we got a response even though the request was canceled
		Assert.assertNull(names);
	}
	
	
	/* 
	 * function to open and set the put and get libraries
	 * also creates and sets the Name Enumerator Objects
	 * 
	 */
	public void setLibraries(){
		try{
			putLibrary = CCNHandle.open();
			getLibrary = CCNHandle.open();
			putne = new CCNNameEnumerator(putLibrary, this);
			getne = new CCNNameEnumerator(getLibrary, this);
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
	
	public void closeLibraries() {
		if (null != putLibrary)
			putLibrary.close();
		if (null != getLibrary)
			getLibrary.close();
	}
    

	public int handleNameEnumerator(ContentName p, ArrayList<ContentName> n) {
		
		System.out.println("got a callback!");
		
		names = n;
		System.out.println("here are the returned names: ");

		for (ContentName cn: names)
			System.out.println(cn.toString()+" ("+p.toString()+cn.toString()+")");
		
		return 0;
	}
	
}
