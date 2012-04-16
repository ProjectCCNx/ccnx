/*
 * A CCNx command line utility.
 *
 * Copyright (C) 2011, 2012 Palo Alto Research Center, Inc.
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

import org.ccnx.ccn.impl.support.Log;

/**
 * Parse arguments that are used in all the I/O utilities
 */
public abstract class CommonArguments {

	protected static String _extraUsage = "";
	public static String[] defaultOkArgs = {"-unversioned", "-timeout", "-log", "-v", "-as", "-ac"};

	public static boolean parseArguments(String[] args, int i, Usage u) {
		return parseArguments(args, i, u, null);
	}

	public static boolean parseArguments(String[] args, int i, Usage u, String[] okArgs) {
		if (null == okArgs)
			okArgs = defaultOkArgs;
		if (i == 0 && args[0].startsWith("[")) {
			_extraUsage = args[0];
			return true;
		} else if (args[i].equals("-h") || args[i].equals("-help")) {
			u.usage(_extraUsage);
		}

		boolean argOK = false;
		for (String okArg : okArgs) {
			if (args[i].equals(okArg)) {
				argOK = true;
				break;
			}
		}
		if (argOK) {
			if (args[i].equals("-unversioned")) {
				CommonParameters.unversioned = true;
			} else if (args[i].equals("-timeout")) {
				if (args.length < (i + 2)) {
					u.usage(_extraUsage);
				}
				try {
					CommonParameters.timeout = Integer.parseInt(args[++i]);
				} catch (NumberFormatException nfe) {
					u.usage(_extraUsage);
				}
			} else if (args[i].equals("-log")) {
				Level level = null;
				if (args.length < (i + 2)) {
					u.usage(_extraUsage);
				}
				try {
					level = Level.parse(args[++i]);
				} catch (NumberFormatException nfe) {
					u.usage(_extraUsage);
				}
				Log.setLevel(Log.FAC_ALL, level);
			} else if (args[i].equals("-v")) {
				CommonParameters.verbose = true;
			} else if (args[i].equals("-as")) {
				if (args.length < (i + 2)) {
					u.usage(_extraUsage);
				}
				CommonSecurity.setUser(args[++i]);
			} else if (args[i].equals("-ac")) {
				CommonSecurity.setAccessControl();
			}
			CommonParameters.startArg = i;
			return true;
		}
		return false;
	}

	public static String getExtraUsage() {
		return _extraUsage;
	}
}
