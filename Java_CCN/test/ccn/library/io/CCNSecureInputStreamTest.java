package test.ccn.library.io;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
import com.parc.ccn.library.io.CCNFileInputStream;
import com.parc.ccn.library.io.CCNFileOutputStream;
import com.parc.ccn.library.io.CCNInputStream;
import com.parc.ccn.library.io.CCNOutputStream;
import com.parc.ccn.library.io.CCNVersionedInputStream;
import com.parc.ccn.library.io.CCNVersionedOutputStream;
import com.parc.ccn.library.profiles.SegmentationProfile;
import com.parc.ccn.library.profiles.VersioningProfile;
import com.parc.ccn.security.crypto.ContentKeys;
import com.parc.ccn.security.crypto.UnbufferedCipherInputStream;

public class CCNSecureInputStreamTest {
	
	static protected abstract class StreamFactory {
		int encrLength = 25*1024+301;
		byte [] digest;
		byte [] encrData;
		ContentName name;
		ContentKeys keys;
		public StreamFactory(ContentName n) throws NoSuchAlgorithmException, XMLStreamException, IOException, InterruptedException {
			name = VersioningProfile.versionName(n);
			keys = ContentKeys.generateRandomKeys();
			digest = writeFileFloss(encrLength);
		}
		public abstract CCNInputStream makeInputStream() throws IOException, XMLStreamException;
		public abstract OutputStream makeOutputStream() throws IOException, XMLStreamException;

		/**
		 * Trick to get around lack of repo. We want the test below to read data out of
		 * ccnd. Problem is to do that, we have to get it into ccnd. This pre-loads
		 * ccnd with data by "flossing" it -- starting up a reader thread that will
		 * pull our generated data into ccnd for us, where it will wait till we read
		 * it back out.
		 */
		public byte [] writeFileFloss(int fileLength) throws XMLStreamException, IOException, NoSuchAlgorithmException, InterruptedException {
			Random randBytes = new Random(); // doesn't need to be secure
			OutputStream stockOutputStream = makeOutputStream();

			DigestOutputStream digestStreamWrapper = new DigestOutputStream(stockOutputStream, MessageDigest.getInstance("SHA1"));
			ByteArrayOutputStream data = new ByteArrayOutputStream();

			byte [] bytes = new byte[BUF_SIZE];
			int elapsed = 0;
			int nextBufSize = 0;
			boolean firstBuf = true;
			final double probFlush = .1;

			while (elapsed < fileLength) {
				nextBufSize = ((fileLength - elapsed) > BUF_SIZE) ? BUF_SIZE : (fileLength - elapsed);
				randBytes.nextBytes(bytes);
				digestStreamWrapper.write(bytes, 0, nextBufSize);
				data.write(bytes, 0, nextBufSize);
				elapsed += nextBufSize;
				if (firstBuf) {
					startReader(fileLength);
					firstBuf = false;
				}
				if (randBytes.nextDouble() < probFlush) {
					System.out.println("Flushing buffers.");
					digestStreamWrapper.flush();
				}
			}
			digestStreamWrapper.close();
			encrData = data.toByteArray();
			return digestStreamWrapper.getMessageDigest().digest();
		}

		public Thread startReader(final int fileLength) {
			Thread t = new Thread(){
				public void run() {
					try {
						InputStream inputStream = makeInputStream();
						readFile(inputStream, fileLength);
					} catch (Exception e) {
						e.printStackTrace();
						Assert.fail("Class setup failed! " + e.getClass().getName() + ": " + e.getMessage());
		           }
		        }
		    };
		    t.start();
		    return t;
		}

		public void streamEncryptDecrypt() throws XMLStreamException, IOException {
			// check we get identical data back out
			System.out.println("Reading CCNInputStream from "+name);
			CCNInputStream vfirst = makeInputStream();
			byte [] readDigest = readFile(vfirst, encrLength);
			Assert.assertArrayEquals(digest, readDigest);

			// check things fail if we use different keys
			ContentKeys keys2 = keys;
			CCNInputStream v2;
			try {
				keys = ContentKeys.generateRandomKeys();
				v2 = makeInputStream();
			} finally {
				keys = keys2;
			}
			byte [] readDigest2 = readFile(v2, encrLength);
			Assert.assertFalse(digest.equals(readDigest2));
		}

