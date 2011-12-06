/*
 * A CCNx command line utility.
 *
 * Copyright (C) 2010 Palo Alto Research Center, Inc.
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

package org.ccnx.ccn.utils;

import java.util.logging.Level;

import org.ccnx.ccn.KeyManager;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.impl.support.Tuple;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.KeyLocator;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;

/**
 * Command line utility for modifying stored identity data. Use ccnwhoami to see what
 * key ids you have, and their currently associated default identities. Use this tool
 * to add an identity to a key that doesn't have one, or replace an existing identity.
 * Identities are used as the key locators used when signing with these keys. This tool
 * allows only specification of NAME type key locators, as ccnx URIs. 
 * To find a keyid to use, use ccnwhoami.
 * 
 * NOTE: We don't actually expect users to use this tool to modify their identities; their
 * identities (even in the simple sense in which we use that term now) will usually be managed
 * by software and users never see them. This tool is for testing and debugging.
 */
public class ccnaddidentity {

	public static void usage() {
		System.err.println("usage: ccnaddidentity [-q] keyid id_as_ccnx_uri [-as <pathToKeystore> [-name <friendly name]] (-q == quiet)");
	}
	/**
	 * @param args
	 */
	public static void main(String[] args) {
		try {
			
			if ((args.length >= 1) && (args[0].equals("-h"))) {
				usage();
				return;
			}
			
			int offset = 0;
			if ((args.length >= 1) && (args[0].equals("-q"))) {
				Log.setDefaultLevel(Level.WARNING);
				offset++;
			}

			if ((args.length-offset < 2) || (args.length-offset > 6)) {
				usage();
				return;
			}
			
			String keyidstring = args[offset++];
			String uristring = args[offset++];

			Tuple<Integer, KeyManager> tuple = CreateUserData.keyManagerAs(args, offset);

			// Can also use command line system properties and environment variables to
			// point this handle to the correct user.
			KeyManager km = ((null == tuple) || (null == tuple.second())) ? KeyManager.getDefaultKeyManager() : tuple.second();

			// Depends on toString() <-> PublisherPublicKeyDigest(String) transformation be the identity
			PublisherPublicKeyDigest ppkd = new PublisherPublicKeyDigest(keyidstring);
			
			ContentName keyName = ContentName.fromURI(uristring);
			KeyLocator kl = new KeyLocator(keyName);
			
			// TODO should check to see if keyid is for one of our keys.
			km.setKeyLocator(ppkd, kl);
			km.saveConfigurationState();
			
			km.close();
			KeyManager.closeDefaultKeyManager();

		} catch (Exception e) {
			handleException("Error: cannot initialize device. ", e);
			System.exit(-3);
		}
	}

	protected static void handleException(String message, Exception e) {
		Log.warning(message + " Exception: " + e.getClass().getName() + ": " + e.getMessage());
		Log.warningStackTrace(e);
	}
}
