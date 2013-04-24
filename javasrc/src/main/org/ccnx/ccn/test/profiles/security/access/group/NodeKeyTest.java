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

package org.ccnx.ccn.test.profiles.security.access.group;

import java.security.SecureRandom;
import java.util.Arrays;

import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.profiles.security.access.group.GroupAccessControlProfile;
import org.ccnx.ccn.profiles.security.access.group.NodeKey;
import org.ccnx.ccn.protocol.ContentName;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;


public class NodeKeyTest {
	static ContentName testPrefix = null;
	static ContentName nodeKeyPrefix = null;
	static ContentName descendantNodeName1 = null;
	static ContentName descendantNodeName2 = null;
	static ContentName descendantNodeName3 = null;
	
	static NodeKey testNodeKey = null;
	static NodeKey descendantNodeKey1 = null;
	static NodeKey descendantNodeKey2 = null;
	static NodeKey descendantNodeKey3 = null;
	
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		testPrefix = ContentName.fromNative("/parc/test/content/");
		nodeKeyPrefix = GroupAccessControlProfile.nodeKeyName(testPrefix);
		nodeKeyPrefix = VersioningProfile.addVersion(nodeKeyPrefix);

		SecureRandom sr = new SecureRandom();
		byte [] key = new byte[NodeKey.DEFAULT_NODE_KEY_LENGTH];
		sr.nextBytes(key);

		testNodeKey = new NodeKey(nodeKeyPrefix, key);
		System.out.println("created node key, name =" + testNodeKey.nodeName());

		descendantNodeName1 = new ContentName(testPrefix, "level1");
		descendantNodeName2 = new ContentName(descendantNodeName1, "level2");

		descendantNodeKey2 = testNodeKey.computeDescendantNodeKey(descendantNodeName2);
		descendantNodeKey1 = testNodeKey.computeDescendantNodeKey(descendantNodeName1);

		descendantNodeKey3 = descendantNodeKey1.computeDescendantNodeKey(descendantNodeName2);
	}

	@Test
	public void testComputeDescendantNodeKey() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testComputeDescendantNodeKey");

		byte[] aKeyBytes = descendantNodeKey3.nodeKey().getEncoded();
		byte[] bKeyBytes = descendantNodeKey2.nodeKey().getEncoded();

		System.out.println(Arrays.hashCode(aKeyBytes));
		System.out.println(Arrays.hashCode(bKeyBytes));

		System.out.println(testNodeKey.nodeKeyVersion());
		System.out.println(descendantNodeKey1.nodeKeyVersion());
		System.out.println(descendantNodeKey2.nodeKeyVersion());
		System.out.println(descendantNodeKey3.nodeKeyVersion());

		System.out.println(testNodeKey.storedNodeKeyName());
		System.out.println(descendantNodeKey1.storedNodeKeyName());
		System.out.println(descendantNodeKey2.storedNodeKeyName());
		System.out.println(descendantNodeKey3.storedNodeKeyName());

		System.out.println(Arrays.hashCode(testNodeKey.generateKeyID()));
		System.out.println(Arrays.hashCode(descendantNodeKey1.generateKeyID()));
		System.out.println(Arrays.hashCode(descendantNodeKey2.generateKeyID()));
		System.out.println(Arrays.hashCode(descendantNodeKey3.generateKeyID()));

		System.out.println(Arrays.hashCode(testNodeKey.storedNodeKeyID()));
		System.out.println(Arrays.hashCode(descendantNodeKey1.storedNodeKeyID()));
		System.out.println(Arrays.hashCode(descendantNodeKey2.storedNodeKeyID()));
		System.out.println(Arrays.hashCode(descendantNodeKey3.storedNodeKeyID()));

		Assert.assertArrayEquals(aKeyBytes, bKeyBytes);
		
		Log.info(Log.FAC_TEST, "Completed testComputeDescendantNodeKey");
	}

	@Test
	public void testIsDerived(){
		Log.info(Log.FAC_TEST, "Starting testIsDerived");

		Assert.assertFalse(testNodeKey.isDerivedNodeKey());
		Assert.assertTrue(descendantNodeKey1.isDerivedNodeKey());
		Assert.assertTrue(descendantNodeKey2.isDerivedNodeKey());
		Assert.assertTrue(descendantNodeKey3.isDerivedNodeKey());
		
		Log.info(Log.FAC_TEST, "Completed testIsDerived");
	}
}
