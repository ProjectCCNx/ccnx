package test.ccn.library;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SignatureException;
import java.util.logging.Level;

import javax.xml.stream.XMLStreamException;

import org.junit.BeforeClass;

import com.parc.ccn.Library;
import com.parc.ccn.data.CompleteName;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.MalformedContentNameStringException;
import com.parc.ccn.library.CCNDescriptor;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.library.StandardCCNLibrary;
import com.parc.ccn.library.CCNLibrary.OpenMode;


public class BlockReadWriteTest extends BaseLibraryTest {
	
	protected static final String fileName = "../data/medium_file.txt";
	protected static final int CHUNK_SIZE = 256;
	
	protected static CCNLibrary libraries[] = new CCNLibrary[2];

	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
	
		// Set debug level: use for more FINE, FINER, FINEST for debug-level tracing
		Library.logger().setLevel(Level.INFO);
		libraries[0] = new StandardCCNLibrary();
		libraries[1] = new StandardCCNLibrary(); // force them to use separate ones.
	}

	@Override
	public void getResults(String baseName, int count, CCNLibrary library) throws InterruptedException, MalformedContentNameStringException, IOException, InvalidKeyException, SignatureException, NoSuchAlgorithmException, XMLStreamException {
		
		CCNLibrary useLibrary = libraries[0]; // looking for cause of semaphore problem...
		ContentName parentName = new ContentName(baseName);
		ContentName thisName = useLibrary.versionName(new ContentName(parentName, fileName), count);
		CCNDescriptor desc = useLibrary.open(new CompleteName(thisName, null, null), OpenMode.O_RDONLY);
		Library.logger().info("Opened descriptor for reading: " + thisName);

		FileOutputStream os = new FileOutputStream(fileName + "_testout.txt");
        byte[] bytes = new byte[CHUNK_SIZE*3];
        long buflen = 0;
        while ((buflen = useLibrary.read(desc, bytes, 0, bytes.length)) >= 0) {
        	Library.logger().info("Read " + buflen + " bytes from CCNDescriptor.");
        	os.write(bytes, 0, (int)buflen);
        }
        useLibrary.close(desc);
        Library.logger().info("Closed CCN reading CCNDescriptor.");
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
	public void doPuts(String baseName, int count, CCNLibrary library) throws InterruptedException, SignatureException, MalformedContentNameStringException, IOException, XMLStreamException, InvalidKeyException, NoSuchAlgorithmException {
		CCNLibrary useLibrary = libraries[1]; // looking for cause of semaphore problem...
		ContentName parentName = new ContentName(baseName);
		ContentName thisName = useLibrary.versionName(new ContentName(parentName, fileName), count);
		CCNDescriptor desc = useLibrary.open(new CompleteName(thisName, null, null), OpenMode.O_WRONLY);
		
		Library.logger().info("Opened descriptor for writing: " + thisName);
		
		// Dump the file in small packets
		InputStream is = new ByteArrayInputStream(TEST_CONTENT.getBytes());
        byte[] bytes = new byte[CHUNK_SIZE];
        int buflen = 0;
        while ((buflen = is.read(bytes)) >= 0) {
        	useLibrary.write(desc, bytes, 0, buflen);
        	Library.logger().info("Wrote " + buflen + " bytes to CCNDescriptor.");
        }
        useLibrary.close(desc);
        Library.logger().info("Closed CCN writing CCNDescriptor.");
	}
	
	@Override
	public void testGetServPut() throws Throwable {
		System.out.println("NO TEST: PutThread/GetServer");
	}

	@Override
	public void testGetPutServ() throws Throwable {
		System.out.println("NO TEST: PutServer/GetThread");
	}
	
	protected static final String TEST_CONTENT = 
		"Four score and seven years ago, our fathers brought forth upon this \n" +
		"continent a new nation: conceived in liberty, and dedicated to the \n" +
		"proposition that all men are created equal.\n" +
		"\n" +
		"Now we are engaged in a great civil war. . .testing whether that \n" +
		"nation, or any nation so conceived and so dedicated. . . can long \n" +
		"endure. We are met on a great battlefield of that war. \n" +
		"\n" +
		"We have come to dedicate a portion of that field as a final resting \n" +
		"place for those who here gave their lives that that nation might \n" +
		"live. It is altogether fitting and proper that we should do this. \n" +
		"\n" +
		"But, in a larger sense, we cannot dedicate. . .we cannot \n" +
		"consecrate. . . we cannot hallow this ground. The brave men, living \n" +
		"and dead, who struggled here have consecrated it, far above our poor \n" +
		"power to add or detract. The world will little note, nor long \n" +
		"remember, what we say here, but it can never forget what they did \n" +
		"here. \n" +
		"\n" +
		"It is for us the living, rather, to be dedicated here to the \n" +
		"unfinished work which they who fought here have thus far so nobly \n" +
		"advanced. It is rather for us to be here dedicated to the great task \n" +
		"remaining before us. . .that from these honored dead we take increased \n" +
		"devotion to that cause for which they gave the last full measure of \n" +
		"devotion. . . that we here highly resolve that these dead shall not \n" +
		"have died in vain. . . that this nation, under God, shall have a new \n" +
		"birth of freedom. . . and that government of the people. . .by the \n" +
		"people. . .for the people. . . shall not perish from the earth. \n" +
		"\n";
}
