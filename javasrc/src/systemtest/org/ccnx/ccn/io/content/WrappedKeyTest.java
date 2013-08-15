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

import org.bouncycastle.asn1.nist.NISTObjectIdentifiers;
import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.impl.CCNFlowControl.SaveType;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.WrappedKey.WrappedKeyObject;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.utils.Flosser;
import org.junit.Assert;
import org.junit.Test;


/**
 * Test both encoding/decoding of WrappedKey data structures and writing them
 * to CCN using WrappedKeyObjects. Move tests that require either unlimited-strength
 * crypto or algorithms that BouncyCastle does not support on all platforms/versions
 * to the expanded tests. See apps/examples/ExpandedCryptoTests.
 */
public class WrappedKeyTest extends WrappedKeyTestCommon {
	
	@Test
	public void testWrappedKeyObject() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testWrappedKeyObject");

		// don't use setUpBeforeClass, may not be handling slow initialization well
		setupTest(); 
		
		WrappedKey wks = WrappedKey.wrapKey(wrappedAESKey, null, aLabel, wrappingAESKey);
		WrappedKey wka = WrappedKey.wrapKey(wrappedAESKey, NISTObjectIdentifiers.id_aes128_CBC.toString(), 
										aLabel, wrappingKeyPair.getPublic());
		wka.setWrappingKeyIdentifier(wrappingKeyID);
		wka.setWrappingKeyName(wrappingKeyName);
		CCNHandle thandle = CCNHandle.open();
		CCNHandle thandle2 = CCNHandle.open();
		
		Flosser flosser = null;
		try {
			flosser = new Flosser();
			flosser.handleNamespace(storedKeyName);
			WrappedKeyObject wko = 
				new WrappedKeyObject(storedKeyName, wks, SaveType.RAW, thandle);
			wko.save();
			Assert.assertTrue(VersioningProfile.hasTerminalVersion(wko.getVersionedName()));
			// should update in another thread
			WrappedKeyObject wkoread = new WrappedKeyObject(storedKeyName, thandle2);
			Assert.assertTrue(wkoread.available());
			Assert.assertEquals(wkoread.getVersionedName(), wko.getVersionedName());
			Assert.assertEquals(wkoread.wrappedKey(), wko.wrappedKey());
			// DKS -- bug in interest handling, can't save wkoread and update wko
			wko.save(wka);
			Assert.assertTrue(VersioningProfile.isLaterVersionOf(wko.getVersionedName(), wkoread.getVersionedName()));
			wkoread.update();
			Assert.assertEquals(wkoread.getVersionedName(), wko.getVersionedName());
			Assert.assertEquals(wkoread.wrappedKey(), wko.wrappedKey());
			Assert.assertEquals(wko.wrappedKey(), wka);
		} finally {
			if (null != flosser) {
				Log.info(Log.FAC_TEST, "WrappedKeyTest: Stopping flosser.");
				flosser.stop();
				Log.info(Log.FAC_TEST, "WrappedKeyTest: flosser stopped.");
			}
			thandle.close();
			thandle2.close();
		}
		
		Log.info(Log.FAC_TEST, "Completed testWrappedKeyObject");
	}

}
