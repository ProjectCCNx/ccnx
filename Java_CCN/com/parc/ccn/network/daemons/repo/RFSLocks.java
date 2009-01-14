package com.parc.ccn.network.daemons.repo;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.TreeMap;

/**
 * Implement a simple locking system to guarantee file consistency
 * in the file system repository while writing. Algorithm is - we 
 * write a lock file containing the name of the file to be written.
 * After it is written, we delete it. When we reinitialize the repo, 
 * we remove any file  with a name in a lockfile.
 * 
 * @author rasmusse
 *
 */
public class RFSLocks {
	private static final String LOCK_DIR = "locks";
	private static final String LOCK_SUFFIX = ".lock";
	private String _lockDir;
	private TreeMap<String, File> map = new TreeMap<String, File>();
	private Integer lockNo = 1;

	public RFSLocks(String repoDir) throws RepositoryException {
		_lockDir = repoDir + File.separator + LOCK_DIR;
		File locksFile = new File(_lockDir);
		locksFile.mkdirs();
		restore(locksFile);
	}
	
	public void lock(String fileName) throws RepositoryException {
		try {
			File lockFile = new File(_lockDir + File.separator + lockNo.toString() + LOCK_SUFFIX);
			lockFile.createNewFile();
			lockNo++;
			FileOutputStream fos = new FileOutputStream(lockFile);
			fos.write(fileName.getBytes());
			fos.close();
			map.put(fileName, lockFile);
		} catch (IOException e) {
			throw new RepositoryException(e.getMessage());
		}
	}
	
	public void unLock(String fileName) {
		File lockFile = map.get(fileName);
		if (lockFile != null) {
			lockFile.delete();
			map.remove(fileName);
		}
	}
	
	private void restore(File lockDir) throws RepositoryException {
		for (File lockFile : lockDir.listFiles()) {
			try {
				FileInputStream fis = new FileInputStream(lockFile);
				byte [] buf = new byte[fis.available()];
				fis.read(buf);
				fis.close();
				File targetFile = new File(new String(buf));
				targetFile.delete();
				lockFile.delete();
			} catch (Exception e) {
				throw new RepositoryException(e.getMessage());
			}
		}
	}
}
