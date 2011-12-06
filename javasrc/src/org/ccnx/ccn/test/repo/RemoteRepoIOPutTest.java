/*
 * A CCNx library test.
 *
 * Copyright (C) 2008, 2009 Palo Alto Research Center, Inc.
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

import org.ccnx.ccn.impl.repo.RepositoryException;
import org.ccnx.ccn.io.RepositoryOutputStream;
import org.ccnx.ccn.protocol.ContentName;


/**
 * Part of repository test infrastructure.
 */
public class RemoteRepoIOPutTest extends RepoIOTest {
	
	protected boolean checkDataFromFile(File testFile, byte[] data, int block, boolean inMeta) throws RepositoryException {
		return true;
	}
	
	protected void checkNameSpace(String contentName, boolean expected) throws Exception {
		ContentName name = ContentName.fromNative(contentName);
		RepositoryOutputStream ros = new RepositoryOutputStream(name, putHandle); 
		byte [] data = "Testing 1 2 3".getBytes();
		ros.write(data, 0, data.length);
		try {
			ros.close();
		} catch (IOException ex) {}	// File not put causes an I/O exception
	}
}
