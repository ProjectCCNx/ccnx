package test.ccn.library;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;

import org.junit.Test;

import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.query.CCNInterestListener;
import com.parc.ccn.data.query.Interest;
import com.parc.ccn.library.CCNLibrary;

/**
 * 
 * @author rasmusse
 * 
 * NOTE: This test requires ccnd to be running
 *
 */

public class ReadTest extends BaseLibrary implements CCNInterestListener {
	
	private static ArrayList<Integer> currentSet;

	public ReadTest() throws Throwable {
		super();
	}
	
	@Test
	public void getNext() throws Throwable {
		System.out.println("getNext test started");
		for (int i = 0; i < count; i++) {
			Thread.sleep(rand.nextInt(50));
			library.put("/getNext/" + Integer.toString(i), Integer.toString(count - i));
		}
		System.out.println("Put sequence finished");
		for (int i = 0; i < count; i++) {
			Thread.sleep(rand.nextInt(50));
			int tValue = rand.nextInt(count - 1);
			ContentName prefix = ContentName.fromNative("/getNext/" + new Integer(tValue).toString(), 1);
			ContentName cn = new ContentName(prefix, ContentObject.contentDigest(Integer.toString(count - tValue)));
			ContentObject result = library.getNext(cn, CCNLibrary.NO_TIMEOUT);
			checkResult(result, tValue + 1);
		}
		System.out.println("getNext test finished");
	}
	
	@Test
	public void getLatest() throws Throwable {
		int highest = 0;
		System.out.println("getLatest test started");
		for (int i = 0; i < count; i++) {
			Thread.sleep(rand.nextInt(50));
			int tValue = getRandomFromSet(count, false);
			if (tValue > highest)
				highest = tValue;
			library.put("/getLatest/" + Integer.toString(tValue), Integer.toString(tValue));
			Thread.sleep(rand.nextInt(50));
			if (i > 1) {
				if (tValue == highest)
					tValue--;
				ContentObject result = library.getLatest(ContentName.fromNative("/getLatest/" + Integer.toString(tValue), 1), CCNLibrary.NO_TIMEOUT);
				checkResult(result, highest);
			}
		}
		System.out.println("getLatest test finished");
	}

	public Interest handleContent(ArrayList<ContentObject> results, Interest interest) {
		return null;
	}
	
	private void checkResult(ContentObject result, int value) {
		String resultAsString = result.name().toString();
		int sep = resultAsString.lastIndexOf('/');
		assertTrue(sep > 0);
		int resultValue = Integer.parseInt(resultAsString.substring(sep + 1));
		assertEquals(new Integer(value), new Integer(resultValue));
	}
	
	public int getRandomFromSet(int length, boolean reset) {
		int result = -1;
		if (reset || currentSet == null)
			currentSet = new ArrayList<Integer>(length);
		if (currentSet.size() >= length)
			return result;
		while (true) {
			result = rand.nextInt(length);
			boolean found = false;
			for (int used : currentSet) {
				if (used == result) {
					found = true;
					break;
				}
			}
			if (!found)
				break;
		}
		currentSet.add(result);
		return result;
	}

}
