package test.ccn.library;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SignatureException;

import javax.xml.stream.XMLStreamException;

import org.junit.Assert;
import org.junit.BeforeClass;

import com.parc.ccn.Library;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.MalformedContentNameStringException;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.library.io.CCNInputStream;
import com.parc.ccn.library.io.CCNOutputStream;
import com.parc.ccn.library.profiles.SegmentationProfile;
import com.parc.ccn.library.profiles.VersioningProfile;

/**
 * 
 * @author rasmusse
 *
 */

public class StreamTest extends BlockReadWriteTest {
	
	static int longSegments = (TEST_LONG_CONTENT.length()/SegmentationProfile.DEFAULT_BLOCKSIZE);
	static int minSegments = 128;
	static int numIterations = ((int)(minSegments/longSegments) + 1);
	
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		// Set debug level: use for more FINE, FINER, FINEST for debug-level tracing
		//Library.logger().setLevel(Level.FINEST);
	}
	
	@Override
	public void getResults(ContentName baseName, int count, CCNLibrary library) throws InterruptedException, MalformedContentNameStringException, IOException, InvalidKeyException, SignatureException, XMLStreamException {
		ContentName thisName = VersioningProfile.addVersion(ContentName.fromNative(baseName, fileName), count);
		sema.acquire(); // Block until puts started
		CCNInputStream istream = new CCNInputStream(thisName, library);
		istream.setTimeout(8000);
		Library.logger().info("Opened descriptor for reading: " + baseName);

		FileOutputStream os = new FileOutputStream(fileName + "_testout.txt");
		byte[] compareBytes = TEST_LONG_CONTENT.getBytes();
        byte[] bytes = new byte[compareBytes.length];
        int buflen;
        for (int i=0; i < numIterations; ++i) {
            int toRead = CHUNK_SIZE * 3;
            int slot = 0;
            while ((buflen = istream.read(bytes, slot, toRead)) > 0) {
        		Library.logger().info("Read " + buflen + " bytes from CCNDescriptor.");
        		os.write(bytes, 0, (int)buflen);
        		if (istream.available() == 0) {
        			Library.logger().info("Stream claims 0 bytes available.");
        		}
        		slot += buflen;
        		toRead = ((compareBytes.length - slot) > CHUNK_SIZE * 3) ? (CHUNK_SIZE * 3) : (compareBytes.length - slot);
        	}
        	Assert.assertArrayEquals(bytes, compareBytes);  
        }

        istream.close();
        Library.logger().info("Closed CCN reading CCNInputStream.");
	}
	
	/**
	 * Responsible for calling checkPutResults on each put. (Could return them all in
	 * a batch then check...)
	 * @throws InterruptedException 
	 * @throws IOException 
	 * @throws MalformedContentNameStringException 
	 * @throws SignatureException 
	 * @throws XMLStreamException 
	 * @throws NoSuchAlgorithmException 
	 * @throws InvalidKeyException 
	 */
	@Override
	public void doPuts(ContentName baseName, int count, CCNLibrary library) throws InterruptedException, 
				SignatureException, MalformedContentNameStringException, IOException, InvalidKeyException {
		ContentName thisName = VersioningProfile.addVersion(ContentName.fromNative(baseName, fileName), count);
		CCNOutputStream ostream = new CCNOutputStream(thisName, null, null, library);
		sema.release();	// put channel open
		
		Library.logger().info("Opened output stream for writing: " + thisName);
		Library.logger().info("Writing " + TEST_LONG_CONTENT.length() + " bytes, " +
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
        	Library.logger().info("Wrote " + buflen + " bytes to CCNDescriptor.");
        }
        ostream.flush();
        Library.logger().info("Finished writing. Closing CCN writing CCNDescriptor.");
        ostream.close();
        Library.logger().info("Closed CCN writing CCNDescriptor.");
	}

}
