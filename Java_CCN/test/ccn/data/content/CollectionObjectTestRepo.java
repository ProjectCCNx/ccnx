/**
 * 
 */
package test.ccn.data.content;


import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.Random;

import javax.xml.stream.XMLStreamException;

import junit.framework.Assert;

import org.junit.BeforeClass;
import org.junit.Test;


import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.content.Link;
import com.parc.ccn.data.content.Collection.CollectionObject;
import com.parc.ccn.data.util.CCNStringObject;
import com.parc.ccn.library.CCNLibrary;

/**
 * @author smetters
 *
 */
public class CollectionObjectTestRepo {

	static ContentName baseName;
	static CCNLibrary getLibrary;
	static CCNLibrary putLibrary;
	/**
	 * @throws java.lang.Exception
	 */
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		baseName = ContentName.fromNative("/libraryTest/CollectionObjectTestRepo-" + new Random().nextInt(10000));
		putLibrary = CCNLibrary.open();
		getLibrary = CCNLibrary.open();
	}
	
	@Test
	public void testCollections() throws Exception {
		ContentName testPrefix  = ContentName.fromNative(baseName, "testCollections");
		ContentName nonCollectionName = ContentName.fromNative(testPrefix, "myNonCollection");
		ContentName collectionName = ContentName.fromNative(testPrefix, "myCollection");
		
		// Write something that isn't a collection
		CCNStringObject so = new CCNStringObject(nonCollectionName, "This is not a collection.", putLibrary);
		so.saveToRepository();
		
		Link[] references = new Link[2];
		references[0] = new Link(ContentName.fromNative(collectionName, "r1"));
		references[1] = new Link(ContentName.fromNative(collectionName, "r2"));
		CollectionObject collection = new CollectionObject(collectionName, references, putLibrary);
		collection.saveToRepository();
		
		try {
			CollectionObject notAnObject = new CollectionObject(nonCollectionName, getLibrary);
			notAnObject.waitForData();
			Assert.fail("Reading collection from non-collection succeeded.");
		} catch (IOException ioe) {
		} catch (XMLStreamException ex) {
			// this is what we actually expect
		}
			
		// test reading latest version
		CollectionObject readCollection = new CollectionObject(collectionName, getLibrary);
		readCollection.waitForData();
		LinkedList<Link> checkReferences = collection.contents();
		Assert.assertEquals(checkReferences.size(), 2);
		Assert.assertEquals(references[0], checkReferences.get(0).targetName());
		Assert.assertEquals(references[1], checkReferences.get(1).targetName());
		
		// test addToCollection
		ArrayList<Link> newReferences = new ArrayList<Link>();
		newReferences.add(new Link(ContentName.fromNative("/libraryTest/r3")));
		newReferences.add(new Link(ContentName.fromNative("/libraryTest/r4")));
		collection.contents().addAll(newReferences);
		collection.save();
		readCollection.update(5000);
		checkReferences = collection.contents();
		Assert.assertEquals(collection.getCurrentVersion(), readCollection.getCurrentVersion());
		Assert.assertEquals(checkReferences.size(), 4);
		Assert.assertEquals(newReferences.get(0), checkReferences.get(2));
		Assert.assertEquals(newReferences.get(1), checkReferences.get(3));
		
		collection.contents().removeAll(newReferences);
		collection.save();
		readCollection.update(5000);
		checkReferences = collection.contents();
		Assert.assertEquals(collection.getCurrentVersion(), readCollection.getCurrentVersion());
		checkReferences = collection.contents();
		Assert.assertEquals(collection.getCurrentVersion(), readCollection.getCurrentVersion());
		Assert.assertEquals(collection.contents(), readCollection.contents());
	}
}
