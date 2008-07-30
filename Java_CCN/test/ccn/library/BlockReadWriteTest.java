package test.ccn.library;

import java.io.ByteArrayInputStream;
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
	
	protected static final String fileName = "medium_file.txt";
	protected static final int CHUNK_SIZE = 512;
	
	protected static CCNLibrary libraries[] = new CCNLibrary[2];

	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
	
		// Set debug level: use for more FINE, FINER, FINEST for debug-level tracing
		Library.logger().setLevel(Level.FINEST);
		libraries[0] = new StandardCCNLibrary();
		libraries[1] = new StandardCCNLibrary(); // force them to use separate ones.
	}

	@Override
	public void getResults(ContentName baseName, int count, CCNLibrary library) throws InterruptedException, MalformedContentNameStringException, IOException, InvalidKeyException, SignatureException, NoSuchAlgorithmException, XMLStreamException {
		
		CCNLibrary useLibrary = libraries[0]; // looking for cause of semaphore problem...
		ContentName thisName = useLibrary.versionName(new ContentName(baseName, fileName), count);
		CCNDescriptor desc = useLibrary.open(new CompleteName(thisName, null, null), OpenMode.O_RDONLY);
		Library.logger().info("Opened descriptor for reading: " + thisName);

		FileOutputStream os = new FileOutputStream(fileName + "_testout.txt");
        byte[] bytes = new byte[CHUNK_SIZE*3];
        long buflen = 0;
        while ((buflen = useLibrary.read(desc, bytes, 0, bytes.length)) > 0) {
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
	public void doPuts(ContentName baseName, int count, CCNLibrary library) throws InterruptedException, SignatureException, MalformedContentNameStringException, IOException, XMLStreamException, InvalidKeyException, NoSuchAlgorithmException {
		CCNLibrary useLibrary = libraries[1]; // looking for cause of semaphore problem...
		ContentName thisName = useLibrary.versionName(new ContentName(baseName, fileName), count);
		CCNDescriptor desc = useLibrary.open(new CompleteName(thisName, null, null), OpenMode.O_WRONLY);
		
		Library.logger().info("Opened descriptor for writing: " + thisName);
		
		// Dump the file in small packets
		InputStream is = new ByteArrayInputStream(TEST_LONG_CONTENT.getBytes());
        byte[] bytes = new byte[CHUNK_SIZE];
        int buflen = 0;
        while ((buflen = is.read(bytes)) >= 0) {
        	useLibrary.write(desc, bytes, 0, buflen);
        	Library.logger().info("Wrote " + buflen + " bytes to CCNDescriptor.");
        }
        Library.logger().info("Finished writing. Closing CCN writing CCNDescriptor.");
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
	
	protected static final String TEST_MEDIUM_CONTENT = 
		"By the President of the United States of America:\n" +
		"\n" + 
		"A PROCLAMATION\n" + 
		"\n" + 
	    "Whereas on the 22nd day of September, A.D. 1862, a\n" + 
	    "proclamation was issued by the President of the United\n" + 
	    "States, containing, among other things, the following, to\n" + 
	    "wit:\n" + 
		"\n" + 
	    "That on the 1st day of January, A.D. 1863, all persons held as\n" + 
	    "slaves within any State or designated part of a State the people\n" + 
	    "whereof shall then be in rebellion against the United States shall\n" + 
	    "be then, thenceforward, and forever free; and the executive\n" + 
	    "government of the United States, including the military and naval\n" + 
	    "authority thereof, will recognize and maintain the freedom of such\n" + 
	    "persons and will do no act or acts to repress such persons, or any\n" + 
	    "of them, in any efforts they may make for their actual freedom.\n" + 
		"\n" + 
	    "That the executive will on the 1st day of January aforesaid, by\n" + 
		"proclamation, designate the States and parts of States, if any, in\n" + 
		"which the people thereof, respectively, shall then be in rebellion\n" + 
		"against the United States; and the fact that any State or the people\n" + 
		"thereof shall on that day be in good faith represented in the Congress\n" + 
		"of the United States by members chosen thereto at elections wherein a\n" + 
		"majority of the qualified voters of such States shall have\n" + 
		"participated shall, in the absence of strong countervailing testimony,\n" + 
		"be deemed conclusive evidence that such State and the people thereof\n" + 
		"are not then in rebellion against the United States.\n" + 
		"\n" + 
	    "Now, therefore, I, Abraham Lincoln, President of the United\n" + 
	    "States, by virtue of the power in me vested as Commander-In-Chief\n" + 
	    "of the Army and Navy of the United States in time of actual armed\n" + 
	    "rebellion against the authority and government of the United\n" + 
	    "States, and as a fit and necessary war measure for supressing said\n" + 
	    "rebellion, do, on this 1st day of January, A.D. 1863, and in\n" + 
	    "accordance with my purpose so to do, publicly proclaimed for the\n" + 
	    "full period of one hundred days from the first day above\n" + 
	    "mentioned, order and designate as the States and parts of States\n" + 
	    "wherein the people thereof, respectively, are this day in\n" + 
	    "rebellion against the United States the following, to wit:\n" + 
		"\n" + 
	    "Arkansas, Texas, Louisiana (except the parishes of St. Bernard,\n" + 
	    "Palquemines, Jefferson, St. John, St. Charles, St. James,\n" + 
	    "Ascension, Assumption, Terrebone, Lafourche, St. Mary, St. Martin,\n" + 
	    "and Orleans, including the city of New Orleans), Mississippi,\n" + 
	    "Alabama, Florida, Georgia, South Carolina, North Carolina, and\n" + 
	    "Virginia (except the forty-eight counties designated as West\n" + 
	    "Virginia, and also the counties of Berkeley, Accomac,\n" + 
	    "Morthhampton, Elizabeth City, York, Princess Anne, and Norfolk,\n" + 
	    "including the cities of Norfolk and Portsmouth), and which\n" + 
	    "excepted parts are for the present left precisely as if this\n" + 
	    "proclamation were not issued.\n" + 
		"\n" + 
	    "And by virtue of the power and for the purpose aforesaid, I do\n" + 
	    "order and declare that all persons held as slaves within said\n" + 
	    "designated States and parts of States are, and henceforward shall\n" + 
	    "be, free; and that the Executive Government of the United States,\n" + 
	    "including the military and naval authorities thereof, will\n" + 
	    "recognize and maintain the freedom of said persons.\n" + 
		"\n" + 
	    "And I hereby enjoin upon the people so declared to be free to\n" + 
	    "abstain from all violence, unless in necessary self-defence; and I\n" + 
	    "recommend to them that, in all case when allowed, they labor\n" + 
	    "faithfully for reasonable wages.\n" + 
		"\n" + 
	    "And I further declare and make known that such persons of\n" + 
	    "suitable condition will be received into the armed service of\n" + 
	    "the United States to garrison forts, positions, stations, and\n" + 
	    "other places, and to man vessels of all sorts in said\n" + 
	    "service.\n" + 
		"\n" + 
	    "And upon this act, sincerely believed to be an act of\n" + 
	    "justice, warranted by the Constitution upon military\n" + 
	    "necessity, I invoke the considerate judgment of mankind and\n" + 
	    "the gracious favor of Almighty God.\n";
	
	protected static final String TEST_LONG_CONTENT = TEST_CONTENT + TEST_MEDIUM_CONTENT + TEST_CONTENT + TEST_MEDIUM_CONTENT;

}
