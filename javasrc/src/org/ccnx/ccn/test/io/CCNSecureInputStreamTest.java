/*
 * A CCNx library test.
 *
 * Copyright (C) 2008-2012 Palo Alto Research Center, Inc.
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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.NoSuchAlgorithmException;
import java.util.Random;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.impl.security.crypto.ContentKeys;
import org.ccnx.ccn.impl.security.crypto.StaticContentKeys;
import org.ccnx.ccn.impl.security.crypto.UnbufferedCipherInputStream;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.CCNFileInputStream;
import org.ccnx.ccn.io.CCNFileOutputStream;
import org.ccnx.ccn.io.CCNInputStream;
import org.ccnx.ccn.io.CCNOutputStream;
import org.ccnx.ccn.io.CCNVersionedInputStream;
import org.ccnx.ccn.io.CCNVersionedOutputStream;
import org.ccnx.ccn.io.content.ContentEncodingException;
import org.ccnx.ccn.profiles.SegmentationProfile;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;
import org.ccnx.ccn.protocol.SignedInfo;
import org.ccnx.ccn.protocol.SignedInfo.ContentType;
import org.ccnx.ccn.test.CCNTestHelper;
import org.ccnx.ccn.test.Flosser;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;


/**
 * Test for stream encryption/decryption.
 */
public class CCNSecureInputStreamTest {

	static protected abstract class StreamFactory {
		ContentName name;
		ContentKeys keys;
		int encrLength;
		byte [] encrData;

		public StreamFactory(String file_name, int length) throws NoSuchAlgorithmException, IOException, InterruptedException {
			name = new ContentName(testHelper.getClassNamespace(), file_name);
			encrLength = length;
			flosser.handleNamespace(name);
			try {
				keys = StaticContentKeys.generateRandomKeys();
			} catch (NoSuchPaddingException e) {
				Log.severe(Log.FAC_TEST, "NoSuchPaddingExcption creating algorithm we have used before! {0}", e.getMessage());
				return;
			}
			writeFile(encrLength);
			flosser.stopMonitoringNamespace(name);
		}

		public abstract CCNInputStream makeInputStream() throws IOException;
		public abstract OutputStream makeOutputStream() throws IOException;

		public void writeFile(int fileLength) throws IOException, NoSuchAlgorithmException, InterruptedException {
			Random randBytes = new Random(0); // always same sequence, to aid debugging
			OutputStream os = makeOutputStream();

			ByteArrayOutputStream data = new ByteArrayOutputStream();

			byte [] bytes = new byte[BUF_SIZE];
			int elapsed = 0;
			int nextBufSize = 0;
			final double probFlush = .3;

			while (elapsed < fileLength) {
				nextBufSize = ((fileLength - elapsed) > BUF_SIZE) ? BUF_SIZE : (fileLength - elapsed);
				randBytes.nextBytes(bytes);
				os.write(bytes, 0, nextBufSize);
				data.write(bytes, 0, nextBufSize);
				elapsed += nextBufSize;
				if (randBytes.nextDouble() < probFlush) {
					Log.info(Log.FAC_TEST, "Flushing buffers.");
					os.flush();
				}
			}
			os.close();
			encrData = data.toByteArray();
		}

		public void streamEncryptDecrypt() throws IOException {
			// check we get identical data back out
			CCNInputStream vfirst = makeInputStream();
			byte [] read_data = readFile(vfirst, encrLength);
			Assert.assertArrayEquals(encrData, read_data);

			// check things fail if we use different keys
			ContentKeys keys2 = keys;
			CCNInputStream v2 = null;
			try {
				keys = StaticContentKeys.generateRandomKeys();
				v2 = makeInputStream();
			} catch (NoSuchAlgorithmException e) {
				Log.severe(Log.FAC_TEST, "Unexpected NoSuchAlgorithmException using default algorithm! " + keys.getBaseAlgorithm());
				Assert.fail("Unexpected NoSuchAlgorithmException using default algorithm! " + keys.getBaseAlgorithm());
			} catch (NoSuchPaddingException e) {
				Log.severe(Log.FAC_TEST, "Unexpected NoSuchPaddingException using default algorithm! " + keys.getBaseAlgorithm());
				Assert.fail("Unexpected NoSuchPaddingException using default algorithm! " + keys.getBaseAlgorithm());
			} finally {
				keys = keys2;
			}
			read_data = readFile(v2, encrLength);
			Assert.assertFalse(encrData.equals(read_data));
		}
		public void seekZero() throws IOException, NoSuchAlgorithmException {
			CCNInputStream i = makeInputStream();
			i.seek(0);
		}

