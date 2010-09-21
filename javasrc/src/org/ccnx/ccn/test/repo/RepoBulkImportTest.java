package org.ccnx.ccn.test.repo;

import org.ccnx.ccn.impl.repo.LogStructRepoStore;
import org.ccnx.ccn.impl.repo.RepositoryStore;
import org.ccnx.ccn.impl.repo.LogStructRepoStore.LogStructRepoStoreProfile;
import org.ccnx.ccn.profiles.CommandMarker;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.junit.Test;

public class RepoBulkImportTest extends RepoTestBase {
	
	private final String Repository3 = "TestRepository3";
	
	@Test
	public void testAddByFile() throws Exception {
		
		// Create some data to add
		System.out.println("testing adding to repo via file in running repo");
		RepositoryStore repolog3 = new LogStructRepoStore();
		repolog3.initialize(_fileTestDir3, null, Repository3, _globalPrefix, null, null);
		ContentName name = ContentName.fromNative("/repoTest/testAddData2");
		ContentObject content = ContentObject.buildContentObject(name, "Testing add by file".getBytes());
		repolog3.saveContent(content);
		repolog3.shutDown();
		
		// Create an Interest
		CommandMarker argMarker = CommandMarker.getMarker(CommandMarker.COMMAND_MARKER_REPO_ADD_FILE.getBytes());
		argMarker.addArgument(_fileTestDir2 + ContentName.SEPARATOR 
				+ LogStructRepoStoreProfile.CONTENT_FILE_PREFIX + "1");
		
	}

}
