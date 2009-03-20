package test.ccn.library;

import java.io.ByteArrayInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SignatureException;

import javax.xml.stream.XMLStreamException;

import org.junit.Assert;

import com.parc.ccn.Library;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.MalformedContentNameStringException;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.library.io.CCNInputStream;
import com.parc.ccn.library.io.CCNOutputStream;

/**
 * 
 * @author rasmusse
 *
 */

public class StreamTest extends BlockReadWriteTest {
	
	@Override
	public void getResults(ContentName baseName, int count, CCNLibrary library) throws InterruptedException, MalformedContentNameStringException, IOException, InvalidKeyException, SignatureException, NoSuchAlgorithmException, XMLStreamException {
		ContentName thisName = CCNLibrary.versionName(ContentName.fromNative(baseName, fileName), count);
		sema.acquire(); // Block until puts started
		CCNInputStream istream = new CCNInputStream(thisName, library);
		//desc.setTimeout(5000);
		Library.logger().info("Opened descriptor for reading: " + baseName);

		FileOutputStream os = new FileOutputStream(fileName + "_testout.txt");
		byte[] compareBytes = TEST_LONG_CONTENT.getBytes();
        byte[] bytes = new byte[compareBytes.length];
        int buflen;
        int slot = 0;
        while ((buflen = istream.read(bytes, slot, CHUNK_SIZE * 3)) > 0) {
        	Library.logger().info("Read " + buflen + " bytes from CCNDescriptor.");
        	os.write(bytes, 0, (int)buflen);
        	if (istream.available() == 0) {
        		Library.logger().info("Descriptor claims 0 bytes available.");
        	}
        	slot += buflen;
        }
        istream.close();
        Library.logger().info("Closed CCN reading CCNDescriptor.");
        Assert.assertArrayEquals(bytes, compareBytes);  
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
				SignatureException, MalformedContentNameStringException, IOException, XMLStreamException, InvalidKeyException, NoSuchAlgorithmException {
		ContentName thisName = CCNLibrary.versionName(ContentName.fromNative(baseName, fileName), count);
		CCNOutputStream ostream = new CCNOutputStream(thisName, null, null, null, library);
		sema.release();	// put channel open
		
		Library.logger().info("Opened descriptor for writing: " + thisName);
		
		// Dump the file in small packets
		InputStream is = new ByteArrayInputStream(TEST_LONG_CONTENT.getBytes());
        byte[] bytes = new byte[CHUNK_SIZE];
        int buflen = 0;
        while ((buflen = is.read(bytes)) >= 0) {
        	ostream.write(bytes, 0, buflen);
        	Library.logger().info("Wrote " + buflen + " bytes to CCNDescriptor.");
        }
        Library.logger().info("Finished writing. Closing CCN writing CCNDescriptor.");
        ostream.close();
        Library.logger().info("Closed CCN writing CCNDescriptor.");
	}

}