		public void seeking() throws IOException, NoSuchAlgorithmException {
			// check really small seeks/reads (smaller than 1 Cipher block)
			doSeeking(10);

			// check small seeks (but bigger than 1 Cipher block)
			doSeeking(600);

			// check large seeks (multiple ContentObjects)
			doSeeking(4096*5+350);
		}

		private void doSeeking(int length) throws IOException, NoSuchAlgorithmException {
			CCNInputStream i = makeInputStream();
			// make sure we start mid ContentObject and past the first Cipher block
			int start = ((int) (encrLength*0.3) % 4096) +600;
			i.seek(start);
			readAndCheck(i, start, length);
			i.seek(start);
			readAndCheck(i, start, length);
		}

		public void markReset() throws IOException, NoSuchAlgorithmException {
			// check really small seeks/reads (smaller than 1 Cipher block)
			doMarkReset(10);

			// check small seeks (but bigger than 1 Cipher block)
			doMarkReset(600);

			// check large seeks (multiple ContentObjects)
			doMarkReset(4096*2+350);
		}

		private void doMarkReset(int length) throws IOException, NoSuchAlgorithmException {
			CCNInputStream i = makeInputStream();
			i.skip(length);
			i.reset();
			readAndCheck(i, 0, length);
			i.skip(1024);
			i.mark(length);
			readAndCheck(i, length+1024, length);
			i.reset();
			readAndCheck(i, length+1024, length);
		}

		private void readAndCheck(CCNInputStream i, int start, int length)
				throws IOException, NoSuchAlgorithmException {
			byte [] origData = new byte[length];
			System.arraycopy(encrData, start, origData, 0, length);
			byte [] readData = new byte[length];
			i.read(readData);
			Assert.assertArrayEquals(origData, readData);
		}

		public void skipping() throws IOException, NoSuchAlgorithmException {
			// read some data, skip some data, read some more data
			CCNInputStream inStream = makeInputStream();

			int start = (int) (encrLength*0.3);

			// check first part reads correctly
			readAndCheck(inStream, 0, start);

			// skip a short bit (less than 1 cipher block)
			inStream.skip(10);
			start += 10;

			// check second part reads correctly
			readAndCheck(inStream, start, 100);
			start += 100;

			// skip a medium bit (more than than 1 cipher block)
			inStream.skip(600);
			start += 600;

			// check third part reads correctly
			readAndCheck(inStream, start, 600);
			start += 600;

			// skip a bug bit (more than than 1 Content object)
			inStream.skip(600+4096*2);
			start += 600+4096*2;

			// check fourth part reads correctly
			readAndCheck(inStream, start, 600);
		}
	}

	/**
	 * Handle naming for the test
	 */
	static CCNTestHelper testHelper = new CCNTestHelper(CCNSecureInputStreamTest.class);

	static CCNHandle outputLibrary;
	static CCNHandle inputLibrary;
	static Flosser flosser;
	static final int BUF_SIZE = 4096;

	static StreamFactory basic;
	static StreamFactory versioned;
	static StreamFactory file;
	static StreamFactory emptyFile;

	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		outputLibrary = CCNHandle.open();
		inputLibrary = CCNHandle.open();

		flosser = new Flosser();

