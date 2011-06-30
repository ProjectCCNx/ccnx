package org.ccnx.ccn.test.repo;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.impl.CCNFlowServer;
import org.ccnx.ccn.impl.repo.RepositoryInfo;
import static org.ccnx.ccn.impl.repo.RepositoryInfo.RepoInfoType.DATA;
import org.ccnx.ccn.io.CCNOutputStream;
import static org.ccnx.ccn.profiles.repo.RepositoryControl.doLocalCheckedWrite;
import org.ccnx.ccn.protocol.ContentName;
import org.junit.Assert;
import org.junit.Test;

public class CheckedWriteTest {

	@Test
	public void testCheckedWrite() throws Exception {
		
		CCNHandle handle = CCNHandle.getHandle();
		ContentName baseName = ContentName.fromNative("/testChecked");
		CCNFlowServer server = new CCNFlowServer(baseName, 1, true, handle);
		CCNOutputStream os = new CCNOutputStream(baseName, null, null, null, null, server);
		os.close();

		Long startingSegmentNumber = 0L;

		RepositoryInfo ri = doLocalCheckedWrite(baseName, startingSegmentNumber, os.getFirstDigest(), handle);
		Assert.assertFalse(ri.getType() == DATA);

		Thread.sleep(2000);

		ri = doLocalCheckedWrite(baseName, startingSegmentNumber, os.getFirstDigest(), handle);
		Assert.assertTrue(ri.getType() == DATA);
	}
}
