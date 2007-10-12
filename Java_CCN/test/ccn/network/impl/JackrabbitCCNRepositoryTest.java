package test.ccn.network.impl;

import static org.junit.Assert.*;

import java.net.MalformedURLException;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;

import javax.jmdns.ServiceInfo;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.parc.ccn.data.ContentName;
import com.parc.ccn.network.impl.JackrabbitCCNRepository;

public class JackrabbitCCNRepositoryTest {
	
	public String baseName = "test";
	public String subName1 = "briggs";
	public String subName2 = "smetters";
	public String document1 = "test.txt";
	public String content1 = "This is the content of a test file.";
	public String document2 = "test2.txt";	
	public String content2 = "This is the content of a second test file.";
	public String document4 = "the important document name.foo";	
	
	String [] arrName1 = new String[]{baseName,subName1,document1};
	ContentName name1 = new ContentName(arrName1);
	String [] arrName2 = new String[]{baseName,subName1,document2};
	ContentName name2 = new ContentName(arrName2);
	String [] arrName3 = new String[]{baseName,subName2,document4};
	ContentName name3 = new ContentName(arrName3);
	
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
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
	public void testJackrabbitCCNRepository() {
		System.out.println("Creating local repository.");
		JackrabbitCCNRepository repo = new JackrabbitCCNRepository();
		
		assertNotNull(repo);
	}

	@Test
	public void testJackrabbitCCNRepositoryServiceInfo() {
		System.out.println("Creating local repository.");
		JackrabbitCCNRepository repo = new JackrabbitCCNRepository();
		
		assertNotNull(repo);
		ServiceInfo info = repo.info();
		
		System.out.println("Creating connection to repository: " + info);
		try {
			JackrabbitCCNRepository repo2 = new JackrabbitCCNRepository(info);
			assertNotNull(repo2);
		} catch (Exception e) {
			System.out.println("Got a " + e.getClass().getName() + ", message: " + e.getMessage());
			e.printStackTrace();
			throw new RuntimeException(e);
		}
	}

	@Test
	public void testPut() {
		System.out.println("Creating local repository.");
		JackrabbitCCNRepository repo = new JackrabbitCCNRepository();
		
		assertNotNull(repo);
		
		System.out.println("Adding content.");
		
		
		
	//	repo.put
		
	}

	@Test
	public void testGetContent() {
		fail("Not yet implemented");
	}

	@Test
	public void testGetAuthenticationInfo() {
		fail("Not yet implemented");
	}

	@Test
	public void testGetContentNameContentAuthenticatorCCNQueryTypeCCNQueryListenerLong() {
		fail("Not yet implemented");
	}

}