		public void seeking() throws XMLStreamException, IOException, NoSuchAlgorithmException {
			// check really small seeks/reads (smaller than 1 Cipher block)
			doSeeking(10);

			// check small seeks (but bigger than 1 Cipher block)
			doSeeking(600);

			// check large seeks (multiple ContentObjects)
			doSeeking(4096*5+350);
		}

		private void doSeeking(int length) throws XMLStreamException, IOException, NoSuchAlgorithmException {
			System.out.println("Reading CCNInputStream from "+name);
			CCNInputStream i = makeInputStream();
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

		public void skipping() throws XMLStreamException, IOException, NoSuchAlgorithmException {
			// read some data, skip some data, read some more data
			System.out.println("Reading CCNInputStream from "+name);
			CCNInputStream inStream = new CCNInputStream(name, null, null, keys, inputLibrary);

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

	static ContentName defaultStreamName;
	static CCNLibrary outputLibrary;
	static CCNLibrary inputLibrary;
	static final int BUF_SIZE = 4096;

	static StreamFactory basic;
	static StreamFactory versioned;
	static StreamFactory file;

	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		Library.logger().setLevel(Level.FINEST);
		SystemConfiguration.setDebugFlag(SystemConfiguration.DEBUGGING_FLAGS.DEBUG_SIGNATURES, true);
		outputLibrary = CCNLibrary.open();
		inputLibrary = CCNLibrary.open();
		
		// Write a set of output
		defaultStreamName = ContentName.fromNative("/test/stream/versioning/LongOutput.bin");
		
		basic = new StreamFactory(defaultStreamName){
			public CCNInputStream makeInputStream() throws IOException, XMLStreamException {
				return new CCNInputStream(name, null, null, keys, inputLibrary);
			}
			public OutputStream makeOutputStream() throws IOException, XMLStreamException {
				return new CCNOutputStream(name, null, null, null, keys, outputLibrary);
			}
		};

		versioned = new StreamFactory(defaultStreamName){
			public CCNInputStream makeInputStream() throws IOException, XMLStreamException {
				return new CCNVersionedInputStream(name, 0L, null, keys, inputLibrary);
			}
			public OutputStream makeOutputStream() throws IOException, XMLStreamException {
				return new CCNVersionedOutputStream(name, null, null, keys, outputLibrary);
			}
		};

		file = new StreamFactory(defaultStreamName){
			public CCNInputStream makeInputStream() throws IOException, XMLStreamException {
				return new CCNFileInputStream(name, null, null, keys, inputLibrary);
			}
			public OutputStream makeOutputStream() throws IOException, XMLStreamException {
				return new CCNFileOutputStream(name, null, keys, outputLibrary);
			}
		};
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
		Cipher c = basic.keys.getSegmentEncryptionCipher(0);
		byte [] d = c.doFinal(basic.encrData);
		c = basic.keys.getSegmentDecryptionCipher(0);
		d = c.doFinal(d);
		// check we get identical data back out
		Assert.assertArrayEquals(basic.encrData, d);
	}

	/**
	 * Test cipher stream encryption & decryption work
	 * @throws IOException
	 */
	@Test
	public void cipherStreamEncryptDecrypt() throws InvalidKeyException, InvalidAlgorithmParameterException, IllegalBlockSizeException, BadPaddingException, IOException {
		Cipher c = basic.keys.getSegmentEncryptionCipher(0);
		InputStream is = new ByteArrayInputStream(basic.encrData, 0, basic.encrData.length);
		is = new UnbufferedCipherInputStream(is, c);
		byte [] cipherText = new byte[4096];
		for(int total = 0, res = 0; res >= 0 && total < 4096; total+=res)
			res = is.read(cipherText,total,4096-total);

		c = basic.keys.getSegmentDecryptionCipher(0);
		is = new ByteArrayInputStream(cipherText);
		is = new UnbufferedCipherInputStream(is, c);
		byte [] output = new byte[4096];
		for(int total = 0, res = 0; res >= 0 && total < 4096; total+=res)
			res = is.read(output,total,4096-total);
		// check we get identical data back out
		byte [] input = new byte[Math.min(4096, basic.encrLength)];
		System.arraycopy(basic.encrData, 0, input, 0, input.length);
		Assert.assertArrayEquals(input, output);
	}

	/**
	 * Test content encryption & decryption work
	 * @throws IOException
	 */
	@Test
	public void contentEncryptDecrypt() throws InvalidKeyException, InvalidAlgorithmParameterException, IllegalBlockSizeException, BadPaddingException, IOException {
		// create an encrypted content block
		Cipher c = basic.keys.getSegmentEncryptionCipher(0);
		InputStream is = new ByteArrayInputStream(basic.encrData, 0, basic.encrData.length);
		is = new UnbufferedCipherInputStream(is, c);
		ContentName rootName = SegmentationProfile.segmentRoot(basic.name);
		PublisherPublicKeyDigest publisher = outputLibrary.getDefaultPublisher();
		PrivateKey signingKey = outputLibrary.keyManager().getSigningKey(publisher);
		byte [] finalBlockID = SegmentationProfile.getSegmentID(1);
		ContentObject co = new ContentObject(SegmentationProfile.segmentName(rootName, 0),
				new SignedInfo(publisher, null, ContentType.ENCR, outputLibrary.keyManager().getKeyLocator(signingKey), new Integer(300), finalBlockID),
				is, 4096);

		// attempt to decrypt the data
		c = basic.keys.getSegmentDecryptionCipher(0);
		is = new UnbufferedCipherInputStream(new ByteArrayInputStream(co.content()), c);
		byte [] output = new byte[co.contentLength()];
		for(int total = 0, res = 0; res >= 0 && total < output.length; total+=res)
			res = is.read(output, total, output.length-total);
		// check we get identical data back out
		byte [] input = new byte[Math.min(4096, co.contentLength())];
		System.arraycopy(basic.encrData, 0, input, 0, input.length);
		Assert.assertArrayEquals(input, output);
	}

	/**
	 * Test stream encryption & decryption work, and that using different keys for decryption fails
	 */
	@Test
	public void basicStreamEncryptDecrypt() throws XMLStreamException, IOException {
		basic.streamEncryptDecrypt();
	}
	@Test
	public void versionedStreamEncryptDecrypt() throws XMLStreamException, IOException {
		versioned.streamEncryptDecrypt();
	}
//	@Test
	public void fileStreamEncryptDecrypt() throws XMLStreamException, IOException {
		file.streamEncryptDecrypt();
	}

	/**
	 * seek forward, read, seek back, read and check the results
	 * do it for different size parts of the data
	 */
	@Test
	public void basicSeeking() throws XMLStreamException, IOException, NoSuchAlgorithmException {
		basic.seeking();
	}
	@Test
	public void versionedSeeking() throws XMLStreamException, IOException, NoSuchAlgorithmException {
		versioned.seeking();
	}
//	@Test
	public void fileSeeking() throws XMLStreamException, IOException, NoSuchAlgorithmException {
		file.seeking();
	}

	/**
	 * Test that skipping while reading an encrypted stream works
	 * Tries small/medium/large skips
	 */
	@Test
	public void basicSkipping() throws XMLStreamException, IOException, NoSuchAlgorithmException {
		basic.skipping();
	}
	@Test
	public void versionedSkipping() throws XMLStreamException, IOException, NoSuchAlgorithmException {
		versioned.skipping();
	}
//	@Test
	public void fileSkipping() throws XMLStreamException, IOException, NoSuchAlgorithmException {
		file.skipping();
	}
}
