package test.ccn.network.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.SignatureException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Date;
import java.util.Random;

import javax.jcr.RepositoryException;
import javax.jcr.query.InvalidQueryException;
import javax.jcr.query.Query;
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
import com.parc.ccn.data.query.Interest;
import com.parc.ccn.data.security.ContentAuthenticator;
import com.parc.ccn.data.security.KeyLocator;
import com.parc.ccn.data.security.PublisherID;
import com.parc.ccn.data.security.PublisherKeyID;
import com.parc.ccn.data.util.DataUtils;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.library.StandardCCNLibrary;
import com.parc.ccn.network.impl.JackrabbitCCNRepository;
import com.parc.security.crypto.certificates.BCX509CertificateGenerator;

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
	static String [] testname = new String[]{"test", "smetters", "Activities", ".directory"};

//	static String [] testname = new String[]{"parc.com", "home", "smetters", "Key", "_b_QVHo0jm3yle8hqO1eJtNtlIpoLf3xZKS_X002F_qexnCviNrs_X003D_"};
//	static String [] testname = new String[]{"parc.com", "home", "smetters", "Key", "_b_QVHo0jm3yle8hqO1eJtNtlIpoLf3xZKS_x002F_qexnCviNrs_x003D_"};
	// static String [] testname = new String[]{"test","smetters","values","data","_b_cSil7rUBIYjrplNNeZxzBLt7IwvOmqFCWFqzfO345do_x003D_!"};
	//static String [] testname = new String[]{"test","smetters","values","data","_b_cSil7rUBIYjrplNNeZxzBLt7IwvOmqFCWFqzfO345do_x003D_"};
	//static String [] testname = new String[]{"test","smetters","values","data", "testdata.txt"};

	static String [] testBase = new String[] {"test", "smetters", "content", "versioned"};
	static final int VERSION_COUNT = 5;
	
	static ContentName collectedBaseName = new ContentName(testBase);
	static ContentName testCN = new ContentName(testname);
	static ContentAuthenticator testAuth = null;
	static byte [] testSig = null;
	
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
	static KeyLocator keyLoc = null;
	static PublisherKeyID pubID = null;	
	
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
			pubID = new PublisherKeyID(pair.getPublic());
			keyLoc = new KeyLocator(keyname, new PublisherID(pubID));
			
			Library.logger().info("Getting local repository.");
			repo = JackrabbitCCNRepository.getLocalJackrabbitRepository();
			library = new StandardCCNLibrary();
						
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

	public void checkGetResults(ArrayList<ContentObject> getResults) {
		boolean verifySig = false;
		for (int i=0; i < getResults.size(); ++i) {
			try {
				verifySig = getResults.get(i).verify(pair.getPublic());
				Library.logger().info("Get signature verified? " + verifySig);
				assertTrue(verifySig);
			} catch (Exception e) {
				Library.logger().info("Exception in checkGetResults for name: " + getResults.get(i).name() +": " + e.getClass().getName() + " " + e.getMessage());
				Library.infoStackTrace(e);
				fail();			
			}
		} 
	}
	
	public void checkPutResults(CompleteName putResult) {
		try {
		} catch (Exception e) {
			Library.logger().info("Exception in checkPutResults for name: " + putResult.name() +": " + e.getClass().getName() + " " + e.getMessage());
			Library.infoStackTrace(e);
			fail();			
		}
	}

	@Test
	public void testQueryString() {
		String queryString = "/jcr:root/test/briggs/foo.txt/_b_Ey8By8VSg1vo9pJ5sB9XmITu8nGEz0u6NqNmbyBVzak_x003D_";
		try {
			repo.session().getWorkspace().getQueryManager().createQuery(queryString, Query.XPATH);
		} catch (InvalidQueryException e) {
			Library.logger().warning("Invalid query string: " + queryString);
			Library.logger().warning("Exception: " + e.getClass().getName() + " m: " + e.getMessage());
			Assert.fail();
		} catch (RepositoryException e) {
			Library.logger().warning("Exception: " + e.getClass().getName() + " m: " + e.getMessage());
			Assert.fail();
		}
	}
	
	public CompleteName generateVersionedNameAndAuth(ContentName startName, int version, byte [] content) throws InvalidKeyException, SignatureException {
		ContentName versionedName = library.versionName(startName, version);
		
		CompleteName authenticatedName = CompleteName.generateAuthenticatedName(versionedName, pubID, null, 
								ContentAuthenticator.ContentType.LEAF, keyLoc, content, pair.getPrivate());
		return authenticatedName;
	}

	@Test
	public void testPut() {
		assertNotNull(repo);
		
		Library.logger().info("Adding content.");

		try {
			int index = new Random().nextInt(1000);
			String collection = "Collection-" + Integer.toString(index);
			ContentName startName = new ContentName(collectedBaseName, collection);
			for (int i=0; i < VERSION_COUNT; ++i) {
				byte [] content = new Integer(i).toString().getBytes("UTF-8");
				CompleteName inname = generateVersionedNameAndAuth(startName, i, content); 
				CompleteName outname = repo.put(inname.name(), inname.authenticator(), content, inname.signature());
				checkPutResults(outname);
				Library.logger().info("Added name: " + outname.name());
			}
			
		} catch (Exception e) {
			Library.logger().info("Got exception : " + e.getClass().getName() + " message: " + e.getMessage());
			e.printStackTrace();
			Assert.fail("Got exception : " + e.getClass().getName() + " message: " + e.getMessage());
		}
	}

	@Test
	public void testRecall() {
		assertNotNull(repo);
		
		Library.logger().info("Adding content.");
		ArrayList<ContentObject> retrievedNames = null;
		try {
			int index = new Random().nextInt(1000);
			String collection = "Collection-" + Integer.toString(index);
			ContentName startName = new ContentName(collectedBaseName, collection);
			for (int i=0; i < VERSION_COUNT; ++i) {
				byte [] content = new Integer(i).toString().getBytes("UTF-8");
				CompleteName inname = generateVersionedNameAndAuth(startName, i, content); 
				CompleteName outname = repo.put(inname.name(), inname.authenticator(), content, inname.signature());
				checkPutResults(outname);
				Library.logger().info("Added name, next will retrieve: " + outname.name());

				retrievedNames = repo.get(outname.name(), null, false);
				
				assertEquals(retrievedNames.size(), 1);
				assertEquals(i, Integer.parseInt(new String(retrievedNames.get(0).content())));
				checkGetResults(retrievedNames);
				System.out.println("Got " + i);
			}
			
		} catch (Exception e) {
			Library.logger().info("Got exception : " + e.getClass().getName() + " message: " + e.getMessage());
			e.printStackTrace();
			Assert.fail("Got exception : " + e.getClass().getName() + " message: " + e.getMessage());
		}
	}


	@Test
	public void testRecurse() {
		assertNotNull(repo);
		
		Library.logger().info("Adding content.");
		ArrayList<ContentObject> retrievedNames = null;
		try {
			int index = new Random().nextInt(1000);
			String collection = "Collection-" + Integer.toString(index);
			ContentName startName = new ContentName(collectedBaseName, collection);
			for (int i=0; i < VERSION_COUNT; ++i) {
				byte [] content = new Integer(i).toString().getBytes("UTF-8");
				CompleteName inname = generateVersionedNameAndAuth(startName, i, content); 
				CompleteName outname = repo.put(inname.name(), inname.authenticator(), content, inname.signature());
				checkPutResults(outname);
				Library.logger().info("Added name: " + outname.name());
			}
			
			retrievedNames = repo.get(startName, null, true);
			Library.logger().info("Recursive retrieve, got " + retrievedNames.size() + " results, expected " + VERSION_COUNT + ".");
				
			assertTrue(retrievedNames.size() >= VERSION_COUNT);
			checkGetResults(retrievedNames);
			
		} catch (Exception e) {
			Library.logger().info("Got exception : " + e.getClass().getName() + " message: " + e.getMessage());
			e.printStackTrace();
			Assert.fail("Got exception : " + e.getClass().getName() + " message: " + e.getMessage());
		}
	}

	

	@Test
	public void testRecallByAuth() {
		assertNotNull(repo);
		
		Library.logger().info("Adding content.");
		ArrayList<ContentObject> retrievedNames = null;
		try {
			int index = new Random().nextInt(1000);
			String collection = "Collection-" + Integer.toString(index);
			ContentName startName = new ContentName(collectedBaseName, collection);
			for (int i=0; i < VERSION_COUNT; ++i) {
				byte [] content = new Integer(i).toString().getBytes("UTF-8");
				CompleteName inname = generateVersionedNameAndAuth(startName, i, content); 
				CompleteName outname = repo.put(inname.name(), inname.authenticator(), content, inname.signature());
				checkPutResults(outname);
				Library.logger().info("Added name, next will retrieve: " + outname.name());

				retrievedNames = repo.get(outname.name(), outname.authenticator(), false);
				
				assertEquals(retrievedNames.size(), 1);
				assertEquals(i, Integer.parseInt(new String(retrievedNames.get(0).content())));
				checkGetResults(retrievedNames);
				System.out.println("Got " + i);
			}
			
		} catch (Exception e) {
			Library.logger().info("Got exception : " + e.getClass().getName() + " message: " + e.getMessage());
			e.printStackTrace();
			Assert.fail("Got exception : " + e.getClass().getName() + " message: " + e.getMessage());
		}
	}


	@Test
	public void testRecurseByAuth() {
		assertNotNull(repo);
		
		Library.logger().info("Adding content.");
		ArrayList<ContentObject> retrievedNames = null;
		try {
			int index = new Random().nextInt(1000);
			String collection = "Collection-" + Integer.toString(index);
			ContentName startName = new ContentName(collectedBaseName, collection);
			for (int i=0; i < VERSION_COUNT; ++i) {
				byte [] content = new Integer(i).toString().getBytes("UTF-8");
				CompleteName inname = generateVersionedNameAndAuth(startName, i, content); 
				CompleteName outname = repo.put(inname.name(), inname.authenticator(), content, inname.signature());
				checkPutResults(outname);
				Library.logger().info("Added name: " + outname.name());
			}
			
			ContentAuthenticator pubOnlyAuth = new ContentAuthenticator(pubID);
			retrievedNames = repo.get(startName, pubOnlyAuth, true);
			Library.logger().info("Recursive retrieve, got " + retrievedNames.size() + " results, expected " + VERSION_COUNT + ".");
				
			assertTrue(retrievedNames.size() >= VERSION_COUNT);
			checkGetResults(retrievedNames);
			
		} catch (Exception e) {
			Library.logger().info("Got exception : " + e.getClass().getName() + " message: " + e.getMessage());
			e.printStackTrace();
			Assert.fail("Got exception : " + e.getClass().getName() + " message: " + e.getMessage());
		}
	}


	@Test
	public void testName() {
		assertNotNull(repo);
		String [] names = new String[]{"*", StandardCCNLibrary.VERSION_MARKER};
		
		for (int i=0; i < names.length; ++i) {
			Library.logger().info("Name: " + names[i]);
			byte [] nameBytes = ContentName.componentParse(names[i]);
			Library.logger().info("Parses into " + nameBytes.length + " bytes: " + 
					DataUtils.printBytes(nameBytes));
			
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
								DataUtils.printBytes(name3v6t2.component(name3v6t2.count()-1)));
			
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
				repo.enumerate(new Interest(nameTop,null));
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
