/*
 * A CCNx library test.
 *
 * Copyright (C) 2008-2013 Palo Alto Research Center, Inc.
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

import junit.framework.Assert;

import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.profiles.nameenum.BasicNameEnumeratorListener;
import org.ccnx.ccn.profiles.nameenum.CCNNameEnumerator;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;
import org.ccnx.ccn.test.CCNTestBase;
import org.junit.Test;

/**
 * Test the asynchronous interface to name enumeration.
 */
public class NameEnumeratorTest extends CCNTestBase implements BasicNameEnumeratorListener{

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
	Object namesLock = new Object();
	ContentName prefix1;
	ContentName c1;
	ContentName c2;

	@Test
	public void testNameEnumerator() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testNameEnumerator");

		//set up CCN libraries for testing
		setupNE();

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

		Log.info(Log.FAC_TEST, "Completed testNameEnumerator");
	}


	public void testRegisterName() throws IOException{
		Log.info(Log.FAC_TEST, "Starting testRegisterName");
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
			Log.info(Log.FAC_TEST, "the names are now registered");
		}
		catch(InterruptedException e){
			Log.warning(Log.FAC_TEST, "error waiting for names to be registered by name enumeration responder");
			Assert.fail();
		}
		Log.info(Log.FAC_TEST, "Completed testRegisterName");
	}


	public void testRegisterPrefix(){
		Log.info(Log.FAC_TEST, "Starting testRegisterPrefix");

		try{
			prefix1 = ContentName.fromNative(prefix1String);
		}
		catch(Exception e){
			Assert.fail("Could not create ContentName from "+prefix1String);
		}

		Log.info(Log.FAC_TEST, "registering prefix: "+prefix1.toString());

		try{
			getne.registerPrefix(prefix1);
		}
		catch(IOException e){
			Log.warning(Log.FAC_TEST, "error registering prefix");
			Log.warningStackTrace(Log.FAC_TEST, e);
			Assert.fail();
		}
		Log.info(Log.FAC_TEST, "Completed testRegisterPrefix");
	}


	public void testGetCallback(){
		Log.info(Log.FAC_TEST, "Starting testGetCallback");

		int attempts = 1;
		try{
			synchronized (namesLock) {
				while (null == names && attempts < 500){
					namesLock.wait(50);
					attempts++;
				}
			}

			//we either broke out of loop or the names are here
			Log.info(Log.FAC_TEST, "done waiting for results to arrive: attempts " + attempts);
		} catch(InterruptedException e){
			Log.warning(Log.FAC_TEST, "error waiting for names to be registered by name enumeration responder");
			Assert.fail();
		}

		synchronized (namesLock) {
			Assert.assertNotNull(names);

			for (ContentName cn: names){
				Log.info(Log.FAC_TEST, "got name: "+cn.toString());
				Assert.assertTrue(cn.toString().equals("/name1") || cn.toString().equals("/name2"));
			}

			names = null;
		}
		Log.info(Log.FAC_TEST, "Completed testGetCallback");
	}

	public void registerAdditionalName(){
		//now add new name
		try{
			name1Dirty = ContentName.fromNative(name1StringDirty);
			putne.registerNameForResponses(name1Dirty);

			while(!putne.containsRegisteredName(name1Dirty)){
				Thread.sleep(50);
			}

			//the names are registered...
			Log.info(Log.FAC_TEST, "the new name is now registered to trigger the dirty flag");
		}
		catch(InterruptedException e){
			Log.warning(Log.FAC_TEST, "error waiting for names to be registered by name enumeration responder");
			Assert.fail();
		}
		catch (MalformedContentNameStringException e) {
			// TODO Auto-generated catch block
			Log.warningStackTrace(Log.FAC_TEST, e);
		}
	}


	public void testGetCallbackDirty(){
		Log.info(Log.FAC_TEST, "Starting testGetCallbackDirty");

		int attempts = 1;
		try{
			synchronized (namesLock) {
				while(names == null && attempts < 1000) {
					namesLock.wait(50);
					attempts++;
				}
			}

			//we either broke out of loop or the names are here
			Log.info(Log.FAC_TEST, "done waiting for results to arrive: attempts " + attempts);
		}
		catch(InterruptedException e){
			Log.warning(Log.FAC_TEST, "error waiting for names to be registered by name enumeration responder");
			Assert.fail();
		}

		synchronized (namesLock) {
			Assert.assertNotNull(names);
			Assert.assertTrue(names.size()==3);
			for(ContentName cn: names){
				Log.info(Log.FAC_TEST, "got name: "+cn.toString());
				Assert.assertTrue(cn.toString().equals("/name1") || cn.toString().equals("/name2") || cn.toString().equals("/name1TestDirty"));
			}
			names = null;
		}
		Log.info(Log.FAC_TEST, "Completed testGetCallbackDirty");
	}


	public void testCancelPrefix(){
		Log.info(Log.FAC_TEST, "Starting testCancelPrefix");

		ContentName prefix1Error = null;

		try{
			prefix1Error = ContentName.fromNative(prefix1StringError);
		}
		catch(Exception e){
			Log.warningStackTrace(Log.FAC_TEST, e);
			Assert.fail("Could not create ContentName from "+prefix1String);
		}

		//try to remove a prefix not registered
		Assert.assertFalse(getne.cancelPrefix(prefix1Error));
		//remove the registered name
		Assert.assertTrue(getne.cancelPrefix(prefix1));
		//try to remove the registered name again
		Assert.assertFalse(getne.cancelPrefix(prefix1));
		Log.info(Log.FAC_TEST, "Completed testCancelPrefix");
	}


	public void testGetCallbackNoResponse(){
		Log.info(Log.FAC_TEST, "Starting testGetCallbackNoResponse");

		ContentName p1 = null;

		try{
			p1 = ContentName.fromNative(prefix1String+"NoNames");
		}
		catch(Exception e){
			Assert.fail("Could not create ContentName from "+prefix1String+"NoNames");
		}

		Log.info(Log.FAC_TEST, "registering prefix: "+p1.toString());

		try{
			getne.registerPrefix(p1);
		}
		catch(IOException e){
			Log.warning(Log.FAC_TEST, "error registering prefix");
			Log.warningStackTrace(Log.FAC_TEST, e);
			Assert.fail();
		}


		int attempts = 0;
		try{
			synchronized (namesLock) {
				while(names==null && attempts < 100){
					namesLock.wait(50);
					attempts++;
				}
				//we either broke out of loop or the names are here
				Log.info(Log.FAC_TEST, "done waiting for results to arrive");
				Assert.assertNull(names);
			}
			getne.cancelPrefix(p1);
		}
		catch(InterruptedException e){
			Log.info(Log.FAC_TEST, "error waiting for names to be registered by name enumeration responder");
			Assert.fail();
		}
		Log.info(Log.FAC_TEST, "Completed testGetCallbackNoResponse");
	}

	public void testGetCallbackAfterCancel(){
		Log.info(Log.FAC_TEST, "Starting testGetCallbackAfterCancel");

		//assert names are still null in case we got a response even though the request was canceled
		synchronized (namesLock) {
			Assert.assertNull(names);
		}
		Log.info(Log.FAC_TEST, "Completed testGetCallbackAfterCancel");
	}


	/*
	 * function to create and set the Name Enumerator Objects
	 *
	 */
	public void setupNE(){
		putne = new CCNNameEnumerator(putHandle, this);
		getne = new CCNNameEnumerator(getHandle, this);
	}

	public int handleNameEnumerator(ContentName p, ArrayList<ContentName> n) {

		Log.info(Log.FAC_TEST, "got a callback!");

		synchronized (namesLock) {
			names = new ArrayList<ContentName>();
			for (ContentName name : n)
				names.add(name);
			namesLock.notify();
			Log.info(Log.FAC_TEST, "here are the returned names: ");

			for (ContentName cn: names)
				Log.info(Log.FAC_TEST, cn.toString()+" ("+p.toString()+cn.toString()+")");
		}

		return 0;
	}
}
