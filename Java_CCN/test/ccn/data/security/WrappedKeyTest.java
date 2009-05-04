package test.ccn.data.security;

import static org.junit.Assert.*;

import java.io.UnsupportedEncodingException;

import org.junit.BeforeClass;
import org.junit.Test;

public class WrappedKeyTest {

	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
	}

	@Test
	public void testWrapKey() {
		try {
			System.out.println("Number of bytes in encoded empty string: " + new String("").getBytes("UTF-8").length);
		} catch (UnsupportedEncodingException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	@Test
	public void testWrappedKeyByteArrayStringStringStringByteArrayByteArray() {
		fail("Not yet implemented");
	}

	@Test
	public void testUnwrapKeySecretKeySpec() {
		fail("Not yet implemented");
	}

	@Test
	public void testUnwrapKeySecretKeySpecString() {
		fail("Not yet implemented");
	}

	@Test
	public void testSetWrappingKeyIdentifierByteArray() {
		fail("Not yet implemented");
	}

	@Test
	public void testGetCipherType() {
		fail("Not yet implemented");
	}

	@Test
	public void testWrapAlgorithmForKey() {
		fail("Not yet implemented");
	}

}
