package test.ccn.data.content;

import java.sql.Timestamp;
import java.util.Arrays;

import org.junit.BeforeClass;
import org.junit.Test;

import test.ccn.data.XMLEncodableTester;

import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.content.Link;
import com.parc.ccn.data.security.ContentAuthenticator;
import com.parc.ccn.data.security.LinkAuthenticator;
import com.parc.ccn.data.security.PublisherID;
import com.parc.ccn.data.security.PublisherID.PublisherType;

public class LinkTest {

	static final  String baseName = "test";
	static final  String subName = "smetters";
	static final  String document1 = "intro.html";	
	static final  String document2 = "key";	
	static final String document3 = "cv.txt";
	static ContentName name = new ContentName(new String[]{baseName, subName, document1});
	static ContentName name2 = new ContentName(new String[]{baseName, subName, document2});
	static ContentName name3 = new ContentName(new String[]{baseName, subName, document3});
	static ContentName name4 = null;
	static ContentName [] ns = null;
	static public byte [] contenthash1 = new byte[32];
	static public byte [] contenthash2 = new byte[32];
	static public byte [] publisherid1 = new byte[32];
	static public byte [] publisherid2 = new byte[32];
	static PublisherID pubID1 = null;	
	static PublisherID pubID2 = null;	
	static LinkAuthenticator [] las = new LinkAuthenticator[4];

	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		name4 = new ContentName("/parc/home/briggs/collaborators.txt");
		ns = new ContentName[]{name,name2,name3,name4};
		Arrays.fill(contenthash1, (byte)2);
		Arrays.fill(contenthash2, (byte)4);
		Arrays.fill(publisherid1, (byte)6);
		Arrays.fill(publisherid2, (byte)3);
		
		pubID1 = new PublisherID(publisherid1, PublisherType.KEY);
		pubID1 = new PublisherID(publisherid2, PublisherType.ISSUER_KEY);
	
		las[0] = new LinkAuthenticator(pubID1);
		las[1] = new LinkAuthenticator();
		las[2] = new LinkAuthenticator(pubID2, null, new Timestamp(System.currentTimeMillis()),
									   null, contenthash1);
		las[3] = new LinkAuthenticator(pubID1, Integer.valueOf(2), new Timestamp(System.currentTimeMillis()),
				   ContentAuthenticator.ContentType.LEAF, contenthash1);
		
	}

	@Test
	public void testEncodeOutputStream() {
		for (int i=0; i < ns.length; ++i) {
			Link l = new Link(ns[i],las[i]);
			Link ldec = new Link();
			XMLEncodableTester.encodeDecodeTest("Link_" + i, l, ldec);
		}
	}

}
