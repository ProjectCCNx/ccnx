package test.ccn.data.security;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.Date;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.BeforeClass;
import org.junit.Test;

import test.ccn.data.XMLEncodableTester;

import com.parc.ccn.crypto.certificates.BCX509CertificateGenerator;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.security.KeyLocator;

public class KeyLocatorTest {

	static final String rootDN = "C=US,O=Organization,OU=Organizational Unit,CN=Issuer";
	static final String endDN = "C=US,O=Final Org,L=Locality,CN=Fred Jones,E=fred@final.org";
	static final Date start = new Date(); 
	static final Date end = new Date(start.getTime() + (60*60*24*365));
	static final  String baseName = "test";
	static final  String subName2 = "smetters";
	static final  String document2 = "key";	
	ContentName name = new ContentName(new String[]{baseName, subName2, document2});

	static KeyPair pair = null;
	static X509Certificate cert = null;
	
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		try {
			Security.addProvider(new BouncyCastleProvider());
			
			// generate key pair
			KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
			kpg.initialize(512); // go for fast
			pair = kpg.generateKeyPair();
			cert = 
				BCX509CertificateGenerator.GenerateX509Certificate(
					pair.getPublic(),
					rootDN,
					endDN,
					null,
					start,
					end,
					null,
					pair.getPrivate(),
					null);
		} catch (Exception ex) {
			XMLEncodableTester.handleException(ex);
			System.out.println("Unable To Initialize Test!!!");
		}	
	}

	@Test
	public void testEncodeOutputStream() {
		KeyLocator nameLoc = new KeyLocator(name);
		KeyLocator nameLocDec = new KeyLocator();
		XMLEncodableTester.encodeDecodeTest("KeyLocator(name)", nameLoc, nameLocDec);

		KeyLocator keyLoc = new KeyLocator(pair.getPublic());
		KeyLocator keyLocDec = new KeyLocator();
		XMLEncodableTester.encodeDecodeTest("KeyLocator(key)", keyLoc, keyLocDec);

		KeyLocator certLoc = new KeyLocator(cert);
		KeyLocator certLocDec = new KeyLocator();
		XMLEncodableTester.encodeDecodeTest("KeyLocator(cert)", certLoc, certLocDec);

	}	

}
