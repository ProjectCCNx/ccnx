package test.ccn.library;

import org.junit.Test;

import com.parc.ccn.config.SystemConfiguration;

public class BasePutGetTest extends LibraryTestBase {
	
	@Test
	public void testGetPut() throws Throwable {
		// Set debug level: use for more FINE, FINER, FINEST for debug-level tracing
		// Library.logger().setLevel(Level.FINEST);
		SystemConfiguration.setDebugFlag(SystemConfiguration.DEBUGGING_FLAGS.DEBUG_SIGNATURES, true);
		System.out.println("TEST: PutThread/GetThread");
		int id = rand.nextInt(1000);
		Thread putter = new Thread(new PutThread(count, id));
		Thread getter = new Thread(new GetThread(count, id));
		genericGetPut(putter, getter);
	}
	
	@Test
	public void testGetServPut() throws Throwable {
		System.out.println("TEST: PutThread/GetServer");
		int id = rand.nextInt(1000);

		//Library.logger().setLevel(Level.FINEST);
		Thread putter = new Thread(new PutThread(count, id));
		Thread getter = new Thread(new GetServer(count, id));
		genericGetPut(putter, getter);
	}

	@Test
	public void testGetPutServ() throws Throwable {
		System.out.println("TEST: PutServer/GetThread");
		int id = rand.nextInt(1000);
		Thread putter = new Thread(new PutServer(count, id));
		Thread getter = new Thread(new GetThread(count, id));
		genericGetPut(putter, getter);
	}
}
