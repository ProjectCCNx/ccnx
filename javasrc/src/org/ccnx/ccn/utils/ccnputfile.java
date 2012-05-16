/*
 * A CCNx command line utility.
 *
 * Copyright (C) 2008-2012 Palo Alto Research Center, Inc.
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
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;

/**
 * Command-line utility to write a file to ccnd; requires a corresponding ccngetfile
 * to pull the data or it will not move (flow balance).
 **/
 public class ccnputfile extends CommonOutput implements Usage {
	 static ccnputfile ccnputfile = new ccnputfile();

	/**
	 * @param args
	 */
	public void write(String[] args) {
		Log.setDefaultLevel(Level.WARNING);

		for (int i = 0; i < args.length; i++) {
			if (CommonArguments.parseArguments(args, i, ccnputfile)) {
				i = CommonParameters.startArg;
				continue;
			}
			if ((i + 2) >= args.length) {
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

		if (args.length < CommonParameters.startArg + 2) {
			usage(CommonArguments.getExtraUsage());
		}

		long starttime = System.currentTimeMillis();
		try {
			// If we get one file name, put as the specific name given.
			// If we get more than one, put underneath the first as parent.
			// Ideally want to use newVersion to get latest version. Start
			// with random version.
			ContentName argName = ContentName.fromURI(args[CommonParameters.startArg]);

			CCNHandle handle = CCNHandle.open();

			if (args.length == (CommonParameters.startArg + 2)) {
				if (CommonParameters.verbose)
					Log.info("ccnputfile: putting file " + args[CommonParameters.startArg + 1]);

				doPut(handle, args[CommonParameters.startArg + 1], argName);
				System.out.println("Inserted file " + args[CommonParameters.startArg + 1] + ".");
				if (CommonParameters.verbose)
					System.out.println("ccnputfile took: "+(System.currentTimeMillis() - starttime)+" ms");
				System.exit(0);
			} else {
				for (int i=CommonParameters.startArg + 1; i < args.length; ++i) {

					// put as child of name
					ContentName nodeName = new ContentName(argName, args[i]);

					doPut(handle, args[i], nodeName);
					// leave this one as always printing for now
					System.out.println("Inserted file " + args[i] + ".");
				}
				if (CommonParameters.verbose)
					System.out.println("ccnputfile took: "+(System.currentTimeMillis() - starttime)+" ms");
				System.exit(0);
			}
		} catch (ConfigurationException e) {
			System.out.println("Configuration exception in put: " + e.getMessage());
			e.printStackTrace();
		} catch (MalformedContentNameStringException e) {
			System.out.println("Malformed name: " + args[CommonParameters.startArg] + " " + e.getMessage());
			e.printStackTrace();
		} catch (IOException e) {
			System.out.println("Cannot put file. " + e.getMessage());
			e.printStackTrace();
		} catch (InvalidKeyException e) {
			System.out.println("Cannot publish invalid key: " + e.getMessage());
			e.printStackTrace();
		}
		System.exit(1);

	}

	@Override
	public void usage(String extraUsage) {
		System.out.println("usage: ccnputfile " + extraUsage + "[-v (verbose)] [-raw] [-unversioned] [-local | -allownonlocal] [-timeout millis] [-log level] [-as pathToKeystore] [-ac (access control)] <ccnname> (<filename>|<url>)*");
		System.exit(1);
	}

	public static void main(String[] args) {
		ccnputfile.write(args);
	}
}
