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

package org.ccnx.ccn.test.profiles;

import static org.junit.Assert.fail;
import junit.framework.Assert;

import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.profiles.VersionMissingException;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.protocol.CCNTime;
import org.ccnx.ccn.protocol.ContentName;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Test basic version manipulation.
 */
public class VersioningProfileTest {
	private static byte[][] abParts = { { 97 }, { 98 } };
	private static ContentName abName = new ContentName(abParts);
	private static byte[] ver = { -3, 16, 64 };
	private static byte[] seg = { 0, 16 };
	private static ContentName abSegName = new ContentName(abName, ver, seg);
	private static byte[] v0 = { -3 };
	private static byte[] notv = { -3, 0 };
	private static ContentName ab0Name = new ContentName(abName, v0);
	private static ContentName abnotvName = new ContentName(abName, notv);
	private static ContentName abvName = new ContentName(abName, ver);
	private static ContentName abvvName = new ContentName(abvName, ver);

	/**
	 * @throws java.lang.Exception
	 */
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		Log.warning("Warning! This tests bakes in low-level knowledge of versioning/segmentation conventions, and must be updated when they change!");
	}

	/**
	 * @throws java.lang.Exception
	 */
	@AfterClass
	public static void tearDownAfterClass() throws Exception {
	}

	/**
	 * @throws java.lang.Exception
	 */
	@Before
	public void setUp() throws Exception {
	}

	/**
	 * @throws java.lang.Exception
	 */
	@After
	public void tearDown() throws Exception {
	}
	
	/**
	 * Test method for org.ccnx.ccn.profiles.VersioningProfile#addVersion(org.ccnx.ccn.protocol.ContentName, long).
	 */
	@Test
	public void testVersionNameContentNameLong() {
		ContentName name;
		/* try with length 2 contentname */
		name = VersioningProfile.addVersion(abName, 256);
		byte[][] parts = { { 97 }, { 98 }, { -3, 1, 0 } };
		if (!name.equals(new ContentName(parts)))
			fail("long encode version failed");

		/* check v=0 comes out 0 length */
		name = VersioningProfile.addVersion(abName, 0);
		if (!name.equals(ab0Name))
			fail("long encode version=0 failed");
	}

	/**
	 * Test method for org.ccnx.ccn.profiles.VersioningProfile#addVersion(org.ccnx.ccn.protocol.ContentName, java.sql.Timestamp).
	 */
	@Test
	public void testVersionNameContentNameTimestamp() {
		/* try with length 2 contentname */
		CCNTime ts = new CCNTime(1000);
		ts.setNanos(15722656);
		
		ContentName name = VersioningProfile.addVersion(abName, ts);
		if (!name.equals(abvName))
			fail("timestamp encode version failed");
	}

	/**
	 * Test method for org.ccnx.ccn.profiles.VersioningProfile#addVersion(org.ccnx.ccn.protocol.ContentName).
	 * @throws InterruptedException 
	 */
	@Test
	public void testVersionNameContentName() throws InterruptedException {
		ContentName name = VersioningProfile.addVersion(abName);
		Thread.sleep(10);
		if (name == VersioningProfile.addVersion(abSegName))
			fail("should be different versions");
	}

	@Test
	public void testFindVersionComponent() {
		if (VersioningProfile.findLastVersionComponent(abnotvName) != -1)
			fail();
		if (VersioningProfile.findLastVersionComponent(abName) != -1)
			fail();
		if (VersioningProfile.findLastVersionComponent(abSegName) != 2)
			fail();
		if (VersioningProfile.findLastVersionComponent(abvName) != 2)
			fail();
		if (VersioningProfile.findLastVersionComponent(abvvName) != 3)
			fail();
	}

	/**
	 * Test method for org.ccnx.ccn.profiles.VersioningProfile#hasTerminalVersion(org.ccnx.ccn.protocol.ContentName).
	 */
	@Test
	public void testhasTerminalVersion() {
		if (VersioningProfile.hasTerminalVersion(abName))
			fail("shouldn't be versioned");
		if (!VersioningProfile.hasTerminalVersion(abvName))
			fail("should be versioned");
		if (!VersioningProfile.hasTerminalVersion(abSegName))
			fail("should be versioned (with segments): " + abSegName);
		if (VersioningProfile.hasTerminalVersion(new ContentName() ))
			fail("shouldn't be versioned");
		
		/* check the sequence 0xf8 0x00 * is not treated as a version */
		byte[][] parts = { { 97 }, { 98 }, { -3, 0 } };
		if (VersioningProfile.hasTerminalVersion(new ContentName(parts)))
			fail("not version component");
		
		if (VersioningProfile.hasTerminalVersion(abnotvName))
			fail();
	}

	/**
	 * Test method for org.ccnx.ccn.profiles.VersioningProfile#cutTerminalVersion(org.ccnx.ccn.protocol.ContentName).
	 */
	@Test
	public void testCutTerminalVersion() {
		if (!VersioningProfile.cutTerminalVersion(abSegName).first().equals(abName))
			fail("Not equals: " + VersioningProfile.cutTerminalVersion(abSegName).first() + " and " + abName);
		if (!VersioningProfile.cutTerminalVersion(abName).first().equals(abName))
			fail();
		if (!VersioningProfile.cutTerminalVersion(new ContentName()).first().equals(new ContentName()))
			fail();
		// check correct version field stripped if 2 present
		if (!VersioningProfile.cutTerminalVersion(abvvName).first().equals(abvName))
			fail();
	}

	/**
	 * Test method for org.ccnx.ccn.profiles.VersioningProfile#isVersionOf(org.ccnx.ccn.protocol.ContentName, org.ccnx.ccn.protocol.ContentName).
	 */
	@Test
	public void testIsVersionOf() {
		if (!VersioningProfile.isVersionOf(abSegName, abName))
			fail();
		if (VersioningProfile.isVersionOf(abName, abSegName))
			fail();
		if (VersioningProfile.isVersionOf(abvName, abvvName))
			fail();
	}

	/**
	 * Test method for org.ccnx.ccn.profiles.VersioningProfile#getLastVersionAsLong(org.ccnx.ccn.protocol.ContentName).
	 * @throws VersionMissingException 
	 */
	@Test
	public void testGetVersionAsLong() throws VersionMissingException {
		if (VersioningProfile.getLastVersionAsLong(abSegName) != 0x1040)
			fail();

		
		ContentName name = VersioningProfile.addVersion(abName, 1);
		ContentName n2 = ContentName.fromNative(name, "addon");
		
		Assert.assertTrue(VersioningProfile.getLastVersionAsLong(name) == 1);
		Assert.assertTrue(VersioningProfile.getLastVersionAsLong(n2) == 1);

		n2 = ContentName.fromNative(n2, "addon2", "addon3");
		Assert.assertTrue(VersioningProfile.getLastVersionAsLong(n2) == 1);
		
		try {
			VersioningProfile.getLastVersionAsLong(abName);			
			fail();
		} catch (VersionMissingException e) {
			return;
		}
		fail();
	}

	/**
	 * Test method for org.ccnx.ccn.profiles.VersioningProfile#getLastVersionAsTimestamp(org.ccnx.ccn.protocol.ContentName).
	 * @throws VersionMissingException 
	 */
	@Test
	public void testGetVersionAsTimestamp() throws VersionMissingException {
		CCNTime ts = VersioningProfile.getLastVersionAsTimestamp(abSegName);
		ContentName name = VersioningProfile.addVersion(abName, ts);
		if (!name.equals(abvName))
			fail();
	}


}
