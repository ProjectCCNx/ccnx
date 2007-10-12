package test.ccn.data.security;

import java.util.Arrays;

import org.junit.BeforeClass;
import org.junit.Test;

import test.ccn.data.XMLEncodableTester;

import com.parc.ccn.data.security.PublisherID;
import com.parc.ccn.data.security.PublisherID.PublisherType;

public class PublisherIDTest {

	static public byte [] publisherid = new byte[32];
	
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		try {
			Arrays.fill(publisherid, (byte)3);			
		} catch (Exception ex) {
			XMLEncodableTester.handleException(ex);
			System.out.println("Unable To Initialize Test!!!");
		}	
	}

	@Test
	public void testDecodeInputStream() {
		PublisherID pubkey = new PublisherID(publisherid, PublisherType.KEY);
		PublisherID pubkeyDec = new PublisherID();
		XMLEncodableTester.encodeDecodeTest("PublisherID(key)", pubkey, pubkeyDec);
	
		PublisherID pubcert = new PublisherID(publisherid, PublisherType.CERTIFICATE);
		PublisherID pubcertDec = new PublisherID();
		XMLEncodableTester.encodeDecodeTest("PublisherID(cert)", pubcert, pubcertDec);
		
		PublisherID pubisskey = new PublisherID(publisherid, PublisherType.ISSUER_KEY);
		PublisherID pubisskeyDec = new PublisherID();
		XMLEncodableTester.encodeDecodeTest("PublisherID(isskey)", pubisskey, pubisskeyDec);
			
		PublisherID pubisscert = new PublisherID(publisherid, PublisherType.ISSUER_CERTIFICATE);
		PublisherID pubisscertDec = new PublisherID();
		XMLEncodableTester.encodeDecodeTest("PublisherID(isscert)", pubisscert, pubisscertDec);
			
	}

}
