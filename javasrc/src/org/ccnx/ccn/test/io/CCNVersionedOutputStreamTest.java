/*
 * A CCNx library test.
 *
 * Copyright (C) 2010, 2011, 2013 Palo Alto Research Center, Inc.
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
import java.util.logging.Level;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.CCNInterestHandler;
import org.ccnx.ccn.impl.support.DataUtils;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.CCNAbstractOutputStream;
import org.ccnx.ccn.io.CCNVersionedInputStream;
import org.ccnx.ccn.io.CCNVersionedOutputStream;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.Interest;
import org.ccnx.ccn.test.CCNTestHelper;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class CCNVersionedOutputStreamTest implements CCNInterestHandler {
	
	static CCNTestHelper testHelper = new CCNTestHelper(CCNVersionedOutputStreamTest.class);
	static CCNHandle readHandle;
	static CCNHandle writeHandle;
	static byte [] writeDigest;
	static int BUF_SIZE = 2048;
	static int FILE_SIZE = 65556;
	static Writer writer;
	
	public static class Writer extends Thread {
		protected static Random random = new Random();
		
		protected CCNAbstractOutputStream _stream;
		protected int _fileLength;
		protected boolean _done = false;
		
		public Writer(CCNAbstractOutputStream stream, int fileLength) {
			_stream = stream;
			_fileLength = fileLength;
		}

		public synchronized boolean isDone() { return _done; }

		/**
		 * @return The digest of the first segment of this stream
		 */
		public byte[] getFirstDigest() {
			return _stream.getFirstDigest();
		}
		
		/**
		 * @return The index of the first segment of stream data.
		 */
		public Long firstSegmentNumber() {
			return _stream.firstSegmentNumber();
		}

		@Override
		public void run() {
			try {
				synchronized (this) {
					writeDigest = writeRandomFile(_stream, _fileLength, random);
					Log.info(Log.FAC_TEST, "Finished writing file of {0} bytes, digest {1}.", _fileLength, DataUtils.printHexBytes(writeDigest));
					_done = true;
					this.notifyAll();
				}
			} catch (IOException e) {
				Log.severe(Log.FAC_TEST, "Exception writing random file: " + e.getClass().getName() + ": " + e.getMessage());
				Log.logStackTrace(Log.FAC_TEST, Level.SEVERE, e);
				Assert.fail("Exception in writeRandomFile: " + e);
			}
		}
	}
	
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		try {
			readHandle = CCNHandle.open();
			writeHandle = CCNHandle.open();
		} catch (Exception e) {
			Log.severe("Exception in setUpBeforeClass: {0}: {1}", e.getClass().getName(), e);
			throw e;
		}
	}
	
	@AfterClass
	public static void cleanupAfterClass() {
		readHandle.close();
		writeHandle.close();
	}

	@Test
	public void testAddOutstandingInterest() throws Exception {
		
		// Let's express an Interest in some data, and see if the network managers can
		// handle the threading for us...
		ContentName streamName = new ContentName(testHelper.getTestNamespace("testAddOutstandingInterest"), "testFile.bin");
	
		writeHandle.registerFilter(streamName, this);
		// Get the latest version when no versions exist. 
		CCNVersionedInputStream vis = new CCNVersionedInputStream(streamName, readHandle);
		byte [] resultDigest = readFile(vis);
		Log.info("Finished reading, read result {0}", DataUtils.printHexBytes(resultDigest));
		synchronized (this) {
			Assert.assertNotNull(writer);
			if (!writer.isDone()) {
				synchronized(writer) {
					while (!writer.isDone()) {
						writer.wait(500);
					}
				}
			}
		}
		Log.info("Finished writing, read result {0}, write result {1}", DataUtils.printHexBytes(resultDigest), DataUtils.printHexBytes(writeDigest));
		Assert.assertArrayEquals(resultDigest, writeDigest);
		Assert.assertArrayEquals(writer.getFirstDigest(), vis.getFirstDigest());
		Assert.assertEquals(writer.firstSegmentNumber(), (Long)vis.firstSegmentNumber());
	}
	
	public static byte [] readFile(InputStream inputStream) throws IOException {
		
		DigestInputStream dis = null;
		try {
			dis = new DigestInputStream(inputStream, MessageDigest.getInstance("SHA1"));
		} catch (NoSuchAlgorithmException e) {
			Log.severe("No SHA1 available!");
			Assert.fail("No SHA1 available!");
		}
		int elapsed = 0;
		int read = 0;
		byte [] bytes = new byte[BUF_SIZE];
		while (true) {
			read = dis.read(bytes);
			if (read < 0) {
				System.out.println("EOF read at " + elapsed + " bytes.");
				break;
			} else if (read == 0) {
				System.out.println("0 bytes read at " + elapsed + " bytes.");
				try {
					Thread.sleep(10);
				} catch (InterruptedException e) {
					
				}
			}
			elapsed += read;
			System.out.println(" read " + elapsed + " bytes.");
		}
		return dis.getMessageDigest().digest();
	}
	

	public static byte [] writeRandomFile(OutputStream stream, int fileLength, Random randBytes) throws IOException {
		DigestOutputStream digestStreamWrapper = null;
		try {
			digestStreamWrapper = new DigestOutputStream(stream, MessageDigest.getInstance("SHA1"));
		} catch (NoSuchAlgorithmException e) {
			Log.severe("No SHA1 available!");
			Assert.fail("No SHA1 available!");
		}
		byte [] bytes = new byte[BUF_SIZE];
		int elapsed = 0;
		int nextBufSize = 0;
		final double probFlush = .3;
		
		while (elapsed < fileLength) {
			nextBufSize = ((fileLength - elapsed) > BUF_SIZE) ? BUF_SIZE : (fileLength - elapsed);
			randBytes.nextBytes(bytes);
			digestStreamWrapper.write(bytes, 0, nextBufSize);
			elapsed += nextBufSize;
			if (randBytes.nextDouble() < probFlush) {
				System.out.println("Flushing buffers, have written " + elapsed + " bytes out of " + fileLength);
				digestStreamWrapper.flush();
			}
		}
		digestStreamWrapper.close();
		return digestStreamWrapper.getMessageDigest().digest();
	}
	
	public boolean handleInterest(Interest interest) {
		if(interest.exclude()!=null && !interest.exclude().empty()) {
			Log.info("this interest is probably a gLV interest, this is what we are looking for");
		} else {
			Log.info("this is not a gLV interest, dropping");
			return false;
		}

		// we only deal with the first interest, at least for now
		synchronized (this) {
			if (null != writer) {
				Log.info("handleInterests: already writing stream, ignoring interest {0}", interest);
				return false;
			}
		}
		Log.info("handleInterests got interest {0}", interest);
		CCNVersionedOutputStream vos = null;
		try {
			vos = new CCNVersionedOutputStream(interest.name(), writeHandle);
		} catch (IOException e) {
			Log.severe("Exception in creating output stream: {0}", e);
			Log.logStackTrace(Level.SEVERE, e);
			Assert.fail("Exception creating output stream " + e);
		}
		vos.addOutstandingInterest(interest);
		synchronized (this) {
			writer = new Writer(vos, FILE_SIZE);
			writer.start();
		}
		return true;
	}

}
