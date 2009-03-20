/**
 * 
 */
package test.ccn.library;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.security.SignatureException;
import java.util.ArrayList;
import java.util.Iterator;

import junit.framework.Assert;

import org.junit.Test;

import com.parc.ccn.CCNBase;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.MalformedContentNameStringException;
import com.parc.ccn.data.content.Collection;
import com.parc.ccn.data.content.LinkReference;
import com.parc.ccn.data.query.BasicInterestListener;
import com.parc.ccn.data.query.Interest;
import com.parc.ccn.data.security.PublisherKeyID;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.library.CCNSegmenter;


/**
 * @author briggs,smetters,rasmusse
 *
 */
public class CCNLibraryTest extends LibraryTestBase {
	static final String contentString = "This is a very small amount of content";
	
	private class NameSeen {
		private ContentName name;
		private boolean seen = false;
		
		private NameSeen(ContentName name) {
			this.name = name;
		}
		
		private boolean setSeen(ContentObject co) {
			if (name.isPrefixOf(co.name())) {
				seen = true;
				return true;
			}
			return false;
		}
	}
	
	@Test
	public void testEnumerate() {
		Assert.assertNotNull(library);

		try {
			
			CCNSegmenter segmenter = new CCNSegmenter("/CPOF", library);
			ArrayList<NameSeen> testNames = new ArrayList<NameSeen>(3);
			testNames.add(new NameSeen(ContentName.fromNative("/CPOF/foo")));
			testNames.add(new NameSeen(ContentName.fromNative("/CPOF/bar/lid")));
			testNames.add(new NameSeen(ContentName.fromNative("/CPOF/bar/jar")));
			
			for (int i = 0; i < testNames.size(); i++) {
				segmenter.put(testNames.get(i).name, Integer.toString(i).getBytes());
			}
			
			ArrayList<ContentObject> availableNames =
				library.enumerate(new Interest("/CPOF"), CCNLibrary.NO_TIMEOUT);

			Iterator<ContentObject> nameIt = availableNames.iterator();

			while (nameIt.hasNext()) {
				ContentObject theName = nameIt.next();

				// Just get by name, to test equivalent to current
				// ONC interface.
				ContentObject theObject = library.get(theName.name(), 1000);

				if (null == theObject) {
					System.out.println("Missing content: enumerated name: " + theName.name() + " not gettable.");

				} else {
					System.out.println("Retrieved name: " + theName.name());
				}
				
				for (NameSeen nt : testNames) {
					if (nt.setSeen(theName))
						break;
				}
			}
			
			for (NameSeen nt : testNames) {
				if (!nt.seen)
					Assert.fail("Didn't see name " + nt.name.toString() + " in enumeration");
			}

		} catch (Exception e) {
			System.out.println("Got an exception in enumerate test: " + e.getClass().getName() + ": " + e.getMessage());
			e.printStackTrace();
			Assert.fail("Exception in testEnumerate: " + e.getMessage());
		}
	}

