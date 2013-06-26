/*
 * A CCNx command line utility.
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

package org.ccnx.ccn.utils;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.security.Key;
import java.security.NoSuchAlgorithmException;
import java.util.logging.Level;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.ccnx.ccn.KeyManager;
import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.impl.security.keystore.AESKeyStoreSpi;
import org.ccnx.ccn.impl.support.DataUtils;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;

/**
 * Command line utility to write and read a symmetric keystore file for testing
 **/
 public class ccnkeystoretool extends CommonOutput implements Usage {
	 static final int KEYSIZE = 256/8;
	 static ccnkeystoretool createkeystore = new ccnkeystoretool();
	 static String[] okArgs = {"-log", "-v"};

	/**
	 * @param args
	 */
	public void keytool(String[] args) {
		Log.setDefaultLevel(Level.WARNING);
		boolean readMode = false;
		String data = null;
		String password = null;
		String digest = null;

		for (int i = 0; i < args.length; i++) {
			if (CommonArguments.parseArguments(args, i, createkeystore, okArgs)) {
				i = CommonParameters.startArg;
				continue;
			}
			if (args[i].equals("-data")) {
				data = args[i + 1];
				CommonParameters.startArg = ++i;
			} else if (args[i].equals("-password")) {
				password = args[i + 1];
				CommonParameters.startArg = ++i;
			} else if (args[i].equals("-read")) {
				readMode = true;
			} else if (args[i].equals("-digest")) {
				digest = args[i + 1];
				CommonParameters.startArg = ++i;
			} else
				usage(CommonArguments.getExtraUsage());
		}

		if (args.length < CommonParameters.startArg) {
			usage(CommonArguments.getExtraUsage());
		}
		
		KeyManager km = KeyManager.getDefaultKeyManager();
		if (readMode) {
			if (null == digest)
				usage(CommonArguments.getExtraUsage());
			try {
				PublisherPublicKeyDigest ppkd = KeyManager.keyStoreToDigest(SystemConfiguration.KEYSTORE_NAMING_VERSION, digest);
				Key key = km.getVerificationKey(ppkd, null, AESKeyStoreSpi.TYPE, null, password, 
						SystemConfiguration.NO_TIMEOUT);
				if (null == key)
					System.out.println("Couldn't get the key for: " + digest);
				else {
					System.out.println("Retrieved key: 0x" +  DataUtils.printHexBytes(((SecretKey)key).getEncoded()));
				}
			} catch (FileNotFoundException fnfe) {
				System.out.println("Can't find key file for digest: " + digest);
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		} else {
			try {
				SecretKey sk = null;
				if (null == data) {
					KeyGenerator kg = KeyGenerator.getInstance("HMAC-SHA256", KeyManager.PROVIDER);
					sk = kg.generateKey();
				} else {
					byte[] dbytes = data.getBytes();
					byte[] keyBytes = new byte[KEYSIZE];
					int len = dbytes.length > KEYSIZE ? KEYSIZE : dbytes.length;
					System.arraycopy(dbytes, 0, keyBytes, 0, len);
					if (len < KEYSIZE)
						for (int i = len; i < KEYSIZE; i++)
							keyBytes[i] = 0;
					sk = new SecretKeySpec(keyBytes, "HMAC-SHA256");
				}
				km.saveVerificationKey(sk, null, null, null, password);
				System.out.println("Stored key successfully");
			} catch (NoSuchAlgorithmException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}

		System.exit(1);

	}

	@Override
	public void usage(String extraUsage) {
		System.out.println("usage: ccncreatekeystore " + extraUsage + "[-v (verbose)] [-log level] [-data <dataforkey>]"
					+ "\n\t\t [-password <password>] [-read -digest <digest>]");
		System.exit(1);
	}

	public static void main(String[] args) {
		createkeystore.keytool(args);
	}
}
