package test.ccn.data.security;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.Date;

import javax.security.auth.x500.X500Principal;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.BeforeClass;
import org.junit.Test;

import test.ccn.data.util.XMLEncodableTester;

import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.security.KeyLocator;
import com.parc.ccn.data.security.PublisherID;
import com.parc.ccn.security.crypto.util.MinimalCertificateGenerator;

public class KeyLocatorTest {

	static final String rootDN = "C=US,O=Organization,OU=Organizational Unit,CN=Issuer";
	static final String endDN = "C=US,O=Final Org,L=Locality,CN=Fred Jones,E=fred@final.org";
	static final Date start = new Date(); 
	static final Date end = new Date(start.getTime() + (60*60*24*365));
	static final  String baseName = "test";
	static final  String subName2 = "smetters";
	static final  String document2 = "key";	
	static ContentName name = null;

	static KeyPair pair = null;
	static X509Certificate cert = null;
	static PublisherID pubID = null;
	
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		try {
			name = ContentName.fromURI(new String[]{baseName, subName2, document2});
			Security.addProvider(new BouncyCastleProvider());
			
			// generate key pair
			KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
			kpg.initialize(512); // go for fast
			pair = kpg.generateKeyPair();
			MinimalCertificateGenerator mg = new MinimalCertificateGenerator(
					endDN, pair.getPublic(), 
					new X500Principal(rootDN), 
					MinimalCertificateGenerator.MSEC_IN_YEAR, false);
			cert = mg.sign(null, pair.getPrivate());
			pubID = new PublisherID(pair.getPublic(), false);
		} catch (Exception ex) {
			XMLEncodableTester.handleException(ex);
			System.out.println("Unable To Initialize Test!!!");
		}	
	}

	@Test
	public void testEncodeOutputStream() {
		KeyLocator nameLoc = new KeyLocator(name);
		KeyLocator nameLocDec = new KeyLocator();
		KeyLocator bnameLocDec = new KeyLocator();
		XMLEncodableTester.encodeDecodeTest("KeyLocator(name)", nameLoc, nameLocDec, bnameLocDec);
		
		KeyLocator nameIDLoc = new KeyLocator(name, pubID);
		KeyLocator nameIDLocDec = new KeyLocator();
		KeyLocator bnameIDLocDec = new KeyLocator();
		XMLEncodableTester.encodeDecodeTest("KeyLocator(name,ID)", nameIDLoc, nameIDLocDec, bnameIDLocDec);
		
		KeyLocator keyLoc = new KeyLocator(pair.getPublic());
		KeyLocator keyLocDec = new KeyLocator();
		KeyLocator bkeyLocDec = new KeyLocator();
		XMLEncodableTester.encodeDecodeTest("KeyLocator(key)", keyLoc, keyLocDec, bkeyLocDec);

		KeyLocator certLoc = new KeyLocator(cert);
		KeyLocator certLocDec = new KeyLocator();
		KeyLocator bcertLocDec = new KeyLocator();
		XMLEncodableTester.encodeDecodeTest("KeyLocator(cert)", certLoc, certLocDec, bcertLocDec);

	}	

}
