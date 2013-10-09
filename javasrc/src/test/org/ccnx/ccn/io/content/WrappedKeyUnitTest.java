/*
 * A CCNx library test.
 *
 * Copyright (C) 2008, 2009, 2011, 2013 Palo Alto Research Center, Inc.
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

package org.ccnx.ccn.io.content;

import java.security.Key;

import org.bouncycastle.asn1.nist.NISTObjectIdentifiers;
import org.ccnx.ccn.encoding.XMLEncodableTester;
import org.ccnx.ccn.impl.support.Log;
import org.junit.Assert;
import org.junit.Test;


/**
 * Test both encoding/decoding of WrappedKey data structures and writing them
 * to CCN using WrappedKeyObjects. Move tests that require either unlimited-strength
 * crypto or algorithms that BouncyCastle does not support on all platforms/versions
 * to the expanded tests. See apps/examples/ExpandedCryptoTests.
 */
public class WrappedKeyUnitTest extends WrappedKeyTestCommon {

	@Test
	public void testWrapUnwrapKey() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testWrapUnwrapKey");

		// don't use setUpBeforeClass, may not be handling slow initialization well
		setupTest(); 
		// for each wrap case, wrap, unwrap, and make sure it matches.
		// Wrap secret in secret
		Log.info(Log.FAC_TEST, "Wrap secret key in secret key.");
		WrappedKey wks = WrappedKey.wrapKey(wrappedAESKey, null, aLabel, wrappingAESKey);
		Key unwrappedKey = wks.unwrapKey(wrappingAESKey);
		Assert.assertArrayEquals(wrappedAESKey.getEncoded(), unwrappedKey.getEncoded());
		// wrap secret in public			
		Log.info(Log.FAC_TEST, "Wrap secret key in public key.");
		WrappedKey wksp = WrappedKey.wrapKey(wrappedAESKey, null, aLabel, wrappingKeyPair.getPublic());
		unwrappedKey = wksp.unwrapKey(wrappingKeyPair.getPrivate());
		Assert.assertArrayEquals(wrappedAESKey.getEncoded(), unwrappedKey.getEncoded());
		// wrap private in public
		Log.info(Log.FAC_TEST, "Wrap private key in public key.");
		WrappedKey wkpp = WrappedKey.wrapKey(wrappingKeyPair.getPrivate(), null, aLabel, wrappingKeyPair.getPublic());
		unwrappedKey = wkpp.unwrapKey(wrappingKeyPair.getPrivate());
		Assert.assertArrayEquals(wrappingKeyPair.getPrivate().getEncoded(), unwrappedKey.getEncoded());
		// wrap private in secret
		Log.info(Log.FAC_TEST, "Wrap private key in secret key.");
		Log.info(Log.FAC_TEST, "wpk length " + wrappingKeyPair.getPrivate().getEncoded().length);
		WrappedKey wkp = WrappedKey.wrapKey(wrappingKeyPair.getPrivate(), null, aLabel, wrappingAESKey);
		unwrappedKey = wkp.unwrapKey(wrappingAESKey);
		Assert.assertArrayEquals(wrappingKeyPair.getPrivate().getEncoded(), unwrappedKey.getEncoded());
		// ditto for el gamal
		/*
		 * ElGamal encryption requires unlimited strength crypto. This used to be installed
		 * by default on OSX, but not anymore, and not on Ubuntu or Windows. Moved to expanded tests.
		 * 
			wksp = WrappedKey.wrapKey(wrappedAESKey, null, aLabel, wrappingEGKeyPair.getPublic());
			unwrappedKey = wksp.unwrapKey(wrappingEGKeyPair.getPrivate());
			Assert.assertEquals(new BigInteger(1, wrappedAESKey.getEncoded()), new BigInteger(1, unwrappedKey.getEncoded()));
			wkpp = WrappedKey.wrapKey(wrappingEGKeyPair.getPrivate(), null, aLabel, wrappingKeyPair.getPublic());
			unwrappedKey = wkpp.unwrapKey(wrappingKeyPair.getPrivate());
			Assert.assertArrayEquals(wrappingEGKeyPair.getPrivate().getEncoded(), unwrappedKey.getEncoded());
		 */		
		// wrap DSA private in public key
		Log.info(Log.FAC_TEST, "Wrap DSA private in private.");
		wkpp = WrappedKey.wrapKey(wrappedDSAKeyPair.getPrivate(), null, aLabel, wrappingKeyPair.getPublic());
		unwrappedKey = wkpp.unwrapKey(wrappingKeyPair.getPrivate());
		Assert.assertArrayEquals(wrappedDSAKeyPair.getPrivate().getEncoded(), unwrappedKey.getEncoded());
		Log.info(Log.FAC_TEST, "Wrap DSA private in secret.");
		wkp = WrappedKey.wrapKey(wrappedDSAKeyPair.getPrivate(), null, aLabel, wrappingAESKey);
		unwrappedKey = wkp.unwrapKey(wrappingAESKey);
		Assert.assertArrayEquals(wrappedDSAKeyPair.getPrivate().getEncoded(), unwrappedKey.getEncoded());
		
