/**
 * 
 */
package test.ccn.library;

import static org.junit.Assert.*;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import java.sql.Timestamp;

import junit.framework.Assert;

import com.parc.ccn.data.ContentName;
import com.parc.ccn.library.profiles.VersionMissingException;
import com.parc.ccn.library.profiles.VersioningProfile;

/**
 *
 */
public class VersioningProfileTest {
	private static byte[][] abParts = { { 97 }, { 98 } };
	private static ContentName abName = new ContentName(abParts);
	private static byte[] ver = { -3, 16, 64 };
	private static byte[] seg = { -8, 16 };
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
	 * Test method for {@link com.parc.ccn.library.profiles.VersioningProfile#addVersion(com.parc.ccn.data.ContentName, long)}.
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
	 * Test method for {@link com.parc.ccn.library.profiles.VersioningProfile#addVersion(com.parc.ccn.data.ContentName, java.sql.Timestamp)}.
	 */
	@Test
	public void testVersionNameContentNameTimestamp() {
		/* try with length 2 contentname */
		Timestamp ts = new Timestamp(1000);
		ts.setNanos(15722656);
		
		ContentName name = VersioningProfile.addVersion(abName, ts);
		if (!name.equals(abvName))
			fail("timestamp encode version failed");
	}

	/**
	 * Test method for {@link com.parc.ccn.library.profiles.VersioningProfile#addVersion(com.parc.ccn.data.ContentName)}.
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
	 * Test method for {@link com.parc.ccn.library.profiles.VersioningProfile#hasTerminalVersion(com.parc.ccn.data.ContentName)}.
	 */
	@Test
	public void testhasTerminalVersion() {
		if (VersioningProfile.hasTerminalVersion(abName))
			fail("shouldn't be versioned");
		if (!VersioningProfile.hasTerminalVersion(abvName))
			fail("should be versioned");
		if (!VersioningProfile.hasTerminalVersion(abSegName))
			fail("should be versioned (with segments)");
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
	 * Test method for {@link com.parc.ccn.library.profiles.VersioningProfile#cutTerminalVersion(com.parc.ccn.data.ContentName)}.
	 */
	@Test
	public void testCutTerminalVersion() {
		if (!VersioningProfile.cutTerminalVersion(abSegName).first().equals(abName))
			fail();
		if (!VersioningProfile.cutTerminalVersion(abName).first().equals(abName))
			fail();
		if (!VersioningProfile.cutTerminalVersion(new ContentName()).first().equals(new ContentName()))
			fail();
		// check correct version field stripped if 2 present
		if (!VersioningProfile.cutTerminalVersion(abvvName).first().equals(abvName))
			fail();
	}

	/**
	 * Test method for {@link com.parc.ccn.library.profiles.VersioningProfile#isVersionOf(com.parc.ccn.data.ContentName, com.parc.ccn.data.ContentName)}.
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
	 * Test method for {@link com.parc.ccn.library.profiles.VersioningProfile#getLastVersionAsLong(com.parc.ccn.data.ContentName)}.
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
	 * Test method for {@link com.parc.ccn.library.profiles.VersioningProfile#getLastVersionAsTimestamp(com.parc.ccn.data.ContentName)}.
	 * @throws VersionMissingException 
	 */
	@Test
	public void testGetVersionAsTimestamp() throws VersionMissingException {
		Timestamp ts = VersioningProfile.getLastVersionAsTimestamp(abSegName);
		ContentName name = VersioningProfile.addVersion(abName, ts);
		if (!name.equals(abvName))
			fail();
	}


}
