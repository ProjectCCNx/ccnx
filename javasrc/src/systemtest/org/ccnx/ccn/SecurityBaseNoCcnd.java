/*
 * A CCNx library test.
 *
 * Copyright (C) 2013 Palo Alto Research Center, Inc.
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
package org.ccnx.ccn;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.util.logging.Level;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.KeyLocator;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;

public final class SecurityBaseNoCcnd {
	public static final int KEY_COUNT = 5;
	public static final int DATA_COUNT_PER_KEY = 3;
	public static final KeyPair [] pairs = new KeyPair[KEY_COUNT];
	static ContentName testprefix = new ContentName("test","pubidtest");
	static ContentName keyprefix = new ContentName(testprefix,"keys");

	public static final PublisherPublicKeyDigest [] publishers = new PublisherPublicKeyDigest[KEY_COUNT];
	public static final KeyLocator [] keyLocs = new KeyLocator[KEY_COUNT];
	
	static  {
		try {
			Security.addProvider(new BouncyCastleProvider());

			// generate key pair
			KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
			kpg.initialize(512); // go for fast
			for (int i=0; i < KEY_COUNT; ++i) {
				pairs[i] = kpg.generateKeyPair();
				publishers[i] = new PublisherPublicKeyDigest(pairs[i].getPublic());
				keyLocs[i] = new KeyLocator(new ContentName(keyprefix, publishers[i].digest()));
			}
		} catch (Exception e) {
			Log.logStackTrace(Log.FAC_TEST, Level.WARNING, e);
		}
	}
}
