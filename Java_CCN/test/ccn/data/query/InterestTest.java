package test.ccn.data.query;


import java.security.InvalidParameterException;
import java.util.ArrayList;

import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import test.ccn.data.XMLEncodableTester;

import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.MalformedContentNameStringException;
import com.parc.ccn.data.query.BloomFilter;
import com.parc.ccn.data.query.ExcludeElement;
import com.parc.ccn.data.query.ExcludeFilter;
import com.parc.ccn.data.query.Interest;
import com.parc.ccn.data.security.PublisherID;
import com.parc.ccn.security.crypto.CCNDigestHelper;

public class InterestTest {
	
	public static String testName = "/test/parc/home/smetters/interestingData.txt/v/5";
	public static ContentName tcn = null;
	public static PublisherID pubID = null;
	
	private ExcludeElement c1 = null;
	private ExcludeElement c2 = null;
	private ExcludeElement b1 = null;
	private ExcludeElement b2 = null;
	private byte [] bloomSeed = "burp".getBytes();
	private ExcludeFilter ef = null;
	
	private String [] bloomTestValues = {
            "one", "two", "three", "four",
            "five", "six", "seven", "eight",
            "nine", "ten", "eleven", "twelve",
            "thirteen"
      	};

	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		
		byte [] testID = CCNDigestHelper.digest(testName.getBytes());
		
		tcn = ContentName.fromURI(testName);
		pubID = new PublisherID(testID,PublisherID.PublisherType.ISSUER_KEY);
	}

	@Before
	public void setUp() throws Exception {
	}
	
	private void excludeSetup() {
		c1 = new ExcludeElement("aaaaaaaa".getBytes());
		c2 = new ExcludeElement("zzzzzzzz".getBytes());
		BloomFilter bf1 = new BloomFilter(13, bloomSeed);
		BloomFilter bf2 = new BloomFilter(10, "foob".getBytes());
		b1 = new ExcludeElement(bf1);
		b2 = new ExcludeElement(bf2);

		
		for (String value : bloomTestValues) {
			bf1.insert(value.getBytes());
		}
		ArrayList<ExcludeElement>excludes = new ArrayList<ExcludeElement>(3);
		excludes.add(c1);
		excludes.add(b1);
		excludes.add(c2);
		ef = new ExcludeFilter(excludes);
	}

	@Test
	public void testSimpleInterest() {
		Interest plain = new Interest(tcn);
		Interest plainDec = new Interest();
		Interest plainBDec = new Interest();
		XMLEncodableTester.encodeDecodeTest("PlainInterest", plain, plainDec, plainBDec);

		Interest nplain = new Interest(tcn,pubID);
		Interest nplainDec = new Interest();
		Interest nplainBDec = new Interest();
		XMLEncodableTester.encodeDecodeTest("FancyInterest", nplain, nplainDec, nplainBDec);
		
		Interest opPlain = new Interest(tcn);
		opPlain.orderPreference(Interest.ORDER_PREFERENCE_LEFT + Interest.ORDER_PREFERENCE_ORDER_ARRIVAL);
		Interest opPlainDec = new Interest();
		Interest opPlainBDec = new Interest();
		XMLEncodableTester.encodeDecodeTest("OPInterest", opPlain, opPlainDec, opPlainBDec);
	}
		
	@Test
	public void testExcludeFilter() {
		excludeSetup();
		
		ArrayList<ExcludeElement> bad1 = new ArrayList<ExcludeElement>(2);
		bad1.add(c2);
		bad1.add(c1);
		try {
			new ExcludeFilter(bad1);
			Assert.fail("Out of order elements in ExcludeFilter succeeded!");
		} catch (InvalidParameterException e) {}
		
		ArrayList<ExcludeElement>bad2 = new ArrayList<ExcludeElement>(3);
		bad2.add(c1);
		bad2.add(b1);
		bad2.add(b2);
		try {
			new ExcludeFilter(bad2);
			Assert.fail("Consecutive bloom filters in ExcludeFilter succeeded!");
		} catch (InvalidParameterException e) {}
		
		Interest exPlain = new Interest(tcn);
		exPlain.excludeFilter(ef);
		Interest exPlainDec = new Interest();
		Interest exPlainBDec = new Interest();
		XMLEncodableTester.encodeDecodeTest("ExcludeInterest", exPlain, exPlainDec, exPlainBDec);
	}
	
	@Test
	public void testMatch() {
		// paul r Comment - should really test more comprehensively
		// For now just do this to test the exclude matching
		excludeSetup();
		try {
			Interest interest = new Interest("/paul");
			interest.excludeFilter(ef);
			Assert.assertTrue(interest.matches(ContentName.fromNative("/paul/car"), null));
			Assert.assertFalse(interest.matches(ContentName.fromNative("/paul/zzzzzzzz"), null));
			for (String value : bloomTestValues) {
				String completeName = "/paul/" + value;
				Assert.assertFalse(interest.matches(ContentName.fromNative(completeName), null));
			}
		} catch (MalformedContentNameStringException e) {
			Assert.fail(e.getMessage());
		}
	}

}
