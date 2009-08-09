package test.ccn.network.daemons.repo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;

import org.junit.Assert;
import org.junit.Test;

import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.query.Interest;
import com.parc.ccn.data.security.PublisherID;
import com.parc.ccn.data.security.PublisherPublicKeyDigest;

public class RepoInitialReadTest extends RepoTestBase {
	
	@Test
	public void testReadViaRepo() throws Throwable {
		System.out.println("Testing reading objects from repo");
		ContentName name = ContentName.fromNative("/repoTest/data1");
		
		// Since we have 2 pieces of data with the name /repoTest/data1 we need to compute both
		// digests to make sure we get the right data.
		ContentName name1 = new ContentName(name, ContentObject.contentDigest("Here's my data!"));
		ContentName digestName = new ContentName(name, ContentObject.contentDigest("Testing2"));
		String tooLongName = "0123456789";
		for (int i = 0; i < 30; i++)
			tooLongName += "0123456789";
		
		// Have 2 pieces of data with the same name here too.
		ContentName longName = ContentName.fromNative("/repoTest/" + tooLongName);
		longName = new ContentName(longName, ContentObject.contentDigest("Long name!"));
		ContentName badCharName = ContentName.fromNative("/repoTest/" + "*x?y<z>u");
		ContentName badCharLongName = ContentName.fromNative("/repoTest/" + tooLongName + "*x?y<z>u");
			
		checkDataWithDigest(name1, "Here's my data!");
		checkDataWithDigest(digestName, "Testing2");
		checkDataWithDigest(longName, "Long name!");
		checkData(badCharName, "Funny characters!");
		checkData(badCharLongName, "Long and funny");
		
		ArrayList<ContentObject>keys = getLibrary.enumerate(new Interest(keyprefix), 4000);
		for (ContentObject keyObject : keys) {
			checkDataAndPublisher(name, "Testing2", new PublisherPublicKeyDigest(keyObject.content()));
		}
	}
	
	private void checkData(ContentName name, String data) throws IOException, InterruptedException{
		checkData(new Interest(name), data.getBytes());
	}
	
	private void checkDataWithDigest(ContentName name, String data) throws IOException, InterruptedException{
		// When generating an Interest for the exact name with content digest, need to set additionalNameComponents
		// to 0, signifying that name ends with explicit digest
		checkData(new Interest(name, 0, (PublisherID)null), data.getBytes());
	}
	
	private void checkData(Interest interest, byte[] data) throws IOException, InterruptedException{
		ContentObject testContent = getLibrary.get(interest, 10000);
		Assert.assertFalse(testContent == null);
		Assert.assertTrue(Arrays.equals(data, testContent.content()));		
	}

	private void checkDataAndPublisher(ContentName name, String data, PublisherPublicKeyDigest publisher) 
				throws IOException, InterruptedException {
		Interest interest = new Interest(name, new PublisherID(publisher));
		ContentObject testContent = getLibrary.get(interest, 10000);
		Assert.assertFalse(testContent == null);
		Assert.assertEquals(data, new String(testContent.content()));
		Assert.assertTrue(testContent.signedInfo().getPublisherKeyID().equals(publisher));
	}

}
