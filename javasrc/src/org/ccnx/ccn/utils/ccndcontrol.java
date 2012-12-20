/*
 * A CCNx command line utility for managing prefix registrations.
 *
 * Copyright (C) 2009-2012 Palo Alto Research Center, Inc.
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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Vector;
import java.util.logging.Level;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.impl.CCNNetworkManager;
import org.ccnx.ccn.impl.CCNNetworkManager.NetworkProtocol;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.profiles.ccnd.CCNDaemonException;
import org.ccnx.ccn.profiles.ccnd.FaceManager;
import org.ccnx.ccn.profiles.ccnd.PrefixRegistrationManager;




/**
 * Java utility to
 *
 */
public class ccndcontrol {

	public static String _extraUsage = "";

	public enum Command {Add, Delete};

	public static class RegEntry {
		public Command command;
		public String uri;
		public NetworkProtocol protocol;
		public String host;
		public String hostName;
		public Integer port;
		public Integer flags;
		public Integer multicastTTL;
		public String multicastInterface;

		public Integer faceID;

		public RegEntry() {}
	}

	private static Vector<RegEntry> regList = new Vector<RegEntry>(5);
	private static boolean verbose = false;

	private static void parseString(String in) {
		String [] tokens = in.split("\\s");
		parseFromTokens(tokens, 0, tokens.length);
	}

	private static void parseFromTokens(String [] tokens, int start, int nTokens) {
		RegEntry entry = new ccndcontrol.RegEntry();
		if (nTokens < 1) {
			usage(_extraUsage);
			System.exit(1);
		}
		String tmp = tokens[start];
		if (null == tmp || tmp.length() == 0) {
			System.err.println("Command token either null or of 0 length.");
			usage(_extraUsage);
			System.exit(1);
		}
		/******** add command **********/
		if (tmp.equalsIgnoreCase("add")) {
			entry.command = Command.Add;
			if (nTokens < 3) {
				usage(_extraUsage);
				System.exit(1);
			}
			tmp = tokens[start + 1];
			if (null == tmp || tmp.length() == 0) {
				System.err.println("URI token either null or of 0 length.");
				usage(_extraUsage);
				System.exit(1);
			}
			entry.uri = tokens[start + 1];

			tmp = tokens[start + 2];
			if (null == tmp || tmp.length() == 0) {
				System.err.println("Protocol token either null or of 0 length.");
				usage(_extraUsage);
				System.exit(1);
			}
			entry.protocol = null;
			for (NetworkProtocol p : NetworkProtocol.values()) {
				String pAsString = p.toString();
				if (tmp.equalsIgnoreCase(pAsString)) {
					entry.protocol = p;
					break;
				}
			}
			if (null == entry.protocol) {
				System.err.println("Protocol (" + tmp + ") not valid.");
				usage(_extraUsage);
				System.exit(1);
			}

			tmp = tokens[start + 3];
			if (null == tmp || tmp.length() == 0) {
				System.err.println("Host name either null or of 0 length.");
				usage(_extraUsage);
				System.exit(1);
			}
			String hostNameNumeric = null;
			try {
				InetAddress ipAddr = InetAddress.getByName(tmp);
				hostNameNumeric = ipAddr.getHostAddress();
			} catch (UnknownHostException e) {
				String reason = e.getMessage();
				System.err.println("Host name (" + tmp + ") not found.  reason: " + reason);
				usage(_extraUsage);
				System.exit(1);
			}
			entry.hostName = tmp;
			entry.host = hostNameNumeric;

			if (start + 4 < nTokens && null != tokens[start + 4]) {
				try {
					entry.port = Integer.valueOf(tokens[start + 4]);
				} catch (NumberFormatException e) {
					System.err.println("Port (" + tokens[start + 4] + ") not valid.");
					usage(_extraUsage);
					System.exit(1);
				}
			} else {
				entry.port = CCNNetworkManager.DEFAULT_AGENT_PORT;
			}
			if (start + 5 < nTokens && null != tokens[start + 5]) {
				try {
					entry.flags = Integer.valueOf(tokens[start + 5]);
				} catch (NumberFormatException e) {
					System.err.println("Flags (" + tokens[start + 5] + ") not valid.");
					usage(_extraUsage);
					System.exit(1);
				}
			}
			if (start + 6 < nTokens && null != tokens[start + 6]) {
				try {
					entry.multicastTTL = Integer.valueOf(tokens[start + 6]);
				} catch (NumberFormatException e) {
					System.err.println("Multicast TTL (" + tokens[start + 6] + ") not valid.");
					usage(_extraUsage);
					System.exit(1);
				}
			}
			if (7 < nTokens && null != tokens[start + 7]) {
				entry.multicastInterface = tokens[start + 7];
			}

		} /* add */

		/******** delete command **********/
		else if ((tmp.equalsIgnoreCase("delete") || tmp.equalsIgnoreCase("del"))) {
			entry.command = Command.Delete;
			if (nTokens < 1) {
				System.err.println("Exiting because nTokens < 1 and command is " + tmp + "nTokens: " + nTokens);
				usage(_extraUsage);
				System.exit(1);
			}
			tmp = tokens[start + 1];
			if (null == tmp || tmp.length() == 0) {
				System.err.println("FaceID token either null or of 0 length.");
				usage(_extraUsage);
				System.exit(1);
			}
			try {
				entry.faceID = Integer.valueOf(tmp);
			} catch (NumberFormatException e) {
				System.err.println("Face ID (" + tokens[start + 1] + ") not valid.");
				usage(_extraUsage);
				System.exit(1);
			}
		} /* del */

		else {
			System.err.println("Command (" + tmp + ") not valid.");
			usage(_extraUsage);
			System.exit(1);
		}

		regList.add(entry);
	}

