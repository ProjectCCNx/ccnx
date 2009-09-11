/**
 * A CCNx command line utility.
 *
 * Copyright (C) 2008, 2009 Palo Alto Research Center, Inc.
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
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.security.InvalidKeyException;
import java.util.logging.Level;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.CCNFileOutputStream;
import org.ccnx.ccn.io.CCNOutputStream;
import org.ccnx.ccn.io.RepositoryFileOutputStream;
import org.ccnx.ccn.io.RepositoryOutputStream;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;


public class put_file {
	
	private static int BLOCK_SIZE = 8096;
	private static boolean rawMode = false;
	private static Integer timeout = null;
	private static boolean unversioned = false;

	/**
	 * @param args
	 */
	public static void main(String[] args) {
		System.err.print("here1");
		int startArg = 0;
		
		for (int i = 0; i < args.length - 2; i++) {
			if (args[i].equals(("-raw"))) {
				if (startArg <= i)
					startArg = i + 1;
				rawMode = true;
			} else if (args[i].equals("-unversioned")) {
				if (startArg <= i)
					startArg = i + 1;
				unversioned = true;
			} else if (args[i].equals("-timeout")) {
				if (args.length < (i + 2)) {
					usage();
				}
				try {
					timeout = Integer.parseInt(args[++i]);
				} catch (NumberFormatException nfe) {
					usage();
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
			} else {
				usage();
			}
				
		}
		
		if (args.length < startArg + 2) {
			usage();
		}
		
		long starttime = System.currentTimeMillis();
		try {
			// If we get one file name, put as the specific name given.
			// If we get more than one, put underneath the first as parent.
			// Ideally want to use newVersion to get latest version. Start
			// with random version.
			ContentName argName = ContentName.fromURI(args[startArg]);
			
			CCNHandle library = CCNHandle.open();
			
			if (args.length == (startArg + 2)) {
				
				Log.info("put_file: putting file " + args[startArg + 1]);
				
				doPut(library, args[startArg + 1], argName);
				System.out.println("put_file took: "+(System.currentTimeMillis() - starttime)+"ms");
				System.exit(0);
			} else {
				for (int i=startArg + 1; i < args.length; ++i) {
					
					// put as child of name
					ContentName nodeName = ContentName.fromURI(argName, args[i]);
					
					doPut(library, args[i], nodeName);
				}
				System.out.println("put_file took: "+(System.currentTimeMillis() - starttime)+"ms");
				System.exit(0);
			}
		} catch (ConfigurationException e) {
			System.out.println("Configuration exception in put: " + e.getMessage());
			e.printStackTrace();
		} catch (MalformedContentNameStringException e) {
			System.out.println("Malformed name: " + args[startArg] + " " + e.getMessage());
			e.printStackTrace();
		} catch (IOException e) {
			System.out.println("Cannot read file. " + e.getMessage());
			e.printStackTrace();
		} catch (InvalidKeyException e) {
			System.out.println("Cannot publish invalid key: " + e.getMessage());
			e.printStackTrace();
		}
		System.exit(1);

	}

	protected static void doPut(CCNHandle library, String fileName,
			ContentName nodeName) throws IOException, InvalidKeyException, ConfigurationException {
		InputStream is;
		
		System.out.printf("filename %s\n", fileName);
		if (fileName.startsWith("http://")) {
			System.out.printf("filename is http\n");			
			is = new URL(fileName).openStream();
		} else {
			System.out.printf("filename is file\n");			
			File theFile = new File(fileName);
	
			if (!theFile.exists()) {
				System.out.println("No such file: " + theFile.getName());
				usage();
			}
			is = new FileInputStream(theFile);
		}

		// If we are using a repository, make sure our key is available to
		// repository clients. For now, write an unversioned form of key.
		if (!rawMode) {
			library.keyManager().publishKeyToRepository(library);
		}

		CCNOutputStream ostream;
		
		// Use file stream in both cases to match behavior. CCNOutputStream doesn't do
		// versioning and neither it nor CCNVersionedOutputStream add headers.
		if (rawMode) {
			if (unversioned)
				ostream = new CCNOutputStream(nodeName, library);
			else
				ostream = new CCNFileOutputStream(nodeName, library);
		} else {
			if (unversioned)
				ostream = new RepositoryOutputStream(nodeName, library);
			else
				ostream = new RepositoryFileOutputStream(nodeName, library);
		}
		if (timeout != null)
			ostream.setTimeout(timeout);
		do_write(ostream, is);
				
		System.out.println("Inserted file " + fileName + ".");
	}
	
	private static void do_write(CCNOutputStream ostream, InputStream is) throws IOException {
		long time = System.currentTimeMillis();
		int size = BLOCK_SIZE;
		int readLen = 0;
		byte [] buffer = new byte[BLOCK_SIZE];
		Log.info("do_write: " + is.available() + " bytes left.");
		while((readLen = is.read(buffer, 0, size)) != -1){	
			ostream.write(buffer, 0, readLen);
			Log.info("do_write: wrote " + size + " bytes.");
			Log.info("do_write: " + is.available() + " bytes left.");
		}
		ostream.close();
		Log.info("finished write: "+(System.currentTimeMillis() - time));
	}
	
	public static void usage() {
		System.out.println("usage: put_file [-raw] [-unversioned] [-timeout millis] [-log level] <ccnname> (<filename>|<url>)*");
		System.exit(1);
	}

}
