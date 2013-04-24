/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2012 Palo Alto Research Center, Inc.
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
package org.ccnx.ccn.test.profiles.sync;

import java.util.TreeSet;

import org.ccnx.ccn.CCNContentHandler;
import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.impl.sync.NodeBuilder;
import org.ccnx.ccn.impl.sync.ProtocolBasedSyncMonitor;
import org.ccnx.ccn.impl.sync.SyncHashCache;
import org.ccnx.ccn.impl.sync.SyncNodeCache;
import org.ccnx.ccn.impl.sync.SyncTreeEntry;
import org.ccnx.ccn.io.content.ConfigSlice;
import org.ccnx.ccn.io.content.SyncNodeComposite;
import org.ccnx.ccn.io.content.SyncNodeComposite.SyncNodeElement;
import org.ccnx.ccn.io.content.SyncNodeComposite.SyncNodeType;
import org.ccnx.ccn.profiles.sync.Sync;
import org.ccnx.ccn.protocol.Component;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Interest;
import org.ccnx.ccn.test.CCNTestBase;
import org.ccnx.ccn.test.CCNTestHelper;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class SyncInternalTestNotYet extends CCNTestBase implements CCNContentHandler {
	
	public static CCNTestHelper testHelper = new CCNTestHelper(SyncInternalTestNotYet.class);
	ContentName prefix;
	ContentName topo;
	ContentObject receivedNode = null;
	SyncNodeCache cache = new SyncNodeCache();
	SyncHashCache shc = new SyncHashCache();
	
	@Before
	public void setUpNameSpace() {
		prefix = testHelper.getTestNamespace("ccnSyncInternalTest");
		topo = testHelper.getTestNamespace("topoPrefix");
		Log.fine(Log.FAC_TEST, "setting up namespace for sync test  data: {0} syncControlTraffic: {1}", prefix, topo);
	}
	
	/**
	 * Test to make sure that our internal build of nodes builds nodes correctly
	 * 
	 * TODO This test is supposed to ensure that nodes are built the same way C side sync does but
	 * we aren't doing that yet.
	 * 
	 * @throws Exception
	 */
	@Test
	public void testSyncNodeBuild() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testSyncNodeBuild");

		ContentName prefix1;
		prefix1 = prefix.append("slice1");
		ConfigSlice slice1 = ConfigSlice.checkAndCreate(topo, prefix1, null, putHandle);
		Assert.assertTrue("Didn't create slice: " + prefix1, slice1 != null);
		
		//the slice should be written..  now save content and get a callback.
		Log.fine(Log.FAC_TEST, "writing out file: {0}", prefix1);
		
		// Write a 100 block file to test a true sync tree
		SyncTestCommon.writeFile(prefix1, false, SystemConfiguration.BLOCK_SIZE * 100, putHandle);
		
		SyncNodeComposite repoNode = SyncTestCommon.getRootAdviseNode(slice1, getHandle);
		Assert.assertTrue(null != repoNode);
		
		TreeSet<ContentName> names = new TreeSet<ContentName>();
		for (SyncNodeElement sne : repoNode.getRefs()) {
			if (sne.getType() == SyncNodeType.LEAF) {
				names.add(sne.getName());
			}
			if (sne.getType() == SyncNodeType.HASH) {
				synchronized (this) {
					receivedNode = null;
					ProtocolBasedSyncMonitor.requestNode(slice1, sne.getData(), getHandle, this);
					wait(SystemConfiguration.EXTRA_LONG_TIMEOUT);
				}
				assert(receivedNode != null);
				SyncNodeComposite snc = new SyncNodeComposite();
				snc.decode(receivedNode.content());
				SyncNodeComposite.decodeLogging(snc);
				for (SyncNodeElement tsne : snc.getRefs()) {
					Assert.assertTrue(tsne.getType() == SyncNodeType.LEAF);
					names.add(tsne.getName());
				}
			}
		}
		
		NodeBuilder nb = new NodeBuilder();
		SyncTreeEntry testNode = nb.newNode(names, shc, cache);
		Assert.assertTrue(testNode.getNode().equals(repoNode));
		
		Log.info(Log.FAC_TEST, "Completed testSyncNodeBuild");
	}

	public Interest handleContent(ContentObject data, Interest interest) {
		ContentName name = data.name();

		int hashComponent = name.containsWhere(Sync.SYNC_NODE_FETCH_MARKER);
		Assert.assertTrue(hashComponent > 0 && name.count() > (hashComponent + 1));
		byte[] hash = name.component(hashComponent + 2);
		Log.fine(Log.FAC_TEST, "Saw data from nodefind in test: hash: {0}", Component.printURI(hash));
		receivedNode = data;
		synchronized (this) {
			notify();
		}
		return null;
	}
}
