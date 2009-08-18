/**
 * 
 */
package test.ccn.data.content;


import java.io.IOException;
import java.util.Random;

import javax.xml.stream.XMLStreamException;

import junit.framework.Assert;

import org.junit.BeforeClass;
import org.junit.Test;


import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.content.Link;
import com.parc.ccn.data.content.Link.LinkObject;
import com.parc.ccn.data.security.SignedInfo.ContentType;
import com.parc.ccn.data.util.CCNStringObject;
import com.parc.ccn.library.CCNLibrary;

/**
 * @author smetters
 *
 */
public class LinkObjectTestRepo {

	static ContentName baseName;
	static CCNLibrary getLibrary;
	static CCNLibrary putLibrary;
	static Random random;
	/**
	 * @throws java.lang.Exception
	 */
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		random = new Random();
		baseName = ContentName.fromNative("/libraryTest/LinkObjectTestRepo-" + random.nextInt(10000));
	}
	
	@Test
	public void testLinks() throws Exception {
		
		ContentName testPrefix  = ContentName.fromNative(baseName, "testLinks");
		ContentName nonLinkName = ContentName.fromNative(testPrefix, "myNonLink");
		ContentName linkName = ContentName.fromNative(testPrefix, "myLink");
		
		// Write something that isn't a collection
		CCNStringObject so = new CCNStringObject(nonLinkName, "This is not a link, number " + random.nextInt(10000), putLibrary);
		so.saveToRepository();
		
		try {
			LinkObject notAnObject = new LinkObject(nonLinkName, getLibrary);
			notAnObject.waitForData();
			Assert.fail("Reading link from non-link succeeded.");
		} catch (IOException ioe) {
		} catch (XMLStreamException ex) {
			// this is what we actually expect
		}

		Link lr = new Link(so.getCurrentVersionName());
		LinkObject aLink = new LinkObject(linkName, lr, putLibrary);
		aLink.save();
		
		ContentObject linkData = getLibrary.get(aLink.getCurrentVersionName(), 5000);
		if (null == linkData) {
			Assert.fail("Cannot retrieve first block of saved link: " + aLink.getCurrentVersionName());
		}
		// Make sure we're writing type LINK.
		Assert.assertEquals(linkData.signedInfo().getType(), ContentType.LINK);
		
		LinkObject readLink = new LinkObject(linkData, getLibrary);
		readLink.waitForData();
		
		Assert.assertEquals(readLink.link(), lr);

		ContentObject firstBlock = aLink.dereference(5000);
		if (null == firstBlock) {
			Assert.fail("Cannot read first block of link target: " + readLink.getTargetName());
		}
		// TODO -- not a good test; does dereference get us back the first block? What about the
		// first block of the latest version? What if thing isn't versioned? (e.g. intermediate node)
		CCNStringObject readString = new CCNStringObject(firstBlock, getLibrary);
		readString.waitForData();
		
		Assert.assertEquals(readString.string(), so.string());
	}
}
