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

package org.ccnx.ccn.test.io.content;


import java.io.IOException;
import java.util.Random;

import junit.framework.Assert;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.impl.CCNFlowControl.SaveType;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.ContentDecodingException;
import org.ccnx.ccn.io.content.Link;
import org.ccnx.ccn.io.content.Link.LinkObject;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.SignedInfo.ContentType;
import org.ccnx.ccn.test.CCNTestHelper;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Test versioned Link objects, written to a repository.
 */
public class LinkObjectTestRepo {


	/**
	 * Handle naming for the test
	 */
	static CCNTestHelper testHelper = new CCNTestHelper(LinkObjectTestRepo.class);

	static CCNHandle getLibrary;
	static CCNHandle putLibrary;
	
	/**
	 * @throws java.lang.Exception
	 */
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		putLibrary = CCNHandle.open();
		getLibrary = CCNHandle.open();
	}
	
	@Test
	public void testLinks() throws Exception {
		
		ContentName nonLinkName = ContentName.fromNative(testHelper.getTestNamespace("testLinks"), "myNonLink");
		ContentName linkName = ContentName.fromNative(testHelper.getTestNamespace("testLinks"), "myLink");
		
		// Write something that isn't a collection
		CCNSerializableStringObject so = 
			new CCNSerializableStringObject(nonLinkName, "This is not a link, number " + new Random().nextInt(10000), SaveType.REPOSITORY, putLibrary);
		so.save();
		
		try {
			LinkObject notAnObject = new LinkObject(nonLinkName, getLibrary);
			notAnObject.waitForData();
			Assert.fail("Reading link from non-link succeeded.");
		} catch (ContentDecodingException ex) {
			// this is what we actually expect
			System.out.println("Got expected exception reading link from non-link.");
			Log.info("Got expected exception reading link from non-link.");
		} catch (IOException ioe) {
			System.out.println("Got another type of IOException reading link from non-link: " + ioe);
			Log.info("Unexpected: got IOException that wasn't a ContentDecodingException reading link from non-link: {0}", ioe);
			throw ioe;
		} catch (Exception e) {
			System.out.println("Got unexpected exception type reading link from non-link: " + e);
			Log.info("Got unexpected exception type reading link from non-link: " + e);
			throw e;
		}

		Link lr = new Link(so.getVersionedName());
		LinkObject aLink = new LinkObject(linkName, lr, SaveType.REPOSITORY, putLibrary);
		aLink.save();
		
		ContentObject linkData = getLibrary.get(aLink.getVersionedName(), 5000);
		if (null == linkData) {
			Assert.fail("Cannot retrieve first block of saved link: " + aLink.getVersionedName());
		}
		// Make sure we're writing type LINK.
		Assert.assertEquals(linkData.signedInfo().getType(), ContentType.LINK);
		
		LinkObject readLink = new LinkObject(linkData, getLibrary);
		readLink.waitForData();
		
		Assert.assertEquals(readLink.link(), lr);

		ContentObject firstBlock = aLink.dereference(5000);
		if (null == firstBlock) {
			Assert.fail("Cannot read first block of link target: " + readLink.getTargetName());
		}
		Log.info("Got block of target: " + firstBlock.name());
		
		// TODO -- not a good test; does dereference get us back the first block? What about the
		// first block of the latest version? What if thing isn't versioned? (e.g. intermediate node)
		CCNSerializableStringObject readString = new CCNSerializableStringObject(firstBlock, getLibrary);
		readString.waitForData();
		
		Assert.assertEquals(readString.string(), so.string());
	}
}
