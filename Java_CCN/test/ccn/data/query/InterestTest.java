package test.ccn.data.query;


import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import test.ccn.data.XMLEncodableTester;

import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.query.Interest;
import com.parc.ccn.data.security.PublisherID;
import com.parc.ccn.security.crypto.CCNDigestHelper;

public class InterestTest {
	
	public static String testName = "/test/parc/home/smetters/interestingData.txt/v/5";
	public static ContentName tcn = null;
	public static PublisherID pubID = null;

	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		
		byte [] testID = CCNDigestHelper.digest(testName.getBytes());
		
		tcn = ContentName.fromURI(testName);
		pubID = new PublisherID(testID,PublisherID.PublisherType.ISSUER_KEY);
	}

	@Before
	public void setUp() throws Exception {
	}

	@Test
	public void testInterest() {
		Interest plain = new Interest(tcn);
		Interest plainDec = new Interest();
		Interest plainBDec = new Interest();
		XMLEncodableTester.encodeDecodeTest("PlainInterest", plain, plainDec, plainBDec);

		Interest nplain = new Interest(tcn,pubID);
		Interest nplainDec = new Interest();
		Interest nplainBDec = new Interest();
		XMLEncodableTester.encodeDecodeTest("FancyInterest", nplain, nplainDec, nplainBDec);
	}

}
