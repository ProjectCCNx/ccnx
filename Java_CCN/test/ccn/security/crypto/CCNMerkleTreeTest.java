package test.ccn.security.crypto;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.util.Random;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.security.KeyLocator;
import com.parc.ccn.data.security.PublisherKeyID;
import com.parc.ccn.data.security.SignedInfo;
import com.parc.ccn.library.profiles.SegmentationProfile;
import com.parc.ccn.library.profiles.VersioningProfile;
import com.parc.ccn.security.crypto.CCNDigestHelper;
import com.parc.ccn.security.crypto.CCNMerkleTree;

public class CCNMerkleTreeTest {

	protected static Random _rand = new Random(); // don't need SecureRandom
	
	protected static KeyPair _pair = null;
	static ContentName keyname = ContentName.fromNative(new String[]{"test","keys","treeKey"});
	static ContentName baseName = ContentName.fromNative(new String[]{"test","data","treeTest"});

	static KeyPair pair = null;
	static PublisherKeyID publisher = null;
	static KeyLocator nameLoc = null;
	
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		try {
			Security.addProvider(new BouncyCastleProvider());
			
			// generate key pair
			KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
			kpg.initialize(512); // go for fast
			pair = kpg.generateKeyPair();
			publisher = new PublisherKeyID(pair.getPublic());
			nameLoc = new KeyLocator(keyname);
		} catch (Exception e) {
			System.out.println("Exception in test setup: " + e.getMessage());
			e.printStackTrace();
			throw e;
		}
	}

	@Before
	public void setUp() throws Exception {
	}
	
	@Test
	public void testMerkleTree() {
		int [] sizes = new int[]{128,256,512,4096};
		
		System.out.println("Testing small trees.");
		for (int i=10; i < 515; ++i) {
			testTree(i,sizes[i%sizes.length],false);
		}
		
		System.out.println("Testing large trees.");
		int [] nodecounts = new int[]{1000,1001,1025,1098,1536,1575,2053,5147,8900,9998,9999,10000};
		for (int i=0; i < nodecounts.length; ++i) {
			testTree(nodecounts[i],sizes[i%sizes.length],false);
		}
	}
	
	public static void testTree(int numLeaves, int nodeLength, boolean digest) {
		try {
			byte [][] data = makeContent(numLeaves, nodeLength, digest);
			testTree(data, numLeaves, digest);
		} catch (Exception e) {
			System.out.println("Building tree of " + numLeaves + " Nodes. Caught a " + e.getClass().getName() + " exception: " + e.getMessage());
			e.printStackTrace();
		}
	}
	
	public static byte [][] makeContent(int numNodes, int nodeLength, boolean digest) {
		
		byte [][] bufs = new byte[numNodes][];
		byte [] tmpbuf = null;
		
		if (digest)
			tmpbuf = new byte[nodeLength];
		
		int blocklen = (digest ? CCNDigestHelper.DEFAULT_DIGEST_LENGTH  : nodeLength);
		
		for (int i=0; i < numNodes; ++i) {
			bufs[i] = new byte[blocklen];
			
			if (digest) {
				_rand.nextBytes(tmpbuf);
				bufs[i] = CCNDigestHelper.digest(tmpbuf);
			} else {
				_rand.nextBytes(bufs[i]);
			}
		}
		return bufs;
	}
	
	public static void testTree(byte [][] content, int count, boolean digest) {
		int version = _rand.nextInt(1000);
		ContentName theName = ContentName.fromNative(baseName, "testDoc.txt");
		theName = VersioningProfile.versionName(theName, version);
		
		try {
			// TODO DKS Need to do offset versions with different ranges of fragments
			// Generate a merkle tree. Verify each path for the content.
			CCNMerkleTree tree = new CCNMerkleTree(theName, 0, new SignedInfo(publisher, null, nameLoc),
													content, digest, count, 0, 
													content[count-1].length,
													pair.getPrivate());
		
			System.out.println("Constructed tree of " + count + " blocks (of " + content.length + "), numleaves: " + 
										tree.numLeaves() + " max pathlength: " + tree.maxDepth());
		
			for (int i=0; i < count; ++i) {
				ContentObject block = tree.block(i, content[i]);
				boolean result = block.verify(pair.getPublic());
	//			if (!result)
					System.out.println("Block name: " + tree.blockName(i) + " num "  + i + " verified? " + result);
				Assert.assertTrue("Path " + i + " failed to verify.", result);
			}
		} catch (Exception e) {
			System.out.println("Exception in testTree: " + e.getClass().getName() + ": " + e.getMessage());
			e.printStackTrace();
			Assert.fail(e.getMessage());
		}
	}
	
}
