/*
 * A CCNx library test.
 *
 * Copyright (C) 2011-2012 Palo Alto Research Center, Inc.
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
import org.ccnx.ccn.impl.CCNFlowControl;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.profiles.ccnd.CCNDCacheManager;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.test.CCNTestBase;
import org.ccnx.ccn.test.CCNTestHelper;
import org.junit.Test;

public class ClearCcndCacheTest extends CCNTestBase {
	static CCNTestHelper testHelper = new CCNTestHelper(ClearCcndCacheTest.class);
	
	@Test
	public void testClearCache() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testClearCache");

		ContentName prefix = new ContentName(testHelper.getClassNamespace(), "AreaToClear");
		CCNFlowControl fc = new CCNFlowControl(prefix, putHandle);
		for (int i = 0; i < 10; i++) {
			ContentName name = new ContentName(prefix, "head-" + i);
			ContentObject obj = ContentObject.buildContentObject(name, "test".getBytes());
			fc.put(obj);
			obj = getHandle.get(prefix, SystemConfiguration.MEDIUM_TIMEOUT);
			Assert.assertNotNull(obj);
			for (int j = 0; j < 10; j++) {
				ContentName subName = new ContentName(name, "subObj-" + j);
				ContentObject subObj = ContentObject.buildContentObject(subName, "test".getBytes());
				fc.put(subObj);
				obj = getHandle.get(prefix, SystemConfiguration.MEDIUM_TIMEOUT);
				Assert.assertNotNull(obj);
			}
		}
		fc.close();
		new CCNDCacheManager().clearCache(prefix, getHandle, 100 * SystemConfiguration.SHORT_TIMEOUT);
		
		for (int i = 0; i < 10; i++) {
			ContentName name = new ContentName(prefix, "head-" + i);
			ContentObject co = getHandle.get(name, SystemConfiguration.SHORT_TIMEOUT);
			Assert.assertEquals(null, co);
			for (int j = 0; j < 10; j++) {
				ContentName subName = new ContentName(name, "subObj-" + j);
				co = getHandle.get(subName, SystemConfiguration.SHORT_TIMEOUT);
				Assert.assertEquals(null, co);
			}
		}
		
		Log.info(Log.FAC_TEST, "Completed testClearCache");
	}
}
