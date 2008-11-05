package test.ccn.data.content;

import java.sql.Timestamp;
import java.util.Arrays;

import junit.framework.Assert;

import org.junit.BeforeClass;
import org.junit.Test;

import test.ccn.data.XMLEncodableTester;

import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.content.Collection;
import com.parc.ccn.data.content.LinkReference;
import com.parc.ccn.data.security.ContentAuthenticator;
import com.parc.ccn.data.security.LinkAuthenticator;
import com.parc.ccn.data.security.PublisherID;
import com.parc.ccn.data.security.PublisherID.PublisherType;

public class CollectionTest {

	static final  String baseName = "test";
	static final  String subName = "smetters";
	static final  String document1 = "intro.html";	
	static final  String document2 = "key";	
	static final String document3 = "cv.txt";
	static ContentName name = null;
	static ContentName name2 = null; 
	static ContentName name3 = null;
	static ContentName name4 = null;
	static ContentName [] ns = null;
	static public byte [] contenthash1 = new byte[32];
	static public byte [] contenthash2 = new byte[32];
	static public byte [] publisherid1 = new byte[32];
	static public byte [] publisherid2 = new byte[32];
	static PublisherID pubID1 = null;	
	static PublisherID pubID2 = null;	
	static LinkAuthenticator [] las = new LinkAuthenticator[4];
	static LinkReference [] ls = null;

	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		name = ContentName.fromURI(new String[]{baseName, subName, document1});
		name2 = ContentName.fromURI(new String[]{baseName, subName, document2});
		name3 = ContentName.fromURI(new String[]{baseName, subName, document3});
		name4 = ContentName.fromURI("/parc/home/briggs/collaborators.txt");
		ns = new ContentName[]{name,name2,name3,name4};
		Arrays.fill(contenthash1, (byte)2);
		Arrays.fill(contenthash2, (byte)4);
		Arrays.fill(publisherid1, (byte)6);
		Arrays.fill(publisherid2, (byte)3);

		pubID1 = new PublisherID(publisherid1, PublisherType.KEY);
		pubID1 = new PublisherID(publisherid2, PublisherType.ISSUER_KEY);

		las[0] = new LinkAuthenticator(pubID1);
		las[1] = null;
		las[2] = new LinkAuthenticator(pubID1, new Timestamp(System.currentTimeMillis()),
				null, contenthash1);
		las[3] = new LinkAuthenticator(pubID1, new Timestamp(System.currentTimeMillis()),
				ContentAuthenticator.ContentType.LEAF, contenthash1);


		ls = new LinkReference[4];
		for (int i=0; i < ls.length; ++i) {
			ls[i] = new LinkReference(ns[i],las[i]);
		}
	}

	@Test
	public void testEncodeOutputStream() {
		Collection c = new Collection(ls);
		Collection cdec = new Collection();
		Collection bcdec = new Collection();
		XMLEncodableTester.encodeDecodeTest("Collection", c, cdec, bcdec);
	}

	@Test
	public void testAddLink() {
		LinkReference [] ls2 = new LinkReference[]{ls[0],ls[1],ls[2]};
		Collection c1 = new Collection(ls2);
		c1.add(ls[3]);
		Assert.assertEquals(c1.size(), 4);
	}

}
