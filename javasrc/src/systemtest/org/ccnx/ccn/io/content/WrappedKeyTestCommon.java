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

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Security;

import javax.crypto.spec.SecretKeySpec;

import org.ccnx.ccn.CCNTestHelper;
import org.ccnx.ccn.KeyManager;
import org.ccnx.ccn.impl.security.crypto.jce.CCNCryptoProvider;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.profiles.security.access.group.GroupAccessControlProfile;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.PublisherID;
import org.junit.BeforeClass;

public class WrappedKeyTestCommon {
	
	public static boolean setupDone = false;
	public static KeyPair wrappingKeyPair = null;
	public static KeyPair wrappedKeyPair = null;
	public static KeyPair wrappedDHKeyPair = null;
	public static KeyPair wrappedDSAKeyPair = null;
	public static SecretKeySpec wrappingAESKey = null;
	public static SecretKeySpec wrappedAESKey = null;
	public static String aLabel = "FileEncryptionKeys";
	public static byte [] wrappingKeyID = null;
	public static ContentName wrappingKeyName = null;
	public static ContentName storedKeyName = null;
	public static byte [] dummyWrappedKey = new byte[64];
	
	public static CCNTestHelper testHelper = new CCNTestHelper(PublicKeyObjectTestRepo.class);

	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		Security.addProvider(new CCNCryptoProvider());
	}
	
	/**
	 * Do this in the first test. Were doing it in setupBeforeClass, but I think
	 * it was failing sometimes, possibly because it was too slow.
	 * @throws Exception
	 */
	public void setupTest() throws Exception {
		if (setupDone) {
			return;
		}
		
		Security.addProvider(KeyManager.PROVIDER);
		
		SecureRandom sr = new SecureRandom();
		sr.nextBytes(dummyWrappedKey);
		
		// generate key pair
		KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
		kpg.initialize(1024); // go for fast
		wrappingKeyPair = kpg.generateKeyPair();
		wrappedKeyPair = kpg.generateKeyPair();
		wrappingKeyID = PublisherID.generatePublicKeyDigest(wrappingKeyPair.getPublic());
		wrappingKeyName = VersioningProfile.addVersion(ContentName.fromNative("/parc/Users/briggs/KEY"));
				
		kpg = KeyPairGenerator.getInstance("DSA");
        kpg.initialize(1024);
        wrappedDSAKeyPair = kpg.genKeyPair();
    
        // Generate a 576-bit DH key pair
        kpg = KeyPairGenerator.getInstance("DH");
        kpg.initialize(576);
        wrappedDHKeyPair = kpg.genKeyPair();

        byte [] key = new byte[16];
		sr.nextBytes(key);
		wrappingAESKey = new SecretKeySpec(key, "AES");
		sr.nextBytes(key);
		wrappedAESKey = new SecretKeySpec(key, "AES");
		
		ContentName nodeName = testHelper.getClassNamespace().append(
				ContentName.fromNative("/test/content/File1.txt"));
		
		storedKeyName = GroupAccessControlProfile.nodeKeyName(nodeName);
		setupDone = true;
		Log.info(Log.FAC_TEST, "Initialized keys for WrappedKeyTest");		
	}
}
