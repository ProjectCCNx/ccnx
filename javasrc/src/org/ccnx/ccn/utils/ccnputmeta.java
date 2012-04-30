/*
 * A CCNx command line utility.
 *
 * Copyright (C) 2008, 2009, 2011, 2012 Palo Alto Research Center, Inc.
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

import java.io.IOException;
import java.security.InvalidKeyException;
import java.util.logging.Level;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.profiles.metadata.MetadataProfile;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;

/**
 * Command-line utility to write metadata associated with an existing file in ccnd. The "metaname" should
 * be the relative path (including filename) for the desired metadata only.
 * By default this writes to the repo. Otherwise there must be a corresponding ccngetfile to retrieve
 * the data.
 **/
 public class ccnputmeta extends CommonOutput implements Usage {
	 static ccnputmeta ccnputmeta = new ccnputmeta();

	/**
	 * @param args
	 */
	public void write(String[] args) {
		Log.setDefaultLevel(Level.WARNING);
		for (int i = 0; i < args.length; i++) {
			if (CommonArguments.parseArguments(args, i, ccnputmeta)) {
				i = CommonParameters.startArg;
				continue;
			}
			if ((i + 3) >= args.length) {
				CommonParameters.startArg = i;
				break;
			}
			if (args[i].equals("-local")) {
				CommonParameters.local = true;
			} else if (args[i].equals(("-allownonlocal"))) {
				CommonParameters.local = false;
			} else if (args[i].equals(("-raw"))) {
				CommonParameters.rawMode = true;
			} else
				usage(CommonArguments.getExtraUsage());
		}

		if (args.length != CommonParameters.startArg + 3) {
			usage(CommonArguments.getExtraUsage());
		}

		long starttime = System.currentTimeMillis();
		try {
			// If we get one file name, put as the specific name given.
			// If we get more than one, put underneath the first as parent.
			// Ideally want to use newVersion to get latest version. Start
			// with random version.

			ContentName baseName = ContentName.fromURI(args[CommonParameters.startArg]);
			String metaArg = args[CommonParameters.startArg + 1];
			if (!metaArg.startsWith("/"))
				metaArg = "/" + metaArg;
			ContentName metaPath = ContentName.fromURI(metaArg);
			CCNHandle handle = CCNHandle.open();
			ContentName prevFileName = MetadataProfile.getLatestVersion(baseName, metaPath, CommonParameters.timeout, handle);
			if (null == prevFileName) {
				System.out.println("File " + baseName + " does not exist");
				System.exit(1);
			}
			ContentName fileName = VersioningProfile.updateVersion(prevFileName);
			if (CommonParameters.verbose)
				Log.info("ccnputmeta: putting metadata file " + args[CommonParameters.startArg + 1]);

			doPut(handle, args[CommonParameters.startArg + 2], fileName);
			System.out.println("Inserted metadata file: " + args[CommonParameters.startArg + 1] + " for file: " + args[CommonParameters.startArg] + ".");
			if (CommonParameters.verbose)
				System.out.println("ccnputmeta took: "+(System.currentTimeMillis() - starttime)+" ms");
			System.exit(0);
		} catch (ConfigurationException e) {
			System.out.println("Configuration exception in put: " + e.getMessage());
			e.printStackTrace();
		} catch (MalformedContentNameStringException e) {
			System.out.println("Malformed name: " + args[CommonParameters.startArg] + " " + e.getMessage());
			e.printStackTrace();
		} catch (IOException e) {
			System.out.println("Cannot put metadata file. " + e.getMessage());
			e.printStackTrace();
		} catch (InvalidKeyException e) {
			System.out.println("Cannot publish invalid key: " + e.getMessage());
			e.printStackTrace();
		}
		System.exit(1);

	}

	@Override
	public void usage(String extraUsage) {
		System.out.println("usage: ccnputmeta " + extraUsage + "[-v (verbose)] [-raw] [-unversioned] [-local | -allownonlocal] [-timeout millis] [-log level] [-as pathToKeystore] [-ac (access control)] <ccnname> <metaname> (<filename>|<url>)*");
		System.exit(1);
	}

	public static void main(String[] args) {
		new ccnputmeta().write(args);
	}
}
