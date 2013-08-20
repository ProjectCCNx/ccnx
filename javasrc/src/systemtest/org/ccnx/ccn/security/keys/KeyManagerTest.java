/*
 * A CCNx library test.
 *
 * Copyright (C) 2008-2013 Palo Alto Research Center, Inc.
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

package org.ccnx.ccn.security.keys;


import java.security.PublicKey;
import java.util.Random;

import junit.framework.Assert;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.KeyManager;
import org.ccnx.ccn.SecurityBaseNoCcnd;
import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.impl.CCNFlowControl;
import org.ccnx.ccn.impl.security.crypto.CCNDigestHelper;
import org.ccnx.ccn.impl.security.keys.KeyServer;
import org.ccnx.ccn.impl.security.keys.PublicKeyCache;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.SignedInfo;
import org.ccnx.ccn.utils.Flosser;
import org.junit.Test;


/**
 * Initial test of KeyManager functionality.
 *
 */
public class KeyManagerTest {

	protected static Random _rand = new Random(); // don't need SecureRandom

	static ContentName testprefix = new ContentName("test","pubidtest");
	static ContentName dataprefix = new ContentName(testprefix,"data");

	@Test
	public void testWriteContent() throws Exception {
		Log.info(Log.FAC_TEST, "Starting testWriteContent");

		// insert your preferred way of writing to the repo here
		// I'm actually about to add a bunch of lower-level write stuff
		// for the access control, but that's not in place now.
		Flosser flosser = new Flosser(testprefix);
		CCNHandle thandle = CCNHandle.open();
		CCNFlowControl fc = new CCNFlowControl(testprefix, thandle);

		KeyManager km = KeyManager.getDefaultKeyManager();

		// Important -- make a different key repository, with a separate cache, so when
		// we retrieve we don't pull from our own cache.
		CCNHandle handle = CCNHandle.open(km);
		PublicKeyCache kr = new PublicKeyCache();
		KeyServer ks = new KeyServer(handle);
		for (int i=0; i < SecurityBaseNoCcnd.KEY_COUNT; ++i) {
			ks.serveKey(SecurityBaseNoCcnd.keyLocs[i].name().name(), 
					SecurityBaseNoCcnd.pairs[i].getPublic(), km.getDefaultKeyID(), null);
		}

		Random rand = new Random();
		for (int i=0; i < SecurityBaseNoCcnd.DATA_COUNT_PER_KEY; ++i) {
			byte [] buf = new byte[1024];
			rand.nextBytes(buf);
			byte [] digest = CCNDigestHelper.digest(buf);
			// make the names strings if it's clearer, this allows you to pull the name
			// and compare it to the actual content digest 
			ContentName dataName = new ContentName(dataprefix, digest);
			for (int j=0; j < SecurityBaseNoCcnd.KEY_COUNT; ++j) {
				SignedInfo si = new SignedInfo(SecurityBaseNoCcnd.publishers[j], 
						SecurityBaseNoCcnd.keyLocs[j]);
				ContentObject co = new ContentObject(dataName, si, buf, 
						SecurityBaseNoCcnd.pairs[j].getPrivate());
				Log.info(Log.FAC_TEST, "Key " + j + ": " + SecurityBaseNoCcnd.publishers[j] 
						+ " signed content " + i + ": " + dataName);
				fc.put(co);
			}
		}

		// now we try getting it back..
		CCNHandle retrieveHandle = CCNHandle.open();
		for (int i=0; i < SecurityBaseNoCcnd.KEY_COUNT; ++i) {
			System.out.println("Attempting to retrieive key " + i + ":");
			PublicKey pk = kr.getPublicKey(SecurityBaseNoCcnd.publishers[i], 
					SecurityBaseNoCcnd.keyLocs[i], SystemConfiguration.getDefaultTimeout(), retrieveHandle);
			Assert.assertNotNull(pk);
			Assert.assertEquals(pk, SecurityBaseNoCcnd.pairs[i].getPublic());
		}

		flosser.stop();
		thandle.close();
		
		Log.info(Log.FAC_TEST, "Completed testWriteContent");
	}
}
