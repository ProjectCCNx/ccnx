package test.ccn.network.daemons.repo;

import java.io.IOException;

import org.ccnx.ccn.protocol.ContentName;
import org.junit.Assert;
import org.junit.Test;


/**
 * Test to insure repo is up before continuing
 * @author rasmusse
 *
 */

public class RepoInitializationTest extends RepoTestBase {
	public static final int TEST_TIME = 15000; // ms - 30 seconds
	public static final int TEST_INTERVAL = 3000;
	public int countDown = TEST_TIME;
	
	@Test
	public void testRepoIsUp() throws Throwable {
		while (countDown >= 0) {
			try {
				testWriteToRepo(ContentName.fromNative("/repoTest/upTest"));
				return;
			} catch (IOException ioe) {}
			Thread.sleep(TEST_INTERVAL);
			countDown -= TEST_INTERVAL;
		}
		Assert.fail("Repo never came up");
	}
}
