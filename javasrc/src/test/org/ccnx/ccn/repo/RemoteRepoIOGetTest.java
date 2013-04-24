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

package org.ccnx.ccn.test.repo;

import java.util.Arrays;

import org.ccnx.ccn.profiles.SegmentationProfile;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Interest;
import org.junit.Assert;
import org.junit.Test;


/**
 * Part of repository test infrastructure.
 * For now to run this you need to first run the RepoIoPutTest, then restart
 * the local ccnd, then run this
 */
public class RemoteRepoIOGetTest extends RepoTestBase {
	
	@Test
	public void testPolicyViaCCN() throws Exception {
		checkContent("/repoTest/data3", false);
		checkContent("/testNameSpace/data1", true);
	}
	
	@Test
	public void testWriteToRepo() throws Exception {
		System.out.println("Testing writing streams to repo");
		byte [] data = new byte[4000];
		byte value = 1;
		for (int i = 0; i < data.length; i++)
			data[i] = value++;
		
		for (int i = 0; i < 40; i++) {
			byte [] testData = new byte[100];
			System.arraycopy(data, i * 100, testData, 0, 100);
			checkData(ContentName.fromNative("/testNameSpace/stream"), testData, i);
		}
	}
	
	private ContentObject checkContent(String contentName, boolean expected) throws Exception {
		return checkContent(ContentName.fromNative(contentName), expected);
	}
	
	private ContentObject checkContent(ContentName contentName, boolean expected) throws Exception {
		Interest interest = new Interest(contentName);
		ContentObject co = getHandle.get(interest, 20000);
		if (expected)
			Assert.assertTrue(co != null);
		else
			Assert.assertTrue(co == null);
		return co;
	}
	
	private void checkData(ContentName currentName, byte[] testData, int i) throws Exception {
		ContentName segmentedName = SegmentationProfile.segmentName(currentName, i);
		ContentObject co = checkContent(segmentedName, true);
		Assert.assertTrue(Arrays.equals(testData, co.content()));
	}
}
