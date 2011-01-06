/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2011 Palo Alto Research Center, Inc.
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

package org.ccnx.ccn.test.profiles.versioning;

import java.util.Arrays;
import java.util.Iterator;
import java.util.Random;
import java.util.TreeSet;
import java.util.logging.Level;

import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.profiles.versioning.InterestData.TimeElement;
import org.ccnx.ccn.protocol.CCNTime;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class TimeElementTest {

	protected final Random _rnd = new Random();
	protected final static long TIMEOUT=30000;
	protected final ContentName prefix;

	public TimeElementTest() throws MalformedContentNameStringException {
		prefix  = ContentName.fromNative(String.format("/test_%016X", _rnd.nextLong()));
	}
	
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		Log.setLevel(Log.FAC_ALL, Level.WARNING);
		Log.setLevel(Log.FAC_ENCODING, Level.FINE);
	}

	@Test
	public void testTimeElementInTree() throws Exception {
		// make sure the sortable work
		long [] values = new long [] {111111000000L, 111000000000L, 111333000000L, 111222000000L};
		TimeElement [] tes = new TimeElement[values.length];
		TreeSet<TimeElement> tree = new TreeSet<TimeElement>();

		for(int i = 0; i < values.length; i++) {
			tes[i] = new TimeElement(new CCNTime(values[i]));
			tree.add(tes[i]);
		}

		// they should be in the same order as the sorted values
		Arrays.sort(values);
		Iterator<TimeElement> iter = tree.iterator();
		int index = 0;
		while( iter.hasNext() ) {
			TimeElement v = iter.next();
			Assert.assertEquals(values[index], v.version.getTime());
			index++;
		}
	}

	@Test
	public void testTimeElementCompare() throws Exception {
		// make sure the sortable work

		TimeElement a = new TimeElement(new CCNTime(111111000000L));
		TimeElement aa = new TimeElement(new CCNTime(111111000000L));
		TimeElement b = new TimeElement(new CCNTime(111222000000L));
		TimeElement c = new TimeElement(new CCNTime(111333000000L));

		Assert.assertTrue(a.compareTo(b) < 0);
		Assert.assertTrue(b.compareTo(a) > 0);
		Assert.assertTrue(a.compareTo(aa) == 0);
		Assert.assertTrue(a.compareTo(c) < 0);
		Assert.assertTrue(b.compareTo(c) < 0);
		Assert.assertTrue(c.compareTo(a) > 0);
		Assert.assertTrue(c.compareTo(b) > 0);	
	}

}
