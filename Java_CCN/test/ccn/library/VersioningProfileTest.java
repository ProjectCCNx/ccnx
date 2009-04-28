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
	private static byte[][] abSegParts = { { 97 }, { 98 }, { -3, 16, 64 }, { -8, 16 } };
	private static ContentName abSegName = new ContentName(abSegParts);
	private static byte[][] ab0Parts = { { 97 }, { 98 }, { -3 } };
	private static ContentName ab0Name = new ContentName(ab0Parts);
	private static byte[][] abvParts = { { 97 }, { 98 }, { -3, 16, 64 } };
	private static ContentName abvName = new ContentName(abvParts);

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
	 * Test method for {@link com.parc.ccn.library.profiles.VersioningProfile#versionName(com.parc.ccn.data.ContentName, long)}.
	 */
	@Test
	public void testVersionNameContentNameLong() {
		ContentName name;
		/* try with length 2 contentname */
		name = VersioningProfile.versionName(abName, 256);
		byte[][] parts = { { 97 }, { 98 }, { -3, 1, 0 } };
		if (!name.equals(new ContentName(parts)))
			fail("long encode version failed");

		/* check v=0 comes out 0 length */
		name = VersioningProfile.versionName(abName, 0);
		if (!name.equals(ab0Name))
			fail("long encode version=0 failed");
	}

	/**
	 * Test method for {@link com.parc.ccn.library.profiles.VersioningProfile#versionName(com.parc.ccn.data.ContentName, java.sql.Timestamp)}.
	 */
	@Test
	public void testVersionNameContentNameTimestamp() {
		/* try with length 2 contentname */
		Timestamp ts = new Timestamp(1000);
		ts.setNanos(15722656);
		
		ContentName name = VersioningProfile.versionName(abName, ts);
		if (!name.equals(abvName))
			fail("timestamp encode version failed");
	}

	/**
	 * Test method for {@link com.parc.ccn.library.profiles.VersioningProfile#versionName(com.parc.ccn.data.ContentName)}.
	 * @throws InterruptedException 
	 */
	@Test
	public void testVersionNameContentName() throws InterruptedException {
		ContentName name = VersioningProfile.versionName(abName);
		Thread.sleep(10);
		if (name == VersioningProfile.versionName(abSegName))
			fail("should be different versions");
	}

	/**
	 * Test method for {@link com.parc.ccn.library.profiles.VersioningProfile#isVersioned(com.parc.ccn.data.ContentName)}.
	 */
	@Test
	public void testIsVersioned() {
		if (VersioningProfile.isVersioned(abName))
			fail("shouldn't be versioned");
		if (!VersioningProfile.isVersioned(abvName))
			fail("should be versioned");
		if (!VersioningProfile.isVersioned(abSegName))
			fail("should be versioned (with segments)");
		if (VersioningProfile.isVersioned(new ContentName() ))
			fail("shouldn't be versioned");
		
		/* check the sequence 0xf8 0x00 * is not treated as a version */
		byte[][] parts = { { 97 }, { 98 }, { -3, 0 } };
		if (VersioningProfile.isVersioned(new ContentName(parts)))
			fail("not version component");
		
		if (!VersioningProfile.isVersioned(ab0Name))
			fail();
	}

	/**
	 * Test method for {@link com.parc.ccn.library.profiles.VersioningProfile#versionRoot(com.parc.ccn.data.ContentName)}.
	 */
	@Test
	public void testVersionRoot() {
		if (!VersioningProfile.versionRoot(abSegName).equals(abName))
			fail();
		if (!VersioningProfile.versionRoot(abName).equals(abName))
			fail();
		if (!VersioningProfile.versionRoot(new ContentName()).equals(new ContentName()))
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
	}

	/**
	 * Test method for {@link com.parc.ccn.library.profiles.VersioningProfile#getVersionAsLong(com.parc.ccn.data.ContentName)}.
	 * @throws VersionMissingException 
	 */
	@Test
	public void testGetVersionAsLong() throws VersionMissingException {
		if (VersioningProfile.getVersionAsLong(abSegName) != 0x1040)
			fail();

		
		ContentName name = VersioningProfile.versionName(abName, 1);
		ContentName n2 = ContentName.fromNative(name, "addon");
		
		Assert.assertTrue(VersioningProfile.getVersionAsLong(name) == 1);
		Assert.assertTrue(VersioningProfile.getVersionAsLong(n2) == 1);

		n2 = ContentName.fromNative(n2, "addon2", "addon3");
		Assert.assertTrue(VersioningProfile.getVersionAsLong(n2) == 1);
		
		try {
			VersioningProfile.getVersionAsLong(abName);			
			fail();
		} catch (VersionMissingException e) {
			return;
		}
		fail();
	}

	/**
	 * Test method for {@link com.parc.ccn.library.profiles.VersioningProfile#getVersionAsTimestamp(com.parc.ccn.data.ContentName)}.
	 * @throws VersionMissingException 
	 */
	@Test
	public void testGetVersionAsTimestamp() throws VersionMissingException {
		Timestamp ts = VersioningProfile.getVersionAsTimestamp(abSegName);
		ContentName name = VersioningProfile.versionName(abName, ts);
		if (!name.equals(abvName))
			fail();
	}


}