		// wrap DH private in public key
		Log.info(Log.FAC_TEST, "Wrap DH private in private.");
		wkpp = WrappedKey.wrapKey(wrappedDHKeyPair.getPrivate(), null, aLabel, wrappingKeyPair.getPublic());
		unwrappedKey = wkpp.unwrapKey(wrappingKeyPair.getPrivate());
		Assert.assertArrayEquals(wrappedDHKeyPair.getPrivate().getEncoded(), unwrappedKey.getEncoded());
		Log.info(Log.FAC_TEST, "Wrap DH private in secret.");
		wkp = WrappedKey.wrapKey(wrappedDHKeyPair.getPrivate(), null, aLabel, wrappingAESKey);
		unwrappedKey = wkp.unwrapKey(wrappingAESKey);
		Assert.assertArrayEquals(wrappedDHKeyPair.getPrivate().getEncoded(), unwrappedKey.getEncoded());
		
		Log.info(Log.FAC_TEST, "Completed testWrapUnwrapKey");
	}

	@Test
	public void testWrappedKeyByteArrayStringStringStringByteArrayByteArray() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testWrappedKeyByteArrayStringStringStringByteArrayByteArray");

		// don't use setUpBeforeClass, may not be handling slow initialization well
		setupTest(); 
		WrappedKey wka = null;
		wka = WrappedKey.wrapKey(wrappedAESKey, null, aLabel, 
				wrappingKeyPair.getPublic());
		WrappedKey wk2 = new WrappedKey(wrappingKeyID,
				WrappedKey.wrapAlgorithmForKey(wrappingKeyPair.getPublic().getAlgorithm()),
				wrappedAESKey.getAlgorithm(),
				aLabel,
				wka.encryptedNonceKey(),
				wka.encryptedKey());

		WrappedKey dwk = new WrappedKey();
		WrappedKey bdwk = new WrappedKey();
		XMLEncodableTester.encodeDecodeTest("WrappedKey(full)", wk2, dwk, bdwk);
		wka.setWrappingKeyIdentifier(wrappingKeyID);
		
		Log.info(Log.FAC_TEST, "Completed testWrappedKeyByteArrayStringStringStringByteArrayByteArray");
	}
	
	@Test
	public void testDecodeInputStream() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testDecodeInputStream");

		// don't use setUpBeforeClass, may not be handling slow initialization well
		setupTest(); 
		WrappedKey wk = new WrappedKey(wrappingKeyID, dummyWrappedKey);
		WrappedKey dwk = new WrappedKey();
		WrappedKey bdwk = new WrappedKey();
		XMLEncodableTester.encodeDecodeTest("WrappedKey(dummy)", wk, dwk, bdwk);

		WrappedKey wks = WrappedKey.wrapKey(wrappedAESKey, null, aLabel, wrappingAESKey);

		WrappedKey dwks = new WrappedKey();
		WrappedKey bdwks = new WrappedKey();
		XMLEncodableTester.encodeDecodeTest("WrappedKey(symmetric, real)", wks, dwks, bdwks);

		WrappedKey wka = WrappedKey.wrapKey(wrappedAESKey, NISTObjectIdentifiers.id_aes128_CBC.toString(), 
										aLabel, wrappingKeyPair.getPublic());

		wka.setWrappingKeyIdentifier(wrappingKeyID);
		wka.setWrappingKeyName(wrappingKeyName);
		WrappedKey dwka = new WrappedKey();
		WrappedKey bdwka = new WrappedKey();
		XMLEncodableTester.encodeDecodeTest("WrappedKey(assymmetric wrap symmetric, with id and name)", wka, dwka, bdwka);
		Assert.assertArrayEquals(dwka.wrappingKeyIdentifier(), wrappingKeyID);
		
		Log.info(Log.FAC_TEST, "Completed testDecodeInputStream");
	}
}
