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

import java.io.IOException;
import java.util.ArrayList;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.RepositoryOutputStream;
import org.ccnx.ccn.profiles.nameenum.BasicNameEnumeratorListener;
import org.ccnx.ccn.profiles.nameenum.CCNNameEnumerator;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.test.CCNTestHelper;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Test to verify the library handles multiple responders for name enumeration properly.
 *
 */
public class MultiResponderNameEnumerationTest implements BasicNameEnumeratorListener {

	CCNNameEnumerator getne;
	
	CCNNameEnumerator putne;
	
	CCNHandle _putHandle;
	CCNHandle _getHandle;
	
	CCNHandle _fileHandle;
	
	CCNTestHelper helper;
	
	ContentName _prefix;
	
	ContentName _class2;
	ContentName _class;
	ContentName _repo;
	
	boolean updated;
	
	ArrayList<ContentName> names;
	
	@Before
	public void setUp() {
		try {
	
			names = new ArrayList<ContentName>();
			
			_putHandle = CCNHandle.open();
			_getHandle = CCNHandle.open();
			_fileHandle = CCNHandle.open();
			
			getne = new CCNNameEnumerator(_getHandle, this);
			
			putne = new CCNNameEnumerator(_putHandle, this);
			
			helper = new CCNTestHelper(this.getClass().getName());
			
			_prefix = helper.getClassNamespace();
			
			_class = new ContentName(_prefix, "classResponder");
			_class2 = new ContentName(_prefix, "classResponder2");
			_repo = new ContentName(_prefix, "repoResponder");
			
			updated = false;
			
			putne.registerNameSpace(_prefix);
			Log.info("registering namespace prefix: {0} count: {1}", _prefix, _prefix.count());
			
			putNERegisterName(_class);
			
			addContentToRepo(_repo);
	
		} catch (ConfigurationException e) {
			Assert.fail("Configuration Exception when setting up test. "+e.getMessage());
		} catch (IOException e) {
			Assert.fail("IOException when setting up test. "+e.getMessage());
		}
	}
	
	@After
	public void cleanup() {
		_putHandle.close();
		_getHandle.close();
		_fileHandle.close();
	}

	private void putNERegisterName(ContentName n) {
		
		try {
			putne.registerNameForResponses(n);
			for (int i = 0 ; i < 100; i++) {
				if (!putne.containsRegisteredName(n))
					Thread.sleep(50);
				else
					break;
			}
			Assert.assertTrue(putne.containsRegisteredName(n));
			
			//the names are registered...
			Log.info("the names are now registered: {0}", n);
		} catch(InterruptedException e){
			System.err.println("error waiting for names to be registered by name enumeration responder");
			Assert.fail();
		}
	}
	
	private void addContentToRepo(ContentName name){
		try{
			RepositoryOutputStream ros = new RepositoryOutputStream(name, _fileHandle);
			ros.setTimeout(5000);
			byte [] data = "Testing 1 2 3".getBytes();
			ros.write(data, 0, data.length);
			ros.close();
		} catch (IOException ex) {
			ex.printStackTrace();
			Assert.fail("could not put the content into the repo ("+name+"); " + ex.getMessage());
		} 
	}
	
	
	public int handleNameEnumerator(ContentName prefix, ArrayList<ContentName> returnedNames) {
		
		Log.info("got a response for: {0}", prefix);
		
		synchronized(names) {
			for(ContentName n: returnedNames) {
				ContentName name = _prefix.append(n);
				Log.info("adding: {0}", name);
				if(!names.contains(name))
					names.add(name);
			}

			if(names.size() == 2 && !updated) {
				putNERegisterName(_class2);
				updated = true; 
			}
		}
		
		return 0;
	}
	
	@Test
	public void multiResponderNameEnumerationTest() {
		Log.info(Log.FAC_TEST, "Starting multiResponderNameEnumerationTest");
		
		try {
			getne.registerPrefix(_prefix);
		} catch (IOException e) {
			Assert.fail("Could not register name for responses.");
		}
		
		testGetResponses();
		
		Log.info(Log.FAC_TEST, "Completed multiResponderNameEnumerationTest");
	}
	
	public void testGetResponses(){
		try {
			int i = 0;
			
			while (i < 500) {
				Thread.sleep(50);
				i++;
				//break out early if possible
				synchronized(names) {
					Log.info("checking names: {0}", names.size());
					if (names.size() == 3) {
						Log.info("we got all three responses!");
						break;
					}
				}
			}
			Assert.assertTrue(names.size() == 3);
			Assert.assertTrue(names.contains(_repo));
			Assert.assertTrue(names.contains(_class));
			Assert.assertTrue(names.contains(_class2));
			
			//cleanup - cancel our enumerations
			getne.cancelEnumerationsWithPrefix(_prefix);
			
		} catch (InterruptedException e) {
			Assert.fail("Error while waiting for results to come in. "+e.getMessage());
		}
	}
	
}
