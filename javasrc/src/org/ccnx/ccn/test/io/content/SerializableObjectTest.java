/*
 * A CCNx library test.
 *
 * Copyright (C) 2008, 2009, 2011 Palo Alto Research Center, Inc.
 *
 * This work is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License version 2 as published by the
 * Free Software Foundation. 
 * This work is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License
 * for more details. You should have received a copy of the GNU General Public
 * License along with this program; if not, write to the
 * Free Software Foundation, Inc., 51 Franklin Street, Fifth Floor,
 * Boston, MA 02110-1301, USA.
 */

package org.ccnx.ccn.test.io.content;

import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;

import org.bouncycastle.util.Arrays;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.ContentNotReadyException;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Test low level (non-CCN) SerializableObject functionality, backing objects to ByteArrayOutputStreams.
 **/
public class SerializableObjectTest {
	
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
		Log.info(Log.FAC_TEST, "Starting testSave");

		SerializablePublicKey spk1 = new SerializablePublicKey(kp1.getPublic());
		SerializablePublicKey spk2 = new SerializablePublicKey(kp1.getPublic());
		SerializablePublicKey spk3 = new SerializablePublicKey(kp2.getPublic());

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
		
		Log.info(Log.FAC_TEST, "Completed testSave");
	}
	
	@Test
	public void testUpdate() {
		Log.info(Log.FAC_TEST, "Starting testUpdate");

		boolean caught = false;
		SerializablePublicKey empty = new SerializablePublicKey();
		try {
			empty.publicKey();
		} catch (ContentNotReadyException iox) {
			// this is what we expect to happen
			caught = true;
		} catch (IOException ie) {
			Assert.fail("Unexpectd IOException!");
		}
		Assert.assertTrue("Failed to produce expected exception.", caught);

		SerializablePublicKey spk1 = new SerializablePublicKey(kp1.getPublic());
		SerializablePublicKey spk2 = new SerializablePublicKey();
		SerializablePublicKey spk3 = new SerializablePublicKey(kp2.getPublic());

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
		}
		
		Log.info(Log.FAC_TEST, "Completed testUpdate");
	}
}