		basic = new StreamFactory("basic.txt", 25*1024+301){
			public CCNInputStream makeInputStream() throws IOException {
				return new CCNInputStream(name, null, null, keys, inputLibrary);
			}
			public OutputStream makeOutputStream() throws IOException {
				return new CCNOutputStream(name, null, null, null, keys, outputLibrary);
			}
		};

		versioned = new StreamFactory("versioned.txt", 25*1024+302){
			public CCNInputStream makeInputStream() throws IOException {
				return new CCNVersionedInputStream(name, 0L, null, keys, inputLibrary);
			}
			public OutputStream makeOutputStream() throws IOException {
				return new CCNVersionedOutputStream(name, null, null, keys, outputLibrary);
			}
		};

		file = new StreamFactory("file.txt",  25*1024+303){
			public CCNInputStream makeInputStream() throws IOException {
				return new CCNFileInputStream(name, null, null, keys, inputLibrary);
			}
			public OutputStream makeOutputStream() throws IOException {
				return new CCNFileOutputStream(name, keys, outputLibrary);
			}
		};

		emptyFile = new StreamFactory("emptyFile.txt",  0){
			public CCNInputStream makeInputStream() throws IOException {
				return new CCNFileInputStream(name, null, null, keys, inputLibrary);
			}
			public OutputStream makeOutputStream() throws IOException {
				return new CCNFileOutputStream(name, keys, outputLibrary);
			}
		};
		flosser.stop();
	}

	@AfterClass
	public static void cleanupAfterClass() {
		outputLibrary.close();
		inputLibrary.close();
	}

	public static byte [] readFile(InputStream inputStream, int fileLength) throws IOException {
		ByteArrayOutputStream bos = null;
		bos = new ByteArrayOutputStream();
		int elapsed = 0;
		int read = 0;
		byte [] bytes = new byte[BUF_SIZE];
		while (elapsed < fileLength) {
			read = inputStream.read(bytes);
			bos.write(bytes, 0, read);
			if (read < 0) {
				break;
			} else if (read == 0) {
				try {
					Thread.sleep(10);
				} catch (InterruptedException e) {

				}
			}
			elapsed += read;
		}
		return bos.toByteArray();
	}

	/**
	 * Test cipher encryption & decryption work
	 * @throws ContentEncodingException 
	 */
	@Test
	public void cipherEncryptDecrypt() throws InvalidKeyException, InvalidAlgorithmParameterException, IllegalBlockSizeException, BadPaddingException, ContentEncodingException {
		Log.info(Log.FAC_TEST, "Starting cipherEncryptDecrypt");

		Cipher c = basic.keys.getSegmentEncryptionCipher(basic.name, outputLibrary.getDefaultPublisher(), 0);
		byte [] d = c.doFinal(basic.encrData);
		c = basic.keys.getSegmentDecryptionCipher(basic.name, outputLibrary.getDefaultPublisher(), 0);
		d = c.doFinal(d);
		// check we get identical data back out
		Assert.assertArrayEquals(basic.encrData, d);

		Log.info(Log.FAC_TEST, "Completed cipherEncryptDecrypt");
	}

	/**
	 * Test cipher stream encryption & decryption work
	 * @throws IOException
	 */
	@Test
	public void cipherStreamEncryptDecrypt() throws InvalidKeyException, InvalidAlgorithmParameterException, IllegalBlockSizeException, BadPaddingException, IOException {
		Log.info(Log.FAC_TEST, "Starting cipherStreamEncryptDecrypt");

		Cipher c = basic.keys.getSegmentEncryptionCipher(basic.name, outputLibrary.getDefaultPublisher(),0);
		InputStream is = new ByteArrayInputStream(basic.encrData, 0, basic.encrData.length);
		is = new UnbufferedCipherInputStream(is, c);
		byte [] cipherText = new byte[4096];
		int total, res;
		for(total = 0, res = 0; res >= 0 && total < 4096; total+=(res > 0) ? res : 0)
			res = is.read(cipherText,total,4096-total);

		c = basic.keys.getSegmentDecryptionCipher(basic.name, outputLibrary.getDefaultPublisher(), 0);
		is = new ByteArrayInputStream(cipherText, 0, total);
		is = new UnbufferedCipherInputStream(is, c);
		byte [] buf = new byte[4096];
		for(total = 0, res = 0; res >= 0 && total < 4096; total+=(res > 0) ? res : 0)
			res = is.read(buf,total,4096-total);
		// check we get identical data back out
		byte [] input = new byte[Math.min(4096, basic.encrLength)];
		byte [] output = new byte[Math.min(4096, total)];
		System.arraycopy(basic.encrData, 0, input, 0, input.length);
		System.arraycopy(buf, 0, output, 0, output.length);
		Assert.assertArrayEquals(input, output);

		Log.info(Log.FAC_TEST, "Completed cipherStreamEncryptDecrypt");
	}

	/**
	 * Test content encryption & decryption work
	 * @throws IOException
	 */
	@Test
	public void contentEncryptDecrypt() throws InvalidKeyException, InvalidAlgorithmParameterException, IllegalBlockSizeException, BadPaddingException, IOException {
		Log.info(Log.FAC_TEST, "Starting contentEncryptDecrypt");

		// create an encrypted content block
		PublisherPublicKeyDigest publisher = outputLibrary.getDefaultPublisher();
		Cipher c = basic.keys.getSegmentEncryptionCipher(basic.name, publisher, 0);
		InputStream is = new ByteArrayInputStream(basic.encrData, 0, basic.encrData.length);
		is = new UnbufferedCipherInputStream(is, c);
		ContentName rootName = SegmentationProfile.segmentRoot(basic.name);
		Key signingKey = outputLibrary.keyManager().getSigningKey(publisher);
		byte [] finalBlockID = SegmentationProfile.getSegmentNumberNameComponent(1);
		int coLength = Math.min(4096, basic.encrData.length);
		ContentObject co = new ContentObject(SegmentationProfile.segmentName(rootName, 0),
				new SignedInfo(publisher, null, ContentType.ENCR, outputLibrary.keyManager().getKeyLocator(signingKey), new Integer(300), finalBlockID),
				is, coLength);

		// attempt to decrypt the data
		c = basic.keys.getSegmentDecryptionCipher(basic.name, publisher, 0);
		is = new UnbufferedCipherInputStream(new ByteArrayInputStream(co.content()), c);
		byte [] output = new byte[co.contentLength()];
		for(int total = 0, res = 0; res >= 0 && total < output.length; total+=res)
			res = is.read(output, total, output.length-total);
		// check we get identical data back out
		byte [] input = new byte[coLength];
		System.arraycopy(basic.encrData, 0, input, 0, input.length);
		Assert.assertArrayEquals(input, output);

		Log.info(Log.FAC_TEST, "Completed contentEncryptDecrypt");
	}

	/**
	 * Test stream encryption & decryption work, and that using different keys for decryption fails
	 */
	@Test
	public void basicStreamEncryptDecrypt() throws IOException {
		Log.info(Log.FAC_TEST, "Starting streamEncryptDecrypt");
		basic.streamEncryptDecrypt();
		Log.info(Log.FAC_TEST, "Completed streamEncryptDecrypt");
	}
	@Test
	public void versionedStreamEncryptDecrypt() throws IOException {
		Log.info(Log.FAC_TEST, "Starting versionedStreamEncryptDecrypt");
		versioned.streamEncryptDecrypt();
		Log.info(Log.FAC_TEST, "Completed versionedStreamEncryptDecrypt");
	}
	@Test
	public void fileStreamEncryptDecrypt() throws IOException {
		Log.info(Log.FAC_TEST, "Starting fileStreamEncryptDecrypt");
		file.streamEncryptDecrypt();
		Log.info(Log.FAC_TEST, "Completed fileStreamEncryptDecrypt");
	}

	@Test
	public void emptyFileStreamEncryptDecrypt() throws IOException {
		Log.info(Log.FAC_TEST, "Starting emptyFileStreamEncryptDecrypt");
		emptyFile.streamEncryptDecrypt();
		Log.info(Log.FAC_TEST, "Completed emptyFileStreamEncryptDecrypt");
	}

	/**
	 * seek forward, read, seek back, read and check the results
	 * do it for different size parts of the data
	 */
	@Test
	public void basicSeekZero() throws IOException, NoSuchAlgorithmException {
		Log.info(Log.FAC_TEST, "Starting basicSeekZero");
		basic.seekZero();
		Log.info(Log.FAC_TEST, "Completed basicSeekZero");
	}

	@Test
	public void versionedSeekZero() throws IOException, NoSuchAlgorithmException {
		Log.info(Log.FAC_TEST, "Starting versionedSeekZero");
		versioned.seekZero();
		Log.info(Log.FAC_TEST, "Completed versionedSeekZero");
	}

	@Test
	public void fileSeekZero() throws IOException, NoSuchAlgorithmException {
		Log.info(Log.FAC_TEST, "Starting fileSeekZero");
		file.seekZero();
		Log.info(Log.FAC_TEST, "Completed fileSeekZero");
	}

	@Test
	public void emptyFileSeekZero() throws IOException, NoSuchAlgorithmException {
		Log.info(Log.FAC_TEST, "Starting emptyFileSeekZero");
		emptyFile.seekZero();
		Log.info(Log.FAC_TEST, "Completed emptyFileSeekZero");
	}

	@Test
	public void basicSeeking() throws IOException, NoSuchAlgorithmException {
		Log.info(Log.FAC_TEST, "Starting basicSeeking");
		basic.seeking();
		Log.info(Log.FAC_TEST, "Completed basicSeeking");
	}
	@Test
	public void versionedSeeking() throws IOException, NoSuchAlgorithmException {
		Log.info(Log.FAC_TEST, "Starting versionedSeeking");
		versioned.seeking();
		Log.info(Log.FAC_TEST, "Completed versionedSeeking");
	}
	@Test
	public void fileSeeking() throws IOException, NoSuchAlgorithmException {
		Log.info(Log.FAC_TEST, "Starting fileSeeking");
		file.seeking();
		Log.info(Log.FAC_TEST, "Completed fileSeeking");
	}

	/**
	 * Test that skipping while reading an encrypted stream works
	 * Tries small/medium/large skips
	 */
	@Test
	public void basicSkipping() throws IOException, NoSuchAlgorithmException {
		Log.info(Log.FAC_TEST, "Starting basicSkipping");
		basic.skipping();
		Log.info(Log.FAC_TEST, "Completed basicSkipping");
	}
	@Test
	public void versionedSkipping() throws IOException, NoSuchAlgorithmException {
		Log.info(Log.FAC_TEST, "Starting versionedSkipping");
		versioned.skipping();
		Log.info(Log.FAC_TEST, "Completed versionedSkipping");
	}
	@Test
	public void fileSkipping() throws IOException, NoSuchAlgorithmException {
		Log.info(Log.FAC_TEST, "Starting fileSkipping");
		file.skipping();
		Log.info(Log.FAC_TEST, "Completed fileSkipping");
	}

	/**
	 * Test that mark and reset on an encrypted stream works
	 * Tries small/medium/large jumps
	 */
	@Test
	public void basicMarkReset() throws IOException, NoSuchAlgorithmException {
		Log.info(Log.FAC_TEST, "Starting basicMarkReset");
		basic.markReset();
		Log.info(Log.FAC_TEST, "Completed basicMarkReset");
	}
	@Test
	public void versionedMarkReset() throws IOException, NoSuchAlgorithmException {
		Log.info(Log.FAC_TEST, "Starting versionedMarkReset");
		versioned.markReset();
		Log.info(Log.FAC_TEST, "Completed versionedMarkReset");
	}
	@Test
	public void fileMarkReset() throws IOException, NoSuchAlgorithmException {
		Log.info(Log.FAC_TEST, "Starting versionedMarkReset");
		file.markReset();
		Log.info(Log.FAC_TEST, "Completed versionedMarkReset");
	}
}
