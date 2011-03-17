/*
 * A CCNx library test.
 *
 * Copyright (C) 2011 Palo Alto Research Center, Inc.
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

package org.ccnx.ccn.test.profiles.ccnd;

import junit.framework.Assert;

import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.profiles.ccnd.CCNDCacheManager;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.test.CCNTestBase;
import org.ccnx.ccn.test.CCNTestHelper;
import org.junit.Test;

public class ClearCacheTest extends CCNTestBase {
	static CCNTestHelper testHelper = new CCNTestHelper(ClearCacheTest.class);
	
	@Test
	public void testClearCache() throws Exception {
		ContentName prefix = ContentName.fromNative(testHelper.getClassNamespace(), "AreaToClear");
		for (int i = 0; i < 10; i++) {
			ContentName name = ContentName.fromNative(prefix, "head-" + i);
			ContentObject obj = ContentObject.buildContentObject(name, "test".getBytes());
			putHandle.put(obj);
			for (int j = 0; j < 10; j++) {
				ContentName subName = ContentName.fromNative(name, "subObj-" + i);
				ContentObject subObj = ContentObject.buildContentObject(subName, "test".getBytes());
				putHandle.put(subObj);
			}
		}
		new CCNDCacheManager().clearCache(prefix, getHandle, 10000);
		
		for (int i = 0; i < 10; i++) {
			ContentName name = ContentName.fromNative(prefix, "head-" + i);
			ContentObject co = getHandle.get(name, SystemConfiguration.SHORT_TIMEOUT);
			Assert.assertEquals(null, co);
			for (int j = 0; j < 10; j++) {
				ContentName subName = ContentName.fromNative(name, "subObj-" + i);
				co = getHandle.get(subName, SystemConfiguration.SHORT_TIMEOUT);
				Assert.assertEquals(null, co);
			}
		}
	}
}
