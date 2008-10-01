package test.ccn.data.read;

import static org.junit.Assert.*;

import java.util.ArrayList;

import org.junit.Test;

import test.ccn.endtoend.BaseLibrarySource;

import com.parc.ccn.data.CompleteName;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.query.CCNInterestListener;
import com.parc.ccn.data.query.Interest;

/**
 * 
 * @author rasmusse
 * 
 * NOTE: This test requires ccnd to be running
 *
 */

public class ReadTest extends BaseLibrarySource implements CCNInterestListener {

	public ReadTest() throws Throwable {
		super();
	}
	
	@Test
	public void getNext() throws Throwable {
		System.out.println("Read test started");
		for (int i = 0; i < count; i++) {
			Thread.sleep(rand.nextInt(50));
			byte[] content = new byte[] { new Integer(count -i).byteValue() };
			library.put(ContentName.fromNative("/ReadTest/" + new Integer(i).toString()), content);
		}
		System.out.println("Put sequence finished");
		for (int i = 0; i < count; i++) {
			Thread.sleep(rand.nextInt(50));
			int tValue = rand.nextInt(count -1);
			ArrayList<ContentObject> results = library.getNext("/ReadTest/" + new Integer(tValue).toString(), 
					new byte[] { new Integer(count - tValue).byteValue() }, 1);
			for (ContentObject result : results) {
				String resultAsString = result.name().toString();
				int sep = resultAsString.lastIndexOf('/');
				assertTrue(sep > 0);
				int resultValue = Integer.parseInt(resultAsString.substring(sep + 1));
				assertEquals(new Integer(resultValue), new Integer(tValue + 1));
			}
		}
		System.out.println("Read test finished");
	}

	public void addInterest(Interest interest) {}

	public void cancelInterests() {}

	public Interest[] getInterests() {
		return null;
	}

	public Interest handleContent(ArrayList<ContentObject> results) {
		return null;
	}

	public void interestTimedOut(Interest interest) {}

	public boolean matchesInterest(CompleteName name) {
		return false;
	}

}
