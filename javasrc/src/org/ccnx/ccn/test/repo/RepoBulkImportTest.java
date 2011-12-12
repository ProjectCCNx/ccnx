/*
 * A CCNx library test.
 *
 * Copyright (C) 2010, 2011 Palo Alto Research Center, Inc.
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
 
/**
 * PD org.ccnx.ccn.test.repo
 */
package org.ccnx.ccn.test.repo;

import java.io.File;

import junit.framework.Assert;

import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.config.UserConfiguration;
import org.ccnx.ccn.impl.repo.LogStructRepoStore;
import org.ccnx.ccn.impl.repo.RepositoryStore;
import org.ccnx.ccn.impl.repo.LogStructRepoStore.LogStructRepoStoreProfile;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.profiles.repo.RepositoryBulkImport;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.junit.Test;

public class RepoBulkImportTest extends RepoTestBase {
	
	private final String Repository3 = "TestRepository3";
	
	@Test
	public void testBulkImport() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testBulkImport");
		
		// Create some data to add
		System.out.println("testing adding to repo via file in running repo");
		RepositoryStore repolog3 = new LogStructRepoStore();
		repolog3.initialize(_fileTestDir3, null, Repository3, _globalPrefix, null, null);
		ContentName name = ContentName.fromNative("/repoTest/testAddData2");
		ContentObject content = ContentObject.buildContentObject(name, "Testing bulk import".getBytes());
		repolog3.saveContent(content);
		ContentName name2 = ContentName.fromNative("/repoTest/testAddData3");
		ContentObject content2 = ContentObject.buildContentObject(name2, "Testing bulk import #2".getBytes());
		repolog3.saveContent(content2);
		repolog3.shutDown();
		File importDir = new File(_fileTestDir + UserConfiguration.FILE_SEP + LogStructRepoStoreProfile.REPO_IMPORT_DIR);
		importDir.mkdir();	// We don't test this result because the dir may have been already created in a previous test
							// and if so this would return false since the directory would not have "just been created"
		File importFile = new File(_fileTestDir3, LogStructRepoStoreProfile.CONTENT_FILE_PREFIX + "1");
		importFile.renameTo(new File(importDir, "BulkImportTest2"));
		Assert.assertTrue(RepositoryBulkImport.bulkImport(getHandle, "BulkImportTest2", SystemConfiguration.MAX_TIMEOUT));
		checkData(name, "Testing bulk import");
		checkData(name2, "Testing bulk import #2");
		
		Log.info(Log.FAC_TEST, "Completed testBulkImport");
	}

}
