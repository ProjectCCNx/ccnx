package test.ccn.data.util;

import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InvalidObjectException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;

import org.bouncycastle.util.Arrays;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import com.parc.ccn.data.util.NullOutputStream;

public class SerializedObjectTest {
	
	static KeyPair kp1 = null;
	static KeyPair kp2 = null;

	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
		kpg.initialize(512); // go for fast
		kp1 = kpg.generateKeyPair();
		kp2 = kpg.generateKeyPair();
	}

	@Test
	public void testSave() {
		boolean caught = false;
		SerializedPublicKey empty = new SerializedPublicKey();
		try {
			NullOutputStream nos = new NullOutputStream();
			empty.save(nos);
		} catch (InvalidObjectException iox) {
			// this is what we expect to happen
			caught = true;
		} catch (IOException ie) {
			Assert.fail("Unexpectd IOException!");
		}
		Assert.assertTrue("Failed to produce expected exception.", caught);
		
		SerializedPublicKey spk1 = new SerializedPublicKey(kp1.getPublic());
		SerializedPublicKey spk2 = new SerializedPublicKey(kp1.getPublic());
		SerializedPublicKey spk3 = new SerializedPublicKey(kp2.getPublic());

		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		ByteArrayOutputStream baos2 = new ByteArrayOutputStream();
		ByteArrayOutputStream baos3 = new ByteArrayOutputStream();

		try {
			spk1.save(baos);
			spk2.save(baos2); // will this save? currently not, should it?
			Assert.assertArrayEquals("Serializing two versions of same content should produce same output",
					baos.toByteArray(), baos2.toByteArray());
			spk3.save(baos3);
			boolean be = Arrays.areEqual(baos.toByteArray(), baos3.toByteArray());
			Assert.assertFalse("Two different objects shouldn't have matching output.", be);
			System.out.println("Saved two public keys, lengths " + baos.toByteArray().length + " and " + baos3.toByteArray().length);
		} catch (IOException e) {
			fail("IOException! " + e.getMessage());
		}
	}
	
	@Test
	public void testUpdate() {
		SerializedPublicKey spk1 = new SerializedPublicKey(kp1.getPublic());
		SerializedPublicKey spk2 = new SerializedPublicKey();
		SerializedPublicKey spk3 = new SerializedPublicKey(kp2.getPublic());

		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		ByteArrayOutputStream baos3 = new ByteArrayOutputStream();

		try {
			spk1.save(baos);
			ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
			spk2.update(bais); // will this save? currently not, should it?
			Assert.assertEquals("Writing content out and back in again should give matching object.",
					spk1, spk2);
			spk3.save(baos3);
			boolean be = Arrays.areEqual(baos.toByteArray(), baos3.toByteArray());
			Assert.assertFalse("Two different objects shouldn't have matching output.", be);
			System.out.println("Saved two public keys, lengths " + baos.toByteArray().length + " and " + baos3.toByteArray().length);
		} catch (IOException e) {
			fail("IOException! " + e.getMessage());
		} catch (ClassNotFoundException e) {
			fail("ClassNotFoundException! " + e.getMessage());
		}
	}



}
