package test.ccn.library.io;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Random;
import java.util.logging.Level;

import javax.xml.stream.XMLStreamException;

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import com.parc.ccn.Library;
import com.parc.ccn.config.SystemConfiguration;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.library.io.CCNInputStream;
import com.parc.ccn.library.io.CCNOutputStream;
import com.parc.ccn.library.profiles.SegmentationProfile;
import com.parc.ccn.library.profiles.VersioningProfile;
import com.parc.ccn.security.crypto.ContentKeys;

public class CCNSecureInputStreamTest {
	
	static ContentName defaultStreamName;
	static ContentName firstVersionName;
	static int firstVersionLength;
	static int firstVersionMaxSegment;
	static ContentKeys firstVersionKeys;
	static byte [] firstVersionDigest;
	static byte [] firstVersionData;
	static CCNLibrary outputLibrary;
	static CCNLibrary inputLibrary;
	static final int MAX_FILE_SIZE = 65*1024+1;
	static final int BUF_SIZE = 4096;
	

	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		Library.logger().setLevel(Level.FINER);
		SystemConfiguration.setDebugFlag(SystemConfiguration.DEBUGGING_FLAGS.DEBUG_SIGNATURES, true);
		Random randBytes = new Random(); // doesn't need to be secure
		outputLibrary = CCNLibrary.open();
		inputLibrary = CCNLibrary.open();
		
		// Write a set of output
		defaultStreamName = ContentName.fromNative("/test/stream/versioning/LongOutput.bin");
		
		firstVersionName = VersioningProfile.versionName(defaultStreamName);
		firstVersionLength = randBytes.nextInt(MAX_FILE_SIZE);
		firstVersionMaxSegment = (int)Math.ceil(firstVersionLength/SegmentationProfile.DEFAULT_BLOCKSIZE);
		firstVersionKeys = ContentKeys.generateRandomKeys();
		firstVersionDigest = writeFileFloss(firstVersionName, firstVersionLength, randBytes, firstVersionKeys);
	}
	
	/**
	 * Trick to get around lack of repo. We want the test below to read data out of
	 * ccnd. Problem is to do that, we have to get it into ccnd. This pre-loads
	 * ccnd with data by "flossing" it -- starting up a reader thread that will
	 * pull our generated data into ccnd for us, where it will wait till we read
	 * it back out.
	 * @param completeName
	 * @param fileLength
	 * @param randBytes
	 * @return
	 * @throws XMLStreamException
	 * @throws IOException
	 * @throws NoSuchAlgorithmException
	 * @throws InterruptedException 
	 */
	public static byte [] writeFileFloss(ContentName completeName, int fileLength, Random randBytes, ContentKeys keys) throws XMLStreamException, IOException, NoSuchAlgorithmException, InterruptedException {
		CCNOutputStream stockOutputStream = new CCNOutputStream(completeName, null, null, null, keys, outputLibrary);
		
		DigestOutputStream digestStreamWrapper = new DigestOutputStream(stockOutputStream, MessageDigest.getInstance("SHA1"));
		ByteArrayOutputStream data = new ByteArrayOutputStream();
		
		byte [] bytes = new byte[BUF_SIZE];
		int elapsed = 0;
		int nextBufSize = 0;
		boolean firstBuf = true;
		System.out.println("Writing file: " + completeName + " bytes: " + fileLength);
		final double probFlush = .1;
		
		while (elapsed < fileLength) {
			nextBufSize = ((fileLength - elapsed) > BUF_SIZE) ? BUF_SIZE : (fileLength - elapsed);
			randBytes.nextBytes(bytes);
			digestStreamWrapper.write(bytes, 0, nextBufSize);
			data.write(bytes, 0, nextBufSize);
			elapsed += nextBufSize;
			if (firstBuf) {
				startReader(completeName, fileLength, keys);
				firstBuf = false;
			}
			System.out.println(completeName + " wrote " + elapsed + " out of " + fileLength + " bytes.");
			if (randBytes.nextDouble() < probFlush) {
				System.out.println("Flushing buffers.");
				digestStreamWrapper.flush();
			}
		}
		digestStreamWrapper.close();
		System.out.println("Finished writing file " + completeName);
		firstVersionData = data.toByteArray();
		return digestStreamWrapper.getMessageDigest().digest();
	}
	
	public static Thread startReader(final ContentName completeName, final int fileLength, final ContentKeys keys) {
		Thread t = new Thread(){
	        public void run() {
	           try {
				readFile(completeName, fileLength, keys);
	           } catch (Exception e) {
	        	   e.printStackTrace();
	        	   Assert.fail("Class setup failed! " + e.getClass().getName() + ": " + e.getMessage());
	           }
	        }
	    };
	    t.start();
	    return t;
	}
	
	public static byte [] readFile(ContentName completeName, int fileLength, ContentKeys keys) throws XMLStreamException, IOException {
		CCNInputStream inputStream = new CCNInputStream(completeName, null, null, keys, null);
		System.out.println("Reading file : " + completeName);
		return readFile(inputStream, fileLength);
	}
	
	public static byte [] readFile(InputStream inputStream, int fileLength) throws IOException, XMLStreamException {
		
		System.out.println("Entering READFILE");
		DigestInputStream dis = null;
		try {
			dis = new DigestInputStream(inputStream, MessageDigest.getInstance("SHA1"));
		} catch (NoSuchAlgorithmException e) {
			Library.logger().severe("No SHA1 available!");
			Assert.fail("No SHA1 available!");
		}
		int elapsed = 0;
		int read = 0;
		byte [] bytes = new byte[BUF_SIZE];
		while (elapsed < fileLength) {
			System.out.println("elapsed = " + elapsed + " fileLength=" + fileLength);
			read = dis.read(bytes);
			System.out.println("read returned " + read + ". elapsed = " + elapsed);
			if (read < 0) {
				System.out.println("EOF read at " + elapsed + " bytes out of " + fileLength);
				break;
			} else if (read == 0) {
				System.out.println("0 bytes read at " + elapsed + " bytes out of " + fileLength);
				try {
					Thread.sleep(10);
				} catch (InterruptedException e) {
					
				}
			}
			elapsed += read;
			System.out.println(" read " + elapsed + " bytes out of " + fileLength);
		}
		return dis.getMessageDigest().digest();
	}
	
	@Test
	public void testInputStream() {
		// Test other forms of read in superclass test.
		try {
			// check we can get identical data back out
			System.out.println("Reading CCNInputStream from "+firstVersionName);
			CCNInputStream vfirst = new CCNInputStream(firstVersionName, null, null, firstVersionKeys, inputLibrary);
			byte [] readDigest = readFile(vfirst, firstVersionLength);
			Assert.assertArrayEquals(firstVersionDigest, readDigest);

			// check things fail if we use different keys
			ContentKeys keys2 = ContentKeys.generateRandomKeys();
			CCNInputStream v2 = new CCNInputStream(firstVersionName, null, null, keys2, inputLibrary);
			byte [] readDigest2 = readFile(v2, firstVersionLength);
			Assert.assertFalse(firstVersionDigest.equals(readDigest2));
		} catch (XMLStreamException e) {
			e.printStackTrace();
			Assert.fail("XMLStreamException: " + e.getMessage());
		} catch (IOException e) {
			e.printStackTrace();
			Assert.fail("IOException: " + e.getMessage());
		}
	}

}
