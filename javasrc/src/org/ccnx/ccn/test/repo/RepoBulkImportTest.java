package org.ccnx.ccn.test.repo;

import junit.framework.Assert;

import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.impl.repo.LogStructRepoStore;
import org.ccnx.ccn.impl.repo.RepositoryStore;
import org.ccnx.ccn.impl.repo.LogStructRepoStore.LogStructRepoStoreProfile;
import org.ccnx.ccn.profiles.repo.RepositoryBulkImport;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.junit.Test;

public class RepoBulkImportTest extends RepoTestBase {
	
	private final String Repository3 = "TestRepository3";
	
	@Test
	public void testBulkImport() throws Exception {
		
		// Create some data to add
		System.out.println("testing adding to repo via file in running repo");
		RepositoryStore repolog3 = new LogStructRepoStore();
		repolog3.initialize(_fileTestDir3, null, Repository3, _globalPrefix, null, null);
		ContentName name = ContentName.fromNative("/repoTest/testAddData2");
		ContentObject content = ContentObject.buildContentObject(name, "Testing add by file".getBytes());
		repolog3.saveContent(content);
		repolog3.shutDown();
		
		Assert.assertTrue(RepositoryBulkImport.bulkImport(getHandle, 
				_fileTestDir3 + ContentName.SEPARATOR + LogStructRepoStoreProfile.CONTENT_FILE_PREFIX + "1", 
				SystemConfiguration.MAX_TIMEOUT));
		checkData(name, "Testing add by file");
		
	}

}
