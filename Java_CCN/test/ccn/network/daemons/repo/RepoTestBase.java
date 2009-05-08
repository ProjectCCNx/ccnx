package test.ccn.network.daemons.repo;

import java.io.File;
import java.util.logging.Level;

import org.junit.BeforeClass;

import com.parc.ccn.Library;

import test.ccn.library.LibraryTestBase;

/**
 * 
 * @author rasmusse
 *
 */

public class RepoTestBase extends LibraryTestBase {
	
	public static final String TOP_DIR = "ccn.test.topdir";
	protected static String _topdir;
	protected static String _fileTestDir = "fileTestDir";
	protected static String _repoName = "TestRepository";
	protected static String _globalPrefix = "/parc.com/csl/ccn/repositories";
	protected static File _fileTest;
	
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		// Let default logging level be set centrally so it can be overridden by property
		_topdir = System.getProperty(TOP_DIR);
		if (null == _topdir)
			_topdir = ".";
	}

}
