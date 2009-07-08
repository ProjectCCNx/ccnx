package test.ccn.security.access;

import java.security.SecureRandom;
import java.util.Arrays;

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import com.parc.ccn.data.ContentName;
import com.parc.ccn.library.profiles.AccessControlProfile;
import com.parc.ccn.library.profiles.VersioningProfile;
import com.parc.ccn.security.access.NodeKey;

public class NodeKeyTest {
	static ContentName testPrefix = null;
	static ContentName nodeKeyPrefix = null;
	static ContentName descendantNodeName1 = null;
	static ContentName descendantNodeName2 = null;
	static ContentName descendantNodeName3 = null;
	
	static NodeKey testNodeKey = null;
	static NodeKey descendantNodeKey1 = null;
	static NodeKey descendantNodeKey2 = null;
	static NodeKey descendantNodeKey3 = null;
	
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		testPrefix = ContentName.fromNative("/parc/test/content/");
		nodeKeyPrefix = AccessControlProfile.nodeKeyName(testPrefix);
		nodeKeyPrefix = VersioningProfile.versionName(nodeKeyPrefix);

		SecureRandom sr = new SecureRandom();
		byte [] key = new byte[NodeKey.DEFAULT_NODE_KEY_LENGTH];
		sr.nextBytes(key);

		testNodeKey = new NodeKey(nodeKeyPrefix, key);
		System.out.println("created node key, name =" + testNodeKey.nodeName());

		descendantNodeName1 = ContentName.fromNative(testPrefix, "level1");
		descendantNodeName2 = ContentName.fromNative(descendantNodeName1, "level2");

		descendantNodeKey2 = testNodeKey.computeDescendantNodeKey(descendantNodeName2);
		descendantNodeKey1 = testNodeKey.computeDescendantNodeKey(descendantNodeName1);

		descendantNodeKey3 = descendantNodeKey1.computeDescendantNodeKey(descendantNodeName2);
	}

	@Test
	public void testComputeDescendantNodeKey() throws Exception {		
		byte[] aKeyBytes = descendantNodeKey3.nodeKey().getEncoded();
		byte[] bKeyBytes = descendantNodeKey2.nodeKey().getEncoded();

		System.out.println(Arrays.hashCode(aKeyBytes));
		System.out.println(Arrays.hashCode(bKeyBytes));

		System.out.println(testNodeKey.nodeKeyVersion());
		System.out.println(descendantNodeKey1.nodeKeyVersion());
		System.out.println(descendantNodeKey2.nodeKeyVersion());
		System.out.println(descendantNodeKey3.nodeKeyVersion());

		System.out.println(testNodeKey.storedNodeKeyName());
		System.out.println(descendantNodeKey1.storedNodeKeyName());
		System.out.println(descendantNodeKey2.storedNodeKeyName());
		System.out.println(descendantNodeKey3.storedNodeKeyName());

		System.out.println(Arrays.hashCode(testNodeKey.generateKeyID()));
		System.out.println(Arrays.hashCode(descendantNodeKey1.generateKeyID()));
		System.out.println(Arrays.hashCode(descendantNodeKey2.generateKeyID()));
		System.out.println(Arrays.hashCode(descendantNodeKey3.generateKeyID()));

		System.out.println(Arrays.hashCode(testNodeKey.storedNodeKeyID()));
		System.out.println(Arrays.hashCode(descendantNodeKey1.storedNodeKeyID()));
		System.out.println(Arrays.hashCode(descendantNodeKey2.storedNodeKeyID()));
		System.out.println(Arrays.hashCode(descendantNodeKey3.storedNodeKeyID()));

		Assert.assertArrayEquals(aKeyBytes, bKeyBytes);
	}

	@Test
	public void testIsDerived(){
		Assert.assertFalse(testNodeKey.isDerivedNodeKey());
		Assert.assertTrue(descendantNodeKey1.isDerivedNodeKey());
		Assert.assertTrue(descendantNodeKey2.isDerivedNodeKey());
		Assert.assertTrue(descendantNodeKey3.isDerivedNodeKey());
	}
}
