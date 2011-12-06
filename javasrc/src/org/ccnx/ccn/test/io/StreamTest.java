/*
 * A CCNx library test.
 *
 * Copyright (C) 2008-2011 Palo Alto Research Center, Inc.
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
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.InvalidKeyException;
import java.security.SignatureException;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.CCNInputStream;
import org.ccnx.ccn.io.CCNOutputStream;
import org.ccnx.ccn.profiles.SegmentationProfile;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;
import org.ccnx.ccn.test.BlockReadWriteTest;
import org.ccnx.ccn.test.CCNTestBase;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;

/**
 * Basic stream test. Relies on old test infrastructure, 
 */
public class StreamTest extends BlockReadWriteTest {
		
	static int longSegments = (TEST_LONG_CONTENT.length()/SegmentationProfile.DEFAULT_BLOCKSIZE);
	static int minSegments = 128;
	static int numIterations = ((int)(minSegments/longSegments) + 1);
	
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		BlockReadWriteTest.setUpBeforeClass();
	}
	
	@AfterClass
	public static void tearDownAfterClass() throws Exception {
		CCNTestBase.tearDownAfterClass();
	}
	
	@Override
	public void getResults(ContentName baseName, int count, CCNHandle handle) 
			throws InterruptedException, IOException, 
					InvalidKeyException, SignatureException {
		ContentName thisName = VersioningProfile.addVersion(ContentName.fromNative(baseName, fileName), count);
		sema.acquire(); // Block until puts started

		CCNInputStream istream = new CCNInputStream(thisName, handle);
		istream.setTimeout(120000);
		Log.info("StreamTest: Opened descriptor for reading: " + thisName);

		FileOutputStream os = new FileOutputStream(_testDir + fileName + "_testout.txt");
		byte[] compareBytes = TEST_LONG_CONTENT.getBytes();
        byte[] bytes = new byte[compareBytes.length];
        int buflen;
        for (int i=0; i < numIterations; ++i) {
            int toRead = CHUNK_SIZE * 3;
            int slot = 0;
            while ((buflen = istream.read(bytes, slot, toRead)) > 0) {
        		Log.info("Read " + buflen + " bytes from CCNDescriptor.");
        		os.write(bytes, 0, (int)buflen);
        		if (istream.available() == 0) {
        			Log.info("Stream claims 0 bytes available.");
        		}
        		slot += buflen;
        		toRead = ((compareBytes.length - slot) > CHUNK_SIZE * 3) ? (CHUNK_SIZE * 3) : (compareBytes.length - slot);
        	}
        	Assert.assertArrayEquals(bytes, compareBytes);  
        }

        istream.close();
        Log.info("Closed CCN reading CCNInputStream.");
	}
	
	/**
	 * Responsible for calling checkPutResults on each put. (Could return them all in
	 * a batch then check...)
	 * @throws InterruptedException 
	 * @throws IOException 
	 * @throws MalformedContentNameStringException 
	 * @throws SignatureException 
	 * @throws InvalidKeyException 
	 */
	@Override
	public void doPuts(ContentName baseName, int count, CCNHandle handle) throws InterruptedException, 
				SignatureException, MalformedContentNameStringException, IOException, InvalidKeyException {
		ContentName thisName = VersioningProfile.addVersion(ContentName.fromNative(baseName, fileName), count);
		CCNOutputStream ostream = new CCNOutputStream(thisName, handle);
		sema.release();	// put channel open
		
		Log.info("StreamTest: Opened output stream for writing: " + thisName);
		Log.info("Writing " + TEST_LONG_CONTENT.length() + " bytes, " +
						(TEST_LONG_CONTENT.length()/ostream.getBlockSize()) + " segments (" + numIterations + " iterations of content");
		
		ByteArrayOutputStream bigBAOS = new ByteArrayOutputStream();
		for (int i=0; i < numIterations; ++i) {
			bigBAOS.write(TEST_LONG_CONTENT.getBytes());
		}
		
		// Dump the file in small packets
		//InputStream is = new ByteArrayInputStream(TEST_LONG_CONTENT.getBytes());
		InputStream is = new ByteArrayInputStream(bigBAOS.toByteArray());

        byte[] bytes = new byte[CHUNK_SIZE];
        int buflen = 0;
        while ((buflen = is.read(bytes)) >= 0) {
        	ostream.write(bytes, 0, buflen);
        	Log.info("Wrote " + buflen + " bytes to CCNDescriptor.");
        }
        ostream.flush();
        Log.info("Finished writing. Closing CCN writing CCNDescriptor.");
        ostream.close();
        Log.info("Closed CCN writing CCNDescriptor.");
	}

}
