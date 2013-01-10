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

package org.ccnx.ccn.test.profiles;

import static org.junit.Assert.fail;
import junit.framework.Assert;

import org.bouncycastle.util.Arrays;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.profiles.VersionMissingException;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.protocol.CCNTime;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Test basic version manipulation.
 */
public class VersioningProfileTest {
	private static ContentName abName = new ContentName(new byte[]{ 97 }, new byte[]{ 98 });
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
		Log.warning(Log.FAC_TEST, "Warning! This tests bakes in low-level knowledge of versioning/segmentation conventions, and must be updated when they change!");
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
		Log.info(Log.FAC_TEST, "Starting testVersionNameContentNameLong");

		ContentName name;
		/* try with length 2 contentname */
		name = VersioningProfile.addVersion(abName, 256);
		if (!name.equals(new ContentName(new byte[]{ 97 }, new byte[]{ 98 }, new byte[]{ -3, 1, 0 })))
			fail("long encode version failed");

		/* check v=0 comes out 0 length */
		name = VersioningProfile.addVersion(abName, 0);
		if (!name.equals(ab0Name))
			fail("long encode version=0 failed");
		
		Log.info(Log.FAC_TEST, "Completed testVersionNameContentNameLong");
	}

	/**
	 * Test method for org.ccnx.ccn.profiles.VersioningProfile#addVersion(org.ccnx.ccn.protocol.ContentName, java.sql.Timestamp).
	 */
	@Test
	public void testVersionNameContentNameTimestamp() {
		Log.info(Log.FAC_TEST, "Starting testVersionNameContentNameTimestamp");

		/* try with length 2 contentname */
		CCNTime ts = new CCNTime(1000);
		ts.setNanos(15722656);
		
		ContentName name = new ContentName(abName, ts);
		if (!name.equals(abvName))
			fail("timestamp encode version failed");
		
		Log.info(Log.FAC_TEST, "Completed testVersionNameContentNameTimestamp");
	}

	/**
	 * Test method for org.ccnx.ccn.profiles.VersioningProfile#addVersion(org.ccnx.ccn.protocol.ContentName).
	 * @throws InterruptedException 
	 */
	@Test
	public void testVersionNameContentName() throws InterruptedException {
		Log.info(Log.FAC_TEST, "Starting testVersionNameContentName");

		ContentName name = VersioningProfile.addVersion(abName);
		Thread.sleep(10);
		if (name == VersioningProfile.addVersion(abSegName))
			fail("should be different versions");
		
		Log.info(Log.FAC_TEST, "Completed testVersionNameContentName");
	}

	@Test
	public void testFindVersionComponent() {
		Log.info(Log.FAC_TEST, "Starting testFindVersionComponent");

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
		
		Log.info(Log.FAC_TEST, "Completed testFindVersionComponent");
	}

	/**
	 * Test method for org.ccnx.ccn.profiles.VersioningProfile#hasTerminalVersion(org.ccnx.ccn.protocol.ContentName).
	 */
	@Test
	public void testhasTerminalVersion() {
		Log.info(Log.FAC_TEST, "Starting testhasTerminalVersion");

		if (VersioningProfile.hasTerminalVersion(abName))
			fail("shouldn't be versioned");
		if (!VersioningProfile.hasTerminalVersion(abvName))
			fail("should be versioned");
		if (!VersioningProfile.hasTerminalVersion(abSegName))
			fail("should be versioned (with segments): " + abSegName);
		if (VersioningProfile.hasTerminalVersion(ContentName.ROOT))
			fail("shouldn't be versioned");
		
		/* check the sequence 0xf8 0x00 * is not treated as a version */
		if (VersioningProfile.hasTerminalVersion(new ContentName(new byte[]{ 97 }, new byte[]{ 98 }, new byte[]{ -3, 0 })))
			fail("not version component");
		
		if (VersioningProfile.hasTerminalVersion(abnotvName))
			fail();
		
		Log.info(Log.FAC_TEST, "Completed testhasTerminalVersion");
	}

	/**
	 * Test method for org.ccnx.ccn.profiles.VersioningProfile#cutTerminalVersion(org.ccnx.ccn.protocol.ContentName).
	 */
	@Test
	public void testCutTerminalVersion() {
		Log.info(Log.FAC_TEST, "Starting testCutTerminalVersion");

		if (!VersioningProfile.cutTerminalVersion(abSegName).first().equals(abName))
			fail("Not equals: " + VersioningProfile.cutTerminalVersion(abSegName).first() + " and " + abName);
		if (!VersioningProfile.cutTerminalVersion(abName).first().equals(abName))
			fail();
		if (!VersioningProfile.cutTerminalVersion(ContentName.ROOT).first().equals(ContentName.ROOT))
			fail();
		// check correct version field stripped if 2 present
		if (!VersioningProfile.cutTerminalVersion(abvvName).first().equals(abvName))
			fail();
		
		Log.info(Log.FAC_TEST, "Completed testCutTerminalVersion");
	}

	/**
	 * Test method for org.ccnx.ccn.profiles.VersioningProfile#isVersionOf(org.ccnx.ccn.protocol.ContentName, org.ccnx.ccn.protocol.ContentName).
	 */
	@Test
	public void testIsVersionOf() {
		Log.info(Log.FAC_TEST, "Starting testIsVersionOf");

		if (!VersioningProfile.isVersionOf(abSegName, abName))
			fail();
		if (VersioningProfile.isVersionOf(abName, abSegName))
			fail();
		if (VersioningProfile.isVersionOf(abvName, abvvName))
			fail();
		
		Log.info(Log.FAC_TEST, "Completed testIsVersionOf");
	}

	/**
	 * Test method for org.ccnx.ccn.profiles.VersioningProfile#getLastVersionAsLong(org.ccnx.ccn.protocol.ContentName).
	 * @throws VersionMissingException 
	 */
	@Test
	public void testGetVersionAsLong() throws VersionMissingException {
		Log.info(Log.FAC_TEST, "Starting testGetVersionAsLong");

		if (VersioningProfile.getLastVersionAsLong(abSegName) != 0x1040)
			fail();

		
		ContentName name = VersioningProfile.addVersion(abName, 1);
		ContentName n2 = new ContentName(name, "addon");
		
		Assert.assertTrue(VersioningProfile.getLastVersionAsLong(name) == 1);
		Assert.assertTrue(VersioningProfile.getLastVersionAsLong(n2) == 1);

		n2 = new ContentName(n2, "addon2", "addon3");
		Assert.assertTrue(VersioningProfile.getLastVersionAsLong(n2) == 1);
		
		try {
			VersioningProfile.getLastVersionAsLong(abName);			
			fail();
		} catch (VersionMissingException e) {
			return;
		}
		fail();
		
		Log.info(Log.FAC_TEST, "Completed testGetVersionAsLong");
	}

	/**
	 * Test method for org.ccnx.ccn.profiles.VersioningProfile#getLastVersionAsTimestamp(org.ccnx.ccn.protocol.ContentName).
	 * @throws VersionMissingException 
	 */
	@Test
	public void testGetVersionAsTimestamp() throws VersionMissingException {
		Log.info(Log.FAC_TEST, "Starting testGetVersionAsTimestamp");

		CCNTime ts = VersioningProfile.getLastVersionAsTimestamp(abSegName);
		ContentName name = new ContentName(abName, ts);
		if (!name.equals(abvName))
			fail();
		
		Log.info(Log.FAC_TEST, "Completed testGetVersionAsTimestamp");
	}
	
	@Test
	public void testUnpaddedVersions() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testUnpaddedVersions");

		ContentName name = ContentName.fromNative("/testme");
		long v0 = 0x7FFFFF;
		byte [] b0 = {VersioningProfile.VERSION_MARKER, (byte) 0x7F, (byte) 0xFF, (byte) 0xFF};
		
		ContentName vn0 = VersioningProfile.addVersion(name, v0);
		byte [] x0 = VersioningProfile.getLastVersionComponent(vn0);
		System.out.println("From long name   : " + vn0.toString());
		Assert.assertTrue(Arrays.areEqual(b0, x0));	
		
		// now do it as ccntime
		CCNTime t0 = CCNTime.fromBinaryTimeAsLong(v0);
		vn0 = new ContentName(name, t0);
		x0 = VersioningProfile.getLastVersionComponent(vn0);
		System.out.println("From ccntime name: " + vn0.toString());
		Assert.assertTrue(Arrays.areEqual(b0, x0));	
		
		Log.info(Log.FAC_TEST, "Completed testUnpaddedVersions");
	}
	
	@Test
	public void testPaddedVersions() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testPaddedVersions");

		ContentName name = ContentName.fromNative("/testme");
		long v0 = 0x80FFFF;
		byte [] b0 = {VersioningProfile.VERSION_MARKER, (byte) 0x80, (byte) 0xFF, (byte) 0xFF};
		
		ContentName vn0 = VersioningProfile.addVersion(name, v0);
		byte [] x0 = VersioningProfile.getLastVersionComponent(vn0);
		System.out.println("From long name   : " + vn0.toString());
		Assert.assertTrue(Arrays.areEqual(b0, x0));	
		
		// now do it as ccntime
		CCNTime t0 = CCNTime.fromBinaryTimeAsLong(v0);
		vn0 = new ContentName(name, t0);
		x0 = VersioningProfile.getLastVersionComponent(vn0);
		System.out.println("From ccntime name: " + vn0.toString());
		Assert.assertTrue(Arrays.areEqual(b0, x0));
		
		Log.info(Log.FAC_TEST, "Completed testPaddedVersions");
	}
	
	@Test
	public void testInvalidVersionComponentTooLong() {
		
		ContentName invalidName = null;
		
		try {
			invalidName = ContentName.fromURI("/name/to/test/%FD%C0%B5g%07%FEcI%FA%3AEW%12%AB%AA%82%07%18%1B%F6L%84%BD%09-%B4sy%0A%234%16");
		} catch (MalformedContentNameStringException e) {
			Assert.fail("unable to create ContentName for test");
		}

		try {
			Log.info(Log.FAC_TEST, "testing name {0}:  componentBytes: {1}", invalidName, invalidName.component(3).length);
			long val = VersioningProfile.getLastVersionAsLong(invalidName);
			Assert.fail("version deemed valid.  error.  "+invalidName+" val = "+val);
		} catch (VersionMissingException e) {
			//this should fail!
		}
	}
	
	@Test
	public void testInvalidVersionComponentTooShort() {
		
		ContentName invalidName = null;
		
		try {
			invalidName = ContentName.fromURI("/name/to/test/%FD%00%01");
		} catch (MalformedContentNameStringException e) {
			Assert.fail("unable to create ContentName for test");
		}
		
		try {
			Log.info(Log.FAC_TEST, "testing name {0}:  componentBytes: {1}", invalidName, invalidName.component(3).length);
			long val = VersioningProfile.getLastVersionAsLong(invalidName);
			Assert.fail("version deemed valid.  error.  "+invalidName+" val = "+val);
		} catch (VersionMissingException e) {
			//should also fail!
		}
	}
	
	@Test
	public void testInvalidVersionComponentValid() {
		
		ContentName validName = null;
		
		try {
			validName = ContentName.fromURI("/name/to/test/%FD%04%FB%DC%3BG%7D");
		} catch (MalformedContentNameStringException e) {
			Assert.fail("unable to create ContentName for test");
		}
		
		try {
			Log.info(Log.FAC_TEST, "testing name {0}:  componentBytes: {1}", validName, validName.component(3).length);
			VersioningProfile.getLastVersionAsLong(validName);
		} catch (VersionMissingException e) {
			//this should work!!
			Assert.fail("version deemed invalid.  error.  "+validName);
		}


	}
}
