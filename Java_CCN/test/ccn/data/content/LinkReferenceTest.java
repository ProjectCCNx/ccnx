package test.ccn.data.content;

import java.sql.Timestamp;
import java.util.Arrays;

import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.PublisherID;
import org.ccnx.ccn.protocol.SignedInfo;
import org.ccnx.ccn.protocol.PublisherID.PublisherType;
import org.junit.BeforeClass;
import org.junit.Test;

import test.ccn.data.util.XMLEncodableTester;

import com.parc.ccn.data.content.Link;
import com.parc.ccn.data.security.LinkAuthenticator;

public class LinkReferenceTest {

	static final  String baseName = "test";
	static final  String linkBaseName = "link";
	static final  String subName = "smetters";
	static final  String document1 = "intro.html";	
	static final  String document2 = "key";	
	static final String document3 = "cv.txt";
	static ContentName name = null;
	static ContentName name2 = null;
	static ContentName name3 = null;
	static ContentName name4 = null;
	static ContentName [] ns = null;
	static ContentName linkName = null;
	static ContentName linkName2 = null;
	static ContentName linkName3 = null;
	static ContentName linkName4 = null;
	static ContentName [] ls = null;
	static public byte [] contenthash1 = new byte[32];
	static public byte [] contenthash2 = new byte[32];
	static public byte [] publisherid1 = new byte[32];
	static public byte [] publisherid2 = new byte[32];
	static PublisherID pubID1 = null;	
	static PublisherID pubID2 = null;	
	static LinkAuthenticator [] las = new LinkAuthenticator[4];
	static String labels[];

	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		name = ContentName.fromURI(new String[]{baseName, subName, document1});
		name2 = ContentName.fromURI(new String[]{baseName, subName, document2});
		name3 = ContentName.fromURI(new String[]{baseName, subName, document3});
		name4 = ContentName.fromURI("/parc/home/briggs/collaborators.txt");
		ns = new ContentName[]{name,name2,name3,name4};
		labels = new String[]{"This is a label", "", null, "BigLabel"};
		linkName = ContentName.fromURI(new String[]{linkBaseName, subName, document1});
		linkName2 = ContentName.fromURI(new String[]{linkBaseName, subName, document2});
		linkName3 = ContentName.fromURI(new String[]{linkBaseName, subName, document3});
		linkName4 = ContentName.fromURI("/link/home/briggs/collaborators.txt");
		ls = new ContentName[]{linkName,linkName2,linkName3,linkName4};
		Arrays.fill(contenthash1, (byte)2);
		Arrays.fill(contenthash2, (byte)4);
		Arrays.fill(publisherid1, (byte)6);
		Arrays.fill(publisherid2, (byte)3);
		
		pubID1 = new PublisherID(publisherid1, PublisherType.KEY);
		pubID1 = new PublisherID(publisherid2, PublisherType.ISSUER_KEY);
	
		las[0] = new LinkAuthenticator(pubID1);
		las[1] = new LinkAuthenticator();
		las[2] = new LinkAuthenticator(pubID2, null, new Timestamp(System.currentTimeMillis()),
									   null, contenthash2);
		las[3] = new LinkAuthenticator(pubID1, null, new Timestamp(System.currentTimeMillis()),
				   SignedInfo.ContentType.DATA, contenthash1);
		
	}

	@Test
	public void testEncodeOutputStream() throws Exception {
		
		for (int i=0; i < ns.length; ++i) {
			Link l = new Link(ls[i], labels[i], las[i]);
			Link ldec = new Link();
			Link lbdec = new Link();
			XMLEncodableTester.encodeDecodeTest("LinkReference_" + i, l, ldec, lbdec);
		}
	}

}
