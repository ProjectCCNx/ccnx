package test.ccn.library.io;

import static org.junit.Assert.fail;

import java.io.IOException;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Random;

import javax.xml.stream.XMLStreamException;

import junit.framework.Assert;

import org.junit.BeforeClass;
import org.junit.Test;

import com.parc.ccn.data.ContentName;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.library.io.CCNInputStream;
import com.parc.ccn.library.io.CCNOutputStream;
import com.parc.ccn.library.io.CCNVersionedInputStream;
import com.parc.ccn.library.profiles.VersionMissingException;
import com.parc.ccn.library.profiles.VersioningProfile;

public class CCNVersionedInputStreamTest {
	
	static ContentName defaultStreamName;
	static ContentName firstVersionName;
	static int firstVersionLength;
	static byte [] firstVersionDigest;
	static ContentName middleVersionName;
	static int middleVersionLength;
	static byte [] middleVersionDigest;
	static ContentName latestVersionName;
	static int latestVersionLength;
	static byte [] latestVersionDigest;
	static CCNLibrary outputLibrary;
	static CCNLibrary inputLibrary;
	static final int MAX_FILE_SIZE = 1024*400; // 1024*1024; // 1 MB
	static final int BUF_SIZE = 4096;
	

	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		Random randBytes = new Random(); // doesn't need to be secure
		outputLibrary = CCNLibrary.open();
		inputLibrary = CCNLibrary.open();
		
		// Write a set of output
		defaultStreamName = ContentName.fromNative("/test/stream/versioning/LongOutput.bin");
		
		firstVersionName = VersioningProfile.versionName(defaultStreamName);
		firstVersionLength = randBytes.nextInt(MAX_FILE_SIZE);;
		firstVersionDigest = writeFileFloss(firstVersionName, firstVersionLength, randBytes);
		
		middleVersionName = VersioningProfile.versionName(defaultStreamName);
		middleVersionLength = randBytes.nextInt(MAX_FILE_SIZE);;
		middleVersionDigest = writeFileFloss(middleVersionName, middleVersionLength, randBytes);

