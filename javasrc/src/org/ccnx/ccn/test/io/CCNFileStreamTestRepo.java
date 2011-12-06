/*
 * A CCNx library test.
 *
 * Copyright (C) 2008, 2009, 2011 Palo Alto Research Center, Inc.
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

package org.ccnx.ccn.test.io;


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
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.CCNFileInputStream;
import org.ccnx.ccn.io.RepositoryFileOutputStream;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.test.CCNTestHelper;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;


/**
 * Test class for CCNFileStream; tests writing file streams to a repository.
 */
public class CCNFileStreamTestRepo {
	
	static CCNHandle writeLib;
	static CCNHandle readLib;
	static Random random = new Random();
	
	/**
	 * Handle naming for the test
	 */
	static CCNTestHelper testHelper = new CCNTestHelper(CCNFileStreamTestRepo.class);
	
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
	}
	
	@AfterClass
	public static void cleanUpAfterClass() throws Exception {
		writeLib.close();
		readLib.close();
	}
	
	@Test
	public void testRepoFileOutputStream() throws Exception {
		
		int fileSize = random.nextInt(50000);
		ContentName fileName = ContentName.fromNative(testHelper.getTestNamespace("testRepoFileOutputStream"), "outputFile.bin");
		
		// Write to a repo. Read it back in. See if repo gets the header.
		RepositoryFileOutputStream rfos = new RepositoryFileOutputStream(fileName, writeLib);
		byte [] digest = writeRandomFile(fileSize, rfos);
		Log.info("Wrote file to repository: " + rfos.getBaseName());
		
		CCNFileInputStream fis = new CCNFileInputStream(fileName, readLib);
		CountAndDigest readDigest = readRandomFile(fis);
		
		Log.info("Read file from repository: " + fis.getBaseName() + " has header? " + 
				fis.hasHeader());
		if (!fis.hasHeader()) {
			Log.info("No header yet, waiting..");
			fis.waitForHeader();
		}
		Assert.assertTrue(fis.hasHeader());
		Log.info("Read file size: " + readDigest.count() + " written size: " + fileSize + " header file size " + fis.header().length());
		Assert.assertEquals(readDigest.count(), fileSize);
		Assert.assertEquals(fileSize, fis.header().length());		
		Log.info("Read digest: " + DataUtils.printBytes(readDigest.digest()) + " wrote digest: " + digest);
		Assert.assertArrayEquals(digest, readDigest.digest());
		
		CCNFileInputStream fis2 = new CCNFileInputStream(rfos.getBaseName(), readLib);
		CountAndDigest readDigest2 = readRandomFile(fis2);
		
		Log.info("Read file from repository again: " + fis2.getBaseName() + " has header? " + 
				fis2.hasHeader());
		if (!fis2.hasHeader()) {
			Log.info("No header yet, waiting..");
			fis2.waitForHeader();
		}
		Assert.assertTrue(fis2.hasHeader());
		Log.info("Read file size: " + readDigest2.count() + " written size: " + fileSize + " header file size " + fis.header().length());
		Assert.assertEquals(readDigest2.count(), fileSize);
		Assert.assertEquals(fileSize, fis2.header().length());		
		Log.info("Read digest: " + DataUtils.printBytes(readDigest2.digest()) + " wrote digest: " + digest);
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
