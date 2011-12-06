/*
 * A CCNx command line utility.
 *
 * Copyright (C) 2010 Palo Alto Research Center, Inc.
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
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.logging.Level;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.impl.repo.LogStructRepoStore.LogStructRepoStoreProfile;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.profiles.repo.RepositoryBulkImport;

/**
 * A command-line utility for bulk importing a file into the repo.  It copies the file, so the original
 * file is not changed.
 * Note class name needs to match command name to work with ccn_run
 */
public class ccnrepoimport {
	
	public static Integer timeout = 20000;
	public static String importFileName = "myImport";
	
	/**
	 * @param args
	 */
	public static void main(String[] args) {
		int startArg = 0;
		
		for (int i = 0; i < args.length - 2; i++) {
			if (args[i].equals("-timeout")) {
				if (args.length < (i + 2)) {
					usage();
					return;
				}
				try {
					timeout = Integer.parseInt(args[++i]);
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
			}
			else {
				usage();
				System.exit(1);
			}
		}
		
		if (args.length < startArg + 2) {
			usage();
			System.exit(1);
		}
		
		try {
			
			File repoDir = new File(args[startArg]);
			if (!repoDir.exists()) {
				System.out.println("Repo at: " + args[startArg + 1] + " does not exist");
				System.exit(1);
			}
			
			File repoImportDir = new File(repoDir, LogStructRepoStoreProfile.REPO_IMPORT_DIR);
			repoImportDir.mkdir();
			
			File theFile = new File(args[startArg + 1]);
			if (!theFile.exists()) {
				System.out.println("File: " + args[startArg + 1] + " does not exist");
				System.exit(1);
			}
			
			File importFile;
			String importName;
			int test = 0;
			while (true) {
				test++;
				importName = importFileName + test;
				importFile = new File(repoImportDir, importName);
				if (!importFile.exists()) {
					break;
				}
			}
			FileInputStream fis = new FileInputStream(theFile);
			FileOutputStream fos = new FileOutputStream(importFile);
			byte[] buf = new byte[8192];
			while (fis.available() > 0) {
				int len = fis.available() > buf.length ? buf.length : fis.available();
				fis.read(buf, 0, len);
				fos.write(buf, 0, len);
			}
			fis.close();
			fos.close();
			
			CCNHandle handle = CCNHandle.open();
			
			long starttime = System.currentTimeMillis();
			
			boolean result = RepositoryBulkImport.bulkImport(handle, importName, timeout);
			System.out.println("Bulk import of " + theFile + (result ? " succeeded" : " failed"));
			System.out.println("ccnrepoimport took: "+(System.currentTimeMillis() - starttime)+" ms");
			System.exit(0);

		} catch (ConfigurationException e) {
			System.out.println("Configuration exception in ccnrepoimport: " + e.getMessage());
			e.printStackTrace();
		} catch (IOException e) {
			System.out.println("Cannot import file. " + e.getMessage());
			e.printStackTrace();
		}
		System.exit(1);
	}
	
	public static void usage() {
		System.out.println("usage: ccnrepoimport [-timeout millis] [-log level] <repodir> <filename>");
	}
	
}
