/*
 * A CCNx command line utility.
 *
 * Copyright (C) 2011 Palo Alto Research Center, Inc.
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
	
	public static boolean parseArguments(String[] args, int i, Usage u) {
		boolean ret = true;
		if (args[i].equals("-unversioned")) {
			CommonParameters.unversioned = true;
		} else if (args[i].equals("-timeout")) {
			if (args.length < (i + 2)) {
				u.usage();
			}
			try {
				CommonParameters.timeout = Integer.parseInt(args[++i]);
			} catch (NumberFormatException nfe) {
				u.usage();
			}
		} else if (args[i].equals("-log")) {
			Level level = null;
			if (args.length < (i + 2)) {
				u.usage();
			}
			try {
				level = Level.parse(args[++i]);
			} catch (NumberFormatException nfe) {
				u.usage();
			}
			Log.setLevel(Log.FAC_ALL, level);
		} else if (args[i].equals("-v")) {
			CommonParameters.verbose = true;
		} else if (args[i].equals("-as")) {
			if (args.length < (i + 2)) {
				u.usage();
			}
			CommonSecurity.setUser(args[++i]);
		} else if (args[i].equals("-ac")) {
			CommonSecurity.setAccessControl();
		} else {
			ret = false;
		}
		if (CommonParameters.startArg < i + 1)
			CommonParameters.startArg = i + 1;
		return ret;
	}
}
