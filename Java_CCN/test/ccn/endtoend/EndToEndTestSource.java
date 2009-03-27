package test.ccn.endtoend;


import static org.junit.Assert.assertTrue;

import java.util.ArrayList;

import org.junit.Test;

import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.query.CCNFilterListener;
import com.parc.ccn.data.query.Interest;
import com.parc.ccn.library.io.CCNWriter;

//NOTE: This test requires ccnd to be running and complementary sink process 

public class EndToEndTestSource extends BaseLibrarySource implements CCNFilterListener {
	protected CCNWriter _writer;
	
	@Test
	public void puts() throws Throwable {
		assert(count <= Byte.MAX_VALUE);
		System.out.println("Put sequence started");
		CCNWriter writer = new CCNWriter("/BaseLibraryTest", library);
		for (int i = 0; i < count; i++) {
			Thread.sleep(rand.nextInt(50));
			byte[] content = getRandomContent(i);
			ContentObject putResult = writer.put(ContentName.fromNative("/BaseLibraryTest/gets/" + new Integer(i).toString()), content);
			System.out.println("Put " + i + " done: " + content.length + " content bytes");
			checkPutResults(putResult);
		}
		writer.close();
		System.out.println("Put sequence finished");
	}
	
	@Test
	public void server() throws Throwable {
		System.out.println("PutServer started");
		name = ContentName.fromNative("/BaseLibraryTest/");
		_writer = new CCNWriter(name, library);
		library.registerFilter(name, this);
		// Block on semaphore until enough data has been received
		sema.acquire();
		library.unregisterFilter(name, this);
		if (null != error) {
			throw error;
		}
	}

	public synchronized int handleInterests(ArrayList<Interest> interests) {
		try {
			if (next >= count) {
				return 0;
			}
			for (Interest interest : interests) {
				assertTrue(name.isPrefixOf(interest.name()));
				byte[] content = getRandomContent(next);
				ContentObject putResult = _writer.put(ContentName.fromNative("/BaseLibraryTest/server/" + new Integer(next).toString()), content);
				System.out.println("Put " + next + " done: " + content.length + " content bytes");
				checkPutResults(putResult);
				next++;
			}
			if (next >= count) {
				sema.release();
			}
		} catch (Throwable e) {
			error = e;
		}
		return 0;
	}
}
