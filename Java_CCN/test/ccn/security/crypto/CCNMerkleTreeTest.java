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
import com.parc.ccn.data.security.PublisherPublicKeyDigest;
import com.parc.ccn.data.security.SignedInfo;
import com.parc.ccn.data.util.DataUtils;
import com.parc.ccn.library.profiles.VersioningProfile;
import com.parc.ccn.security.crypto.CCNDigestHelper;
import com.parc.ccn.security.crypto.CCNMerkleTree;

public class CCNMerkleTreeTest {

	protected static Random _rand = new Random(); // don't need SecureRandom
	
	protected static KeyPair _pair = null;
	static ContentName keyname = ContentName.fromNative(new String[]{"test","keys","treeKey"});
	static ContentName baseName = ContentName.fromNative(new String[]{"test","data","treeTest"});

	static KeyPair pair = null;
	static PublisherPublicKeyDigest publisher = null;
	static KeyLocator keyLoc = null;
	
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		try {
			Security.addProvider(new BouncyCastleProvider());
			
			// generate key pair
			KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
			kpg.initialize(512); // go for fast
			pair = kpg.generateKeyPair();
			publisher = new PublisherPublicKeyDigest(pair.getPublic());
			keyLoc = new KeyLocator(pair.getPublic());
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
		int [] nodecounts = new int[]{1000,1001,1025,1098,1536,1575,2053,5147,8900,9998};
//		int [] nodecounts = new int[]{1000,1001,1025,1098,1536,1575,2053,5147,8900,9998,9999,10000};
		for (int i=0; i < nodecounts.length; ++i) {
			testTree(nodecounts[i],sizes[i%sizes.length],false);
		}
	}
	
