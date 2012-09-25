/*
 * A CCNx library test.
 *
 * Copyright (C) 2008-2012 Palo Alto Research Center, Inc.
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

package org.ccnx.ccn.test.repo;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;

import org.ccnx.ccn.io.CCNInputStream;
import org.ccnx.ccn.io.RepositoryFileOutputStream;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Interest;
import org.ccnx.ccn.test.LibraryTestBase;
import org.junit.Assert;
import org.junit.BeforeClass;

/**
 * 
 * A base class for repository-specific tests. 
 * Defines a few common parameters.
 *
 */
public class RepoTestBase extends LibraryTestBase {
	
	public static final String TOP_DIR = "ccn.test.topdir";
	
	protected static String _topdir;
	protected static String _fileTestDir;
	protected static String _fileTestDir2;
	protected static String _fileTestDir3;
	protected static String _repoName = "TestRepository";
	protected static String _globalPrefix = "/parc.com/csl/ccn/repositories/" + _repoName;
	protected static File _fileTest;
	protected static ContentName testprefix = new ContentName("repoTest","pubidtest");
	protected static ContentName keyprefix = new ContentName(testprefix,"keys");
	
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		LibraryTestBase.setUpBeforeClass();
		
		_topdir = System.getProperty(TOP_DIR);
		if (null == _topdir)
			_topdir = "src";
		
		_fileTestDir = System.getProperty("REPO_ROOT");
		if( null == _fileTestDir )
			_fileTestDir = "repotest";
		_fileTestDir2 = _fileTestDir + "2";
		_fileTestDir3 = _fileTestDir + "3";
	}
	
	protected void checkNameSpace(String contentName, boolean expected) throws Exception {
		ContentName name = ContentName.fromNative(contentName);
		ContentName baseName = null;
		try {
			baseName = writeToRepo(name);
		} catch (IOException ex) {
			if (expected)
				Assert.fail(ex.getMessage());
			return;
		}
		if (!expected)
			Assert.fail("Got a repo response on a bad namespace " + contentName);
		Thread.sleep(1000);

		CCNInputStream input = new CCNInputStream(baseName, getHandle);
		byte[] buffer = new byte["Testing 1 2 3".length()];
		if (expected) {
			Assert.assertTrue(-1 != input.read(buffer));
			Assert.assertArrayEquals(buffer, "Testing 1 2 3".getBytes());
		} else {
			Assert.assertEquals(-1, input.read(buffer));
		}
		input.close();
	}
	
	protected ContentName writeToRepo(ContentName name) throws Exception {
		RepositoryFileOutputStream ros = new RepositoryFileOutputStream(name, putHandle);	
		byte [] data = "Testing 1 2 3".getBytes();
		ros.write(data, 0, data.length);
		ContentName baseName = ros.getBaseName();
		ros.close();
		return baseName;
	}
	
	protected void checkData(ContentName name, String data) throws IOException, InterruptedException{
		checkData(new Interest(name), data.getBytes());
	}
	
	protected void checkData(Interest interest, byte[] data) throws IOException, InterruptedException{
		ContentObject testContent = getHandle.get(interest, 10000);
		Assert.assertFalse(testContent == null);
		Assert.assertTrue(Arrays.equals(data, testContent.content()));		
	}
}
