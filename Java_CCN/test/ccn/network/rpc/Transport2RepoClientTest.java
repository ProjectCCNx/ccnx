package test.ccn.network.rpc;

import java.net.InetAddress;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Date;
import java.util.Random;

import junit.framework.Assert;

import org.acplt.oncrpc.OncRpcProtocols;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.BeforeClass;
import org.junit.Test;

import test.ccn.data.XMLEncodableTester;

import com.parc.ccn.Library;
import com.parc.ccn.config.SystemConfiguration;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.security.ContentAuthenticator;
import com.parc.ccn.data.security.KeyLocator;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.library.StandardCCNLibrary;
import com.parc.ccn.network.rpc.DataBlock;
import com.parc.ccn.network.rpc.NameList;
import com.parc.ccn.network.rpc.RepoTransport_TRANSPORTTOREPOPROG_Client;
import com.parc.ccn.security.crypto.certificates.BCX509CertificateGenerator;

public class Transport2RepoClientTest {

	static RepoTransport_TRANSPORTTOREPOPROG_Client _client = null;
	static CCNLibrary _library = null;

	static public String baseName = "test";
	static public String subName1 = "briggs";
	static public String subName2 = "smetters";
	static public String document1 = "test7.txt";
	static public String content1 = "This is the content of a test file.";
	static public String document2 = "test8.txt";	
	static public String content2 = "This is the content of a second test file.";
	static public String document4 = "the newer important document name.foo";	

	static String [] arrName1 = new String[]{baseName,subName1,document1};
	static ContentName name1 = new ContentName(arrName1);
	static String [] arrName2 = new String[]{baseName,subName1,document2};
	static ContentName name2 = new ContentName(arrName2);
	static String [] arrName3 = new String[]{baseName,subName2,document1};
	static ContentName name3 = new ContentName(arrName3);
	static String [] arrNameTop = new String[]{baseName};
	static ContentName nameTop = new ContentName(arrNameTop);

	static public byte [] document3 = new byte[]{0x01, 0x02, 0x03, 0x04,
		0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c,
		0x0d, 0x0e, 0x0f, 0x1f, 0x1b, 0x1c, 0x1d, 0x1e,
		0x1f, 0x2e, 0x3c, 0x4a, 0x5c, 0x6d, 0x7e, 0xf};

	static final String rootDN = "C=US,O=Organization,OU=Organizational Unit,CN=Issuer";
	static final String endDN = "C=US,O=Final Org,L=Locality,CN=Fred Jones,E=fred@final.org";
	static final Date start = new Date(); 
	static final Date end = new Date(start.getTime() + (60*60*24*365));
	static final  String keydoc = "key";	
	static ContentName keyname = new ContentName(new String[]{baseName, subName2, keydoc});

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

			System.out.println("Getting client.");
			InetAddress address = InetAddress.getByName("127.0.0.1");
			System.out.println("Address: " + address);
			_client = new RepoTransport_TRANSPORTTOREPOPROG_Client(
					//InetAddress.getLocalHost(), 
					InetAddress.getByName("127.0.0.1"),
					SystemConfiguration.defaultRepositoryPort(),
					OncRpcProtocols.ONCRPC_TCP);
			_library = new StandardCCNLibrary();
			
		} catch (Exception ex) {
			XMLEncodableTester.handleException(ex);
			System.out.println("Unable To Initialize Test!!!");
			Assert.fail("Exception: " + ex.getMessage());
		}
	}

	@Test
	public void testPutBlock_1() {
		PrivateKey signingKey = _library.keyManager().getDefaultSigningKey();
		KeyLocator locator = _library.keyManager().getKeyLocator(signingKey);
		
		try {
			ContentName versionedName = _library.versionName(name3, new Random().nextInt(1000));
			System.out.println("Adding name: " + versionedName);
			ContentAuthenticator authenticator = 
				new ContentAuthenticator(_library.getDefaultPublisher(), null,
										 ContentAuthenticator.now(),
										 ContentAuthenticator.ContentType.LEAF,
										 locator,
										 document3, false);
			ContentObject obj = new ContentObject(versionedName, versionedName.count(), authenticator, document3, signingKey);
			DataBlock block = new DataBlock();
			block.data = obj.canonicalizeAndEncode(signingKey);
			block.length = block.data.length;
			
			Library.logger().info("Calling putBlock.");
			_client.PutBlock_1(versionedName.toONCName(), 
							   block);
			
			Library.logger().info("Calling getBlock.");
			DataBlock returnedBlock = _client.GetBlock_1(versionedName.toONCName());
		
			System.out.println("Block: " + block.length +  " bytes.");
			System.out.println("Returned block: " + returnedBlock.length +  " bytes.");
			
			if (returnedBlock.length > 0 ) {
				ContentObject returnedObj = new ContentObject();
				returnedObj.decode(returnedBlock.data);
			
				System.out.println("Original block:");
				System.out.println(obj);
			
				System.out.println("Returned block:");
				System.out.println(returnedObj);
			}
			
			// DataBlock does not define an equals...
			Assert.assertEquals(block.length, returnedBlock.length);
			int diff = 0;
			for (diff = 0; diff < block.length; ++diff) {
				if (block.data[diff] != returnedBlock.data[diff]) {
					// DKS TODO: fix problem in base64/quoting that is returning different vals
					System.out.println("Original and returned blocks differ at byte: " + diff + " values are o:" + Byte.toString(block.data[diff]) + " r: " + Byte.toString(returnedBlock.data[diff]));
					
				}
			}
			Assert.assertTrue("Blocks differ -- problem in base64 somewhere? ", Arrays.equals(block.data, returnedBlock.data));
			
		} catch (Exception e) {
			XMLEncodableTester.handleException(e);
			Assert.fail("Exception: " + e.getMessage());
		}
	}

	@Test
	public void testEnumerate_1() {
		try {
			System.out.println("Enumerating data under name: " + nameTop);
			NameList nl = _client.Enumerate_1(nameTop.toONCName());
			System.out.println("Got " + nl.count + " results.");
			for (int i=0; i < nl.count; ++i) {
				ContentName name = new ContentName(nl.names[i]);
				System.out.println(name.toString());
			}
			ContentName nameTopR = new ContentName(nameTop, "*");
			System.out.println("Enumerating data under name: " + nameTopR);
			NameList nl2 = _client.Enumerate_1(nameTopR.toONCName());
			System.out.println("Got " + nl2.count + " results.");
			for (int i=0; i < nl2.count; ++i) {
				ContentName name = new ContentName(nl2.names[i]);
				System.out.println(name.toString());
			}
		} catch (Exception e) {
			XMLEncodableTester.handleException(e);
			Assert.fail("Exception: " + e.getMessage());
		}	
	}
}
