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
		startAndStopRepo(rdt);
		File testFile = new File(_fileTestDir2 + "/" + LogStructRepoStoreProfile.CONTENT_FILE_PREFIX + "1");
		long testLength = testFile.length();
		startAndStopRepo(rdt);
		Assert.assertEquals(testLength, testFile.length());
		RepoDaemonTest rdtNoChange = new RepoDaemonTest(new String[]{"-root", _fileTestDir2,
				"-global", "/parc.com/csl/ccn/repositories/TestRepository"}, this);
		Thread th = new Thread(rdtNoChange);
		th.start();
		synchronized (this) {
			this.wait();
		}
		checkNameSpace("/repoTest/data3", false);
		checkNameSpace("/testNameSpace/data1", true);
		rdtNoChange.shutdown();
		th.join();
		rdt = new RepoDaemonTest(new String[]{"-root", _fileTestDir2, 
				"-prefix", "/policyTest/foo",
				"-global", "/parc.com/csl/ccn/repositories/TestRepository"}, this);
		startAndStopRepo(rdt);
		testLength = testFile.length();
		startAndStopRepo(rdt);
		Assert.assertEquals(testLength, testFile.length());
		th = new Thread(rdtNoChange);
		th.start();
		synchronized (this) {
			this.wait();
		}
		checkNameSpace("/testNameSpace/data1", false);
		checkNameSpace("/policyTest/foo/bar", true);
		rdtNoChange.shutdown();
		th.join();
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
	
	private void startAndStopRepo(RepoDaemonTest rdt) throws InterruptedException {
		Thread th = new Thread(rdt);
		th.start();
		synchronized (this) {
			this.wait();
		}
		rdt.shutdown();
		th.join();
	}
}
