package org.ccnx.ccn.test.profiles.nameenum;


import java.util.SortedSet;

import junit.framework.Assert;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.io.RepositoryOutputStream;
import org.ccnx.ccn.profiles.nameenum.EnumeratedNameList;
import org.ccnx.ccn.protocol.ContentName;
import org.junit.BeforeClass;
import org.junit.Test;


public class SampleTestRepo {
	static final String base = "/parc.com/csl/ccn/repositories/SampleTestRepo";
	static final String file_name = "/simon.txt";
	static final String txt =  "Sample text file from Simon.";
	static final String UTF8 = "UTF-8";

	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		ContentName name = ContentName.fromNative(base + file_name);
		RepositoryOutputStream os = new RepositoryOutputStream(name, CCNHandle.getLibrary());
		
		os.write(txt.getBytes(UTF8));
		os.close();
	}

	@Test
	public void readWrite() throws Exception {
		EnumeratedNameList l = new EnumeratedNameList(ContentName.fromNative(base), null);
		SortedSet<ContentName> r = l.getNewData();
		Assert.assertNotNull(r);
		Assert.assertEquals(1, r.size());
		ContentName expected = ContentName.fromNative(file_name);
		Assert.assertEquals(expected, r.first());
	}
}
