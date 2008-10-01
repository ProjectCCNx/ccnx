package test.ccn.endtoend;


import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.Semaphore;
import java.util.logging.Level;

import org.junit.BeforeClass;
import org.junit.Test;

import com.parc.ccn.Library;
import com.parc.ccn.data.CompleteName;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.query.CCNFilterListener;
import com.parc.ccn.data.query.Interest;
import com.parc.ccn.data.util.BinaryXMLCodec;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.library.StandardCCNLibrary;

//NOTE: This test requires ccnd to be running and complementary sink process 

public class EndToEndTestSource extends BaseLibrarySource implements CCNFilterListener {
	
	public EndToEndTestSource() throws Throwable {
		super();
	}
	
	@Test
	public void puts() throws Throwable {
		assert(count <= Byte.MAX_VALUE);
		System.out.println("Put sequence started");
		for (int i = 0; i < count; i++) {
			Thread.sleep(rand.nextInt(50));
			byte[] content = getRandomContent(i);
			CompleteName putName = library.put(ContentName.fromNative("/BaseLibraryTest/gets/" + new Integer(i).toString()), content);
			System.out.println("Put " + i + " done: " + content.length + " content bytes");
			checkPutResults(putName);
		}
		System.out.println("Put sequence finished");
	}
	
	@Test
	public void server() throws Throwable {
		System.out.println("PutServer started");
		// Register filter
		name = ContentName.fromNative("/BaseLibraryTest/");
		library.setInterestFilter(name, this);
		// Block on semaphore until enough data has been received
		sema.acquire();
		library.cancelInterestFilter(name, this);
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
				CompleteName putName = library.put(ContentName.fromNative("/BaseLibraryTest/server/" + new Integer(next).toString()), content);
				System.out.println("Put " + next + " done: " + content.length + " content bytes");
				checkPutResults(putName);
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
