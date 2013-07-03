/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2013 Palo Alto Research Center, Inc.
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License version 2.1
 * as published by the Free Software Foundation.
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details. You should have received
 * a copy of the GNU Lesser General Public License along with this library;
 * if not, write to the Free Software Foundation, Inc., 51 Franklin Street,
 * Fifth Floor, Boston, MA 02110-1301 USA.
 */

package org.ccnx.ccn.profiles.sync;

import java.util.ArrayList;
import java.util.TreeSet;

import junit.framework.Assert;

import org.ccnx.ccn.CCNSyncHandler;
import org.ccnx.ccn.CCNTestBase;
import org.ccnx.ccn.CCNTestHelper;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.impl.sync.NodeBuilder;
import org.ccnx.ccn.impl.sync.SliceComparator;
import org.ccnx.ccn.impl.sync.SyncHashCache;
import org.ccnx.ccn.impl.sync.SyncNodeCache;
import org.ccnx.ccn.impl.sync.SyncTreeEntry;
import org.ccnx.ccn.io.content.ConfigSlice;
import org.ccnx.ccn.io.content.SyncNodeComposite;
import org.ccnx.ccn.io.content.SyncNodeComposite.SyncNodeElement;
import org.ccnx.ccn.protocol.Component;
import org.ccnx.ccn.protocol.ContentName;
import org.junit.Test;

/**
 * This test can serve as a framework for more deterministic testing of various combinations
 * of node comparisons and updates. For now, it tests a corner case that once worked incorrectly
 * TODO - add more tests
 */
public class SliceComparatorTest extends CCNTestBase implements CCNSyncHandler {
	
	static CCNTestHelper testHelper = new CCNTestHelper(SliceComparatorTest.class);
	static NodeBuilder nb = new NodeBuilder();
	static SyncHashCache shc = new SyncHashCache();
	static SyncNodeCache snc = new SyncNodeCache();
	static boolean sawContent = false;
	
	/**
	 * Tests update with existing node tree which contains a single component node after the
	 * update.
	 * 
	 * @throws Exception
	 */
	@Test
	public void testUpdateWithEmbeddedSingleNode() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testUpdateWithEmbeddedSingleNode");
		ContentName prefix = new ContentName(testHelper.getTestNamespace("testSliceComparator"));
		ArrayList<SyncNodeElement> elements = new ArrayList<SyncNodeElement>();
		SyncTreeEntry entry1 = createNode(prefix, 1, 4);
		elements.add(new SyncNodeElement(entry1.getHash()));
		SyncTreeEntry entry2 = createNode(prefix, 6, 1);
		elements.add(new SyncNodeElement(entry2.getHash()));
		SyncTreeEntry entry3 = createNode(prefix, 7, 4);
		elements.add(new SyncNodeElement(entry3.getHash()));
		SyncTreeEntry startSte = nb.createHeadRecursive(elements, shc, snc, 2);
		SyncTreeEntry compareSte = createNode(prefix, 4, 3);
		SliceComparator sc = new SliceComparator(null, snc, this, null, startSte.getHash(), null, getHandle);
		sc.addPending(compareSte);
		sc.kickCompare();
		while (sc.comparing())
			Thread.sleep(100);
		Assert.assertTrue("Didn't see our missing name", sawContent);
		SyncTreeEntry updatedEntry = sc.getCurrentRoot();
		SyncNodeComposite newEntry = updatedEntry.getNode();
		SyncNodeElement first = newEntry.getMinName();
		Assert.assertNotNull("Can't find first element in update node", first);
		ContentName firstName = first.getName();
		Assert.assertTrue("First name is wrong: " + firstName, firstName.contains(new Component("test-1")));
		SyncNodeElement last = newEntry.getMaxName();
		Assert.assertNotNull("Can't find last element in update node", last);
		ContentName lastName = last.getName();
		Assert.assertTrue("Last name is wrong: " + lastName, lastName.contains(new Component("test-10")));
		Log.info(Log.FAC_TEST, "Completed testUpdateWithEmbeddedSingleNode");
	}
	
	private SyncTreeEntry createNode(ContentName prefix, int start, int size) {
		TreeSet<ContentName> names = new TreeSet<ContentName>();
		for (int i = start; i < start + size; i++) {
			names.add(new ContentName(prefix, "test-" + i));
		}
		return nb.newLeafNode(names, shc, snc);
	}

	@Override
	public void handleContentName(ConfigSlice syncSlice,
			ContentName syncedContent) {
		sawContent = true;
	}
}
