/*
 * A CCNx library test.
 *
 * Copyright (C) 2008, 2009, 2011, 2012 Palo Alto Research Center, Inc.
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
import java.util.ArrayList;
import java.util.LinkedList;

import junit.framework.Assert;

import org.ccnx.ccn.impl.CCNFlowControl.SaveType;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.ContentDecodingException;
import org.ccnx.ccn.io.content.Link;
import org.ccnx.ccn.io.content.Collection.CollectionObject;
import org.ccnx.ccn.protocol.CCNTime;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.test.CCNTestBase;
import org.ccnx.ccn.test.CCNTestHelper;
import org.ccnx.ccn.test.TestUtils;
import org.junit.Test;



/**
 * Tests writing versioned Collection objects to a repository.
 */
public class CollectionObjectTestRepo extends CCNTestBase {

	/**
	 * Handle naming for the test.
	 */
	static CCNTestHelper testHelper = new CCNTestHelper(CollectionObjectTestRepo.class);

	/**
	 * @throws java.lang.Exception
	 */

	@Test
	public void testCollections() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testCollections");

		ContentName nonCollectionName = new ContentName(testHelper.getTestNamespace("testCollections"), "myNonCollection");
		ContentName collectionName = new ContentName(testHelper.getTestNamespace("testCollections"), "myCollection");

		// Write something that isn't a collection
		CCNSerializableStringObject so = new CCNSerializableStringObject(nonCollectionName, "This is not a collection.", SaveType.REPOSITORY, putHandle);
		so.save();

		Link[] references = new Link[2];
		references[0] = new Link(new ContentName(collectionName, "r1"));
		references[1] = new Link(new ContentName(collectionName, "r2"));
		CollectionObject collection =
			new CollectionObject(collectionName, references, SaveType.REPOSITORY, putHandle);
		collection.save();
		TestUtils.checkObject(putHandle, collection);

		try {
			CollectionObject notAnObject = new CollectionObject(nonCollectionName, getHandle);
			notAnObject.waitForData();
			Assert.fail("Reading collection from non-collection succeeded.");
		} catch (ContentDecodingException ex) {
			// this is what we actually expect
			Log.info(Log.FAC_TEST, "Got expected exception reading collection from non-collection.");
		} catch (IOException ioe) {
			Log.info(Log.FAC_TEST, "Unexpected: got IOException that wasn't a ContentDecodingException reading collection from non-collection: {0}", ioe);
		} catch (Exception e) {
			Log.warning(Log.FAC_TEST, "Got unexpected exception type reading collection from non-collection: " + e);
			Assert.fail("Got unexpected exception type reading collection from non-collection: " + e);
		}

		// test reading latest version
		CollectionObject readCollection = new CollectionObject(collectionName, getHandle);
		readCollection.waitForData();
		LinkedList<Link> checkReferences = collection.contents();
		Assert.assertEquals(checkReferences.size(), 2);
		Assert.assertEquals(references[0], checkReferences.get(0));
		Assert.assertEquals(references[1], checkReferences.get(1));

		// test addToCollection
		ArrayList<Link> newReferences = new ArrayList<Link>();
		newReferences.add(new Link(ContentName.fromNative("/libraryTest/r3")));
		newReferences.add(new Link(ContentName.fromNative("/libraryTest/r4")));
		collection.contents().addAll(newReferences);
		if (!collection.save()) {
			Log.info(Log.FAC_TEST, "Collection not saved -- data should have been updated?");
		}
		readCollection.update(5000);
		Log.info(Log.FAC_TEST, "read collection version: " + readCollection.getVersion());
		checkReferences = collection.contents();
		Assert.assertEquals(collection.getVersion(), readCollection.getVersion());
		Assert.assertEquals(checkReferences.size(), 4);
		Assert.assertEquals(newReferences.get(0), checkReferences.get(2));
		Assert.assertEquals(newReferences.get(1), checkReferences.get(3));
		CCNTime oldVersion = collection.getVersion();

		collection.contents().removeAll(newReferences);
		collection.save();
		Log.info(Log.FAC_TEST, "New version: " + collection.getVersion() + " old version " + oldVersion);
		readCollection.update(5000);
		checkReferences = collection.contents();
		Assert.assertEquals(collection.getVersion(), readCollection.getVersion());
		checkReferences = collection.contents();
		Assert.assertEquals(collection.getVersion(), readCollection.getVersion());
		Assert.assertEquals(collection.contents(), readCollection.contents());
		Assert.assertTrue("Updated version contents", collection.getVersion().after(oldVersion));

		Log.info(Log.FAC_TEST, "Completed testCollections");
	}
}
