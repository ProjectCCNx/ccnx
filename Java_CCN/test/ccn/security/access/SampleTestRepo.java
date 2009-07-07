package test.ccn.security.access;


import java.nio.charset.Charset;
import java.util.ArrayList;

import junit.framework.Assert;

import org.junit.BeforeClass;
import org.junit.Test;

import com.parc.ccn.data.ContentName;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.library.EnumeratedNameList;
import com.parc.ccn.library.io.repo.RepositoryOutputStream;

public class SampleTestRepo {
	static final String base = "/parc.com/csl/ccn/repositories";
	static final String file_name = "simon.txt";
	static final String txt =  "Sample text file from Simon.";
	static final String UTF8 = "UTF-8";

	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		ContentName name = ContentName.fromNative(base + "/" + file_name);
		RepositoryOutputStream os = new RepositoryOutputStream(name, CCNLibrary.getLibrary());
		
		os.write(txt.getBytes(UTF8));
		os.close();
	}

	@Test
	public void readWrite() throws Exception {
		EnumeratedNameList l = new EnumeratedNameList(ContentName.fromNative(base), null);
		ArrayList<byte []> r = l.getNewData();
		Assert.assertNotNull(r);
		Assert.assertEquals(1, r.size());
		Assert.assertEquals(file_name.getBytes(UTF8), r.get(0));
	}
}
