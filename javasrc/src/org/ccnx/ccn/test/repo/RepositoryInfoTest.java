/**
 * 
 */
package org.ccnx.ccn.test.repo;

import java.util.ArrayList;

import org.ccnx.ccn.impl.repo.RepositoryInfo;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.test.impl.encoding.XMLEncodableTester;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * @author smetters
 *
 */
public class RepositoryInfoTest {

	private static String CURRENT_VERSION = "1.4";
	private static String DEFAULT_LOCAL_NAME = "Repository";
	private static String DEFAULT_GLOBAL_NAME = "/parc.com/csl/ccn/Repos";
	/**
	 * @throws java.lang.Exception
	 */
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
	}

	/**
	 * Test method for {@link org.ccnx.ccn.impl.repo.RepositoryInfo#encode(org.ccnx.ccn.impl.encoding.XMLEncoder)}.
	 */
	@Test
	public void testDecodeInputStream() throws Exception {
		
		RepositoryInfo ri = new RepositoryInfo(CURRENT_VERSION, DEFAULT_GLOBAL_NAME, DEFAULT_LOCAL_NAME);
		RepositoryInfo dri = new RepositoryInfo();
		RepositoryInfo bri = new RepositoryInfo();
		XMLEncodableTester.encodeDecodeTest("RepositoryInfo", ri, dri, bri);
		
		ArrayList<ContentName> names = new ArrayList<ContentName>();
		names.add(ContentName.fromNative("/aprefix/asuffix"));
		names.add(ContentName.fromNative("/aprefix/anothersuffix"));
		names.add(ContentName.fromNative(names.get(0), "moresuffix"));
		RepositoryInfo rin = new RepositoryInfo(CURRENT_VERSION, DEFAULT_GLOBAL_NAME, DEFAULT_LOCAL_NAME, names);
		RepositoryInfo drin = new RepositoryInfo();
		RepositoryInfo brin = new RepositoryInfo();
		XMLEncodableTester.encodeDecodeTest("RepositoryInfo(Names)", rin, drin, brin);
	}

}
