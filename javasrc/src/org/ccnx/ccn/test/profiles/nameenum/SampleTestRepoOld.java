/*
 * A CCNx library test.
 *
 * Copyright (C) 2008, 2009, 2011 Palo Alto Research Center, Inc.
 *
 * This work is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License version 2 as published by the
 * Free Software Foundation. 
 * This work is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License
 * for more details. You should have received a copy of the GNU General Public
 * License along with this program; if not, write to the
 * Free Software Foundation, Inc., 51 Franklin Street, Fifth Floor,
 * Boston, MA 02110-1301, USA.
 */

package org.ccnx.ccn.test.profiles.nameenum;


import java.util.SortedSet;

import junit.framework.Assert;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.impl.support.DataUtils;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.RepositoryOutputStream;
import org.ccnx.ccn.profiles.nameenum.EnumeratedNameList;
import org.ccnx.ccn.protocol.ContentName;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Used in some early name enumeration tests. Currently unused.
 *
 */
public class SampleTestRepoOld {
	static final String base = "/parc.com/csl/ccn/repositories/SampleTestRepo";
	static final String file_name = "/simon.txt";
	static final String txt =  "Sample text file from Simon.";

	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		ContentName name = ContentName.fromNative(base + file_name);
		RepositoryOutputStream os = new RepositoryOutputStream(name, CCNHandle.getHandle());
		
		os.write(DataUtils.getBytesFromUTF8String(txt));
		os.close();
	}

	@Test
	public void readWrite() throws Exception {
		Log.info(Log.FAC_TEST, "Starting readWrite");

		EnumeratedNameList l = new EnumeratedNameList(ContentName.fromNative(base), null);
		SortedSet<ContentName> r = l.getNewData();
		Assert.assertNotNull(r);
		Assert.assertEquals(1, r.size());
		ContentName expected = ContentName.fromNative(file_name);
		Assert.assertEquals(expected, r.first());
		
		Log.info(Log.FAC_TEST, "Completed readWrite");
	}
}
