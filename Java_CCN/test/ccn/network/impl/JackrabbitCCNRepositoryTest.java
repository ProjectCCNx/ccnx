package test.ccn.network.impl;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Date;
import java.util.Random;

import javax.jmdns.ServiceInfo;

import junit.framework.Assert;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import test.ccn.data.XMLEncodableTester;

import com.parc.ccn.Library;
import com.parc.ccn.data.CompleteName;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.security.ContentAuthenticator;
import com.parc.ccn.data.security.KeyLocator;
import com.parc.ccn.data.security.PublisherID;
import com.parc.ccn.data.util.XMLHelper;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.library.StandardCCNLibrary;
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
	static public String document5 = "thisisareallyreallyreallylongnamefortesting.foo";
	static String [] arrName1 = new String[]{baseName,subName2,document1};
	static ContentName name1 = new ContentName(arrName1);
	static String [] arrName2 = new String[]{baseName,subName1,document2};
	static ContentName name2 = new ContentName(arrName2);
	static String [] arrName3 = new String[]{baseName,subName2,document4};
	static ContentName name3 = new ContentName(arrName3);
	static String [] arrName4 = new String[]{baseName,subName1,document5};
	static ContentName name4 = new ContentName(arrName4);
	
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
	static PublisherID pubkey = null;	
	static ContentAuthenticator [] auth = null;
	static ContentAuthenticator pubonlyauth = null;
	
	static ContentName [] names = null;
	static ContentName [] versionedNames = null;
	static byte [][] content = null;
	
	// Really only want one of these per VM per port.
	static JackrabbitCCNRepository repo = null;
	static CCNLibrary library = null;
	
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		try {
			Security.addProvider(new BouncyCastleProvider());
			
			Library.logger().info("Generating test key pair...");
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
			
			Library.logger().info("Getting local repository.");
			repo = JackrabbitCCNRepository.getLocalJackrabbitRepository();
			library = new StandardCCNLibrary();
			
			Library.logger().info("Organizing content...");
			pubkey = new PublisherID(pair.getPublic(), false);
			
			names = new ContentName[]{name1, name2, name3, name4};
			int v = new Random().nextInt(1000);
			versionedNames = new ContentName[names.length];
			content = new byte[versionedNames.length][];
			auth = new ContentAuthenticator[versionedNames.length];
			for (int i=0; i < names.length; ++i) {
				versionedNames[i] = library.versionName(names[i], v);
				content[i] = document1.getBytes("UTF-8");
			}

			Library.logger().info("Generating content authenticators.");
			for (int i=0;i<versionedNames.length; ++i) {
				auth[i] = new ContentAuthenticator(
						versionedNames[i],
						null,
						pubkey, ContentAuthenticator.now(),
					ContentAuthenticator.ContentType.LEAF, 
					content[i], false,
					nameLoc, pair.getPrivate());
			}
			
			Library.logger().info("Generating authenticator for query.");
			pubonlyauth = new ContentAuthenticator(pubkey);
			
			
		} catch (Exception ex) {
			XMLEncodableTester.handleException(ex);
			Library.logger().info("Unable To Initialize Test!!!");
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
		
		Library.logger().info("Adding content.");
		CompleteName cn = null;
		try {
			for (int i=0; i < versionedNames.length; ++i) {
				cn = repo.put(versionedNames[i], auth[i], content[i]);
				Library.logger().info("Added name: " + cn.name());
			}
			
		} catch (Exception e) {
			Library.logger().info("Got exception : " + e.getClass().getName() + " message: " + e.getMessage());
			e.printStackTrace();
			Assert.fail("Got exception : " + e.getClass().getName() + " message: " + e.getMessage());
		}
	}

	@Test
	public void testGetContent() {
		
		assertNotNull(repo);
		
		try {
			
			Library.logger().info("Retrieving content.");
			
			for (int i=0; i < versionedNames.length; ++i) {
				Library.logger().info("Querying for name: " + versionedNames[i]);
				ArrayList<ContentObject> obj2 = 
					repo.get(versionedNames[i], null);
				Library.logger().info("For name: " + versionedNames[i] + " got: " + obj2.size() + " answers.");
				for (int j=0; j < obj2.size(); ++j) {
					if (null != obj2.get(j))
						Library.logger().info(j + ": " + obj2.get(j));
				}
			}
		} catch (Exception e) {
			Library.logger().info("Got exception : " + e.getClass().getName() + " message: " + e.getMessage());
			e.printStackTrace();
			throw new AssertionError(e);
		}
	}

	@Test
	public void testGetContentNameContentAuthenticatorCCNQueryTypeCCNQueryListenerLong() {
		assertNotNull(repo);
		
		try {
			Library.logger().info("Testing name decoding: ");
			
			Library.logger().info("Retrieving content by name and publisher ID.");
			
			// don't request versioned name, just request
			// base name/*, see what we get back, make
			// sure it selects by publisher.
			ContentName queryName = null;
			for (int i=0; i < versionedNames.length; ++i) {
				queryName = new ContentName(names[i], "*");
				Library.logger().info("Querying for name: " + queryName);
				ArrayList<ContentObject> obj2 = 
					repo.get(queryName, pubonlyauth);
				Library.logger().info("For name: " + names[i] + " got: " + obj2.size() + " answers.");
				for (int j=0; j < obj2.size(); ++j) {
					if (null != obj2.get(j))
						Library.logger().info(j + ": " + obj2.get(j));
				}
			}
		} catch (Exception e) {
			Library.logger().info("Got exception : " + e.getClass().getName() + " message: " + e.getMessage());
			e.printStackTrace();
			throw new AssertionError(e);
		}
	}

	@Test
	public void testName() {
		assertNotNull(repo);
		String [] names = new String[]{"*", StandardCCNLibrary.VERSION_MARKER};
		
		for (int i=0; i < names.length; ++i) {
			Library.logger().info("Name: " + names[i]);
			byte [] nameBytes = ContentName.componentParse(names[i]);
			Library.logger().info("Parses into " + nameBytes.length + " bytes: " + XMLHelper.printBytes(nameBytes));
			
			String unparse = ContentName.componentPrint(nameBytes);
			Library.logger().info("Prints back as: " + unparse);
		}
		
		ContentName name3v6 = library.versionName(name3, 6);
		Library.logger().info("Versioned name: " + name3v6);
		
		byte [] byteMarker = ContentName.componentParse(StandardCCNLibrary.VERSION_MARKER);
		byte [] byteV = ContentName.componentParse(Integer.toString(6));
		ContentName name3v6t2 = new ContentName(name3, 
					byteMarker,
					byteV);
		Library.logger().info("Constructed versioned name: " + name3v6t2);
		Library.logger().info("Bytes of last name component: " + 
								XMLHelper.printBytes(name3v6t2.component(name3v6t2.count()-1)));
			
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
		
		Library.logger().info("Creating connection to repository: " + info);
		try {
			JackrabbitCCNRepository repo2 = new JackrabbitCCNRepository(info);
			assertNotNull(repo2);
			Library.logger().info("Successful.");
		} catch (Exception e) {
			Library.logger().info("Got a " + e.getClass().getName() + ", message: " + e.getMessage());
			e.printStackTrace();
			Assert.fail("Exception.");
		}

	}
	
	@Test
	public void testEnumerate() {
		assertNotNull(repo);
		String [] arrNameTop = new String[]{baseName};
		ContentName nameTop = new ContentName(arrNameTop);
		try {
			System.out.println("Enumerating: " + nameTop);
			ArrayList<CompleteName> results = 
				repo.enumerate(new CompleteName(nameTop,null));
			System.out.println("Got " + results.size() + " results:");
			for (int i=0; i < results.size(); ++i) {
				System.out.println("Result " + i + ": " + results.get(i).name());
			}
		} catch (IOException e) {
			Library.logger().info("Got a " + e.getClass().getName() + ", message: " + e.getMessage());
			e.printStackTrace();
			Assert.fail("Exception.");
		}
	}

}