	private static void processConfigFile(String configFile) {
		Log.info("Processing configuration file " + configFile + ".");
		File file = new File(configFile);
		FileReader reader = null;
		try {
			reader = new FileReader(file);
		} catch (FileNotFoundException e) {
			Log.severe("Unable to find " + configFile + ".");
			System.err.println("Unable to find " + configFile + ".");
			System.exit(1);
		}
		BufferedReader read = new BufferedReader(reader);
		String line = null;
		try {
			while ((line = read.readLine()) != null) {
				Log.info("processConfigFile: parsing " + line);
				if (line.charAt(0) == '#') {
					continue;
				}
				parseString(line);
			}
		} catch (IOException e) {
			Log.severe("IO Error (" + e.getMessage() + ") reading " + configFile + ".");
			System.err.println("IO Error (" + e.getMessage() + ") reading " + configFile + ".");
			System.exit(1);
		}
	}



	/**
	 * Function to print out the options for ccndcontrol
	 *
	 * @returns void
	 */
	public static void usage(String extraUsage) {
		/*
		 *     fprintf(stderr,
            "%s [-d] (-f configfile | (add|del) uri proto host [port [flags [mcastttl [mcastif]]]])\n"
            "   -d enter dynamic mode and create FIB entries based on DNS SRV records\n"
            "   -f configfile add or delete FIB entries based on contents of configfile\n"
            "	add|del add or delete FIB entry based on parameters\n",
            progname);

		 */
		System.out.println("usage: ccndcontrol " + extraUsage + "[-v|-vv] add uri protocol host [port [flags [multicastTTL [multicastInterface]]]]\n" +
						    "      ccndcontrol " + extraUsage + "[-v|-vv] del face_id\n" +
						    "      ccndcontrol " + extraUsage + "[-v|-vv] -f configfile");
	}

	/**
	 *Utility function for the ccndcontrol tool.  Initializes the tool,
     * reads the argument list and constructs face to be added.
	 *
	 * @param args Command line arguments:
	 *
	 * @return void
	 */
	@SuppressWarnings("unused")
	public static int executeCommand(String[] args) {
		boolean dynamic = false;
		String configFile = null;
		int startArg = 0;
		Level logLevel = Level.SEVERE;
		verbose = false;

		for (int i = 0; i < args.length; i++) {
			if (i == 0 && args[0].startsWith("[")) {
				_extraUsage = args[0];
				startArg++;
			} else if (args[i].equals("-h")) {
				usage(_extraUsage);
                return(-1);
			} else if (args[i].equals(("-v"))) {
				if (startArg <= i)
					startArg = i + 1;
				verbose = true;
				logLevel = Level.INFO;
			} else if (args[i].equals(("-vv"))) {
				if (startArg <= i)
					startArg = i + 1;
				logLevel = Level.ALL;
			} else if (args[i].equals(("-d"))) {
				if (startArg <= i)
					startArg = i + 1;
				dynamic = true; // never read
			} else if (args[i].equals("-f")) {
				if (args.length < (i + 2)) {
					usage(_extraUsage);
                    return(-1);
				}
				configFile = args[++i];
				if (startArg <= i) {
					startArg = i + 1;
				}
			}
		}
		Log.setDefaultLevel(logLevel);

		if (null == configFile && args.length < startArg + 2) {
			usage(_extraUsage);
            return(-1);
		}

		if (null != configFile) {
			processConfigFile(configFile);
		} else {
			parseFromTokens(args, startArg, args.length);
		}

		int nReg = regList.size();
		for (int i = 0; i < nReg; i++) {
			RegEntry entry = regList.get(i);
			CCNHandle ccnHandle = null;
			FaceManager fHandle = null;
			Integer faceID = null;
			try {
				ccnHandle = CCNHandle.open();
				fHandle = new FaceManager(ccnHandle);
				if (entry.command == Command.Add) {
					faceID = fHandle.createFace(entry.protocol, entry.host, entry.port);
					if (verbose) {
						System.out.println("Created face " + faceID.toString());
					}
					PrefixRegistrationManager pre = new PrefixRegistrationManager(ccnHandle);
					pre.registerPrefix(entry.uri, faceID, entry.flags);
					if (verbose) {
						System.out.println("Added registration for " + entry.uri);
					}

				} else if (entry.command == Command.Delete) {
					fHandle.deleteFace(entry.faceID);
					if (verbose) {
						System.out.println("Deleted face " + entry.faceID.toString() + " in local ccnd");
					}
				} else {
					/* This really can't happen unless the check above was wrong. */
					System.err.println("Internal error.  command (" + entry.command + ") not add or del");
					return(-1);
				}

			} catch (ConfigurationException e) {
				String m = e.getMessage();
				System.err.println(m);
                return(-1);
			} catch (IOException e) {
				String m = e.getMessage();
				System.err.println(m);
                return(-1);
			}catch (CCNDaemonException e) {
				String m = e.getMessage();
				System.err.println(m);
                return(-1);
			} finally {
                if (ccnHandle != null) {
                    ccnHandle.close();
                }
            }
		}

		return(0);
	}

	/**
	 * Main function for the ccndcontrol tool.
	 * Calls executeCommand() to pass args for processing the command.
	 *
	 * @param args Command line arguments:
	 *
	 * @return void
	 */
	public static void main(String[] args) {
        if (executeCommand(args) < 0) {
            System.err.println("Error processing command, unable to complete");
            System.exit(1);
        } else {
            System.out.println("Success");
            System.exit(0);
        }
    }

}