	@Test
	public void testPut() {
		Assert.assertNotNull(library);

		ContentName name = null;
		byte[] content = null;
//		SignedInfo.ContentType type = SignedInfo.ContentType.LEAF;
		PublisherKeyID publisher = null;

		try {
			content = contentString.getBytes("UTF-8");	
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
		}

		try {
			name = ContentName.fromNative("/test/briggs/foo.txt");
		} catch (MalformedContentNameStringException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		try {
			CCNSegmenter segmenter = new CCNSegmenter(name, library);
			ContentObject result = segmenter.put(name, content, publisher);
			System.out.println("Resulting ContentObject: " + result);
		} catch (SignatureException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	@Test
	public void testRevision() {
		String key = "/test/key";
		byte[] data1 = "data".getBytes();
		byte[] data2 = "newdata".getBytes();
		ContentObject revision1;
		ContentObject revision2;

		try {
			ContentName keyName = ContentName.fromNative(key);
			CCNSegmenter segmenter = new CCNSegmenter(keyName, library);
			revision1 = segmenter.newVersion(keyName, data1);
			revision2 = segmenter.newVersion(keyName, data2);
			int version1 = library.getVersionNumber(revision1.name());
			int version2 = library.getVersionNumber(revision2.name());
			System.out.println("Version1: " + version1 + " version2: " + version2);
			Assert.assertTrue("Revisions are strange", 
					version2 > version1);
		} catch (Exception e) {
			System.out.println("Exception in updating versions: " + e.getClass().getName() + ": " + e.getMessage());
			Assert.fail(e.getMessage());
		}	
	}

	@Test
	public void testVersion() throws Exception {

		String name = "/test/smetters/stuff/versioned_name";
		ContentName cn = ContentName.fromNative(name);
		String name2 = "/test/smetters/stuff/.directory";
		ContentName cn2 = ContentName.fromNative(name2);
		String data = "The associated data.";
		String newdata = "The new associated data.";

		versionTest(cn, data.getBytes(), newdata.getBytes());
		versionTest(cn2, data.getBytes(), newdata.getBytes());

	}

	@Test
	public void testRecall() {
		String key = "/test/smetters/values/data";
		byte[] data1 = "data".getBytes();
		try {
			ContentName keyName = ContentName.fromNative(key);
			CCNSegmenter segmenter = new CCNSegmenter(keyName, library);
			ContentObject name = segmenter.put(keyName, data1);
			System.out.println("Put under name: " + name.name());
			ContentObject result = library.get(name.name(), CCNBase.NO_TIMEOUT);

			System.out.println("Querying for returned name, Got back: " + (result == null ? "0"  : "1") + " results.");

			if (result == null) {
				System.out.println("Didn't get back content we just put in.");
				System.out.println("Put under name: " + keyName);
				System.out.println("Final name: " + name.name());
				//Assert.fail("Didn't get back content we just put!");

				result = library.get(name.name(), CCNBase.NO_TIMEOUT);

				System.out.println("Recursive querying for returned name, Got back: " + (result == null ? "0"  : "1") + " results.");

				ContentName parentName = name.name().parent();
				System.out.println("Inserted name's parent same as key name? " + parentName.equals(keyName));

			} else {
				byte [] content = result.content();
				Assert.assertNotNull("No content associated with name we just put!", content);
				Assert.assertTrue("didn't get back same data", new String(data1).equals(new String(content)));
			}

			result = library.get(keyName, CCNBase.NO_TIMEOUT);

			System.out.println("Querying for inserted name, Got back: " 
							+ (result == null ? "0"  : "1") + " results.");

			if (result == null)
				Assert.fail("Didn't get back content we just put!");

			if (result.name().equals(name.name()) &&
					result.signedInfo().equals(name.signedInfo())) {
				System.out.println("Got back name we inserted.");
			} else
				Assert.fail("Didn't get back data we just inserted - result name: " + result.name() + 
						", auth: " + result.signedInfo() + ", orig name: " + name.name() + ", auth: " + name.signedInfo());
		} catch (Exception e) {
			System.out.println("Exception in testing recall: " + e.getClass().getName() + ": " + e.getMessage());
			Assert.fail(e.getMessage());
		}
	}

	public void versionTest(ContentName docName,
			byte [] content1,
			byte [] content2) throws Exception {

		CCNSegmenter segmenter = new CCNSegmenter(docName, library);
		ContentObject version1 = segmenter.newVersion(docName, content1);
		System.out.println("Inserted first version as: " + version1.name());
		Assert.assertNotNull("New version is null!", version1);

		ContentObject latestVersion =
			library.getLatestVersion(docName, null, CCNLibrary.NO_TIMEOUT);

		Assert.assertNotNull("Retrieved latest version of " + docName + " got null!", latestVersion);
		System.out.println("Latest version name: " + latestVersion.name());

		ContentObject version2 = 
			segmenter.newVersion(docName, content2);

		Assert.assertNotNull("New version is null!", version2);
		System.out.println("Inserted second version as: " + version2.name());

		ContentObject newLatestVersion = 
			library.getLatestVersion(docName, null, CCNLibrary.NO_TIMEOUT);
		Assert.assertNotNull("Retrieved new latest version of " + docName + " got null!", newLatestVersion);
		System.out.println("Latest version name: " + newLatestVersion.name());

		Assert.assertTrue("Version is not a version of the parent name!", library.isVersionOf(version1.name(), docName));
		Assert.assertTrue("Version is not a version of the parent name!", library.isVersionOf(version2.name(), docName));
		Assert.assertTrue("Version numbers don't increase!", library.getVersionNumber(version2.name()) > library.getVersionNumber(version1.name()));
	}

	@Test
	public void testNotFound() throws Exception {
		try {
			String key = "/some_strange_key_we_should_never_find";
			ContentObject result = library.get(ContentName.fromNative(key), 1000);
			Assert.assertTrue("found something when there shouldn't have been anything", result == null);
		} catch (Exception e) {
			System.out.println("Exception in testing recall: " + e.getClass().getName() + ": " + e.getMessage());
			Assert.fail(e.getMessage());
		}
	}
	
	@Test
	public void testLinks() throws Exception {
		ContentName baseName = ContentName.fromNative("/libraryTest/linkTest/base");
		CCNSegmenter segmenter = new CCNSegmenter(baseName, library);
		segmenter.put(baseName, "base".getBytes());
		LinkReference lr = new LinkReference(baseName);
		ContentName linkName = ContentName.fromNative("/libraryTest/linkTest/l1");
		segmenter.put(linkName, lr);
		ContentObject linkContent = library.get(linkName, 5000);
		ArrayList<ContentObject> al = library.dereference(linkContent, 5000);
		Assert.assertEquals(al.size(), 1);
		ContentObject baseContent = al.get(0);
		Assert.assertEquals(new String(baseContent.content()), "base");
		LinkReference[] references = new LinkReference[2];
		LinkReference lr2 = new LinkReference(baseName);
		ContentName linkName2 = ContentName.fromNative("/libraryTest/linkTest/l2");
		segmenter.put(linkName2, lr2);
		references[0] = lr;
		references[1] = lr2;
		ContentName c1 = ContentName.fromNative("/libraryTest/linkTest/collection");
		library.put(c1, references);
		ContentObject collectionContent = library.get(c1, 5000);
		al = library.dereference(collectionContent, 5000);
		Assert.assertEquals(al.size(), 2);
		baseContent = al.get(0);
		Assert.assertEquals(new String(baseContent.content()), "base");
		baseContent = al.get(1);
		Assert.assertEquals(new String(baseContent.content()), "base");
	}
	
	@Test
	public void testCollections() throws Exception {
		ContentName baseName = ContentName.fromNative("/libraryTest/collectionTest/base");
		CCNSegmenter segmenter = new CCNSegmenter(baseName, library);
		ContentName collectionName = ContentName.fromNative("/libraryTest/collectionTest/myCollection");
		ContentName[] references = new ContentName[2];
		segmenter.newVersion(baseName, "base".getBytes());
		references[0] = ContentName.fromNative("/libraryTest/collectionTest/r1");
		references[1] = ContentName.fromNative("/libraryTest/collectionTest/r2");
		Collection collection = library.put(collectionName, references);
		
		try {
			library.getCollection(baseName, 5000);
			Assert.fail("getCollection for non-collection succeeded");
		} catch (IOException ioe) {}
		
		// test getCollection
		collection = library.getCollection(collectionName, 5000);
		ArrayList<LinkReference> checkReferences = collection.contents();
		Assert.assertEquals(checkReferences.size(), 2);
		Assert.assertEquals(references[0], checkReferences.get(0).targetName());
		Assert.assertEquals(references[1], checkReferences.get(1).targetName());
		
		// test addToCollection
		ContentName[] newReferences = new ContentName[2];
		newReferences[0] = ContentName.fromNative("/libraryTest/r3");
		newReferences[1] = ContentName.fromNative("/libraryTest/r4");
		library.addToCollection(collection, newReferences);
		collection = library.getCollection(collectionName, 5000);
		checkReferences = collection.contents();
		Assert.assertEquals(checkReferences.size(), 4);
		Assert.assertEquals(newReferences[0], checkReferences.get(2).targetName());
		Assert.assertEquals(newReferences[1], checkReferences.get(3).targetName());
		
		library.removeFromCollection(collection, newReferences);
		collection = library.getCollection(collectionName, 5000);
		checkReferences = collection.contents();
		Assert.assertEquals(checkReferences.size(), 2);
		Assert.assertEquals(references[0], checkReferences.get(0).targetName());
		Assert.assertEquals(references[1], checkReferences.get(1).targetName());
		
		library.updateCollection(collection, newReferences, references);
		collection = library.getCollection(collectionName, 5000);
		checkReferences = collection.contents();
		Assert.assertEquals(checkReferences.size(), 2);
		Assert.assertEquals(newReferences[0], checkReferences.get(0).targetName());
		Assert.assertEquals(newReferences[1], checkReferences.get(1).targetName());
	}

	class TestListener extends BasicInterestListener {
		int _count = 0;
		Thread _mainThread;

		public TestListener(CCNBase queryProvider,
				Interest initialInterest,
				Thread mainThread) {
			super(queryProvider);
			_mainThread = mainThread;
		}

		public Interest handleContent(ArrayList<ContentObject> results, Interest interest) {
			byte[] content = null;
			if (null != results) {
				Iterator<ContentObject> rit = results.iterator();
				while (rit.hasNext()) {
					ContentObject co = rit.next();

					content = co.content();
					String strContent = new String(content);
					System.out.println("Got update for " + co.name() + ": " + strContent + " (revision " + library.getVersionNumber(co.name()) + ")");
					_count++;
					switch(_count) {
					case 1:
						Assert.assertEquals("data1", strContent);
						System.out.println("Got data1 back!");
						_mainThread.interrupt();
						break;
					case 2: 
						Assert.assertEquals("data2", strContent);
						System.out.println("Got data2 back!");
						_mainThread.interrupt();
						break;
					default:
						Assert.fail("Somehow got a third update");
					}
				}
			}
			return null;
		}
	}

	@Test
	public void testInterest() {
		String key = "/test/interest";
		final Thread mainThread = Thread.currentThread();

		byte[] data1 = "data1".getBytes();
		byte[] data2 = "data2".getBytes();

		try {
			CCNSegmenter segmenter = new CCNSegmenter(key, library);
			Interest ik = new Interest(key);
			TestListener tl = new TestListener(library, ik, mainThread);
			library.expressInterest(ik, 
					tl);
			segmenter.put(ContentName.fromNative(key), data1);
			// wait a little bit before we move on...
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
			}

			segmenter.put(ContentName.fromNative(key), data2);

			// wait a little bit before we move on...
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
			}

			library.cancelInterest(ik, tl);

		} catch (Exception e) {
			System.out.println("Exception in testing interests: " + e.getClass().getName() + ": " + e.getMessage());
			Assert.fail(e.getMessage());
		}
	}

}