	@Test
	public void testMerkleTreeBuf() {
		int [] sizes = new int[]{128,256,512,4096};
		System.out.println("Testing small trees.");
		for (int i=10; i < 515; ++i) {
			int buflen = i*sizes[i%sizes.length];
			testBufTree(buflen,buflen-_rand.nextInt(buflen/2),sizes[i%sizes.length]);
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
	
	public static void testBufTree(int bufLength, int testLength, int blockWidth) {
		try {
			byte [] data = makeContent(bufLength);
			testTree(data, testLength, blockWidth);
		} catch (Exception e) {
			System.out.println("Building tree of " + testLength + " bytes. Caught a " + e.getClass().getName() + " exception: " + e.getMessage());
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
	
	public static byte [] makeContent(int length) {
		
		byte [] buf = new byte[length];
		_rand.nextBytes(buf);

		return buf;
	}

	public static void testTree(byte [][] content, int count, boolean digest) {
		int version = _rand.nextInt(1000);
		ContentName theName = ContentName.fromNative(baseName, "testDoc.txt");
		theName = VersioningProfile.versionName(theName, version);
		
		try {
			// TODO DKS Need to do offset versions with different ranges of fragments
			// Generate a merkle tree. Verify each path for the content.
			CCNMerkleTree tree = new CCNMerkleTree(theName, 0, new SignedInfo(publisher, null, keyLoc),
													content, digest, count, 0, 
													content[count-1].length,
													pair.getPrivate());
		
			System.out.println("Constructed tree of " + count + " blocks (of " + content.length + "), numleaves: " + 
										tree.numLeaves() + " max pathlength: " + tree.maxDepth());
		
			for (int i=0; i < count; ++i) {
				ContentObject block = tree.block(i, content[i], 0, content[i].length);
				boolean result = block.verify(pair.getPublic());
				System.out.println("Block name: " + tree.blockName(i) + " num "  + i + " verified? " + result + " content: " + DataUtils.printBytes(block.contentDigest()));
				if (!result) {
					System.out.println("Raw content digest: " + DataUtils.printBytes(CCNDigestHelper.digest(content[i], 0, content[i].length)) +
							" object content digest:  " + DataUtils.printBytes(CCNDigestHelper.digest(block.content())));
					byte [] objdigest = CCNDigestHelper.digest(block.encode());
					byte [] tbsdigest = CCNDigestHelper.digest(ContentObject.prepareContent(block.name(), block.signedInfo(), content[i], 0, content[i].length));
					System.out.println("Raw content digest: " + DataUtils.printBytes(CCNDigestHelper.digest(content[i], 0, content[i].length)) +
							" object content digest:  " + DataUtils.printBytes(CCNDigestHelper.digest(block.content())));
					System.out.println("Block: " + block.name() + " timestamp: " + block.signedInfo().getTimestamp() + " encoded digest: " + DataUtils.printBytes(objdigest) + " tbs content: " + DataUtils.printBytes(tbsdigest));
				}
				Assert.assertTrue("Path " + i + " failed to verify.", result);
			}
			tree = null;
		} catch (Exception e) {
			System.out.println("Exception in testTree: " + e.getClass().getName() + ": " + e.getMessage());
			e.printStackTrace();
			Assert.fail(e.getMessage());
		}
	}
	
	public static void testTree(byte [] content, int length, int blockWidth) {
		int version = _rand.nextInt(1000);
		ContentName theName = ContentName.fromNative(baseName, "testDocBuffer.txt");
		theName = VersioningProfile.versionName(theName, version);
		
		try {
			// TODO DKS Need to do offset versions with different ranges of fragments
			// Generate a merkle tree. Verify each path for the content.
			CCNMerkleTree tree = new CCNMerkleTree(theName, 0, new SignedInfo(publisher, null, keyLoc),
													content, 0, length, blockWidth,
													pair.getPrivate());
		
			System.out.println("Constructed tree of " + length + " bytes (of " + content.length + "), numleaves: " + 
										tree.numLeaves() + " max pathlength: " + tree.maxDepth());
		
			ContentObject block;
			for (int i=0; i < tree.numLeaves()-1; ++i) {
				block = tree.block(i, content, i*blockWidth, blockWidth);
				boolean result = block.verify(pair.getPublic());
				System.out.println("Block name: " + tree.blockName(i) + " num "  + i + " verified? " + result + ", content: " + DataUtils.printBytes(block.contentDigest()));
				if (!result) {
					byte [] digest = CCNDigestHelper.digest(block.encode());
					byte [] tbsdigest = CCNDigestHelper.digest(ContentObject.prepareContent(block.name(), block.signedInfo(), content, i*blockWidth, blockWidth));
					System.out.println("Raw content digest: " + DataUtils.printBytes(CCNDigestHelper.digest(content, i*blockWidth, blockWidth)) +
							" object content digest:  " + DataUtils.printBytes(CCNDigestHelper.digest(block.content())));
					System.out.println("Block: " + block.name() + " timestamp: " + block.signedInfo().getTimestamp() + " encoded digest: " + DataUtils.printBytes(digest) + " tbs content: " + DataUtils.printBytes(tbsdigest));
				}
				Assert.assertTrue("Path " + i + " failed to verify.", result);
			}
			block = tree.block(tree.numLeaves()-1, content, (tree.numLeaves()-1)*blockWidth, length - ((tree.numLeaves()-1)*blockWidth));
			boolean result = block.verify(pair.getPublic());
			System.out.println("Block name: " + tree.blockName(tree.numLeaves()-1) + " num "  + (tree.numLeaves()-1) + " verified? " + result + " content: " + DataUtils.printBytes(block.contentDigest()));
			if (!result) {
				System.out.println("Raw content digest: " + 
						DataUtils.printBytes(CCNDigestHelper.digest(
								content, ((tree.numLeaves()-1)*blockWidth), 
								(length - ((tree.numLeaves()-1)*blockWidth)))) +
								" object content digest:  " + 
								DataUtils.printBytes(CCNDigestHelper.digest(block.content())));
			}
			tree = null;
		} catch (Exception e) {
			System.out.println("Exception in testTree: " + e.getClass().getName() + ": " + e.getMessage());
			e.printStackTrace();
			Assert.fail(e.getMessage());
		}
	}
}
