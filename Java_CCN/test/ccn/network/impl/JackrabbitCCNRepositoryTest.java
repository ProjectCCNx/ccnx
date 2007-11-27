package test.ccn.network.impl;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;

import javax.jmdns.ServiceInfo;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import test.ccn.data.XMLEncodableTester;

import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.security.ContentAuthenticator;
import com.parc.ccn.data.security.KeyLocator;
import com.parc.ccn.data.security.PublisherID;
import com.parc.ccn.data.security.PublisherID.PublisherType;
import com.parc.ccn.network.impl.JackrabbitCCNRepository;
import com.parc.ccn.security.crypto.certificates.BCX509CertificateGenerator;

public class JackrabbitCCNRepositoryTest {
	
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
	static String [] arrName3 = new String[]{baseName,subName2,document4};
	static ContentName name3 = new ContentName(arrName3);
	
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
	static KeyLocator nameLoc = null;
	static public byte [][] signature = new byte[3][256];
	static public byte [][] contenthash = new byte[3][32];
	static public byte [] publisherid = new byte[32];
	static PublisherID pubkey = null;	
	static ContentAuthenticator [] auth = new ContentAuthenticator[3];
	static ContentAuthenticator pubonlyauth = null;
	
	// Really only want one of these per VM per port.
	static JackrabbitCCNRepository repo = null;
	
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
			nameLoc = new KeyLocator(keyname);
			
			Arrays.fill(publisherid, (byte)10);
			
			pubkey = new PublisherID(publisherid, PublisherType.KEY);

			for (int i=0; i<3; ++i) {
				Arrays.fill(signature[i], (byte)(i+1));
				Arrays.fill(contenthash[i], (byte)(i+2));
				auth[i] = new ContentAuthenticator(pubkey, 
					new Timestamp(System.currentTimeMillis()), 
					ContentAuthenticator.ContentType.LEAF, 
					contenthash[i],
					nameLoc, signature[i]);
			}
			
			pubonlyauth = new ContentAuthenticator(pubkey);
			
			System.out.println("Creating local repository.");
			repo = new JackrabbitCCNRepository();
			
		} catch (Exception ex) {
			XMLEncodableTester.handleException(ex);
			System.out.println("Unable To Initialize Test!!!");
		}	
	}

	@AfterClass
	public static void tearDownAfterClass() throws Exception {
	}

	@Before
	public void setUp() throws Exception {
	}

	@After
	public void tearDown() throws Exception {
	}


	@Test
	public void testPut() {
		assertNotNull(repo);
		
		System.out.println("Adding content.");
		
		try {
			repo.put(name1, auth[0], document1.getBytes("UTF-8"));
			repo.put(name2, auth[1], document2.getBytes("UTF-8"));
			repo.put(name3, auth[2], document3);
		
		} catch (Exception e) {
			System.out.println("Got exception : " + e.getClass().getName() + " message: " + e.getMessage());
			e.printStackTrace();
			throw new AssertionError(e);
		}
	}

	@Test
	public void testGetContent() {
		
		assertNotNull(repo);
		
		System.out.println("Adding content.");
		
		try {
			repo.put(name1, auth[0], document1.getBytes("UTF-8"));

			System.out.println("Adding name: " + name2);
			name2.encode(System.out);
			
			repo.put(name2, auth[1], document2.getBytes("UTF-8"));
			repo.put(name3, auth[2], document3);
			
			System.out.println("Retrieving content.");
			
			ArrayList<ContentObject> obj2 = repo.get(name2, null);
			System.out.println("For name: " + name2 + " got: " + obj2.size() + " answers.");
			for (int i=0; i < obj2.size(); ++i) {
				if (null != obj2.get(i))
					System.out.println(i + ": " + obj2.get(i));
			}
			
			ArrayList<ContentObject> obj3 = repo.get(name3, null);
			System.out.println("For name: " + name3 + " got: " + obj3.size() + " answers.");
			for (int i=0; i < obj3.size(); ++i) {
				if (null != obj3.get(i))
					System.out.println(i + ": " + obj3.get(i));
			}
		} catch (Exception e) {
			System.out.println("Got exception : " + e.getClass().getName() + " message: " + e.getMessage());
			e.printStackTrace();
			throw new AssertionError(e);
		}
	}

	@Test
	public void testGetAuthenticationInfo() {
		fail("Not yet implemented, need to improve filters so the use this stuff to query repo");
	}

	@Test
	public void testGetContentNameContentAuthenticatorCCNQueryTypeCCNQueryListenerLong() {
		assertNotNull(repo);
		
		System.out.println("Adding content.");
		
		try {
			repo.put(name1, auth[0], document1.getBytes("UTF-8"));
			repo.put(name2, auth[1], document2.getBytes("UTF-8"));
			repo.put(name3, auth[2], document3);
			
			System.out.println("Retrieving content.");
			
			ArrayList<ContentObject> obj1 = repo.get(name1, pubonlyauth);
			ArrayList<ContentObject> obj2 = repo.get(name2, pubonlyauth);
			ArrayList<ContentObject> obj3 = repo.get(name3, pubonlyauth);
			
			System.out.println("For name: " + name1 + " got: " + obj1.size() + " answers.");
			System.out.println("For name: " + name2 + " got: " + obj2.size() + " answers.");
			System.out.println("For name: " + name3 + " got: " + obj3.size() + " answers.");
			for (int i=0; i < obj1.size(); ++i) {
				if (null != obj1.get(i))
					System.out.println(i + ": " + obj1.get(i));
			}
		} catch (Exception e) {
			System.out.println("Got exception : " + e.getClass().getName() + " message: " + e.getMessage());
			e.printStackTrace();
			throw new AssertionError(e);
		}
	}

	@Test
	public void testFindRepo() {
		assertNotNull(repo);
		// use discovery to find repo
		fail("Not yet implemented");
		
	}
	@Test
	public void testJackrabbitCCNRepositoryServiceInfo() {
		
		assertNotNull(repo);
		ServiceInfo info = repo.info();
		
		System.out.println("Creating connection to repository: " + info);
		try {
			JackrabbitCCNRepository repo2 = new JackrabbitCCNRepository(info);
			assertNotNull(repo2);
			System.out.println("Successful.");
		} catch (Exception e) {
			System.out.println("Got a " + e.getClass().getName() + ", message: " + e.getMessage());
			e.printStackTrace();
			throw new RuntimeException(e);
		}

	}
}
