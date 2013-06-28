/*
 * A CCNx command line utility.
 *
 * Copyright (C) 2008, 2009, 2012 Palo Alto Research Center, Inc.
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
import java.util.ArrayList;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.logging.Level;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.profiles.nameenum.BasicNameEnumeratorListener;
import org.ccnx.ccn.profiles.nameenum.CCNNameEnumerator;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;




/**
 * Java utility to explore content stored under a given prefix in a repository.  Uses name
 * enumeration to limit responses to repositories and other NE responders.
 * The program defaults to a prefix of "/"
 * but takes a prefix as the first command-line argument.  The tool displays names under the prefix
 * after collecting names for a given time period.  The initial default setting is 2 seconds.  To enumerate
 * names for more than 2 seconds (for example, if you have a long round trip time to a repository, the time
 * can be extended using the -timeout flag and the time to wait in milliseconds.  Another option is to have
 * a long running enumeration that outputs results as they are received at the client.  This is triggered
 * with the -c flag.  The tool utilizes the basic name enumeration protocol and currently does not properly
 * handle responses from multiple repositories.  If this is run with multiple repositories responding, it
 * will not crash, it just may not receive all of the information from each repository.
 *
 */
public class ccnlsrepo implements BasicNameEnumeratorListener {

	private String prefix = "";
	private ContentName name = null;
	private long timeout = 2000;
	private SortedSet<ContentName> allNames;


	/**
	 * Main function for the ccnlsrepo tool.  Initializes the tool and triggers name enumeration.
	 *
	 * @param args Command line arguments: prefix to enumeration and timeout flag (and time in ms)
	 *
	 * @return void
	 */

	public static void main(String[] args) {
		Log.setDefaultLevel(Level.WARNING);
		ccnlsrepo lister = new ccnlsrepo();
		lister.init(args);
		lister.enumerateNames();
		System.exit(0);
	}

	/**
	 * Initialization function for ccnlsrepo.  This method parses the command line input
	 * and creates a ContentName for the supplied prefix (or creates a new ContentName for the default "/" prefix).
	 * The program prints the usage and exits if the input is not correct.
	 *
	 * @param args Command line arguments.  Prefix to enumerate and timeout flags.
	 *
	 * @return void
	 *
	 * @throws org.ccnx.ccn.protocol.MalformedContentNameStringException Converting the input to a
	 * ContentName can throw a MalformedContentNameException.
	 *
	 * @see org.ccnx.ccn.protocol.ContentName
	 */

	private void init(String[] args) {
		// first look for prefix and timeout in the args list
		boolean tflag = false;
		boolean cflag = false;
		String extraUsage = "";

		for (int i = 0; i < args.length; i++) {
			if (i == 0 && args[0].startsWith("[")) {
				extraUsage = args[0];
				continue;
			}
			if (args[i].equals("-h")) {
				usage(extraUsage);
				System.exit(0);
			}
			if (!args[i].equals("-timeout") && !args[i].equals("-c") && !args[i].equals("-continuous")) {
				prefix = args[i];
			} else if (args[i].equals("-timeout")) {
				if (cflag) {
					System.err.println("please use either the -timeout or -c flags, not both");
					usage(extraUsage);
					System.exit(1);
				}
				tflag = true;
				i++;
				if (i >= args.length) {
					usage(extraUsage);
					System.exit(1);
				} else {
					try {
						timeout = Long.parseLong(args[i]);
					} catch (Exception e) {
						System.err.println("Could not parse timeout.  Please check and retry.");
						usage(extraUsage);
						System.exit(1);
					}

				}
			} else if (args[i].equals("-c") || args[i].equals("-continuous")) {
				cflag = true;
				if (tflag) {
					System.err.println("please use either the -timeout or -c flags, not both");
					usage(extraUsage);
					System.exit(1);
				}
				timeout = 0;
			}

		}

		try {
			if (prefix == null || prefix.equals(""))
				name = ContentName.ROOT;
			else
				name = ContentName.fromURI(prefix);
			Log.fine("monitoring prefix " + name.toString());
		} catch (MalformedContentNameStringException e) {
			System.err.println(e.toString());
			System.err.println("could not create parse prefix, please be sure it is a valid name prefix");
			System.exit(1);
		}

		if (timeout > 0)
			Log.fine("monitoring prefix for " + timeout + "ms");

		allNames = new TreeSet<ContentName>();
	}

	/**
	 * Method to initialize a CCNHandle and the CCNNameEnumerator for the ccnlsrepo tool.
	 * This method also determines when the program should print out results and exit.
	 *
	 * @return void
	 *
	 * @throws org.ccnx.ccn.config.ConfigurationException A configuration exception is
	 * 	thrown if the CCNHandle is not properly configured
	 * @throws java.io.IOException  Am IOException is thrown if the CCNHandle is not properly initialized.
	 *
	 * @see org.ccnx.ccn.CCNHandle
	 * @see org.ccnx.ccn.profiles.nameenum.CCNNameEnumerator
	 */

	private void enumerateNames() {
		try {
			CCNHandle handle = CCNHandle.open();

			CCNNameEnumerator ccnNE = new CCNNameEnumerator(handle, this);
			ccnNE.registerPrefix(name);

			if (timeout > 0) {
				try {
					Thread.sleep(timeout);
				} catch (InterruptedException e) {
					System.err.println("error while waiting for responses from CCNNameEnumerator");
				}

				Log.fine("finished waiting for responses, cleaning up state");
				ccnNE.cancelPrefix(name);
				printNames();
			} else {
				// we do not have to exit
				while (true) {

				}
			}

		} catch (ConfigurationException e) {
			System.err.println("Configuration Error");
			e.printStackTrace();
		} catch (IOException e) {
			System.err.println(e.toString());
			e.printStackTrace();
		}
	}

	/**
	 * Function to print out the options for ccnlsrepo
	 *
	 * @returns void
	 */

	public void usage(String extraUsage) {
		System.out.println("usage: ccnlsrepo " + extraUsage + "<ccnprefix> [-timeout millis (default is 2000ms) | -c(ontinuous)]");
	}

	/**
	 * Callback method to handle names returned through enumeration.  Adds all names not already in the
	 * stored list to be printed out before the program exits.  In the case of a long-running iteration
	 * (called with -c), the names are printed out as they are returned in enumeration responses.
	 *
	 * @param prefix  The registered prefix for the returned names.
	 * @param names   Returned names matching the prefix.
	 *
	 * @return int Number of names in the collection. (currently unused in this implementation)
	 *
	 * @see org.ccnx.ccn.profiles.nameenum.BasicNameEnumeratorListener
	 */

	public int handleNameEnumerator(ContentName prefix,	ArrayList<ContentName> names) {
		synchronized (allNames) {
			allNames.addAll(names);
		}
		if (timeout <= 0) {
			System.out.println("-----");
			printNames();
			System.out.println("-----");
			System.out.println();
		}

		return 0;
	}


	/**
	 *	Method to print the names collection through enumeration.  Iterates through the names and prints each content name.
	 *	Uses the ContentName.toString method and removes the leading "/" - component separator.
	 *
	 *  @return void
	 */
	private void printNames() {
		synchronized (allNames) {
		for (ContentName c : allNames)
			System.out.println(c.toString().replaceFirst("/", ""));
		}
	}
}
