package org.ccnx.ccn.test.repo;

import java.io.File;

import junit.framework.Assert;

import org.ccnx.ccn.impl.repo.RepositoryDaemon;
import org.ccnx.ccn.impl.repo.LogStructRepoStore.LogStructRepoStoreProfile;
import org.junit.Test;

public class RepoInitialPolicyTest extends RepoTestBase {

	@Test
	public void testPolicyInitialization() throws Exception {
		RepoDaemonTest rdt = new RepoDaemonTest(new String[]{"-root", _fileTestDir2, 
						"-policy", _topdir + "/org/ccnx/ccn/test/repo/policyTest.xml",
						"-global", "/parc.com/csl/ccn/repositories/TestRepository"}, this);
		Thread th = new Thread(rdt);
		th.start();
		synchronized (this) {
			this.wait();
		}
		rdt.shutdown();
		th.join();
		File testFile = new File(_fileTestDir2 + "/" + LogStructRepoStoreProfile.CONTENT_FILE_PREFIX + "1");
		long testLength = testFile.length();
		th = new Thread(rdt);
		th.start();
		synchronized (this) {
			this.wait();
		}
		rdt.shutdown();
		th.join();
		Assert.assertEquals(testLength, testFile.length());
	}
	
	public class RepoDaemonTest extends RepositoryDaemon implements Runnable {
		protected String[] _args = null;
		protected WorkerThread _wt = null;
		protected Object _waiter;
		public RepoDaemonTest(String[] args, Object waiter) {
			_args = args;
			_waiter = waiter;
		}
		public void run() {
			initialize(_args, this);
			_wt = createWorkerThread();
			_wt.start();
			_wt.waitForStart();
			synchronized (_waiter) {
				_waiter.notifyAll();
			}
			try {
				_wt.join();
			} catch (InterruptedException e) {}
		}
		public void shutdown() {
			_wt.shutDown();
		}
	}
}
