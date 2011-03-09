package org.ccnx.ccn.utils;

import java.util.logging.Level;

import org.ccnx.ccn.impl.support.Log;

public abstract class CommonArguments {
	
	public static boolean parseArguments(String[] args, int i, Usage u) {
		if (args[i].equals("-unversioned")) {
			if (CommonParameters.startArg <= i)
				CommonParameters.startArg = i + 1;
			CommonParameters.unversioned = true;
			return true;
		} else if (args[i].equals("-timeout")) {
			if (args.length < (i + 2)) {
				u.usage();
			}
			try {
				CommonParameters.timeout = Integer.parseInt(args[++i]);
			} catch (NumberFormatException nfe) {
				u.usage();
			}
			if (CommonParameters.startArg <= i)
				CommonParameters.startArg = i + 1;
			return true;
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
			if (CommonParameters.startArg <= i)
				CommonParameters.startArg = i + 1;
			return true;
		} else if (args[i].equals("-as")) {
			if (args.length < (i + 2)) {
				u.usage();
			}
			CommonSecurity.setUser(args[++i]);
			if (CommonParameters.startArg <= i)
				CommonParameters.startArg = i + 1;
			return true;
		} else if (args[i].equals("-ac")) {
			CommonSecurity.setAccessControl();
			if (CommonParameters.startArg <= i)
				CommonParameters.startArg = i + 1;
			return true;
		}
		return false;
	}
}
