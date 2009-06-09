package test.ccn.library.io;


import java.security.MessageDigest;
import java.util.Random;

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import test.ccn.data.util.Flosser;

import com.parc.ccn.data.ContentName;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.library.io.CCNOutputStream;
import com.parc.ccn.library.io.CCNVersionedInputStream;
import com.parc.ccn.library.io.CCNVersionedOutputStream;
import com.parc.ccn.library.io.repo.RepositoryVersionedOutputStream;

public class FastFlossTestSlow {
	
	public static final int BUF_SIZE = 1024;
	public static final int FILE_SIZE = 1024*1024; // bytes
	public static Random random = new Random();
	public static CCNLibrary readLibrary;
	public static CCNLibrary writeLibrary;

	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		readLibrary = CCNLibrary.open();
		writeLibrary = CCNLibrary.open();
	}
	
	@Test
	public void fastFlossTest() {
		Flosser flosser = null;
		try {
			ContentName ns = ContentName.fromNative("/test/content/media/bigMediaTests/FlosserFile");			
			flosser = new Flosser(ns);
			CCNVersionedOutputStream vos = new CCNVersionedOutputStream(ns, writeLibrary);
			streamData(vos);
		} catch (Exception e) {
			System.out.println("Exception in test: " + e);
			e.printStackTrace();
			Assert.fail();
		} finally {
			flosser.stop();
		}
	}
	
	@Test
	public void fastRepoTest() {
		try {
			ContentName ns = ContentName.fromNative("/test/content/media/bigMediaTests/RepoFile");			
			RepositoryVersionedOutputStream vos = new RepositoryVersionedOutputStream(ns, writeLibrary);
			streamData(vos);
		} catch (Exception e) {
			System.out.println("Exception in test: " + e);
			e.printStackTrace();
			Assert.fail();
		} finally {
		}
	}

	public void streamData(CCNOutputStream outputStream) throws Exception {
		System.out.println("Streaming data to file " + outputStream.getBaseName() + 
					" using stream class: " + outputStream.getClass().getName());
		long elapsed = 0;
		byte [] buf = new byte[BUF_SIZE];
		MessageDigest digest = MessageDigest.getInstance("SHA-1");
		while (elapsed < FILE_SIZE) {
			random.nextBytes(buf);
			outputStream.write(buf);
			digest.update(buf);
			elapsed += BUF_SIZE;
		}
		outputStream.close();
		
		byte [] writeDigest = digest.digest(); // resets digest
		
		elapsed = 0;
		int read = 0;
		byte [] read_buf = new byte[BUF_SIZE]; // different increments might be useful for testing
		CCNVersionedInputStream vis = new CCNVersionedInputStream(outputStream.getBaseName(), readLibrary);
		while (elapsed < FILE_SIZE) {
			read = vis.read(read_buf);
			digest.update(read_buf, 0, read);
			elapsed += read;
		}
		
		byte [] readDigest = digest.digest();
		
		Assert.assertArrayEquals(writeDigest, readDigest);

	}

}
