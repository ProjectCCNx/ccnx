package test.ccn.network.daemons.repo;

import java.io.File;
import java.io.IOException;

import com.parc.ccn.data.ContentName;
import com.parc.ccn.library.io.repo.RepositoryOutputStream;
import com.parc.ccn.network.daemons.repo.RepositoryException;

/**
 * 
 * @author rasmusse
 * 
 */

public class RemoteRepoIOPutTest extends RepoIOTest {
	
	protected boolean checkDataFromFile(File testFile, byte[] data, int block, boolean inMeta) throws RepositoryException {
		return true;
	}
	
	protected void checkNameSpace(String contentName, boolean expected) throws Exception {
		ContentName name = ContentName.fromNative(contentName);
		RepositoryOutputStream ros = putLibrary.repoOpen(name, null, putLibrary.getDefaultPublisher());
		byte [] data = "Testing 1 2 3".getBytes();
		ros.write(data, 0, data.length);
		try {
			ros.close();
		} catch (IOException ex) {}	// File not put causes an I/O exception
	}
}
