/*
 * A CCNx command line utility.
 *
 * Copyright (C) 2008, 2009, 2010 Palo Alto Research Center, Inc.
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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.logging.Level;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.CCNFileInputStream;
import org.ccnx.ccn.io.CCNInputStream;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.profiles.metadata.MetadataProfile;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;

/**
 * A command-line utility for pulling meta files associated with a file
 * out of a repository. The "metaname" should be the relative path (including filename) for 
 * the desired metadata only.
 * Note class name needs to match command name to work with ccn_run
 */
public class ccngetmeta {
	
	/**
	 * @param args
	 */
	public static void main(String[] args) {
		int startArg = 0;
		
		for (int i = 0; i < args.length - 3; i++) {
			if (args[i].equals("-unversioned")) {
				if (startArg <= i)
					startArg = i + 1;
				CommonParameters.unversioned = true;
			} else if (args[i].equals("-timeout")) {
				if (args.length < (i + 2)) {
					usage();
					return;
				}
				try {
					CommonParameters.timeout = Integer.parseInt(args[++i]);
				} catch (NumberFormatException nfe) {
					usage();
					return;
				}
				if (startArg <= i)
					startArg = i + 1;
			} else if (args[i].equals("-log")) {
				Level level = null;
				if (args.length < (i + 2)) {
					usage();
				}
				try {
					level = Level.parse(args[++i]);
				} catch (NumberFormatException nfe) {
					usage();
				}
				Log.setLevel(level);
				if (startArg <= i)
					startArg = i + 1;
			} else if (args[i].equals("-as")) {
				if (args.length < (i + 2)) {
					usage();
				}
				CommonSecurity.setUser(args[++i]);
				if (startArg <= i)
					startArg = i + 1;				
			} else if (args[i].equals("-ac")) {
				CommonSecurity.setAccessControl();
				if (startArg <= i)
					startArg = i + 1;				
			}
			else {
				usage();
				System.exit(1);
			}
		}
		
		if (args.length != startArg + 3) {
			usage();
			System.exit(1);
		}
		
		try {
			int readsize = 1024; // make an argument for testing...
			
			CCNHandle handle = CCNHandle.open();

			String metaArg = args[startArg + 1];
			if (!metaArg.startsWith("/"))
				metaArg = "/" + metaArg;
			ContentName fileName = MetadataProfile.getLatestVersion(ContentName.fromURI(args[startArg]), 
					ContentName.fromNative(metaArg), CommonParameters.timeout, handle);
		
			if (fileName == null) {
				//This base content does not exist...  cannot get metadata associated with the base name.
				System.out.println("File " + args[startArg] + " does not exist");
				System.exit(1);
			}
			
			if (VersioningProfile.hasTerminalVersion(fileName)) {
				//MetadataProfile has found a terminal version...  we have something to get!
			} else {
				//MetadataProfile could not find a terminal version...  nothing to get
				System.out.println("File " + fileName + " does not exist...  exiting");
				System.exit(1);
			}
			
			File theFile = new File(args[startArg + 2]);
			if (theFile.exists()) {
				System.out.println("Overwriting file: " + args[startArg + 1]);
			}
			FileOutputStream output = new FileOutputStream(theFile);
			
			long starttime = System.currentTimeMillis();
			CCNInputStream input;
			if (CommonParameters.unversioned)
				input = new CCNInputStream(fileName, handle);
			else
				input = new CCNFileInputStream(fileName, handle);
			if (CommonParameters.timeout != null) {
				input.setTimeout(CommonParameters.timeout); 
			}
			byte [] buffer = new byte[readsize];
			
			int readcount = 0;
			long readtotal = 0;
			//while (!input.eof()) {
			while ((readcount = input.read(buffer)) != -1){
				//readcount = input.read(buffer);
				readtotal += readcount;
				output.write(buffer, 0, readcount);
				output.flush();
			}
			System.out.println("ccngetfile took: "+(System.currentTimeMillis() - starttime)+"ms");
			System.out.println("Retrieved content " + args[1] + " got " + readtotal + " bytes.");
			System.exit(0);

		} catch (ConfigurationException e) {
			System.out.println("Configuration exception in ccngetfile: " + e.getMessage());
			e.printStackTrace();
		} catch (MalformedContentNameStringException e) {
			System.out.println("Malformed name: " + args[0] + " " + e.getMessage());
			e.printStackTrace();
		} catch (IOException e) {
			System.out.println("Cannot write file or read content. " + e.getMessage());
			e.printStackTrace();
		}
		System.exit(1);
	}
	
	public static void usage() {
		System.out.println("usage: ccngetmeta [-unversioned] [-timeout millis] [-as pathToKeystore] [-ac (access control)] <ccnname> <metaname> <filename>");
	}
	
}
