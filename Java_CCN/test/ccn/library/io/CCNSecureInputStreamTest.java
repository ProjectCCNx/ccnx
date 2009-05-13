package test.ccn.library.io;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.util.Random;
import java.util.logging.Level;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.IllegalBlockSizeException;
import javax.xml.stream.XMLStreamException;

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import com.parc.ccn.Library;
import com.parc.ccn.config.SystemConfiguration;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.security.PublisherPublicKeyDigest;
import com.parc.ccn.data.security.SignedInfo;
import com.parc.ccn.data.security.SignedInfo.ContentType;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.library.io.CCNInputStream;
import com.parc.ccn.library.io.CCNOutputStream;
import com.parc.ccn.library.profiles.SegmentationProfile;
import com.parc.ccn.library.profiles.VersioningProfile;
import com.parc.ccn.security.crypto.ContentKeys;

public class CCNSecureInputStreamTest {
	
	static ContentName defaultStreamName;
	static ContentName encrName;
	static int encrLength;
	static ContentKeys encrKeys;
	static byte [] encrDigest;
	static byte [] encrData;
	static CCNLibrary outputLibrary;
	static CCNLibrary inputLibrary;
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
		
		encrName = VersioningProfile.versionName(defaultStreamName);
		encrLength = 25*1024+301;
		encrKeys = ContentKeys.generateRandomKeys();
		encrDigest = writeFileFloss(encrName, encrLength, randBytes, encrKeys);
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
		encrData = data.toByteArray();
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
	
	/**
	 * Test cipher encryption & decryption work
	 */
	@Test
	public void cipherEncryptDecrypt() throws InvalidKeyException, InvalidAlgorithmParameterException, IllegalBlockSizeException, BadPaddingException {
		Cipher c = encrKeys.getSegmentEncryptionCipher(null, 0);
		byte [] d = c.doFinal(encrData);
		c = encrKeys.getSegmentDecryptionCipher(null, 0);
		d = c.doFinal(d);
		// check we get identical data back out
		Assert.assertArrayEquals(encrData, d);
	}

	/**
	 * Test cipher stream encryption & decryption work
	 * @throws IOException
	 */
	@Test
	public void cipherStreamEncryptDecrypt() throws InvalidKeyException, InvalidAlgorithmParameterException, IllegalBlockSizeException, BadPaddingException, IOException {
		Cipher c = encrKeys.getSegmentEncryptionCipher(null, 0);
		InputStream is = new ByteArrayInputStream(encrData, 0, encrData.length);
		is = new CipherInputStream(is, c);
		byte [] cipherText = new byte[4096];
		for(int total = 0, res = 0; res >= 0 && total < 4096; total+=res)
			res = is.read(cipherText,total,4096-total);

		c = encrKeys.getSegmentDecryptionCipher(null, 0);
		is = new ByteArrayInputStream(cipherText);
		is = new CipherInputStream(is, c);
		byte [] output = new byte[4096];
		for(int total = 0, res = 0; res >= 0 && total < 4096; total+=res)
			res = is.read(output,total,4096-total);
		// check we get identical data back out
		byte [] input = new byte[Math.min(4096, encrLength)];
		System.arraycopy(encrData, 0, input, 0, input.length);
		Assert.assertArrayEquals(input, output);
	}

	/**
	 * Test content encryption & decryption work
	 * @throws IOException
	 */
	@Test
	public void contentEncryptDecrypt() throws InvalidKeyException, InvalidAlgorithmParameterException, IllegalBlockSizeException, BadPaddingException, IOException {
		// create an encrypted content block
		Cipher c = encrKeys.getSegmentEncryptionCipher(null, 0);
		InputStream is = new ByteArrayInputStream(encrData, 0, encrData.length);
		is = new CipherInputStream(is, c);
		ContentName rootName = SegmentationProfile.segmentRoot(encrName);
		PublisherPublicKeyDigest publisher = outputLibrary.getDefaultPublisher();
		PrivateKey signingKey = outputLibrary.keyManager().getSigningKey(publisher);
		byte [] finalBlockID = SegmentationProfile.getSegmentID(1);
		ContentObject co = new ContentObject(SegmentationProfile.segmentName(rootName, 0),
				new SignedInfo(publisher, null, ContentType.ENCR, outputLibrary.keyManager().getKeyLocator(signingKey), new Integer(300), finalBlockID),
				is, 4096);

		// attempt to decrypt the data
		c = encrKeys.getSegmentDecryptionCipher(null, 0);
		is = new CipherInputStream(new ByteArrayInputStream(co.content()), c);
		byte [] output = new byte[co.contentLength()];
		for(int total = 0, res = 0; res >= 0 && total < output.length; total+=res)
			res = is.read(output, total, output.length-total);
		// check we get identical data back out
		byte [] input = new byte[Math.min(4096, co.contentLength())];
		System.arraycopy(encrData, 0, input, 0, input.length);
		Assert.assertArrayEquals(input, output);
	}

	/**
	 * Test basic stream encryption & decryption work, and that using different keys for decryption fails
	 */
	@Test
	public void streamEncryptDecrypt() throws XMLStreamException, IOException {
		// check we get identical data back out
		System.out.println("Reading CCNInputStream from "+encrName);
		CCNInputStream vfirst = new CCNInputStream(encrName, null, null, encrKeys, inputLibrary);
		byte [] readDigest = readFile(vfirst, encrLength);
		Assert.assertArrayEquals(encrDigest, readDigest);

		// check things fail if we use different keys
		ContentKeys keys2 = ContentKeys.generateRandomKeys();
		CCNInputStream v2 = new CCNInputStream(encrName, null, null, keys2, inputLibrary);
		byte [] readDigest2 = readFile(v2, encrLength);
		Assert.assertFalse(encrDigest.equals(readDigest2));
	}

	/**
	 * seek forward, read, seek back, read and check the results
	 * do it for different size parts of the data
	 */
	@Test
	public void seeking() throws XMLStreamException, IOException, NoSuchAlgorithmException {
		// check really small seeks/reads (smaller than 1 Cipher block)
		doSeeking(10);

		// check small seeks (but bigger than 1 Cipher block)
		doSeeking(600);

		// check large seeks (multiple ContentObjects)
		doSeeking(4096*5+350);
	}

	private void doSeeking(int length) throws XMLStreamException, IOException, NoSuchAlgorithmException {
		System.out.println("Reading CCNInputStream from "+encrName);
		CCNInputStream i = new CCNInputStream(encrName, null, null, encrKeys, inputLibrary);
		// make sure we start mid ContentObject and past the first Cipher block
		int start = ((int) (encrLength*0.3) % 4096) +600;
		i.seek(start);
		readAndCheck(i, start, length);
		i.seek(start);
		readAndCheck(i, start, length);
	}

	private void readAndCheck(CCNInputStream i, int start, int length)
			throws IOException, XMLStreamException, NoSuchAlgorithmException {
		byte [] origData = new byte[length];
		System.arraycopy(encrData, start, origData, 0, length);
		byte [] readData = new byte[length];
		i.read(readData);
		Assert.assertArrayEquals(origData, readData);
	}

	/**
	 * Test that skipping while reading an encrypted stream works
	 * Tries small/medium/large skips
	 */
	@Test
	public void skipping() throws XMLStreamException, IOException, NoSuchAlgorithmException {
		// read some data, skip some data, read some more data
		System.out.println("Reading CCNInputStream from "+encrName);
		CCNInputStream inStream = new CCNInputStream(encrName, null, null, encrKeys, inputLibrary);

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