		latestVersionName = VersioningProfile.versionName(defaultStreamName);
		latestVersionLength = randBytes.nextInt(MAX_FILE_SIZE);;
		latestVersionDigest = writeFileFloss(latestVersionName, latestVersionLength, randBytes);
		
	}
	
	public static byte [] writeFileFloss(ContentName completeName, int fileLength, Random randBytes) throws XMLStreamException, IOException, NoSuchAlgorithmException {
		CCNOutputStream stockOutputStream = new CCNOutputStream(completeName, outputLibrary);
		
		DigestOutputStream digestStreamWrapper = new DigestOutputStream(stockOutputStream, MessageDigest.getInstance("SHA1"));
		byte [] bytes = new byte[BUF_SIZE];
		int elapsed = 0;
		int nextBufSize = 0;
		boolean firstBuf = true;
		System.out.println("Writing file: " + completeName + " bytes: " + fileLength);
		while (elapsed < fileLength) {
			nextBufSize = ((fileLength - elapsed) > BUF_SIZE) ? BUF_SIZE : (fileLength - elapsed);
			randBytes.nextBytes(bytes);
			digestStreamWrapper.write(bytes, 0, nextBufSize);
			elapsed += nextBufSize;
			if (firstBuf) {
				startReader(completeName, fileLength);
				firstBuf = false;
			}
			System.out.println(completeName + " wrote " + elapsed + " out of " + fileLength + " bytes.");
		}
		digestStreamWrapper.close();
		System.out.println("Finished writing file " + completeName);
		return digestStreamWrapper.getMessageDigest().digest();
	}
	
	public static void startReader(final ContentName completeName, final int fileLength) {
		new Thread(){
	        public void run() {
	           try {
				readFile(completeName, fileLength);
			} catch (Exception e) {
				e.printStackTrace();
				Assert.fail("Class setup failed! " + e.getClass().getName() + ": " + e.getMessage());
			} 
	        }
	    }.start();
	}
	
	public static void readFile(ContentName completeName, int fileLength) throws IOException, XMLStreamException {
		CCNInputStream inputStream = new CCNInputStream(completeName);
		int elapsed = 0;
		int read = 0;
		byte [] bytes = new byte[BUF_SIZE];
		System.out.println("Reading file : " + completeName);
		while (elapsed < fileLength) {
			read = inputStream.read(bytes);
			elapsed += read;
			if (read == 0) {
				System.out.println("Ran out of things to read at " + elapsed + " bytes out of " + fileLength);
				break;
			}
			System.out.println(completeName + " read " + elapsed + " bytes out of " + fileLength);
		}
	}
	
	@Test
	public void testCCNVersionedInputStreamContentNameLongPublisherKeyIDCCNLibrary() {
		fail("Not yet implemented");
	}

	@Test
	public void testCCNVersionedInputStreamContentNamePublisherKeyIDCCNLibrary() {
		fail("Not yet implemented");
	}

	@Test
	public void testCCNVersionedInputStreamContentName() {
		fail("Not yet implemented");
	}

	@Test
	public void testCCNVersionedInputStreamContentNameCCNLibrary() {
		try {
			CCNVersionedInputStream vis = new CCNVersionedInputStream(firstVersionName, inputLibrary);
			Assert.assertEquals(vis.baseName(), firstVersionName);
			Assert.assertEquals(VersioningProfile.versionRoot(vis.baseName()), defaultStreamName);
			byte b = (byte)vis.read();
			if (b != b) {
				// suppress warning...
			}
			Assert.assertEquals(VersioningProfile.getVersionAsTimestamp(firstVersionName), 
								VersioningProfile.getVersionAsTimestamp(vis.baseName()));
			Assert.assertEquals(VersioningProfile.getVersionAsTimestamp(firstVersionName),
							    vis.getVersionAsTimestamp());

			CCNVersionedInputStream vls = new CCNVersionedInputStream(defaultStreamName, inputLibrary);
			System.out.println("Opened stream on latest version, expected: " + latestVersionName + " got: " + 
								vls.baseName());
			b = (byte)vls.read();
			System.out.println("Post-read: Opened stream on latest version, expected: " + latestVersionName + " got: " + 
					vls.baseName());
			Assert.assertEquals(vls.baseName(), latestVersionName);
			Assert.assertEquals(VersioningProfile.versionRoot(vls.baseName()), defaultStreamName);
			Assert.assertEquals(VersioningProfile.getVersionAsTimestamp(latestVersionName), 
								VersioningProfile.getVersionAsTimestamp(vis.baseName()));
			Assert.assertEquals(VersioningProfile.getVersionAsTimestamp(latestVersionName),
					vls.getVersionAsTimestamp());
		
		} catch (XMLStreamException e) {
			e.printStackTrace();
			Assert.fail("XMLStreamException: " + e.getMessage());
		} catch (IOException e) {
			e.printStackTrace();
			Assert.fail("IOException: " + e.getMessage());
		} catch (VersionMissingException e) {
			e.printStackTrace();
			Assert.fail("VersionMissingException: " + e.getMessage());
		}
	}

	@Test
	public void testCCNVersionedInputStreamContentNameInt() {
		fail("Not yet implemented");
	}

	@Test
	public void testCCNVersionedInputStreamContentObjectCCNLibrary() {
		fail("Not yet implemented");
	}

	@Test
	public void testSkip() {
		fail("Not yet implemented");
	}

	@Test
	public void testReset() {
		fail("Not yet implemented");
	}

	@Test
	public void testRead() {
		fail("Not yet implemented");
	}

	@Test
	public void testReadByteArray() {
		fail("Not yet implemented");
	}

	@Test
	public void testReadByteArrayIntInt() {
		fail("Not yet implemented");
	}

}
