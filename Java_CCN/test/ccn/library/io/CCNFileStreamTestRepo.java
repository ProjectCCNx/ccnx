/**
 * 
 */
package test.ccn.library.io;


import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Random;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.impl.security.crypto.CCNDigestHelper;
import org.ccnx.ccn.impl.support.DataUtils;
import org.ccnx.ccn.io.CCNFileInputStream;
import org.ccnx.ccn.io.RepositoryFileOutputStream;
import org.ccnx.ccn.protocol.ContentName;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;


/**
 * @author smetters
 *
 */
public class CCNFileStreamTestRepo {
	
	static ContentName baseName;
	static ContentName fileName;
	static Random random = new Random();
	static CCNHandle writeLib;
	static CCNHandle readLib;
	
	static final int BUF_SIZE = 1024;
	
	public static class CountAndDigest {
		int _count;
		byte [] _digest;
		
		public CountAndDigest(int count, byte [] digest) {
			_count = count;
			_digest = digest;
		}
		
		public int count() { return _count; }
		public byte [] digest() { return _digest; }
	}

	/**
	 * @throws java.lang.Exception
	 */
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		writeLib = CCNHandle.open();
		readLib = CCNHandle.open();
		baseName = ContentName.fromNative("/test/CCNFileStreamTestRepo-" + random.nextInt(10000));
	}
	
	@Test
	public void testRepoFileOutputStream() throws Exception {
		
		int fileSize = random.nextInt(50000);
		fileName = ContentName.fromNative(baseName, "testRepoFileOutputStream", "outputFile.bin");
		
		// Write to a repo. Read it back in. See if repo gets the header.
		RepositoryFileOutputStream rfos = new RepositoryFileOutputStream(fileName, writeLib);
		byte [] digest = writeRandomFile(fileSize, rfos);
		System.out.println("Wrote file to repository: " + rfos.getBaseName());
		
		CCNFileInputStream fis = new CCNFileInputStream(fileName, readLib);
		CountAndDigest readDigest = readRandomFile(fis);
		
		System.out.println("Read file from repository: " + fis.getBaseName() + " has header? " + 
				fis.hasHeader());
		Assert.assertTrue(fis.hasHeader());
		System.out.println("Read file size: " + readDigest.count() + " written size: " + fileSize + " header file size " + fis.header().length());
		Assert.assertEquals(readDigest.count(), fileSize);
		Assert.assertEquals(fileSize, fis.header().length());		
		System.out.println("Read digest: " + DataUtils.printBytes(readDigest.digest()) + " wrote digest: " + digest);
		Assert.assertArrayEquals(digest, readDigest.digest());
		
		CCNFileInputStream fis2 = new CCNFileInputStream(rfos.getBaseName(), readLib);
		CountAndDigest readDigest2 = readRandomFile(fis2);
		
		System.out.println("Read file from repository again: " + fis2.getBaseName() + " has header? " + 
				fis2.hasHeader());
		Assert.assertTrue(fis2.hasHeader());
		System.out.println("Read file size: " + readDigest2.count() + " written size: " + fileSize + " header file size " + fis.header().length());
		Assert.assertEquals(readDigest2.count(), fileSize);
		Assert.assertEquals(fileSize, fis2.header().length());		
		System.out.println("Read digest: " + DataUtils.printBytes(readDigest2.digest()) + " wrote digest: " + digest);
		Assert.assertArrayEquals(digest, readDigest2.digest());

	}

	public static byte [] writeRandomFile(int bytes, OutputStream out) throws IOException {
		try {
			DigestOutputStream dos = new DigestOutputStream(out, MessageDigest.getInstance(CCNDigestHelper.DEFAULT_DIGEST_ALGORITHM));
			
			byte [] buf = new byte[BUF_SIZE];
			int count = 0;
			int towrite = 0;
			while (count < bytes) {
				random.nextBytes(buf);
				towrite = ((bytes - count) > buf.length) ? buf.length : (bytes - count);
				dos.write(buf, 0, towrite);
				count += towrite;
			}
			dos.flush();
			dos.close();
			return dos.getMessageDigest().digest();
			
		} catch (NoSuchAlgorithmException e) {
			throw new RuntimeException("Cannot find digest algorithm: " + CCNDigestHelper.DEFAULT_DIGEST_ALGORITHM);
		}
	}
	
	public static CountAndDigest readRandomFile(InputStream in) throws IOException {
		try {
			DigestInputStream dis = new DigestInputStream(in, MessageDigest.getInstance(CCNDigestHelper.DEFAULT_DIGEST_ALGORITHM));
		
			byte [] buf = new byte[BUF_SIZE];
			int count = 0;
			int read = 0;
			while (read >= 0) {
				read = dis.read(buf);
				if (read > 0)
				 count += read;
			}
			return new CountAndDigest(count, dis.getMessageDigest().digest());
			
		} catch (NoSuchAlgorithmException e) {
			throw new RuntimeException("Cannot find digest algorithm: " + CCNDigestHelper.DEFAULT_DIGEST_ALGORITHM);
		}
	}
}
