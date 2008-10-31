package com.parc.ccn.apps;

import java.io.File;
import java.io.IOException;
import java.security.SignatureException;

import com.parc.ccn.config.ConfigurationException;
import com.parc.ccn.data.CompleteName;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.MalformedContentNameStringException;
import com.parc.ccn.library.CCNLibrary;

public class put {

	/**
	 * @param args
	 */
	public static void main(String[] args) {
		if (args.length < 2) {
			usage();
			return;
		}
		
		try {
			// If we get one file name, put as the specific name given.
			// If we get more than one, put underneath the first as parent.
			// Ideally want to use newVersion to get latest version. Start
			// with random version.
			ContentName argName = ContentName.fromURI(args[0]);
			
			CCNLibrary library = CCNLibrary.open();
			
			if (args.length == 2) {
				
				File theFile = new File(args[1]);
				if (!theFile.exists()) {
					System.out.println("No such file: " + args[1]);
					usage();
					return;
				}
				byte [] contents = Utils.getBytesFromFile(theFile);
				
				// put as name
				// int version = new Random().nextInt(1000);
				// would be version = library.latestVersion(argName) + 1;
				CompleteName result = library.newVersion(argName, contents);
				
				System.out.println("Inserted file " + args[1] + " as " + result.name());
				System.exit(0);
			} else {
				for (int i=1; i < args.length; ++i) {
					
					File theFile = new File(args[i]);
					if (!theFile.exists()) {
						System.out.println("No such file: " + args[i]);
						usage();
						return;
					}
					byte [] contents = Utils.getBytesFromFile(theFile);
					
					// put as child of name
					ContentName nodeName = ContentName.fromURI(argName, theFile.getName());
					
					// int version = new Random().nextInt(1000);
					// would be version = library.latestVersion(argName) + 1;
					CompleteName result = library.newVersion(nodeName, contents);
					
					System.out.println("Inserted file " + args[i] + " as " + result.name());
					
				}
			}
		} catch (ConfigurationException e) {
			System.out.println("Configuration exception in put: " + e.getMessage());
			e.printStackTrace();
		} catch (MalformedContentNameStringException e) {
			System.out.println("Malformed name: " + args[0] + " " + e.getMessage());
			e.printStackTrace();
		} catch (IOException e) {
			System.out.println("Cannot read file. " + e.getMessage());
			e.printStackTrace();
		} catch (SignatureException e) {
			System.out.println("Cannnot insert content. " + e.getMessage());
			e.printStackTrace();
		} catch (InterruptedException e) {
			System.out.println("Cannnot insert content. " + e.getMessage());
			e.printStackTrace();
		}

	}
	
	public static void usage() {
		System.out.println("usage: put <ccnname> <filename> [<filename> ...]");
	}

}
