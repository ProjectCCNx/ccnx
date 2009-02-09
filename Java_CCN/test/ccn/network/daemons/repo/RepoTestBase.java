package test.ccn.network.daemons.repo;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;

import test.ccn.library.LibraryTestBase;

public class RepoTestBase extends LibraryTestBase {
	
	protected String badPolicyXML = 
		"<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
		"<policy>\n" +
			"<namespace> /testNameSpace </namespace>\n" +
		"</policy>";
	protected String policyXMLStart = 
		"<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
		"<policy>\n" +
			"<version id=\"1.0\"/>\n" +
			"<hostname>";
	protected String policyXMLEnd =
			"</hostname>\n" +
			"<namespace> /testNameSpace </namespace>\n" +
			"<namespace> /testNameSpace2 </namespace>\n" +
		"</policy>";
	
	protected static String _badPolicyFileName = null;
	protected static String _policyFileName = null;
	protected byte [] _badPolicyContent = null;
	protected byte [] _policyContent = null;
	
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
	}
	
	@AfterClass
	public static void cleanup() throws Exception {
		if (_badPolicyFileName != null) {
			File file = new File(_badPolicyFileName);
			file.delete();
		}
		if (_policyFileName != null) {
			File file = new File(_policyFileName);
			file.delete();
		}
	}
	
	@Before
	public void setUp() throws Exception {
	}
	
	protected void createBadPolicy(boolean asFile) {
		_badPolicyContent = badPolicyXML.getBytes();
		if (asFile) {
			File tmpFile = null;
			try {
				tmpFile = File.createTempFile("policyTest", ".xml");
				FileOutputStream fos = new FileOutputStream(tmpFile);
				fos.write(_badPolicyContent);
				fos.close();
			} catch (IOException e) {}
			_badPolicyFileName = tmpFile.getAbsolutePath();
		}
	}
	
	protected void createGoodPolicy(boolean asFile) {
		File tmpFile = null;
		String hostName = null;
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		
		try {
			hostName = InetAddress.getLocalHost().getHostName();
		} catch (UnknownHostException e) {
			try {
				hostName = InetAddress.getLocalHost().getHostAddress();
			} catch (UnknownHostException e1) {
				// TODO Auto-generated catch block
				e1.printStackTrace();
				return;
			}
		}
		
		try {
			baos.write(policyXMLStart.getBytes());
			baos.write(hostName.getBytes());
			baos.write(policyXMLEnd.getBytes());
			_policyContent = baos.toByteArray();
			baos.close();
		} catch (IOException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}
		
		if (asFile) {
			try {
				tmpFile = File.createTempFile("policyTest", ".xml");
				FileOutputStream fos = new FileOutputStream(tmpFile);
				fos.write(_policyContent);
				fos.close();
			} catch (IOException e) {}
			_policyFileName = tmpFile.getAbsolutePath();
		}
	}
}
