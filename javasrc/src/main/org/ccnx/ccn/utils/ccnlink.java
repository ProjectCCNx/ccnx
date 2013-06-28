/*
 * A CCNx command line utility.
 *
 * Copyright (C) 2010, 2012 Palo Alto Research Center, Inc.
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

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.impl.CCNFlowControl.SaveType;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.impl.support.Tuple;
import org.ccnx.ccn.io.content.Link;
import org.ccnx.ccn.io.content.Link.LinkObject;
import org.ccnx.ccn.protocol.ContentName;

/**
 * Command line utility for making links. Currently does not take authenticator
 * information, just target name.
 * TODO add ability to specify authenticators
 */
public class ccnlink {

	public static void usage(String extraUsage) {
		System.err.println("usage: ccnlink " + extraUsage + "[-q] [-r] <link uri> <link target uri> [-as <pathToKeystore> [-name <friendly name]] (-q == quiet, -r == raw)");
	}
	/**
	 * @param args
	 */
	public static void main(String[] args) {
		String extraUsage = "";
		Log.setDefaultLevel(Level.WARNING);

		try {

			int offset = 0;
			SaveType type = SaveType.REPOSITORY;

			for (int i = 0; i < args.length; i++) {
				if (i == 0 && args[0].startsWith("[")) {
					extraUsage = args[0];
					offset++;
					continue;
				} else if (args[i].equals("-h")) {
					usage(extraUsage);
					System.exit(0);
				} else if (!args[i].startsWith("-"))
					break;
				if (args[i].equals("-q")) {
					Log.setDefaultLevel(Level.WARNING);
					offset++;
				}

				if (args[i].equals("-r")) {
					type = SaveType.RAW;
					offset++;
				}
			}

			if (args.length-offset < 2) {
				usage(extraUsage);
				System.exit(1);
			}

			boolean hasAs = false;
			if (args.length - offset > 2) {
				if (args[offset + 2].equals("-as"))
					hasAs = true;
				else {
					usage(extraUsage);
					System.exit(1);
				}
			}

			ContentName linkName = ContentName.fromURI(args[offset++]);
			ContentName targetName = ContentName.fromURI(args[offset++]);

			Tuple<Integer, CCNHandle> tuple = null;

			if (hasAs) {
				tuple = CreateUserData.handleAs(args, offset);
				if (null == tuple) {
					usage(extraUsage);
					System.exit(1);
				}
			}

			// Can also use command line system properties and environment variables to
			// point this handle to the correct user.
			CCNHandle handle = ((null == tuple) || (null == tuple.second())) ? CCNHandle.getHandle() : tuple.second();

			LinkObject theLink = new LinkObject(linkName, new Link(targetName), type, handle);
			theLink.save();
			theLink.close();

			System.out.println("Created link: " + theLink);

			handle.close();

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
